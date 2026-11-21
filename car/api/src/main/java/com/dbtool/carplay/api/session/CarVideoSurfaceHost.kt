package com.dbtool.carplay.api.session

import android.view.Surface

/**
 * 视频输出宿主：UI 提供 `Surface`，由 Core 侧把它绑定到视频解码器。
 *
 * 为什么这个接口带 Android 类型：
 * 视频输出最终必须落在一个 `Surface` 上（`TextureView` / `SurfaceView` / 车机硬件视频层），
 * 这是 Android 平台的既有事实，硬套一层抽象只会增加无谓的转换。
 * `:car:api` 因此保持"零项目依赖"（不依赖 `:shared` / `:common` / 任何第三方），
 * 但允许引用 Android 框架类型。
 *
 * 与 [CarSessionController] 分开而不合并的原因：状态/命令契约与"显示输出"是两件事，
 * 把 Surface 生命周期塞进会话契约会让测试与替代 UI 都变复杂。
 */
interface CarVideoSurfaceHost {
    /**
     * 绑定/解绑视频输出 Surface。
     * @param surface null 表示 Surface 已销毁（UI 切换、Activity 重建）。
     */
    fun attachVideoSurface(surface: Surface?)

    /** 当前是否已经绑定 Surface。 */
    fun hasVideoSurface(): Boolean

    /**
     * 绑定/解绑**仪表副屏**的视频输出 Surface（2026-10-07，吉利 HDMI2 移植）。
     *
     * 副屏与主屏是两条独立的视频流（上游 `STREAM_TYPE_ALT_SCREEN = 111`）。
     * 声明了 `AirPlayConfig.cluster` 的车机需要把副屏窗口的 Surface 交给这里；
     * 没有副屏能力的车机（或没声明 cluster）永远不需要调用它。
     *
     * 默认空实现：多数车机不需要副屏，不该被迫实现一个空方法。
     */
    fun attachClusterVideoSurface(surface: Surface?) {}
    /** Main-window ALT output, never an instrument Presentation. */
    fun attachImmersiveVideoSurface(surface: Surface?) {}

    /**
     * 绑定/解绑 **HUD 镜像**输出 Surface（2026-10-22）。
     *
     * 与 [attachClusterVideoSurface] 的关键区别：副屏是 111 流**唯一**的输出，
     * HUD 是同一路 111 流的**第二个消费者**。实现必须走 `setMirrorSurface`
     * 那一类「不抢占主输出」的入口，不能把仪表正在用的 Surface 换掉。
     *
     * 默认空实现：没有 HUD 投屏能力的平台不用关心它。
     */
    fun attachHudVideoSurface(surface: Surface?) {}
}
