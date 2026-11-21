package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer

internal data class UsbReadQueueResult(val queued: Boolean, val firstBytes: Int, val fallbackBytes: Int? = null)

/**
 * 对"厂商固件**明确拒绝**某个长度的排队请求"这一现象的有界兼容假设。
 *
 * 2026-10-08 移植自 DiPlay（`shared/.../transport/UsbReadQueuePolicy.kt`）。
 *
 * ## 这不是 API 28 的长度上限
 *
 * AOSP 的 Android 9 `UsbRequest.queue(ByteBuffer)` 接受任意长度，且在返回 false 时
 * 会把自己的排队状态清掉（见 DiPlay 引用的 AOSP 源码链接）。所以**默认不做任何限制**：
 * 先按调用方要的长度排一次。
 *
 * ## 只在"明确被拒"时才退一次
 *
 * 只有当 [submit] **明确返回 false**（而不是抛异常、也不是偷偷改了 buffer 状态）时，
 * 才把长度缩到 16 KiB 再试**一次**，并把这次成功的长度记下来给后续调用复用。
 * 依据是 DiPlay v0.2.14 的现场结论（#362）：
 * *"retry explicitly rejected large USB reads once at 16 KiB"*。
 *
 * ## 三条纪律
 *
 * 1. **不重试"含糊的失败"**：抛异常或 buffer 状态被改动，都说明这次请求的结果不可知，
 *    再排一次可能把两个请求叠在一起。此时直接判失败。
 * 2. **只在有状态锁时调用**：本类自身不加锁，发布、排队、开闭态检查都必须在调用方的锁里。
 * 3. **成功过的长度会被记住**：避免每个包都先撞一次墙。
 */
internal class UsbReadQueuePolicy {
    private var successfulLimit: Int? = null

    fun queue(buffer: ByteBuffer, checkOpen: () -> Unit, submit: (ByteBuffer) -> Boolean): UsbReadQueueResult {
        require(buffer.isDirect && !buffer.isReadOnly) { "USB read requires a writable direct buffer" }
        checkOpen()
        val position = buffer.position()
        val originalLimit = buffer.limit()
        val firstBytes = minOf(buffer.remaining(), successfulLimit ?: buffer.remaining())
        buffer.limit(position + firstBytes)
        if (submit(buffer)) return UsbReadQueueResult(true, firstBytes)
        // AOSP guarantees an unchanged buffer on explicit false. Do not retry an ambiguous
        // request that threw or unexpectedly changed its buffer state.
        check(buffer.position() == position && buffer.limit() == position + firstBytes) {
            "Rejected USB queue changed its buffer state"
        }
        if (firstBytes <= COMPATIBILITY_BYTES) {
            buffer.limit(originalLimit)
            return UsbReadQueueResult(false, firstBytes)
        }
        checkOpen()
        buffer.limit(position + COMPATIBILITY_BYTES)
        val queued = submit(buffer)
        if (queued) successfulLimit = COMPATIBILITY_BYTES else buffer.limit(originalLimit)
        return UsbReadQueueResult(queued, firstBytes, COMPATIBILITY_BYTES)
    }

    private companion object {
        const val COMPATIBILITY_BYTES = 16 * 1024
    }
}
