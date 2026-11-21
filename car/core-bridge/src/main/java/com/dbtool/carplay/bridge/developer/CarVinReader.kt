package com.dbtool.carplay.bridge.developer

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 车架号（VIN）多级降级读取。
 *
 * ## 为什么需要它
 *
 * 后台要按 VIN 做灰度，但**普通车机上 VIN 极难读到**：需要 car 权限、
 * 各 OEM 属性名都不一样、Android Automotive 各版本 `Car` 构造签名也不同。
 * 早先版本只读 5 个"猜出来"的系统属性（`ro.product.vin` / `ro.car.vin` …），
 * 在真车机上**一个都不存在** ⇒ VIN 恒为空（2026-10-07 实测确认）。
 *
 * 这里移植 DBtool3.0 的降级链（`VinReader.java`），逐级放宽：
 *
 * ```
 * 0. 本地缓存（命中过就不再重探，省掉每次启动的几秒开销）
 * 1. VHAL INFO_VIN（反射 CarPropertyManager，AOSP 标准 0x11100100）
 * 2. ECarX PA（ecarxcar_service → car_signal → ECarXCarVinManager）★ 本车机主路径
 *    2a. 编译期 stub 直调（`ecarx.car.*`，见 `car/core-bridge/src/main/java/ecarx/`）
 *    2b. 全反射兜底（stub 签名对不上 / ROM 无对应类时）
 * 3. APVP 通道（best-effort 探测几种服务名/类名）
 * 4. 系统属性（含 `sys.vehicle.hardware.vin.code` 等 ECarX 专用键）
 * 5. getprop shell（app 进程内跑，不需 root）
 * 6. Settings.Global / ECarX 文件节点
 * 7. 外部 shell 兜底（app 自身 uid 的 sh；含 `settings` / 文件 / `dumpsys` / `cmd`，
 *    慢路径还含 `logcat` 抓 17 位 VIN）
 * ```
 *
 * ## 与 DBtool3.0 的关系
 *
 * 降级链的顺序与判据来自 DBtool3.0 的 `VinReader.java`；`ecarx.car.*` 的
 * **编译期 stub 也照搬**（DBPlay 侧落在 `car/core-bridge/src/main/java/ecarx/`）。
 * 在 stub 之外**额外多留一条全反射路径**：stub 把厂商构造签名写死在编译期，
 * 换一代 ROM 就可能对不上；反射版的"按参数类型试匹配"是这类场景的唯一后手。
 * 两条路都是"读不到就继续降级"，谁先命中算谁的。
 *
 * ## 线程与超时
 *
 * 全程可能碰 binder/文件/shell，**必须在后台线程调用**。带缓存的路径很快，
 * 但首次未命中会走完整条链（实测数秒）。因此：
 *
 * - 整条链包在 [TOTAL_TIMEOUT_MILLIS] 的**一次性守护线程**里跑。Binder 在服务端
 *   挂死时 `transact` 不响应中断，"调用方不等了"是唯一出路 —— 超时即放弃。
 * - [deps] 的 `shellFallback()` **只调用一次**（不是每级一次），见 [Deps] 的说明。
 */
