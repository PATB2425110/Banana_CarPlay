package com.dbtool.carplay.geely.hud

import android.view.Surface

/**
 * HUD `Surface` 的生命周期宿主：**create / release 严格成对**（2026-10-22）。
 *
 * ## 为什么必须单独一层
 *
 * `GeelyClusterPresentation` 的教训（PATCH-0069）：只把引用置空不够 ——
 * `Surface(SurfaceTexture)` 是一个持有 BufferQueue 引用的包装对象，框架
 * **不会**替你释放它。窗口每销毁一次漏一个，长时间多次重连后内存上涨，
 * 更常见的现场表现是「重连后花屏」：旧 BufferQueue 仍被引用。
 *
 * 本类保证：
 * 1. 交付给上层（`sink`）的每个非空 Surface，在换绑/关闭时**先** `sink(null)`
 *    声明旧通道作废，**再** `release()`；
 * 2. 同一个 Surface 重复交付是幂等的（不会 release 两次）；
 * 3. `release()` 之后的迟到回调一律丢弃并就地释放，不会把已销毁的 Surface
 *    重新交给解码器。
 *
 * ## 线程
 *
 * `TextureView.SurfaceTextureListener` 与 `Presentation` 生命周期都在主线程，
 * 因此本类不加锁；调用方不要跨线程使用。
 */
class HudSurfaceHost(
    private val sink: (Surface?) -> Unit,
) {
    private var delivered: Surface? = null
    private var released = false

    /** 当前已交付给解码器的 Surface（诊断用）。 */
    val currentSurface: Surface? get() = delivered

    /**
     * 交付一个新的 Surface；`null` = 当前通道销毁。
     *
     * 非空且无效的 Surface 直接拒绝并释放 —— 交给 `MediaCodec` 只会得到一个
     * 初始化失败的异常，日志还看不出根因。
     */
    fun publish(next: Surface?) {
        if (released) {
            next?.releaseQuietly()
            return
        }
        if (next != null && !next.isValid) {
            next.releaseQuietly()
            return
        }
        if (delivered === next) return

        val previous = delivered
        delivered = next
        if (previous != null) {
            // 顺序不能反：先让解码器停止使用旧 Surface，再 release 它的 native 引用。
            sink(null)
            previous.releaseQuietly()
        }
        if (next != null) {
            sink(next)
        }
    }

    /** 窗口销毁 / 会话结束：解绑并释放，之后任何 `publish` 都是空操作。 */
    fun release() {
        if (released) return
        released = true
        val previous = delivered
        delivered = null
        if (previous != null) {
            sink(null)
            previous.releaseQuietly()
        } else {
            // 即使没有 Surface，也补一次 null：上层可能记着「上一轮有输出」。
            sink(null)
        }
    }

    private fun Surface.releaseQuietly() {
        runCatching { release() }
    }
}
