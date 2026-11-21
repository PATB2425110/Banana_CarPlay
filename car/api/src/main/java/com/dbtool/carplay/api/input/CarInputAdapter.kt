package com.dbtool.carplay.api.input

/**
 * 车机按键的归一化语义。
 *
 * 只覆盖 CarPlay 场景真正会用到的键；方控（方向盘按键）与车机硬键都映射到同一组语义，
 * 由具体 Adapter 决定来源。
 */
enum class CarKey {
    PREVIOUS,
    NEXT,
    PLAY_PAUSE,
    STOP,
    /** 语音助手 / Siri。 */
    VOICE,
    HOME,
    BACK,
    MENU,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
}

enum class CarKeyAction {
    DOWN,
    UP,
    MULTIPLE,
}

/** 按键事件来源，用于诊断与优先级仲裁。 */
enum class CarInputSource {
    /** 方向盘按键。 */
    STEERING_WHEEL,

    /** 车机面板硬键 / 旋钮。 */
    PANEL,

    /** 其它（蓝牙遥控、外部设备）。 */
    OTHER,
}

data class CarInputEvent(
    val key: CarKey,
    val action: CarKeyAction,
    val source: CarInputSource,
    val timestampMillis: Long,
    /** MULTIPLE 时的重复次数，其余为 0。 */
    val repeatCount: Int = 0,
    /** 原始平台键码，便于排查"识别不了"的车机。 */
    val rawKeyCode: Int? = null,
)

/**
 * 输入监听。返回 true 表示事件已被消费（不再向下传递）。
 * 与 Android `onKeyDown` 的返回值语义一致，方便实现方直接透传。
 */
fun interface CarInputListener {
    fun onInputEvent(event: CarInputEvent): Boolean
}

/**
 * 方控 / 按键适配。
 *
 * 典型实现路径：
 *
 * ```
 * CarPlay Input (Core: AirPlay HID / iAP2)
 *        ↓
 * CarInputAdapter
 *        ↓
 * 方向盘 / 车机按键（KeyEvent、车机专有按键服务、CAN 总线）
 * ```
 */
interface CarInputAdapter {
    fun attach(listener: CarInputListener)

    fun detach(listener: CarInputListener)

    /**
     * 向车机申请独占/优先接收媒体按键。
     *
     * 多数车机（Flyme Auto、OSN）需要先注册 MediaSession 或占用方控焦点，
     * 否则按键会被系统音乐 App 抢走。默认视为成功。
     */
    fun requestExclusiveAccess(): Boolean = true

    fun releaseExclusiveAccess() {}

    /** 车机是否支持把方控事件交给我们；false 时 UI 应隐藏相关开关。 */
    fun isSupported(): Boolean = true
}
