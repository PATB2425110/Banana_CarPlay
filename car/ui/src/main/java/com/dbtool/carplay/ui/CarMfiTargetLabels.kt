package com.dbtool.carplay.ui

import com.dbtool.carplay.api.settings.CarMfiTarget

/**
 * MFi 认证方式的中文短标签。
 *
 * 集中在一处的原因很实际：设置页的选项、设置页的摘要行、设备页、首页摘要
 * 四处都要显示它。散着写必然出现"加了一个枚举值，改了三个地方漏了一个"，
 * 而漏掉的那个位置在 Kotlin 里未必编译报错（`when` 作语句时不要求穷尽）。
 */
internal fun CarMfiTarget.displayLabel(): String = when (this) {
    CarMfiTarget.USB_CH341 -> "CH341 桥接"
    CarMfiTarget.I2C -> "板载 I2C"
    CarMfiTarget.REMOTE -> "远程认证"
    CarMfiTarget.LOCAL_OFFLINE -> "本机软件认证"
}
