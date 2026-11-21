package com.dbtool.carplay.flyme

import android.content.Context
import com.dbtool.carplay.android.AndroidCarPlatform

/**
 * Flyme Auto 车机适配。
 *
 * **当前为骨架，全部沿用 Android 默认行为，未实现任何 Flyme 专有能力。**
 * 这是 Phase 1 的刻意选择：先建立可验证的接缝，再按需填实现
 * （见本模块 README 的待实现清单）。
 *
 * 覆写方式（后续实作时参照）：
 * ```kotlin
 * override val audio: CarAudioAdapter by lazy { FlymeCarAudioAdapter() }
 * ```
 * 注意用 `by lazy` 而不是直接赋值：`AndroidCarPlatform` 的所有适配器都是懒加载的，
 * 保持一致可避免在 `Application.onCreate` 阶段做重活。
 *
 * 用法（产品 App）：
 * ```kotlin
 * CarPlatformRegistry.install(FlymeCarPlatform(this))
 * ```
 */
class FlymeCarPlatform(context: Context) : AndroidCarPlatform(context) {

    override val id: String = "flyme"

    override val displayName: String = "Flyme Auto"
}
