package com.dbtool.carplay.ecarx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 车机 ECU 方控按键的 OneOS KeyInput 桥。
 *
 * ## 链路
 *
 * ```
 * bindService(com.geely.service.oneosapi/.OneOSApiService)   ← 拿到 IServiceManager
 *        ↓ transact getService(8)                            ← 拿到 IInputManager
 *        ↓ transact registerListener(binder, clientId, keys)  ← 声明关注哪些键码
 * IInputListener.onKeyCodeEvent / onShortClick               ← 车机把方控按键推过来
 * ```
 *
 * 全部走 binder，不需要任何 OneOS SDK 依赖，也不需要系统签名。
 *
 * ## 与 DBTOOL2.0 原版的差异
 *
 * 原版 `OneOSClient.OneOSKeyInputClient` 是**静态单例 + 直接调用业务服务**
 * （`DBToolBridgeService.sInstance.handleSteering*`）的写法，且把重连调度、
 * 按键分发、媒体源注册全塞在一个类里。本移植做了三处收紧：
 *
 * 1. **无全局状态**：实例由 [EcarxCarInputAdapter] 持有，生命周期跟着
 *    `attach` / `detach` 走，进程里不会留下 `static sInstance`。
 * 2. **回调而不是直连业务**：本类只吐 `(keyCode, action, repeatCount)` 原始事件，
 *    键码含义与"该不该响应"由上层决定。这样同一份桥接逻辑将来也能服务
 *    `:car:steering` 的路由，不必重写。
 * 3. **可重复 start/stop**：`stop()` 之后能再 `start()`，不依赖进程常驻。
 *
 * ## 线程
 *
 * 所有 binder 操作都排在 [worker] 这一个线程上。原因是
 * [currentControlIndex] 是**同步** transact，而它又要在收到按键后调用——
 * 若直接在 Binder 回调线程里做，会占住 Binder 线程池。回调进来到分发出去
 * 因此统一经过 [worker]。
 *
 * ## 关于 clientId
 *
 * `registerListener` 的 `clientId` 参数在吉利车机上相当于白名单标识：
 * 不在名单里的包名可能注册失败或收不到事件。DBTOOL2.0 在真机上用的是
 * 帆书的包名 `io.dushu.car`，并注明这是"伪装成帆书的 clientId"。
 * 本移植把该值提成构造参数，默认值见 [DEFAULT_CLIENT_ID]，不做隐藏。
 */
