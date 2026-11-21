package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * 「镜像 Surface 先到还是 codec config 先到」的纯状态机。
 *
 * ## 为什么需要它（P1-A，2026-10-21 方案评审）
 *
 * HUD 在中途才挂上 Surface 时，手机可能**早就把 SPS/PPS（codec config）发完了**。
 * 参考实现 DiPlay 的 `AndroidMediaSink.setMirrorSurface` 在收到非空 Surface 时
 * 会**回放 `lastVideoConfig`**（`AndroidMediaSink.java:253-263`）——不这么做，
 * 新建的镜像解码器永远等不到配置，HUD 会一帧都不出。
 *
 * 本类把「什么时候该 configure、什么时候该 start」抽成无 Android 依赖的纯逻辑，
 * 于是「先 A 后 config」和「先 config 后 A」两条顺序都能在 JVM 单测里钉死，
 * 不必上车试。
 *
 * ## 状态含义
 *
 * | 状态 | Surface | Config | 含义 |
 * | --- | --- | --- | --- |
 * | [State.WAITING_SURFACE] | 无 | 有 | 配置已到，等窗口交出 Surface |
 * | [State.WAITING_CONFIG] | 有 | 无 | Surface 已到，等手机发 SPS/PPS |
 * | [State.CONFIG_READY] | 有 | 有 | 解码器可以 configure + start |
 *
 * 注意：[State.WAITING_SURFACE] 与 [State.WAITING_CONFIG] **不能合并成一个
 * "未就绪"**。两者的补救动作相反：前者等窗口（不可控），后者等手机（可催 I 帧），
 * 日志与诊断必须能区分。
 *
 * ## 线程
 *
 * 所有公开方法都加了 `@Synchronized`：`setMirrorSurface` 可能在主线程（窗口挂载），
 * `onVideoConfig` 在协议读线程。状态机本身不启动任何线程。
 */
class MirrorSurfaceGate {

    enum class State {
        WAITING_SURFACE,
        WAITING_CONFIG,
        CONFIG_READY,
    }

    /**
     * 状态机建议调用方执行的动作。
     *
     * [START] 单独存在是为了让上层日志能区分「配置刚下发」与「解码器开始收帧」；
     * 本 fork 的 `VideoDecoder` 构造即启动工作线程，所以生产实现把 START 记为
     * 「解码器已就绪」即可，不需要额外调用。
     */
    enum class Action {
        CONFIGURE,
        START,
    }

    /** 一份 codec config 快照；字节数组在构造时复制，避免被协议层复用缓冲改写。 */
    data class Config(val codec: VideoCodec, val codecData: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Config) return false
            return codec == other.codec && codecData.contentEquals(other.codecData)
        }

        override fun hashCode(): Int = 31 * codec.hashCode() + codecData.contentHashCode()
    }

    private var surfaceAttached = false
    private var config: Config? = null

    val state: State
        @Synchronized get() = when {
            config != null && surfaceAttached -> State.CONFIG_READY
            config != null -> State.WAITING_SURFACE
            else -> State.WAITING_CONFIG
        }

    /** 最近一次 config（null = 手机还没发）。Surface 晚到时靠它回放。 */
    val lastConfig: Config?
        @Synchronized get() = config

    val isSurfaceAttached: Boolean
        @Synchronized get() = surfaceAttached

    /**
     * 收到一个可用 Surface。
     *
     * @return 若配置已存在 ⇒ `[CONFIGURE, START]`（**回放**，P1-A 的核心）；
     *         否则空表，状态停在 [State.WAITING_CONFIG]。
     */
    @Synchronized
    fun attachSurface(): List<Action> {
        surfaceAttached = true
        return replayActions()
    }

    /**
     * Surface 被销毁/换掉。
     *
     * 保留 config：窗口重建后同一会话的 SPS/PPS 仍然有效，重新挂载时应立即回放，
     * 而不是等手机再发一次（它通常只在流开始时发一次）。
     */
    @Synchronized
    fun detachSurface(): List<Action> {
        surfaceAttached = false
        return emptyList()
    }

    /**
     * 收到 codec config。
     *
     * @return Surface 已在 ⇒ `[CONFIGURE, START]`；否则空表，等 Surface。
     */
    @Synchronized
    fun onConfig(codec: VideoCodec, codecData: ByteArray): List<Action> {
        val snapshot = codecData.copyOf()
        if (config?.codec == codec && config?.codecData?.contentEquals(snapshot) == true) {
            // 重复配置不重复下发；但若 Surface 是刚到的，仍然要回放一次。
            return replayActions()
        }
        config = Config(codec, snapshot)
        return replayActions()
    }

    /**
     * 流停止：配置作废，但 Surface 仍然挂着。
     *
     * 新流会重新发 SPS/PPS；继续拿旧配置去 configure 会得到一个解不出画面的解码器。
     */
    @Synchronized
    fun invalidateConfig(): List<Action> {
        config = null
        return emptyList()
    }

    /** 彻底复位（sink close / 换 owner）。 */
    @Synchronized
    fun reset() {
        surfaceAttached = false
        config = null
    }

    private fun replayActions(): List<Action> =
        if (surfaceAttached && config != null) ACTIONS else emptyList()

    private companion object {
        val ACTIONS = listOf(Action.CONFIGURE, Action.START)
    }
}
