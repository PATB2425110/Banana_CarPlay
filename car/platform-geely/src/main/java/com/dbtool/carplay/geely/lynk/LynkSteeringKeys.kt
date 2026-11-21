package com.dbtool.carplay.geely.lynk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lynk 车机**方向盘按键**：把方向盘上的上一曲/播放暂停/下一曲接到 CarPlay。
 *
 * ## 协议（从 DiPlay(Lynk) 反编译提取）
 *
 * ```
 * 绑定：com.geely.inputservice / com.geely.inputservice.InputService
 * 接口：com.geely.lib.oneosapi.input.IInputManager / IInputListener
 * 注册：registerListener(listener, codes)   ← 反射
 * 回调：onKey(code, action)
 * transact：3、4（注册/注销）
 * ```
 *
 * 按键码（DiPlay 注册的）：
 * - `PREVIOUS` / `SEEK_PREVIOUS` —— 上一曲
 * - `CONSOLE_PLAY_PAUSE` —— 播放/暂停
 * - `NEXT` / `SEEK_NEXT` —— 下一曲
 *
 * ## 与仪表菜单的关系
 *
 * 只在仪表停在**音乐页签**时才该响应（见 [LynkClusterMenu.isMusicTab]）。
 *
 * ⚠️ 车机私有类，不存在时反射失败 → 静默降级，不崩。未实机验证。
 */
object LynkSteeringKeys {

    private const val TAG = "DBPlay-LynkWheel"

    private const val SERVICE_PKG = "com.geely.inputservice"
    private const val SERVICE_CLS = "com.geely.inputservice.InputService"
    private const val LISTENER_INTERFACE = "com.geely.lib.oneosapi.input.IInputListener"
    private const val MANAGER_INTERFACE = "com.geely.lib.oneosapi.input.IInputManager"

    /** 注册/注销的 transact 码。 */
    private const val TXN_REGISTER = 3
    private const val TXN_UNREGISTER = 4

    /** 方向盘键值。 */
    const val KEY_PREVIOUS = 1
    const val KEY_PLAY_PAUSE = 2
    const val KEY_NEXT = 3

    /** 按键回调。 */
    fun interface KeyListener {
        /** @param code 见 KEY_* 常量 */
        fun onKey(code: Int)
    }

    private val registered = AtomicBoolean(false)
    private var manager: IBinder? = null
    private var bound = false
    private var listenerProxy: Any? = null
    private var listener: KeyListener? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "方向盘按键服务已连接")
            manager = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "方向盘按键服务断开")
            manager = null
        }
    }

    /**
     * 绑定并注册按键监听。幂等。
     *
     * @return true = 注册成功
     */
    fun register(context: Context, codes: IntArray, onKey: KeyListener): Boolean {
        listener = onKey
        if (!bound) {
            val intent = Intent().setComponent(ComponentName(SERVICE_PKG, SERVICE_CLS))
            bound = runCatching {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            Log.i(TAG, "wheel key service bind=$bound")
        }
        return registerOnService(codes)
    }

    /** 注销。 */
    fun unregister(context: Context) {
        registered.set(false)
        unregisterOnService()
        if (bound) {
            runCatching { context.unbindService(connection) }
                .onFailure { Log.w(TAG, "wheel key unbind failed", it) }
            bound = false
        }
        manager = null
        listener = null
    }

    private fun registerOnService(codes: IntArray): Boolean {
        val remote = manager ?: run {
            Log.w(TAG, "方向盘按键服务未就绪")
            return false
        }
        return try {
            val cls = Class.forName(LISTENER_INTERFACE)
            val handler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "toString" -> "DBPlay wheel key listener"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> args?.firstOrNull() === this
                    "onKey" -> {
                        val code = (args?.getOrNull(0) as? Int) ?: -1
                        Log.i(TAG, "wheel key=$code")
                        if (registered.get()) listener?.onKey(code)
                    }
                }
                null
            }
            val proxy = Proxy.newProxyInstance(cls.classLoader, arrayOf(cls), handler)
            listenerProxy = proxy

            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(MANAGER_INTERFACE)
                data.writeStrongBinder((proxy as? IBinder))
                data.writeIntArray(codes)
                remote.transact(TXN_REGISTER, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
            registered.set(true)
            Log.i(TAG, "registered wheel key listener codes=${codes.toList()}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "wheel key registration failed", e)
            false
        }
    }

    private fun unregisterOnService() {
        val remote = manager ?: return
        try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(MANAGER_INTERFACE)
                remote.transact(TXN_UNREGISTER, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "wheel key unregister failed", e)
        }
        listenerProxy = null
    }
}
