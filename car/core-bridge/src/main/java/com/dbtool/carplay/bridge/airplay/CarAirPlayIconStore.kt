package com.dbtool.carplay.bridge.airplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.dbtool.carplay.api.airplay.CarAirPlayIconManager
import com.dbtool.carplay.api.airplay.CarAirPlayIconStatus
import com.shilapi.xcertplay.airplay.AirPlayIcon
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [CarAirPlayIconManager] 的实现：**随包内置 + 用户导入**两级供给。
 *
 * ## 文件位置
 *
 * - 内置：`assets/airplay-icon/default.png`（源图 `asset/领克-iOS-512x512.png`）。
 *   放在 `:car:app` 的 assets 里 —— Android 会把它合进 APK 的 assets，
 *   `:car:core-bridge` 的代码在运行时读得到（与 `assets/offline-mfi/` 同一手法）。
 * - 导入：`filesDir/airplay-icon.png`。文件名刻意与上游
 *   `AirPlayPersistence.CUSTOM_ICON_FILE` 保持一致，便于将来对齐；
 *   但**不共用 prefs**——二开的设置全部落在 `car_ui_settings`。
 *
 * 优先级：导入 > 内置。用户导入的读不出来（文件损坏）时**自动回落内置**，
 * 而不是让 CarPlay 侧没有图标——与上游 `loadAirPlayIcon()` 的降级顺序一致。
 *
 * ## 为什么必须在这里裁成正方形
 *
 * 见 [CarAirPlayIconManager] 的说明：上游在非正方形时**静默丢弃**。
 * 这里做居中裁剪（cover）而不是加边（contain）：加边会在图标四周留下
 * 一圈与车机主题无关的底色，在 CarPlay 启动器上非常显眼。
 */
class CarAirPlayIconStore(context: Context) : CarAirPlayIconManager {

    private val appContext = context.applicationContext

    private val importedFile: File get() = File(appContext.filesDir, IMPORTED_FILE_NAME)

    // ------------------------------------------------------------------ 对外契约

    override fun status(): CarAirPlayIconStatus {
        readImported()?.let { icon ->
            return CarAirPlayIconStatus(
                available = true,
                sourceLabel = SOURCE_IMPORTED,
                detail = "${icon.widthPixels}×${icon.heightPixels} 像素（已裁为正方形）",
                imported = true,
                bundled = false,
                widthPixels = icon.widthPixels,
                heightPixels = icon.heightPixels,
                previewPng = icon.data,
            )
        }
        readBundled()?.let { icon ->
            return CarAirPlayIconStatus(
                available = true,
                sourceLabel = SOURCE_BUNDLED,
                detail = "${icon.widthPixels}×${icon.heightPixels} 像素",
                imported = false,
                bundled = true,
                widthPixels = icon.widthPixels,
                heightPixels = icon.heightPixels,
                previewPng = icon.data,
            )
        }
        return CarAirPlayIconStatus(
            available = false,
            detail = "随包内置图标读取失败（assets/$BUNDLED_ASSET 缺失或不是有效图片）。",
        )
    }

    override fun importIcon(iconUri: Uri): CarAirPlayIconStatus {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(iconUri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            return status().withFailure("无法读取所选文件（可能是权限或文件已被移动）。")
        }
        val normalized = normalize(bytes)
        if (normalized == null) {
            return status().withFailure("所选文件不是可识别的图片格式。")
        }
        // 先写临时文件再原子改名：中途失败（磁盘满、进程被杀）不会留下
        // 一个半截的 PNG，把一个本来能用的图标弄坏。
        val staged = File(appContext.filesDir, "$IMPORTED_FILE_NAME.staging")
        val written = runCatching {
            staged.outputStream().use { it.write(normalized.data) }
            if (importedFile.exists() && !importedFile.delete()) {
                error("无法覆盖已存在的图标文件")
            }
            if (!staged.renameTo(importedFile)) {
                error("无法写入图标文件")
            }
            true
        }.getOrElse {
            staged.delete()
            false
        }
        if (!written) {
            return status().withFailure("写入图标文件失败，已保留原有图标。")
        }
        return status()
    }

