package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val bindAddress: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    override fun onCreate() {
        super.onCreate()
        CarPlayInternetConnection.initialize(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get() || tun != null || bridge != null) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                // This tunnel carries only link-local IPv6. Android otherwise blocks
                // the unconfigured IPv4 family, including the car's IPv4 DNS server.
                // No IPv4 route is added: it must fall through to the original network.
                .allowFamily(OsConstants.AF_INET)
                .addAllowedApplication(packageName)
                .allowBypass()
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd
            CarPlayInternetConnection.usbVpnActive = true
            Log.i(TAG, "USB VPN established: link-local IPv6 only; IPv4/DNS fall-through enabled")

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     *
     * @param bindAddress 本次会话**实际通告给 iPhone 的那一个主地址**。
     *
     * 移植自 DiPlay v0.2.16 / `docs/KX11_ROUTED_HOTSPOT.md` 第 6 条：
     * AirPlay 监听、mDNS 通告与 iAP2 0x5703 必须使用同一个选定主地址。
     * 旧实现固定 bind `::`（所有地址），在 KX11/CS11 原厂热点这条“路由式热点”上，
     * 监听 socket 会继承进程默认网络的 fwmark，和手机入网侧不是同一条路，
     * 现场表现就是 40 秒内零 `connection accepted`。现在显式绑定到选定地址，
     * 与 DiPlay 的显式地址监听一致。
     *
     * 非 IPv4 地址只接受**带 scope 的 link-local IPv6**（与上游
     * `CarPlayController.runWireless` 的校验同一口径）。
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get() || tun != null || bridge != null) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            // `::` 是总开关关闭时的回滚路径（1.1.42 行为），必须放行。
            if (!bindAddress.isAnyLocalAddress &&
                bindAddress !is java.net.Inet4Address &&
                !(bindAddress is Inet6Address && bindAddress.isLinkLocalAddress && bindAddress.scopeId != 0)
            ) {
                throw IllegalArgumentException(
                    "wireless AirPlay bindAddress must be IPv4 or a scoped link-local IPv6 literal",
                )
            }
            startAirPlayServer(
                generation,
                AirPlayAttachment(
                    bindAddress,
                    config,
                    identity,
                    pairings,
                    mfi,
                    listener,
                    media,
                ),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    /** Does not tear down a newer wireless listener or any other app's VPN. */
    @Synchronized
    fun detachWired() {
        if (tun != null || bridge != null) {
            Log.i(TAG, "releasing USB VPN attachment")
            releaseLocked()
        }
    }

    override fun onRevoke() {
        detach()
        super.onRevoke()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val server = ServerSocket()
        server.bind(InetSocketAddress(replacement.bindAddress, replacement.config.port))
        Log.i(TAG, "airplay listener bound=${server.localSocketAddress}")
        attachment = replacement
        serverSocket = server
        Thread(
            { acceptLoop(generation, server) },
            "airplay-accept",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get()) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            private var sessionWasActive = false

                            override fun onSessionActive(session: AirPlaySession) {
                                sessionWasActive = true
                                current.listener.onSessionActive(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (sessionWasActive) releaseEndedWiredSession(generation)
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun releaseEndedWiredSession(generation: Int) {
        // Session callbacks can be emitted during teardown. Never block that callback on
        // the service monitor or allow an old generation to close a new wireless listener.
        Thread({
            synchronized(this) {
                if (generation != attachGeneration || tun == null) return@Thread
                if (synchronized(sessionsLock) { sessions.isNotEmpty() }) return@Thread
                Log.i(TAG, "wired AirPlay session ended; releasing USB VPN")
                releaseLocked()
            }
        }, "wired-session-vpn-release").apply { isDaemon = true; start() }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                }
                listener.onTransportError(message)
                stopSelf()
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        val oldTun = tun
        val oldServer = serverSocket
        val oldBridge = bridge
        if (oldTun != null) CarPlayInternetConnection.usbVpnActive = false
        tun = null
        serverSocket = null
        bridge = null
        closeCarPlayTransportResources(
            closeTun = { oldTun?.close() },
            closeServer = { oldServer?.close() },
            closeSessions = ::closeSessionsLocked,
            closeBridge = { oldBridge?.close() },
            onFailure = { name, error -> Log.w(TAG, "cleanup failed: $name", error) },
        )
        if (oldTun != null) Log.i(TAG, "USB VPN tunnel release completed")
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
