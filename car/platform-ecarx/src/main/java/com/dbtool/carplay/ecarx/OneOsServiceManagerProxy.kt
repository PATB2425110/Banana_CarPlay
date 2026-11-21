package com.dbtool.carplay.ecarx

import android.os.IBinder
import android.os.Parcel

/**
 * OneOS 服务总线 `IServiceManager` 的客户端代理。
 *
 * OneOS 把各子系统抽象成 int 代号，用 `getService(code)` 取 binder。
 * 我们只要一个代号：[OneOsKeyInputBridge.SERVICE_ID_KEY_INPUT]。
 *
 * ## 为什么不用现成的强类型 Stub
 *
 * DBTOOL2.0 的源码树里带了 `com.geely.lib.oneosapi.IServiceManager`（Java 版
 * AIDL Stub），它的 `onTransact` 明确写着：
 *
 * ```
 * case 1: getService(int)          → reply.writeStrongBinder(result)
 * case 2: addService(int, IBinder)
 * ```
 *
 * 但同一个工程的 `OneOSClient` 取 KeyInput binder 时用的是
 * `transact(TX_GET_SERVICE = 2, ...)` —— 按它自己那份 Stub，TX=2 是
 * `addService`，只会读走一个 int 和一个 binder，**不可能**回一个 binder 出来。
 *
 * 两者自相矛盾，说明"哪个 TX 才是 getService"在不同 OneOS 版本上并不一致。
 * 所以这里不把任一方固化成唯一实现，而是**两种都试**（见 [OneOsKeyInputBridge]），
 * 谁先成功就用谁。TX 常量因此单独暴露，便于真机上按日志调整。
 *
 * descriptor 是硬编码字符串，与 Java 包名无关，所以放在本包下
 * 不影响与车机服务的互通。
 */
internal class OneOsServiceManagerProxy(private val remote: IBinder) {

    /**
     * 取一个子系统的 binder。
     *
     * @return 子系统 binder；服务端返回 null 或 transact 失败时返回 null（不抛）。
     */
    fun getService(code: Int): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(code)
            if (!remote.transact(TX_GET_SERVICE, data, reply, 0)) return null
            reply.readException()
            reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        const val DESCRIPTOR = "com.geely.lib.oneosapi.IServiceManager"

        /** 与 DBTOOL2.0 源码树里那份 `IServiceManager.Stub` 一致。 */
        const val TX_GET_SERVICE = 1

        /** DBTOOL2.0 的 `OneOSClient` 实际使用的值（与它自带的 Stub 矛盾，见类注释）。 */
        const val TX_GET_SERVICE_LEGACY = 2
    }
}