    override fun clearImported(): Boolean {
        val existed = importedFile.exists()
        importedFile.delete()
        File(appContext.filesDir, "$IMPORTED_FILE_NAME.staging").delete()
        return existed
    }

    /**
     * 会话启动时取当前生效的图标。
     *
     * @return null 表示一份都没有——此时 `AirPlayConfig.icons` 留空，
     *         上游会回落到它自带的占位图，行为安全。
     */
    fun loadActive(): AirPlayIcon? = readImported() ?: readBundled()

    // ---------------------------------------------------------------- 读取

    private fun readImported(): AirPlayIcon? {
        val file = importedFile
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val icon = toAirPlayIcon(bytes) ?: return null
        return icon
    }

    private fun readBundled(): AirPlayIcon? {
        val bytes = runCatching {
            appContext.assets.open(BUNDLED_ASSET).use { it.readBytes() }
        }.getOrNull() ?: return null
        return toAirPlayIcon(bytes)
    }

    /**
     * 把编码字节包成上游的 [AirPlayIcon]。
     *
     * ⚠️ **尺寸必须来自图片本身**：上游把 `widthPixels` / `heightPixels` 原样写进
     * `/info` 的 `oemIcons`，声明值与真实像素不一致时 iPhone 侧会画错或直接不用。
     */
    private fun toAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth != bounds.outHeight) return null
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    // ---------------------------------------------------------------- 归一化

    private class NormalizedIcon(val widthPixels: Int, val data: ByteArray)

    /**
     * 裁成正方形并按需缩放。
     *
     * 已经是正方形且不超过 [MAX_EDGE] 的图**原样返回**——避免每次导入都做一次
     * 有损的重新编码（PNG 无损，但重编码会改变字节、白白多一次解码）。
     */
    private fun normalize(encoded: ByteArray): NormalizedIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth == bounds.outHeight && bounds.outWidth <= MAX_EDGE) {
            return NormalizedIcon(bounds.outWidth, encoded)
        }
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        }.getOrNull() ?: return null
        return runCatching {
            val side = minOf(decoded.width, decoded.height)
            val cropped = if (decoded.width == side && decoded.height == side) {
                decoded
            } else {
                Bitmap.createBitmap(
                    decoded,
                    (decoded.width - side) / 2,
                    (decoded.height - side) / 2,
                    side,
                    side,
                )
            }
            val target = minOf(side, MAX_EDGE)
            val scaled = if (target != side) {
                Bitmap.createScaledBitmap(cropped, target, target, true)
            } else {
                cropped
            }
            val output = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "PNG 编码失败"
            }
            if (scaled !== decoded) scaled.recycle()
            if (cropped !== decoded && cropped !== scaled) cropped.recycle()
            decoded.recycle()
            NormalizedIcon(target, output.toByteArray())
        }.getOrNull()
    }

    private fun CarAirPlayIconStatus.withFailure(reason: String): CarAirPlayIconStatus =
        CarAirPlayIconStatus(
            available = available,
            sourceLabel = sourceLabel,
            detail = "$reason${if (available) " 当前仍在使用：$sourceLabel。" else ""}",
            imported = imported,
            bundled = bundled,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            previewPng = previewPng,
        )

    private companion object {
        /** 与上游 `AirPlayPersistence.CUSTOM_ICON_FILE` 同名，便于将来对齐。 */
        const val IMPORTED_FILE_NAME = "airplay-icon.png"

        /** 随 APK 内置的默认图标。 */
        const val BUNDLED_ASSET = "airplay-icon/default.png"

        const val SOURCE_BUNDLED = "随包内置"
        const val SOURCE_IMPORTED = "用户导入"

        /**
         * 图标边长上限。上游不做缩放，选多大就发多大给 iPhone；
         * CarPlay 启动器上的图标本来就按点阵渲染，512 已经远超实际需要，
         * 再大只是白白占用内存与 iAP2 报文。
         */
        const val MAX_EDGE = 512
    }
}
