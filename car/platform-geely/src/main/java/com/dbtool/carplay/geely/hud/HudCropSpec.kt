package com.dbtool.carplay.geely.hud

import android.graphics.Matrix
import android.view.TextureView
import com.dbtool.carplay.api.display.HudCropGeometry
import com.dbtool.carplay.api.display.HudCropSpec
import com.dbtool.carplay.api.display.HudScaleMode

/**
 * 把归一化裁剪落到 Android `TextureView` 的**纹理矩阵**上（2026-10-22）。
 *
 * ## 为什么放在平台层
 *
 * 预设、校验与纯几何住在 `:car:api`（`:car:ui` 的设置页要画预览，不能依赖
 * 平台层）。这里只剩 Android 落点：把
 * [HudCropGeometry.plan] 算出的 `scale/pivot/translate` 写进 `Matrix`。
 *
 * ## 为什么不用 CPU 逐帧裁
 *
 * HUD 是 1024×274、源 1920×1080、60Hz。CPU 逐帧 Bitmap 裁剪 + 上传纹理
 * 每帧要拷 ~8MB，必然掉帧。`TextureView.setTransform` 是 **GPU 纹理矩阵**，
 * 由 SurfaceFlinger/HWUI 在合成时应用，零额外拷贝。
 *
 * 注意：`setTransform` 的矩阵作用于**视图坐标**（`TextureView` 默认把整帧
 * 拉伸铺满视图），`HudCropGeometry.plan` 正是按这个前提推导的。
 *
 * @return true = 已下发；false = 视图还没有有效尺寸（等下一次布局回调重试）。
 */
internal fun applyHudCropTransform(
    texture: TextureView,
    crop: HudCropSpec,
    scaleMode: HudScaleMode,
    sourceWidthPixels: Int = HudCropGeometry.SOURCE_WIDTH_PIXELS,
    sourceHeightPixels: Int = HudCropGeometry.SOURCE_HEIGHT_PIXELS,
): Boolean {
    val viewWidth = texture.width
    val viewHeight = texture.height
    if (viewWidth <= 0 || viewHeight <= 0) return false

    val plan = HudCropGeometry.plan(
        sourceWidth = sourceWidthPixels,
        sourceHeight = sourceHeightPixels,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        crop = crop,
        mode = scaleMode,
    )
    val matrix = Matrix()
    matrix.setScale(plan.scaleX, plan.scaleY, plan.pivotX, plan.pivotY)
    matrix.postTranslate(plan.translateX, plan.translateY)
    texture.setTransform(matrix)
    return true
}
