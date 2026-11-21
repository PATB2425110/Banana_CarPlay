package com.shilapi.xcertplay.media

import android.util.Log

/**
 * 建轨失败时的降级助手。
 *
 * ## 为什么需要它（DBPlay PATCH-0013，思路来自 DiPlay）
 *
 * 部分车机 ROM 不接受 `AudioTrack(streamType, ...)` 这种老式构造（例如把
 * `STREAM_MUSIC` 之外的车机私有流号映射到「大印/灯光秀」一类的非扬声器输出），
 * 构造出来的轨可能 `state != STATE_INITIALIZED`，也可能直接抛异常。
 *
 * 这两种失败必须让调用方**有机会退回按 usage 建轨**，否则结果就是「整条音频没了」——
 * 比声音走错通道更糟。
 *
 * 抽成独立对象是为了能在 `shared`（纯 Kotlin/JVM 单测可达）里单测这段分支逻辑，
 * 而不必启动一个真实 `AudioTrack`。
 *
 * ⚠️ 本文件是**重写实现**，不是 DiPlay 源码的复制（DiPlay 为 GPL-3.0，
 * 照搬会把传染性带进本项目）。
 */
internal object LegacyAudioFallback {

    private const val TAG = "xcertplay-usb"

    /**
     * 先尝试 [createLegacy]；若建出来的轨未被初始化或抛异常，则释放可能的残轨并
     * 走 [createFallback]。
     *
     * @param createLegacy 老式 streamType 建轨
     * @param isInitialized 判断建出来的轨是否可用
     * @param release 释放已经建出来的（可能是坏掉的）轨
     * @param createFallback 按 usage 建轨
     */
    fun <T> build(
        createLegacy: () -> T,
        isInitialized: (T) -> Boolean,
        release: (T) -> Unit,
        createFallback: () -> T,
    ): T {
        val legacy = try {
            createLegacy()
        } catch (error: Exception) {
            Log.w(TAG, "legacy stream track rejected; falling back to usage-based track", error)
            return createFallback()
        }
        if (isInitialized(legacy)) return legacy
        Log.w(TAG, "legacy stream track not initialized; falling back to usage-based track")
        runCatching { release(legacy) }
        return createFallback()
    }
}