internal class CarVinReader(
    context: Context,
    private val deps: Deps,
) {

    /** 外部依赖注入（避免本类直接耦合 CarSystemAdapter / adb 会话）。 */
    interface Deps {
        /** 读系统属性；读不到返回 null。 */
        fun systemProperty(key: String): String?

        /**
         * 通过 **shell（app 自身 uid）** 兜底读 VIN。返回空串表示该通道不可用/没读到。
         *
         * ## 契约（2026-10-07 修订，务必遵守）
         *
         * - **整体只允许一次 shell 往返**：实现内部自己跑完那串命令（getprop →
         *   `settings get global` → 文件 → 必要时 `logcat`/`dumpsys`/`cmd`），
         *   在内存里筛出**最长的一个合法 VIN 候选**再返回，而不是"每级命令调一次
         *   `shellFallback()`"。后者等价于一次 VIN 读取起十几个 shell 进程，
         *   在车机上是秒级开销，还会把上报线程堵住。
         * - 首屏预算见 [VinShellFallback.BRIEF_TIMEOUT_MILLIS]：**默认不许跑
         *   `logcat -d`**（实测 12s）。只在 `briefBudget` 为 false 时
         *   （用户手动触发的上报 / 后台宽裕的路径）才允许跑慢命令。
         * - **不要在这里碰 adb 会话**（`AdbShellSession.ensure()`）：那会跑完整的
         *   "开通车机 ADB → 起 server → 自连 → 提权探测"，等于擅自改一项用户没同意的
         *   系统设置，也正是 [CarSystemShell] 契约明令禁止的。app 自身 uid 的 `sh`
         *   已经够读 `getprop` / `settings`。
         *
         * @param briefBudget true = 本次调用方在"首屏/自动上报"路径上，只跑快命令。
         */
        fun shellFallback(briefBudget: Boolean): String
    }

    private val appContext = context.applicationContext

    /** 命中来源，写入诊断便于判断"从哪一级读到的"。 */
    data class Result(
        val vin: String,
        val source: String,
        val missReason: String = "",
    ) {
        val hit: Boolean get() = vin.isNotEmpty()
    }

    // ------------------------------------------------------------------ 入口

    /**
     * @param briefBudget true = 调用方在"首屏 / 自动上报"路径上。
     *
     * 只影响**最后一级** shell 兜底：为 false 时才允许那条链跑 `logcat -d`
     * （实测 12 秒），见 [Deps.shellFallback]。
     */
    fun read(briefBudget: Boolean = true): String = readWithSource(briefBudget).vin

    /**
     * 读 VIN 并带上"从哪一级读到的"。
     *
     * ⚠️ 全程有 [TOTAL_TIMEOUT_MILLIS] 的上限，且**超时不影响缓存**：
     * 超时返回 miss，但已经写入过的缓存仍然服务下一次调用。
     */
    fun readWithSource(briefBudget: Boolean = true): Result {
        readCached()?.let {
            Log.i(TAG, "VIN 命中缓存: ${mask(it)}")
            return Result(it, "cache")
        }

        val box = arrayOfNulls<Result>(1)
        val worker = Thread({
            box[0] = runCatching { probe(briefBudget) }.getOrElse { t ->
                Log.d(TAG, "VIN 探测异常: ${t.javaClass.simpleName}")
                Result("", "", "探测异常：${t.javaClass.simpleName}")
            }
        }, "dbplay-vin-probe").apply { isDaemon = true }

        return try {
            worker.start()
            worker.join(TOTAL_TIMEOUT_MILLIS)
            box[0] ?: run {
                // ⚠️ 这里**不能 join 第二次/不能 interrupt 等它**：卡住的几乎必然是
                //    binder transact，不响应中断。直接放弃，让守护线程自己去死。
                Log.w(TAG, "VIN 探测超时（${TOTAL_TIMEOUT_MILLIS}ms），放弃本次读取")
                Result("", "", "探测超时 ${TOTAL_TIMEOUT_MILLIS}ms")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result("", "", "被中断")
        }
    }

    private fun probe(briefBudget: Boolean): Result {
        val steps: List<Pair<String, () -> String>> = listOf(
            "vhal_info_vin" to ::readViaCarProperty,
            "ecarx_pa_native" to ::readViaEcarxPaNative,
            "ecarx_pa" to ::readViaEcarxPa,
            "apvp" to ::readViaApvp,
            "system_prop" to ::readViaSystemProps,
            "getprop_shell" to ::readViaGetpropShell,
            "settings_file" to ::readViaSettingsAndFiles,
        )
        for ((source, step) in steps) {
            val raw = runCatching { step() }.getOrElse {
                Log.d(TAG, "VIN 路径 $source 异常: ${it.javaClass.simpleName}")
                ""
            }
            if (isValid(raw)) {
                val vin = normalize(raw)
                Log.i(TAG, "VIN 命中 $source: ${mask(vin)}")
                cache(vin)
                return Result(vin, source)
            }
        }

        // 最后一级：shell 兜底（慢，带预算与超时）
        val shellVin = runCatching { deps.shellFallback(briefBudget) }.getOrNull().orEmpty()
        if (isValid(shellVin)) {
            val vin = normalize(shellVin)
            Log.i(TAG, "VIN 命中 shell 兜底: ${mask(vin)}")
            cache(vin)
            return Result(vin, "shell_fallback")
        }

        Log.w(TAG, "所有 VIN 读取路径均未命中")
        return Result("", "", "所有路径均未命中（非车机 / 无 car 权限 / 无 shell 通道）")
    }

    // ------------------------------------------------------------- 2. ECarX PA

    /**
     * ECarX PA native 路径（**本车机的主路径**）——编译期 stub 直调。
     *
     * 链路：
     * `ServiceManager.getService("ecarxcar_service")` → `IECarXCar.getCarService("car_signal")`
     * → `ECarXCarPropertyManagerBase(binder, handler, true, tag)`
     * → `ECarXCarVinManager(manager).getPA_VIN_VinNum().getData()`
     *
     * ## 为什么既留 stub 版、又留反射版（2026-10-07 定案）
     *
     * 两条路走的是**同一条 binder 链**，结果等价，差别只在"类从哪来"：
     *
     * | | stub 版（本方法） | 反射版（[readViaEcarxPa]） |
     * | --- | --- | --- |
     * | 编译期 | 要 `ecarx.car.*` 在 classpath | 不需要 |
     * | 运行时 | ROM 有真类 ⇒ **用真类**；没有 ⇒ 用我们的空壳（返回 null，等价于没读到） | ROM 没有 ⇒ `Class.forName` 抛异常，降级 |
     * | 代价 | 模块里多一份厂商标识 | 每次多几次反射查找 |
     *
     * 之所以两个都留：**stub 版是"显式依赖"，反射版是"兜底"**。
     * stub 在 ROM 缺类时会静默返回 null（不抛异常），因此放在**前**面不会有风险；
     * 反射版则在 stub 被 ROM 版本"改了签名"时仍能靠 `instantiateMatching` 试出来。
     * 两者都是"读不到就继续降级"，**任一条先命中就返回**。
     */
    private fun readViaEcarxPaNative(): String {
        return try {
            val raw = runCatching {
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, ECARX_CAR_SERVICE)
            }.getOrNull()
            if (raw !is IBinder) return ""

            val car = ecarx.car.IECarXCar.Stub.asInterface(raw)
            val carService = car.getCarService(CAR_SIGNAL) ?: return ""
            val manager = ecarx.car.hardware.property.ECarXCarPropertyManagerBase(
                carService, Handler(Looper.getMainLooper()), true, "dbplay-vin",
            )
            try {
                // ⚠️ 用方法调 `getPA_VIN_VinNum()`，**不要**写成 Kotlin 属性
                //    `paVinVinNum`：stub 是 Java 类，Java 的 `getXxx()` 只有在
                //    **有对应 setter 或本身是 `is`/`get` 命名且被 Kotlin 识别**时
                //    才合成属性；这个全大写的 `getPA_VIN_VinNum` 不合成，写属性名编不过。
                val paVin = ecarx.car.hardware.vehicle.ECarXCarVinManager(manager)
                    .getPA_VIN_VinNum()
                paVin?.data.orEmpty()
            } finally {
                runCatching { manager.onCarDisconnected() }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "ECarX PA native 不可用: ${t.javaClass.simpleName}")
            ""
        }
    }

    /**
     * ECarX PA 路径——**全程反射**（stub 签名对不上 / ROM 无对应类时的兜底）。
     *
     * 与 [readViaEcarxPaNative] 走同一条链，只是类全部靠 `Class.forName` 找，
     * 构造函数靠 [instantiateMatching] 按类型试匹配。**为什么值得单独留一份**：
     * stub 版把厂商类写死在编译期，一旦某代 ROM 换了构造签名（或多一个 `long` 参数），
     * stub 版就构造失败 ⇒ 反射版的"按参数类型试"还能救回来。
     */
    private fun readViaEcarxPa(): String {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val raw = sm.getMethod("getService", String::class.java)
                .invoke(null, ECARX_CAR_SERVICE)
            if (raw !is IBinder) return ""

            val stub = Class.forName("ecarx.car.IECarXCar\$Stub")
            val car = stub.getMethod("asInterface", IBinder::class.java).invoke(null, raw) ?: return ""
            val carService = car.javaClass.getMethod("getCarService", String::class.java)
                .invoke(car, CAR_SIGNAL)
            if (carService !is IBinder) return ""

            val pmClass = Class.forName("ecarx.car.hardware.property.ECarXCarPropertyManagerBase")
            val pm = instantiateMatching(pmClass, carService) ?: return ""
            val vinMgrClass = Class.forName("ecarx.car.hardware.vehicle.ECarXCarVinManager")
            val vinMgr = instantiateMatching(vinMgrClass, pm) ?: return ""
            val getPaVin = findNoArgMethod(vinMgrClass, "getPA_VIN_VinNum") ?: return ""
            val paVin = getPaVin.invoke(vinMgr) ?: return ""
            val getData = findNoArgMethod(paVin.javaClass, "getData") ?: return ""
            firstToken(getData.invoke(paVin))
        } catch (t: Throwable) {
            Log.d(TAG, "ECarX PA 反射失败: ${t.javaClass.simpleName}")
            ""
        }
    }

    /** APVP 通道：best-effort 探测若干服务名 / 类名组合。 */
    private fun readViaApvp(): String {
        val serviceNames = listOf("apvp", "ecarx_apvp", "vendor.apvp", "car_apvp")
        val managerClasses = listOf(
            "android.car.apvp.IApvpManager\$Stub",
            "com.ecarx.apvp.IApvpManager\$Stub",
            "ecarx.car.apvp.IApvpManager\$Stub",
        )
        for (serviceName in serviceNames) {
            for (managerClass in managerClasses) {
                try {
                    val sm = Class.forName("android.os.ServiceManager")
                    val raw = sm.getMethod("getService", String::class.java)
                        .invoke(null, serviceName)
                    if (raw !is IBinder) continue
                    val stub = Class.forName(managerClass)
                    val mgr = stub.getMethod("asInterface", IBinder::class.java)
                        .invoke(null, raw) ?: continue
                    for (methodName in listOf("getVin", "getProperty", "readVin")) {
                        val m = findNoArgMethod(mgr.javaClass, methodName) ?: continue
                        val value = runCatching { m.invoke(mgr) }.getOrNull() ?: continue
                        val s = firstToken(value)
                        if (isValid(s)) return s
                    }
                } catch (_: Throwable) {
                    // best-effort：这个组合不存在就试下一个
                }
            }
        }
        return ""
    }

    // ---------------------------------------------------------- 1. VHAL 反射

    private fun readViaCarProperty(): String =
        readPropertyViaCarProperty(PROP_INFO_VIN)

    private fun readPropertyViaCarProperty(propId: Int): String {
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "car_service") as? IBinder ?: return ""
            val iCar = Class.forName("android.car.ICar\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val carCls = Class.forName("android.car.Car")
            val iCarCls = Class.forName("android.car.ICar")
            val car = createCar(carCls, iCarCls, iCar) ?: return ""

            // getCarManager 必须有 Looper；用主线程 + 短超时，避免后台线程被拖住
            val out = arrayOfNulls<Any>(1)
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    out[0] = carCls.getMethod("getCarManager", String::class.java)
                        .invoke(car, "property")
                } catch (_: Throwable) {
                } finally {
                    latch.countDown()
                }
            }
            latch.await(CAR_MANAGER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            val cpm = out[0] ?: return ""

            val getProp = cpm.javaClass.getMethod(
                "getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            val cpv = getProp.invoke(cpm, String::class.java, propId, 0) ?: return ""
            val value = cpv.javaClass.getMethod("getValue").invoke(cpv)
            value?.toString().orEmpty()
        } catch (t: Throwable) {
            Log.d(TAG, "VHAL 读属性失败（非车机/无权限）: ${t.javaClass.simpleName}")
            ""
        }
    }

    /** `Car` 构造 4 策略降级：各 Android Automotive 版本签名不同。 */
    private fun createCar(carCls: Class<*>, iCarCls: Class<*>, iCar: Any?): Any? {
        val ui = Handler(Looper.getMainLooper())
        runCatching {
            val c = carCls.getDeclaredConstructor(Context::class.java, iCarCls, Handler::class.java)
            c.isAccessible = true
            return c.newInstance(appContext, iCar, ui)
        }
        runCatching {
            val c = carCls.getDeclaredConstructor(
                Context::class.java, iCarCls, Handler::class.java, Boolean::class.javaPrimitiveType,
            )
            c.isAccessible = true
            return c.newInstance(appContext, iCar, ui, false)
        }
        // 兜底：任意构造 + 反射注入 mService
        return runCatching {
            val c = carCls.declaredConstructors[0]
            c.isAccessible = true
            val ps = c.parameterTypes
            val args = arrayOfNulls<Any>(ps.size)
            for (i in ps.indices) {
                args[i] = when {
                    ps[i] == Context::class.java -> appContext
                    ps[i] == iCarCls -> iCar
                    ps[i] == Handler::class.java -> ui
                    ps[i] == Boolean::class.javaPrimitiveType -> false
                    ps[i] == Long::class.javaPrimitiveType -> 0L
                    ps[i] == Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
            val car = c.newInstance(*args)
            runCatching {
                val f = carCls.getDeclaredField("mService")
                f.isAccessible = true
                f.set(car, iCar)
            }
            car
        }.getOrNull()
    }

    // -------------------------------------------------------- 5/6. 属性与 shell

    private fun readViaSystemProps(): String {
        for (key in VIN_PROP_KEYS) {
            val v = deps.systemProperty(key).orEmpty()
            if (isValid(v)) return v
        }
        return ""
    }

    private fun readViaGetpropShell(): String {
        for (key in VIN_PROP_KEYS) {
            val out = CarShell.getProp(key)
            if (isValid(out)) return out
        }
        return ""
    }

    // --------------------------------------------------------- 7. Settings/文件

    private fun readViaSettingsAndFiles(): String {
        for (key in SETTINGS_KEYS) {
            val v = runCatching {
                Settings.Global.getString(appContext.contentResolver, key)
            }.getOrNull()
            if (isValid(v)) return v!!
        }
        val f = File(SDV_FLOW_VIN_FILE)
        if (f.canRead()) {
            val v = runCatching {
                BufferedReader(InputStreamReader(FileInputStream(f), StandardCharsets.UTF_8))
                    .use { it.readText() }
            }.getOrNull()
            if (isValid(v)) return v!!
        }
        return ""
    }

    // ------------------------------------------------------------------ 缓存

    private fun readCached(): String? {
        val v = runCatching {
            appContext.getSharedPreferences(PREFS_VIN_CACHE, Context.MODE_PRIVATE)
                .getString(KEY_VIN, null)
        }.getOrNull()
        return v?.takeIf { isValid(it) }?.let { normalize(it) }
    }

    private fun cache(vin: String) {
        if (vin.isEmpty()) return
        runCatching {
            appContext.getSharedPreferences(PREFS_VIN_CACHE, Context.MODE_PRIVATE)
                .edit().putString(KEY_VIN, vin).apply()
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun normalize(v: String): String = v.trim().uppercase()

    private fun firstToken(data: Any?): String {
        if (data == null) return ""
        val s = when (data) {
            is ByteArray -> String(data, StandardCharsets.UTF_8)
            else -> data.toString()
        }.trim()
        val space = s.indexOf(' ')
        return if (space > 0) s.substring(0, space) else s
    }

    private fun instantiateMatching(cls: Class<*>, known: Any?): Any? {
        for (c in cls.declaredConstructors) {
            runCatching {
                c.isAccessible = true
                val ps = c.parameterTypes
                val args = arrayOfNulls<Any>(ps.size)
                var ok = true
                for (i in ps.indices) {
                    val p = ps[i]
                    args[i] = when {
                        known != null && p.isInstance(known) -> known
                        p == Handler::class.java -> Handler(Looper.getMainLooper())
                        p == Boolean::class.javaPrimitiveType -> true
                        p == String::class.java -> "DBPlay-VinReader"
                        p == Int::class.javaPrimitiveType -> 0
                        p == Long::class.javaPrimitiveType -> 0L
                        else -> {
                            ok = false
                            null
                        }
                    }
                    if (!ok) break
                }
                if (ok) return c.newInstance(*args)
            }
        }
        return null
    }

    private fun findNoArgMethod(cls: Class<*>, name: String): java.lang.reflect.Method? {
        runCatching {
            val m = cls.getDeclaredMethod(name)
            m.isAccessible = true
            return m
        }
        for (m in cls.declaredMethods) {
            if (m.name == name && m.parameterTypes.isEmpty()) {
                m.isAccessible = true
                return m
            }
        }
        for (m in cls.methods) {
            if (m.name == name && m.parameterTypes.isEmpty()) {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    /** 脱敏：前 3 + *** + 后 3，避免日志泄露完整车架号。 */
    private fun mask(vin: String?): String {
        if (vin == null) return "null"
        val t = vin.trim()
        if (t.length < 7) return if (t.isEmpty()) "(空)" else t
        return t.substring(0, 3) + "***" + t.substring(t.length - 3)
    }

    /**
     * 纯函数集合（判据 / 提取 / 属性键）。
     *
     * ⚠️ **不能是 `private`**：[VinShellFallback] 要用 [isValid] /
     * [extractVinCandidate] 来筛它自己那条 shell 链的输出。本类虽是 `internal`，
     * 但同模块内可见即足够，不扩大外部可见性。
     */
    companion object {
        private const val TAG = "dbplay-vin"
        private const val CAR_SIGNAL = "car_signal"

        /** ECarX 车辆服务的 Binder 名（`ServiceManager.getService(...)` 的 key）。 */
        private const val ECARX_CAR_SERVICE = "ecarxcar_service"

        /** VHAL 标准属性（AOSP `0x11100100`）。 */
        private const val PROP_INFO_VIN = 286261504

        /** 主线程回收 `getCarManager` 的超时；超时即降级，不阻塞调用方。 */
        private const val CAR_MANAGER_TIMEOUT_MILLIS = 500L

        /**
         * 整条 VIN 探测链的总预算。
         *
         * 取 6s：命中缓存是毫秒级、VHAL / ECarX PA 是百毫秒级，只有最后的
         * shell 兜底会跑到秒级。6s 足够"该快的都快"，又能保证后台线程不被
         * 卡死的 binder 无限期拖住。
         */
        const val TOTAL_TIMEOUT_MILLIS = 6_000L

        private const val PREFS_VIN_CACHE = "dbplay_vin_cache"
        private const val KEY_VIN = "vin"

        /** ECarX SDV 文件节点（部分车机把 VIN 落在这）。[VinShellFallback] 也用它。 */
        const val SDV_FLOW_VIN_FILE = "/bigdata/data/sdv-flow/v"

        /**
         * VIN 属性键。
         *
         * ⚠️ `sys.vehicle.hardware.vin.code` 是**实测**在 25 款 ECarX 车机上有效的键，
         * 排在第一位。其余为各 OEM 常见键，命中即可。
         */
        val VIN_PROP_KEYS = listOf(
            "sys.vehicle.hardware.vin.code",
            "persist.sys.vehicle.vin",
            "persist.ecarx.vin",
            "persist.sys.vin",
            "ro.vehicle.vin",
            "ro.vin",
            "ro.boot.vin",
            "ro.hardware.vin",
        )

        /** `Settings.Global` 里可能存 VIN 的键（ECarX / 部分 OEM 会写）。 */
        val SETTINGS_KEYS = listOf("Car_Vin", "ACCOUNT_DEVICE_ID")

        /**
         * fake positive：`dumpsys` / `cmd` / 字段名不能当 VIN。
         *
         * ⚠️ **全量小写比较**（`in`），不要改回 `contains` 之外又要留意副作用：
         * 子串匹配只对"帮助文本"这一类有意义（`usage: ...` 里含空格本来就会被
         * VIN_CHARS 挡掉），而 `vehicleId` / `modelYear` 这类**字段名**长度合法、
         * 没有任何触发词，必须靠下面的 [FIELD_NAME_BLACKLIST] 精确挡。
         */
        private val FALSE_POSITIVES = setOf(
            "commands", "usage", "help", "error", "invalid", "failed",
            "permission", "denied", "unknown", "null", "n/a",
        )

        /**
         * `dumpsys` 输出里的**字段名黑名单**（全量小写匹配）。
         *
         * ## 为什么必须单独一张表（2026-10-07 加）
         *
         * 单测 `从 dumpsys 的噪声里挖出 17 位 VIN` 第一次跑就**失败**：
         * 提取器返回了 `vehicleId`——`dumpsys vehicle` 的第一行字段名。
         * 它长度合法（9 位）、全是字母数字、也没有任何触发词，所以旧判据放行了。
         * 后果是后台「DBPlay 车辆」页会出现一台 VIN 是 `vehicleId` 的车，
         * 而且可以被当成灰度目标——**错了也不报错**，正是最该避免的一类 bug。
         *
         * 只比对完整词，所以真 VIN 不会被误伤（它不可能恰好等于某个字段名）。
         */
        private val FIELD_NAME_BLACKLIST = setOf(
            "vehicleid", "vehicle", "make", "model", "modelyear", "year",
            "brand", "series", "trim", "status", "result", "output",
            "service", "manager", "android", "binder", "native",
        )

        private val VIN_CHARS = Pattern.compile("^[A-Za-z0-9_-]+$")

        /**
         * VIN 合法性：标准 17 位；容错 8~24 位（部分 OEM 非标准）。
         *
         * 仍过滤明显占位词（`unknown` / `00000000` …）、命令帮助文本与
         * [FIELD_NAME_BLACKLIST]，否则 `getprop` 空值、`cmd` 用法说明、
         * `dumpsys` 的字段名都会被误判成 VIN。
         */
        fun isValid(v: String?): Boolean {
            val t = v?.trim().orEmpty()
            if (t.isEmpty()) return false
            val low = t.lowercase()
            if (low in FALSE_POSITIVES) return false
            if (low in FIELD_NAME_BLACKLIST) return false
            for (fp in FALSE_POSITIVES) {
                if (low.contains(fp)) return false
            }
            if (low.contains("00000000")) return false
            if (!VIN_CHARS.matcher(t).matches()) return false
            return t.length in 8..24
        }

        /** 从一段长文本（`dumpsys` / `cmd` / `logcat` 输出）里挖出第一个合法 VIN。 */
        fun extractVinCandidate(text: String?): String? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            // ⚠️ 必须用 `matcher(...)` 迭代，**不能**写 `VIN_CANDIDATE.findAll(t)`：
            //    `Pattern` 是 `java.util.regex.Pattern`，只有 Kotlin 的 `Regex`
            //    才带 `findAll`。写错了不是运行时问题，是**编不过**（已踩过）。
            val matcher = VIN_CANDIDATE.matcher(t)
            while (matcher.find()) {
                val candidate = matcher.group()
                if (isValid(candidate)) return candidate
            }
            return null
        }

        /**
         * 候选词：**恰好 17 位**字母数字。
         *
         * ## 为什么提取时要求 17 位（2026-10-07 定案）
         *
         * [isValid] 容错 8~24 位，因为**单值**上下文（`getprop` 的整行输出）里
         * OEM 确实可能给 8 位或带连字符的号。但在**长文本**里扫候选时，放宽到下界
         * 会一路抓到噪声——实测 `dumpsys vehicle` 里 `vehicleId: 0x00000001`
         * 就被抓成了 VIN（字段名挡住了，值本身没有触发词）。
         *
         * VIN 的行业标准长度就是 17 位（`[A-HJ-NPR-Z0-9]{17}`，DBtool3.0 的
         * `logcat` 兜底用的也是这条）。因此：**长文本扫描只认标准 17 位**；
         * 非标准号只能靠前面的整值路径（属性 / `settings` / 文件）拿到。
         * 宁可少一个来源，也不能把噪声当 VIN 上报。
         */
        private val VIN_CANDIDATE = Pattern.compile("[A-Za-z0-9]{17}")
    }
}
