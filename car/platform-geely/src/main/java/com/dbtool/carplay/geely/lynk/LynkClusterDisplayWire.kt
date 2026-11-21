package com.dbtool.carplay.geely.lynk

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.lang.reflect.Method

/**
 * Lynk 车机私有 Binder 通道：向 `DimNaviService` **借仪表屏**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk 版) 反编译产物提取的协议契约（未在真机跑通，标注「未验证」）。
 * 上游 GPL-3.0；此处**只照协议重写实现**，不抄码。
 *
 * ## 协议
 *
 * ```
 * 服务名：DimNaviService        ServiceManager.checkService("DimNaviService")
 * 接口：  ecarx.dimprotocol.custom.service.IDimNaviService
 * ```
 *
 * | transact | 方法 | 数据 | 语义 |
 * |---|---|---|---|
 * | 9 | setShown(boolean) | writeInt(1/0) | 1=借屏(enable) / 0=还屏(restore) |
 * | 10 | isShown() | readInt() → 0/1 | 查当前是否已借到；1=是、0=否、其他=未知 |
 *
 * ## 为什么必须有这一层
 *
 * 只挂 Presentation + 给解码器 setSurface（DBPlay 现状）＝ 在一块**被车机自己占着**的屏上
 * 建窗口 —— 车机不认，仪表黑屏/无画面，但应用侧一切「正常」（窗口挂上、Surface 有效、
 * 帧更新计数在涨、解码器 rendered first frame 都出得来）。
 *
 * ## 实现要点
 *
 * `ServiceManager` 是 **hidden API**，直接 import 会编不过；必须走反射
 * （`ServiceManager.getService` → `checkService` 同理，本类用 `getService`）。
 *
 * ⚠️ `DimNaviService` 是否存在、`isShown()` 返回什么，**必须回真机核实**。
 * 最稳的验证是写一个只调 [probe] 的最小包，别直接照抄整套出包。
 */
object LynkClusterDisplayWire {

    private const val TAG = "DBPlay-LynkDisplay"

    /** 车机侧服务名（不是组件名，走 ServiceManager）。 */
    private const val SERVICE_NAME = "DimNaviService"

    /** 接口描述符，transact 前必须 writeInterfaceToken。 */
    private const val INTERFACE = "ecarx.dimprotocol.custom.service.IDimNaviService"

    /** transact：setShown(boolean)。 */
    private const val TXN_SET_SHOWN = 9

    /** transact：isShown()。 */
    private const val TXN_IS_SHOWN = 10

    /** 借屏结果。null = 服务不存在或调用失败。 */
    interface Port {
        fun isShown(): Boolean?
        fun enable(): Boolean
        fun restore(): Boolean
        fun close()
    }

    /** 反射拿 ServiceManager.getService(String)；失败返回 null。 */
    private fun serviceManagerGet(name: String): IBinder? = try {
        val cls = Class.forName("android.os.ServiceManager")
        val m: Method = cls.getMethod("getService", String::class.java)
        m.invoke(null, name) as? IBinder
    } catch (e: Throwable) {
        Log.w(TAG, "反射 ServiceManager.getService($name) 失败", e)
        null
    }

    /**
     * 探测服务是否可用。**最小验证用**：只查服务是否存在，不发任何状态改变。
     *
     * @return true = 车机上有 DimNaviService
     */
    fun probe(): Boolean {
        val binder = serviceManagerGet(SERVICE_NAME)
        val ok = binder != null && binder.isBinderAlive
        Log.i(TAG, "probe DimNaviService available=$ok")
        return ok
    }

    /**
     * 打开一条借屏通道。调用方负责 [Port.close]。
     *
     * @return 通道；服务不存在时返回 null（**不抛异常**，便于上层降级）
     */
    fun open(): Port? {
        val binder: IBinder = serviceManagerGet(SERVICE_NAME) ?: run {
            Log.w(TAG, "DimNaviService 不存在，无法借屏")
            return null
        }
        return BinderPort(binder)
    }

    private class BinderPort(private var binder: IBinder?) : Port {

        override fun isShown(): Boolean? = transactReadInt(TXN_IS_SHOWN)?.let { it == 1 }

        override fun enable(): Boolean = transactWriteInt(TXN_SET_SHOWN, 1)

        override fun restore(): Boolean = transactWriteInt(TXN_SET_SHOWN, 0)

        override fun close() {
            binder = null
        }

        /** transact 只写一个 int（setShown）。 */
        private fun transactWriteInt(code: Int, value: Int): Boolean {
            val remote = binder ?: return false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(INTERFACE)
                data.writeInt(value)
                val ok = remote.transact(code, data, reply, 0)
                if (ok) reply.readException()
                ok
            } catch (e: Exception) {
                Log.w(TAG, "transact($code, $value) failed", e)
                false
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        /** transact 只读一个 int（isShown）。0/1 之外返回 null。 */
        private fun transactReadInt(code: Int): Int? {
            val remote = binder ?: return null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(INTERFACE)
                if (!remote.transact(code, data, reply, 0)) return null
                reply.readException()
                reply.readInt()
            } catch (e: Exception) {
                Log.w(TAG, "transact read($code) failed", e)
                null
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
