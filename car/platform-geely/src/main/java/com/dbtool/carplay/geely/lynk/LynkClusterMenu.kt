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
 * Lynk 车机**仪表菜单联动**：让本应用出现在车机仪表菜单里，并感知仪表页签切换。
 *
 * ## 协议（从 DiPlay(Lynk) 反编译提取）
 *
 * ### ① Binder 控制服务（读仪表菜单状态）
 * ```
 * 绑定：Intent("android.intent.action.GEELY_DIM_MENU_SERVICE")
 *       → com.geely.dimmenu / com.geely.dimmenu.app.service.ExternalAIDLService
 * 接口：com.geely.dimmenu.IExternalService
 * 查询：transact(1) → readInt() → controlStatus
 * ```
 *
 * ### ② 页签监听（**反射** ecarx 的 `DimInteraction`，非公开 API）
 * ```kotlin
 * val dim = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
 * val interaction = dim.getMethod("create", Context::class.java).invoke(null, appCtx)
 * val menu = dim.getMethod("getDimMenuInteraction").invoke(interaction)
 * val cbClass = Class.forName(
 *     "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction\$IDimMenuInteractionCallback")
 * menu.registerDimMenuInteractionCallback(proxy)   // 动态代理实现回调
 * ```
 * 回调方法：
 * - `onControlCenterStateChanged(centerState: Int)` —— 仪表中心态
 * - `onTabChanged(tab: Int)` —— 仪表页签切换
 *
 * 页签常量：`MUSIC_COVER_TAB = 4`、`MENU_CLOSED_TAB = 5`。
 *
 * ## 用途
 *
 * 只在「音乐页签」时方向盘媒体键才生效（[isMusicTab]）。见 [LynkSteeringKeys]。
 *
 * ⚠️ `com.ecarx.xui.adaptapi.diminteraction.DimInteraction` 是车机私有类，
 * 不存在时反射失败 → **静默降级**（[register] 返回 false），不崩。
 */
object LynkClusterMenu {

    private const val TAG = "DBPlay-LynkMenu"

    /** 控制服务接口描述符。 */
    private const val CONTROL_INTERFACE = "com.geely.dimmenu.IExternalService"

    /** 控制服务 action / 组件。 */
    private const val ACTION = "android.intent.action.GEELY_DIM_MENU_SERVICE"
    private const val PKG = "com.geely.dimmenu"
    private const val CLS = "com.geely.dimmenu.app.service.ExternalAIDLService"

    /** 读仪表菜单状态的 transact 码。 */
    private const val TXN_STATUS = 1

    /** 页签常量。 */
    const val MUSIC_COVER_TAB = 4
    const val MENU_CLOSED_TAB = 5

    @Volatile private var currentTab: Int = -1
    @Volatile private var centerState: Int = -1

    private val listening = AtomicBoolean(false)
    private var controlBinder: IBinder? = null
    private var controlBound = false
    private var callbackProxy: Any? = null
    private var interaction: Any? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            controlBinder = service
            val status = queryStatus()
            Log.i(TAG, "instrument control service connected status=$status")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controlBinder = null
            Log.w(TAG, "instrument control service disconnected")
        }
    }

    /** 当前是否停在「音乐页签」。 */
    fun isMusicTab(): Boolean = currentTab == MUSIC_COVER_TAB

    /** 当前仪表中心态。 */
    fun centerState(): Int = centerState

    /**
     * 注册仪表菜单联动。幂等；失败静默降级。
     *
     * @return true = 页签监听注册成功
     */
    fun register(context: Context): Boolean {
        bindControl(context)
        return registerTabListener(context)
    }

    /** 注销。 */
    fun unregister(context: Context) {
        listening.set(false)
        if (controlBound) {
            runCatching { context.unbindService(connection) }
                .onFailure { Log.w(TAG, "instrument control service unbind failed", it) }
            controlBound = false
        }
        unregisterTabListener()
    }

    // ---- 控制服务 ----

    private fun bindControl(context: Context) {
        if (controlBound) return
        val intent = Intent(ACTION).setClassName(PKG, CLS)
        controlBound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        Log.i(TAG, "instrument control service bind=$controlBound")
    }

    /** 查询仪表菜单状态。失败返回 null。 */
    fun queryStatus(): Int? {
        val remote = controlBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CONTROL_INTERFACE)
            if (!remote.transact(TXN_STATUS, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            Log.w(TAG, "instrument control status query failed", e)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ---- 页签监听（反射）----

    private fun registerTabListener(context: Context): Boolean {
        listening.set(true)
        return try {
            val dimCls = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
            val created = dimCls.getMethod("create", Context::class.java)
                .invoke(null, context.applicationContext)
            val menu = dimCls.getMethod("getDimMenuInteraction").invoke(created)
            if (menu == null) {
                Log.w(TAG, "getDimMenuInteraction 返回 null")
                return false
            }
            interaction = menu
            val cbCls = Class.forName(
                "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction\$IDimMenuInteractionCallback"
            )
            val handler = InvocationHandler { _, method, args -> onCallback(method, args) }
            val proxy = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls), handler)
            callbackProxy = proxy
            menu.javaClass.getMethod("registerDimMenuInteractionCallback", cbCls)
                .invoke(menu, proxy)
            Log.i(TAG, "instrument tab listener registered")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "instrument tab listener unavailable; media keys disabled", e)
            false
        }
    }

    private fun unregisterTabListener() {
        val menu = interaction ?: return
        val proxy = callbackProxy ?: return
        try {
            val cbCls = Class.forName(
                "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction\$IDimMenuInteractionCallback"
            )
            menu.javaClass.getMethod("unregisterDimMenuInteractionCallback", cbCls)
                .invoke(menu, proxy)
        } catch (e: Throwable) {
            Log.w(TAG, "instrument tab listener removal failed", e)
        }
        callbackProxy = null
        interaction = null
    }

    /** 动态代理回调分发。 */
    private fun onCallback(method: Method, args: Array<out Any?>?): Any? {
        when (method.name) {
            "toString" -> return "DBPlay instrument tab listener"
            "hashCode" -> return System.identityHashCode(this)
            "equals" -> return args?.firstOrNull() === this
            "onControlCenterStateChanged" -> {
                if (listening.get()) {
                    centerState = (args?.firstOrNull() as? Int) ?: -1
                    Log.i(TAG, "instrument centerState=$centerState mediaKeys=${isMusicTab()}")
                }
            }
            "onTabChanged" -> {
                if (listening.get()) {
                    currentTab = (args?.firstOrNull() as? Int) ?: -1
                    Log.i(TAG, "instrument tab=$currentTab mediaKeys=${isMusicTab()}")
                }
            }
        }
        return null
    }
}
