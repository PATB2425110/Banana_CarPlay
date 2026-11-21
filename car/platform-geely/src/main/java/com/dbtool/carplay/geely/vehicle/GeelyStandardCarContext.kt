package com.dbtool.carplay.geely.vehicle

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.UserHandle

/**
 * One wrapper per independent Android Car. It owns the SDK's ACTUAL connection arguments, not our
 * external callback. Car.java:258 retains this context verbatim. Its onServiceDisconnected sets
 * state=0 before disconnect (243-253), so a second disconnect can return without unbinding.
 *
 * IMPORTANT: the captured Car.java:497 calls bindServiceAsUser, NOT bindService. Both public
 * SDK37 methods are intercepted. The four-argument descriptor already exists on this Android 9
 * runtime (the shipped Car itself invokes it). We forward the original UserHandle unchanged;
 * there is no reflective hidden-method access, user switch, identity change or permission bypass.
 */
internal class GeelyStandardCarContext(
    private val parent: Context,
    cleanupFailure: (RuntimeException) -> Unit,
) : ContextWrapper(parent), AutoCloseable {
    private val bindings = GeelyOwnedBindingRegistry<ServiceConnection>(
        backendUnbind = { parent.unbindService(it) },
        cleanupFailure = cleanupFailure,
    )

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
        bindings.bind(conn) { parent.bindService(service, conn, flags) }

    @SuppressLint("NewApi") // Public in compile SDK37; exact method is present in the supplied Android 9 ROM.
    override fun bindServiceAsUser(
        service: Intent,
        conn: ServiceConnection,
        flags: Int,
        user: UserHandle,
    ): Boolean = bindings.bind(conn) { parent.bindServiceAsUser(service, conn, flags, user) }

    override fun unbindService(conn: ServiceConnection) = bindings.unbind(conn)

    override fun close() = bindings.close()
}
