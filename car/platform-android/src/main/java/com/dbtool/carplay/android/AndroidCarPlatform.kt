package com.dbtool.carplay.android

import android.content.Context
import com.dbtool.carplay.api.CarPlatform
import com.dbtool.carplay.api.audio.CarAudioAdapter
import com.dbtool.carplay.api.diagnostics.CarDiagnosticsAdapter
import com.dbtool.carplay.api.display.CarDisplayAdapter
import com.dbtool.carplay.api.input.CarInputAdapter
import com.dbtool.carplay.api.media.CarMediaAdapter
import com.dbtool.carplay.api.microphone.CarMicrophoneAdapter
import com.dbtool.carplay.api.navigation.CarNavigationAdapter
import com.dbtool.carplay.api.system.CarSystemAdapter

/**
 * 通用 Android 车机平台的默认适配。
 *
 * 定位：**基线实现 + 可继承骨架**。
 * - 对"标准 Android 车机"（含多数 AAOS / 模拟器）开箱即用。
 * - 厂商适配（Flyme / OSN / Ecarx）继承本类，只覆写真正有差异的适配器，
 *   其余自动继承默认行为，避免每个车机都重写一遍全套实现。
 *
 * 这是"新增车机只加 Adapter"得以成立的关键：差异点收敛在少数几个 `open val` 上。
 *
 * 用法：
 * ```kotlin
 * // Application.onCreate
 * CarPlatformRegistry.install(AndroidCarPlatform(this))
 * ```
 */
open class AndroidCarPlatform(
    protected val context: Context,
) : CarPlatform {

    override val id: String = "android"

    override val displayName: String = "Android"

    override val audio: CarAudioAdapter by lazy { AndroidCarAudioAdapter() }

    override val display: CarDisplayAdapter by lazy { AndroidCarDisplayAdapter(context) }

    override val media: CarMediaAdapter by lazy { AndroidCarMediaAdapter() }

    override val input: CarInputAdapter by lazy { AndroidCarInputAdapter() }

    override val system: CarSystemAdapter by lazy { AndroidCarSystemAdapter(context) }

    override val navigation: CarNavigationAdapter by lazy { AndroidCarNavigationAdapter(context) }

    override val microphone: CarMicrophoneAdapter by lazy { AndroidCarMicrophoneAdapter() }

    override val diagnostics: CarDiagnosticsAdapter by lazy { AndroidCarDiagnosticsAdapter() }
}
