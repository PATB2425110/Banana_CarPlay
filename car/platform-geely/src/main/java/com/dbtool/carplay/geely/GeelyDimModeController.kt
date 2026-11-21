package com.dbtool.carplay.geely

import android.content.Context
import java.lang.reflect.Method

/**
 * 车机仪表模式接口（便于纯 JVM 单测注入假实现）。
 *
 * 真机实现见 [GeelyDimModeController]；方法语义与它逐条一致。
 */
internal interface GeelyNaviModeController {
    /** 厂商接口是否可用（类 / 方法探测成功）。 */
    fun isAvailable(): Boolean

    /** 进入全屏导航（把仪表交给我们）。 */
    fun enterFullNavi(): Boolean

    /** 退出全屏导航（把仪表还给原厂）。 */
    fun exitFullNavi(): Boolean

    /** 读车机当前仪表模式（拿不到返回 null）。 */
    fun readNaviMode(): Int?
}

/**
 * 吉利仪表「抢屏」控制器 —— 把仪表屏从车机原厂界面（DimMenu）手里要过来。
 *
 * ## 为什么必须有这一步
 *
 * 只往仪表屏挂一个 `Presentation` 是**不够**的。车机自己的 DimMenu 服务
 * （媒体控制卡片 / 原厂全图导航）占着那块屏，我们挂上去的 Surface 会在
 * 几秒内被回收。真车日志里就是这个模式：
 *
 * ```
 * 21:20:37  Cluster: 仪表副屏已挂载 displayId=3 面板=1024x274
 * 21:20:42  BufferQueueProducer: BufferQueue has been abandoned
 * 21:20:42  SurfaceUtils: disconnecting from surface …disconnectFromSurface
 * ```
 *
 * 挂载是成功的（有日志），画面却没了（Surface 被废）—— 缺的就是「抢」。
 *
 * ## 原版怎么做（反编译结论）
 *
 * `io.dushu.car.geely.GeelyDimModeController` 用**反射**调车机私有 API：
 *
 * - `Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction")`
 * - `getConstructor(Context).newInstance(context)`
 * - `getMethod("switchNaviMode", int).invoke(obj, 3)` → 进入全屏导航（把仪表交出来）
 * - 同方法传 `1` → 退出，还给原厂
 * - `getMethod("getNaviMode")` → 读当前模式
 *
 * 🔴 **值不是猜的，是逐寄存器读出来的**（`GeelyDimModeController.smali`，2026-10-09 核实）：
 * ```
 * enterFullNavi():  const/4 v0, 0x3   # = 3
 * exitFullNavi():   const/4 v0, 0x1   # = 1
 * ```
 * 实车日志（`日志/1791510147443_.../logcat_app.txt`）也印证了值域是 `{1=关, 3=全屏地图}`：
 * ```
 * 09:40:51.276  DimMenuInteraction: switchNaviMode naviMode:1
 * 09:40:51.276  DimMenuInteraction: switchNaviMode    mode:NAVI_MODE_OFF   ← 1 = 关
 * ```
 * ⚠️ PATCH-0046 初版曾把这两值写成 `1` / `0`（**反的**，且 `0` 不在车机值域里）——
 * 于是"抢屏"实际调的是"关闭全屏导航"= 主动让屏，仪表随即被原厂收回 ⇒ 白屏。
 * 详见 `REF-pitfalls.md` §79 / `仪表白屏-诊断报告-20261009.md`。
 *
 * ## 类名为什么要逐个探测
 *
 * 原版硬编码的是 `com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction`，
 * 那是**亿咖通(ECARX)体系**的类名。而本车（吉利 CS11 + Flyme）日志里出现的是
 * `com.geely.dimmenu.MainActivity` —— **体系不同，类名不能照抄**。
 *
 * 所以这里按候选清单逐个探测，**探测结果全部写进诊断日志**，好在真车上确认
 * 到底哪个类名/方法签名存在。探到哪个用哪个，全不中则本控制器静默失效
 * （presenter 仍然只挂窗口，行为与加这个类之前一致，不会更差）。
 *
 * 本类按原版**思路**重写（上游 GPL-3.0，只照思路不抄码）。
 */
