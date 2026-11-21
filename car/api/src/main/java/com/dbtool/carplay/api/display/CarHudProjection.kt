package com.dbtool.carplay.api.display

import android.view.Surface

/**
 * HUD 画面投屏的平台能力契约（2026-10-22）。
 *
 * ## 与 [com.dbtool.carplay.api.navigation.CarHudBridge] 的区别（别混）
 *
 * | | [CarHudBridge] | 本接口 |
 * | --- | --- | --- |
 * | 传什么 | 导航转向提示（文字/箭头/距离） | **整幅 CarPlay 地图画面** |
 * | 谁画 | 车机自带地图服务 | 我们用 `Presentation` + `TextureView` 自己画 |
 * | 依赖 | hudbridge 外部服务 | 平台层的一块 display（1024×274） |
 *
 * 两条链路互不影响：HUD 提示关掉不影响投屏，投屏关掉也不影响提示。
 *
 * ## 硬约束（来自 2026-10-21 实车）
 *
 * 1. **必须用 `Presentation`**：1.0.8 实测 `TYPE_APPLICATION_OVERLAY.addView()`
 *    返回成功、1ms 后建窗被拒，系统一个像素都没渲染；
 * 2. **禁止按 `com.geely.hud` 定位**：它的 Activity 跑在 display 2（仪表内 HUD 区），
 *    会把画面投到仪表而不是 HUD 玻璃上；
 * 3. 找不到匹配屏 ⇒ [CarHudStartResult.Unavailable] + [HUD_NOT_FOUND]，
 *    **不回退 display 2**；
 * 4. Surface 必须成对释放（窗口销毁/换 Surface 时先解绑再释放）。
 */
interface CarHudProjection {

    /** 本机当前是否恰好存在一块可用的 HUD 屏。 */
    fun isAvailable(): Boolean

    /**
     * 启动或更新 HUD 窗口。
     *
     * 幂等：窗口已在且目标屏未变时只更新裁剪参数，不重建窗口。
     *
     * @param profile 裁剪 / 缩放 / 模式
     * @param clusterWindowActive 仪表副屏窗口是否已挂载。`true` 时共享 111 会变成
     *        三路解码（110 主 + 111 主 + 111 mirror），这是已知代价，不阻断；
     *        `false` 时 HUD 单独消费 111。
     * @param onSurface Surface 生命周期回调；窗口层保证 release 时补一次 `null`。
     */
    fun start(
        profile: CarHudDisplayProfile,
        clusterWindowActive: Boolean,
        onSurface: (Surface?) -> Unit,
    ): CarHudStartResult

    /** 就地更新裁剪/缩放（窗口已挂时下一帧生效）。 */
    fun updateProfile(profile: CarHudDisplayProfile)

    /** 停止并释放窗口与 Surface。幂等。 */
    fun stop()

    /** 当前阶段状态（供设置页/诊断使用；不参与投屏决策）。 */
    fun status(): CarHudDisplayStatus

    /**
     * 主视频流开始/结束（由会话桥按 `STREAM_TYPE_ALT_SCREEN = 111` 转发）。
     *
     * 默认空实现，保持其它平台零改动。
     */
    fun onScreenStreamActive(active: Boolean) {}

    /** 镜像解码器是否已经拿到 codec config（P1-A 的回放结果）。 */
    fun onConfigReady(ready: Boolean) {}

    /**
     * 流真正建立时的回调。
     *
     * 为什么需要：HUD 可能在握手**之前**就开了开关，此时 `setProjectionEnabled(true)`
     * 会因 `clusterStream=0` 被拒。等 111 流出现后再补一次，HUD 才不会永远空窗。
     */
    fun setStreamActiveListener(listener: ((Boolean) -> Unit)?) {}

    /** 首帧看门狗触发时请求一次 111 流关键帧；返回 false = 当前没有会话。 */
    fun setKeyFrameRequester(requester: (() -> Boolean)?) {}

    /** 进程退出前释放全部资源（默认等同 [stop]）。 */
    fun release() = stop()

    companion object {
        /** 没有找到 1024×274 + FLAG_PRESENTATION 的屏。 */
        const val HUD_NOT_FOUND = "HUD_NOT_FOUND"

        /** 同时命中多块屏 —— 不猜，按「没找到」处理。 */
        const val HUD_AMBIGUOUS = "HUD_AMBIGUOUS"

        /** 共享 111 模式要求至少能声明仪表副屏；拿不到 spec 就无法让 iPhone 开 111。 */
        const val REASON_CLUSTER_SPEC_UNAVAILABLE = "CLUSTER_SPEC_UNAVAILABLE"

        /** 仪表窗口已挂 + HUD 镜像 ⇒ 111 会出现三路解码（允许，但必须留痕）。 */
        const val REASON_SHARED_111_THREE_DECODER = "SHARED_111_THREE_DECODER"

        /** `Presentation.show()` 必须在主线程；调用方应切线程后重试。 */
        const val REASON_MAIN_THREAD_REQUIRED = "MAIN_THREAD_REQUIRED"
    }
}

/** [CarHudProjection.start] 的结果。 */
sealed interface CarHudStartResult {
    /** 窗口已建立（或已存在并更新了参数）。 */
    data object Started : CarHudStartResult

    /** 本机没有可用的 HUD 屏 / 建窗失败。 */
    data class Unavailable(val reason: String) : CarHudStartResult

    /**
     * 槽位策略判定冲突（例如拿不到副屏 spec，iPhone 不会开 111）。
     * **不静默选一个替代屏**。
     */
    data class Conflict(val reason: String) : CarHudStartResult
}