internal class OneOsKeyInputBridge(
    private val context: Context,
    private val clientId: String,
    private val keyCodes: IntArray,
    private val onRawKey: (keyCode: Int, action: Int, repeatCount: Int) -> Unit,
) : ServiceConnection {

    private val worker = HandlerThread("ecarx-key-input").apply { start() }
    private val handler = Handler(worker.looper)

    private val started = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)

    @Volatile
    private var keyInputBinder: IBinder? = null

    @Volatile
    private var reconnectAttempts = 0

    private val listenerBinder = InputListenerBinder()

    // ------------------------------------------------------------- 生命周期

    /** 幂等。重复调用不会重复 bind。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        reconnectAttempts = 0
        handler.post { bindOnWorker() }
    }

    /** 幂等。停掉之后仍可再次 [start]。 */
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        handler.post { unbindOnWorker() }
    }

    /** 终态：停掉并释放 worker 线程。释放后不可再用。 */
    fun close() {
        stop()
        handler.post { worker.quitSafely() }
    }

    /** 是否已成功注册到车机（诊断用）。 */
    fun isRegistered(): Boolean = registered.get()

    // ------------------------------------------------------------- 状态查询

    /**
     * 查询当前方控焦点落在哪个控制器上。
     *
     * `2` = 媒体控制器。车机原生据此判断方控该发给谁；焦点在车信息页时
     * 按键不应被我们消费，否则会出现"在别的页面按音量键结果切了歌"。
     *
     * @return 控制器索引；binder 未就绪或查询失败时返回 [CONTROL_INDEX_UNKNOWN]。
     */
    fun currentControlIndex(): Int {
        val binder = keyInputBinder ?: return CONTROL_INDEX_UNKNOWN
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_INPUT_MANAGER)
            if (!binder.transact(TX_GET_CONTROL_INDEX, data, reply, 0)) {
                return CONTROL_INDEX_UNKNOWN
            }
            reply.readException()
            reply.readInt()
        } catch (error: Throwable) {
            Log.w(TAG, "getControlIndex 失败", error)
            CONTROL_INDEX_UNKNOWN
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // -------------------------------------------------------- ServiceConnection

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.i(TAG, "OneOSApiService 已连接 name=$name")
        if (service == null) {
            Log.w(TAG, "onServiceConnected 但 binder 为 null")
            scheduleReconnect()
            return
        }
        handler.post { acquireOnWorker(service) }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.w(TAG, "OneOSApiService 断开 name=$name")
        bound.set(false)
        keyInputBinder = null
        registered.set(false)
        scheduleReconnect()
    }

    // --------------------------------------------------------------- 内部实现

    private fun bindOnWorker() {
        if (!started.get()) return
        if (bound.get()) return
        val ok = runCatching {
            context.bindService(
                Intent().setComponent(ONE_OS_API_COMPONENT),
                this,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrElse { error ->
            Log.w(TAG, "bindService 抛异常", error)
            false
        }
        bound.set(ok)
        Log.i(TAG, "bindService(${ONE_OS_API_COMPONENT.flattenToShortString()}) ok=$ok")
        if (!ok) scheduleReconnect()
    }

    private fun unbindOnWorker() {
        val binder = keyInputBinder
        if (binder != null && registered.getAndSet(false)) {
            runCatching { transactUnregister(binder) }
                .onFailure { Log.w(TAG, "unregisterListener 失败", it) }
        }
        keyInputBinder = null
        if (bound.getAndSet(false)) {
            runCatching { context.unbindService(this) }
                .onFailure { Log.w(TAG, "unbindService 失败", it) }
        }
        reconnectAttempts = 0
        Log.i(TAG, "已解绑 OneOS KeyInput")
    }

    private fun acquireOnWorker(serviceManager: IBinder) {
        if (!started.get()) return
        val keyInput = acquireKeyInputBinder(serviceManager)
        if (keyInput == null) {
            Log.w(TAG, "拿不到 KeyInput binder，稍后重试")
            scheduleReconnect()
            return
        }
        keyInputBinder = keyInput
        registerOnWorker(keyInput)
    }

    /**
     * 取 KeyInput binder。
     *
     * 先试强类型（`TX=1`，与 DBTOOL2.0 源码树里的 `IServiceManager.Stub` 一致），
     * 再试裸 transact（`TX=2`，DBTOOL2.0 实际使用的值）。两者的矛盾见
     * [OneOsServiceManagerProxy] 的类注释——不把任一方固化成唯一实现。
     */
    private fun acquireKeyInputBinder(serviceManager: IBinder): IBinder? {
        val strongTyped = runCatching {
            OneOsServiceManagerProxy(serviceManager).getService(SERVICE_ID_KEY_INPUT)
        }.getOrElse { error ->
            Log.d(TAG, "getService 强类型(TX=1) 失败: $error")
            null
        }
        if (strongTyped != null) {
            Log.i(TAG, "getService($SERVICE_ID_KEY_INPUT) 走强类型 TX=1 命中")
            return strongTyped
        }

        val legacy = runCatching {
            rawGetService(serviceManager, OneOsServiceManagerProxy.TX_GET_SERVICE_LEGACY)
        }.getOrElse { error ->
            Log.d(TAG, "getService 裸 transact(TX=2) 失败: $error")
            null
        }
        if (legacy != null) {
            Log.i(TAG, "getService($SERVICE_ID_KEY_INPUT) 走裸 transact TX=2 命中")
            return legacy
        }

        return null
    }

    private fun rawGetService(serviceManager: IBinder, tx: Int): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE_MANAGER)
            data.writeInt(SERVICE_ID_KEY_INPUT)
            if (!serviceManager.transact(tx, data, reply, 0)) return null
            reply.readException()
            return reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun registerOnWorker(keyInput: IBinder) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_INPUT_MANAGER)
            data.writeStrongBinder(listenerBinder)
            data.writeString(clientId)
            data.writeIntArray(keyCodes)
            // reply 传 null：该调用是 oneway，服务端不写回复。
            val ok = keyInput.transact(TX_REGISTER_LISTENER, data, null, 0)
            registered.set(ok)
            Log.i(TAG, "registerListener(clientId=$clientId, keys=${keyCodes.size}) ok=$ok")
            if (!ok) scheduleReconnect()
        } catch (error: Throwable) {
            Log.w(TAG, "registerListener 抛异常", error)
            registered.set(false)
            scheduleReconnect()
        } finally {
            data.recycle()
        }
    }

    private fun transactUnregister(keyInput: IBinder) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_INPUT_MANAGER)
            data.writeStrongBinder(listenerBinder)
            keyInput.transact(TX_UNREGISTER_LISTENER, data, null, 0)
        } finally {
            data.recycle()
        }
    }

    private fun scheduleReconnect() {
        if (!started.get()) return
        val attempt = ++reconnectAttempts
        val delay = RECONNECT_BASE_MILLIS * attempt.coerceAtMost(MAX_BACKOFF_STEPS)
        Log.i(TAG, "第 $attempt 次重连将在 ${delay}ms 后开始")
        handler.postDelayed({ bindOnWorker() }, delay)
    }

    private fun dispatch(keyCode: Int, action: Int, repeatCount: Int) {
        if (!started.get()) return
        // 回调发生在 Binder 线程；后续查方控焦点是同步 transact，不能占住它。
        handler.post { onRawKey(keyCode, action, repeatCount) }
    }

    /**
     * `IInputListener` 的裸 binder 实现。
     *
     * 不引 OneOS SDK，自己实现 `onTransact`：车机只认 descriptor 字符串
     * 和 TX 号，与 Java 包名无关。
     */
    private inner class InputListenerBinder : Binder() {

        init {
            attachInterface(null, DESCRIPTOR_INPUT_LISTENER)
        }

        override fun getInterfaceDescriptor(): String = DESCRIPTOR_INPUT_LISTENER

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // oneway 调用时 reply 为 null，只有 INTERFACE_TRANSACTION 需要它。
            if (code == INTERFACE_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            return runCatching {
                data.enforceInterface(DESCRIPTOR_INPUT_LISTENER)
                when (code) {
                    TX_ON_KEY_CODE_EVENT -> {
                        val keyCode = data.readInt()
                        val action = data.readInt()
                        val repeat = data.readInt()
                        dispatch(keyCode, action, repeat)
                        true
                    }

                    TX_ON_SHORT_CLICK -> {
                        val keyCode = data.readInt()
                        val repeat = data.readInt()
                        // 短按 = 一次完整的按下，action 固定按 DOWN 处理，
                        // 不沿用 DBTOOL2.0 里那个来源不明的常量 1。
                        dispatch(keyCode, ACTION_DOWN, repeat)
                        true
                    }

                    // 3..6：反编译里（carplaybox_v32 的 IInputListener 实现）这几个码
                    // 与 2 **同形**——各带两个 int，且**只消费、不分发**。
                    // 语义未公开，所以这里也只读掉参数、记一行调试日志。
                    //
                    // ⚠️ 关键是**不要返回 false**：返回 false 会落回 super.onTransact，
                    // 车机侧虽然收不到异常（这些调用都是 oneway），但我们会失去
                    // "这个码来过"的唯一线索，排查时只能看到一片安静。
                    in TX_UNKNOWN_FIRST..TX_UNKNOWN_LAST -> {
                        val keyCode = data.readInt()
                        val action = data.readInt()
                        Log.d(TAG, "IInputListener code=$code key=$keyCode action=$action（语义未定义，仅消费）")
                        true
                    }

                    else -> false
                }
            }.getOrElse { error ->
                Log.w(TAG, "IInputListener.onTransact code=$code 处理失败", error)
                false
            }
        }
    }

    companion object {
        private const val TAG = "Ecarx-OneOS"

        private val ONE_OS_API_COMPONENT = ComponentName(
            "com.geely.service.oneosapi",
            "com.geely.service.oneosapi.OneOSApiService",
        )

        private const val DESCRIPTOR_SERVICE_MANAGER = "com.geely.lib.oneosapi.IServiceManager"
        private const val DESCRIPTOR_INPUT_MANAGER = "com.geely.lib.oneosapi.input.IInputManager"
        private const val DESCRIPTOR_INPUT_LISTENER = "com.geely.lib.oneosapi.input.IInputListener"

        /** OneOS 里 KeyInput 子系统的服务代号。 */
        const val SERVICE_ID_KEY_INPUT = 8

        private const val TX_REGISTER_LISTENER = 3
        private const val TX_UNREGISTER_LISTENER = 4
        private const val TX_GET_CONTROL_INDEX = 5

        /** `IInputListener.onKeyCodeEvent(keyCode, action, repeatCount)`。 */
        private const val TX_ON_KEY_CODE_EVENT = 1

        /** `IInputListener.onShortClick(keyCode, repeatCount)`。 */
        private const val TX_ON_SHORT_CLICK = 2

        /**
         * `IInputListener` 里**语义未公开**的事务码区间。
         *
         * 反编译 carplaybox_v32 的 `IInputListener` 实现（`BinderC0364wa`）：
         * `if (1 > code || code >= 7) super.onTransact(...)` ⇒ 服务端只会用 **1..6**。
         * 其中只有 code 1 真正分发；2..6 都是"读两个 int 然后什么都不做"。
         *
         * 这里把 3..6 也收下（2 已按短按处理，见 [TX_ON_SHORT_CLICK]），
         * 只为让行为与车机预期一致，并留下日志线索。
         */
        private const val TX_UNKNOWN_FIRST = 3
        private const val TX_UNKNOWN_LAST = 6

        /** 方控焦点在媒体控制器上（车机原生用 2 表示）。 */
        const val CONTROL_INDEX_MEDIA = 2

        /** 查询失败/未就绪。与 DBTOOL2.0 的 `-999` 保持一致，便于对日志。 */
        const val CONTROL_INDEX_UNKNOWN = -999

        /**
         * `onKeyCodeEvent` 的 action 值。
         *
         * 【2026-10-19 更正】原先这里写着"这两个值是推断的"——现在**已被反编译证实**。
         * carplaybox_v32 的 `IInputListener` 实现（`BinderC0364wa`）里那条分发条件
         * 是 `if (code == 1 && action >= 0 && action < 2)`，即合法取值只有 0 和 1，
         * 与 Android `KeyEvent.ACTION_DOWN/ACTION_UP` 一致。
         *
         * ⚠️ 注意条件写的是 `action < 2`（**两个都放行**），说明车机按下与抬起**都会**
         * 回调 —— 上层若要"一次按压只响应一次"，去抖必须自己做（见 `CarInputRouter`）。
         */
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        private const val RECONNECT_BASE_MILLIS = 3_000L
        private const val MAX_BACKOFF_STEPS = 5

        /**
         * `registerListener` 的 clientId。
         *
         * 默认用帆书的包名：吉利车机对能接方控的第三方有白名单，
         * 该值在 DBTOOL2.0 上实测能收到按键事件。换成自己的包名大概率
         * 注册不上——除非把应用加进车机白名单。
         */
        const val DEFAULT_CLIENT_ID = "io.dushu.car"

        /**
         * 本机是否存在 OneOS 服务总线。
         *
         * 判据是 `com.geely.service.oneosapi/.OneOSApiService` 这个组件能不能被解析到。
         * 不用 `Build.MANUFACTURER` 之类的字符串：厂商名会被刷机包和白牌车机改掉，
         * 而组件在就在、不在就不在。
         *
         * 注意 `resolveService` 在 Android 11+ 上要求先声明 `<queries>`，
         * 否则对任何包都返回 null——本模块的 AndroidManifest 已声明。
         */
        fun isAvailable(context: Context): Boolean = runCatching {
            context.packageManager.resolveService(
                Intent().setComponent(ONE_OS_API_COMPONENT),
                0,
            ) != null
        }.getOrDefault(false)
    }
}