internal class GeelyDimModeController(
    private val context: Context,
    private val diagnostics: (String) -> Unit,
) : GeelyNaviModeController {

    /** 已实例化的车机私有对象（探测成功后赋值）。 */
    private var target: Any? = null

    private var switchMethod: Method? = null
    private var readMethod: Method? = null

    /** 最终用上的类名（入日志，便于真车核对）。 */
    var resolvedClass: String? = null
        private set

    /** 探测只做一次。 */
    private var probed = false

    /**
     * 候选类名。
     *
     * 第一条是原版用的 ECARX 类名；后面几条是按本车日志
     * （`com.geely.dimmenu.*`）推断的吉利体系候选。
     */
    private val candidateClasses = listOf(
        "com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction",
        "com.geely.dimmenu.DimMenuInteraction",
        "com.geely.dimmenu.interaction.DimMenuInteraction",
        "com.geely.dimmenu.DimInteraction",
        "com.geely.dimmenu.DimMenuController",
        "android.hardware.dimmenu.DimMenuInteraction",
    )

    /** 构造参数候选：先试 `(Context)`，再试无参。 */
    private fun instantiate(cls: Class<*>): Any? {
        runCatching {
            cls.getConstructor(Context::class.java).newInstance(context)
        }.getOrNull()?.let { return it }
        return runCatching { cls.getConstructor().newInstance() }.getOrNull()
    }

    /** 找 `switchNaviMode(int)`；拿不到再试整型包装类签名。 */
    private fun findSwitch(cls: Class<*>): Method? {
        runCatching { cls.getMethod("switchNaviMode", Int::class.javaPrimitiveType) }
            .getOrNull()?.let { return it }
        return runCatching { cls.getMethod("switchNaviMode", Integer::class.java) }.getOrNull()
    }

    private fun findRead(cls: Class<*>): Method? =
        runCatching { cls.getMethod("getNaviMode") }.getOrNull()

    /**
     * 探测一次：找到可用的类 + 方法就缓存下来。
     *
     * 探测过程逐条写日志（含失败原因），**不抛异常**。
     */
    private fun ensureReady(): Boolean {
        if (probed) return switchMethod != null
        probed = true

        for (name in candidateClasses) {
            val cls = runCatching { Class.forName(name) }.getOrNull()
            if (cls == null) {
                diagnostics("Cluster: DimMenu 探测 · $name → 类不存在")
                continue
            }
            val method = findSwitch(cls)
            if (method == null) {
                diagnostics("Cluster: DimMenu 探测 · $name → 有类但无 switchNaviMode(int)")
                continue
            }
            val obj = instantiate(cls)
            if (obj == null) {
                diagnostics("Cluster: DimMenu 探测 · $name → 实例化失败")
                continue
            }
            target = obj
            switchMethod = method
            readMethod = findRead(cls)
            resolvedClass = name
            diagnostics("Cluster: DimMenu 探测 · $name → 可用（将用它抢屏）")
            return true
        }
        diagnostics("Cluster: DimMenu 探测 · 全部候选不可用，退化为只挂窗口")
        return false
    }

    /** 进入全屏导航（把仪表交给我们）。返回车机是否接受。 */
    override fun enterFullNavi(): Boolean = switchNaviMode(NAVI_MODE_FULL_MAP)

    /** 退出全屏导航（把仪表还给原厂）。 */
    override fun exitFullNavi(): Boolean = switchNaviMode(NAVI_MODE_OFF)

    /** 厂商接口是否可用（探测结果会缓存）。 */
    override fun isAvailable(): Boolean = ensureReady()

    private fun switchNaviMode(mode: Int): Boolean {
        if (!ensureReady()) return false
        val method = switchMethod ?: return false
        val obj = target ?: return false
        return runCatching {
            // ⚠️ 返回值语义：车机方法返回 Boolean，但 `true` **只表示"调用没抛异常"**，
            // **不代表"抢到屏了"**。参数错（如旧的 1=关）照样返回 true。
            // 判"抢到没"要看车机侧日志 `switchNaviMode naviMode:N` / `onChangeNaviMode:N`。
            method.invoke(obj, mode) as? Boolean ?: false
        }.getOrElse { error ->
            diagnostics(
                "Cluster: switchNaviMode($mode) 调用失败 " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            false
        }
    }

    /** 读车机当前仪表模式（拿不到返回 null）。 */
    override fun readNaviMode(): Int? {
        if (!ensureReady()) return null
        val method = readMethod ?: return null
        val obj = target ?: return null
        // 原版对返回值做了 `instance-of Integer` 判定；这里同样容忍
        // int / Integer / Number 三种回包形态（`as? Number` 一并覆盖）。
        return runCatching { (method.invoke(obj) as? Number)?.toInt() }.getOrNull()
    }

    internal companion object {
        /**
         * 车机 `switchNaviMode(int)` 的模式值。
         *
         * 值域来自两处实证（见类注释）：
         * - 反编译 smali：`enterFullNavi=3` / `exitFullNavi=1`；
         * - 实车日志：`naviMode:1` 被车机标注 `NAVI_MODE_OFF`。
         *
         * 车机侧实际只会出现 `1`（关闭）与 `3`（全屏地图）这两个值；
         * 其它值不在值域里，属于无效参数，**不要传**。
         */
        const val NAVI_MODE_OFF = 1
        const val NAVI_MODE_FULL_MAP = 3
    }
}
