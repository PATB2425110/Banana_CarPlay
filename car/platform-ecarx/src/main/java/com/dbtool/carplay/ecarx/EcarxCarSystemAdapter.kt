package com.dbtool.carplay.ecarx

import android.content.Context
import com.dbtool.carplay.android.AndroidCarSystemAdapter

/**
 * Ecarx 车机的系统适配。
 *
 * 目前只做一件事：把平台身份改成 `ecarx`。
 *
 * 这不是纯装饰。父类 [AndroidCarSystemAdapter] 的 `info.platformId` 取的是
 * 它自己的 `id`（默认 `"android"`），而 `id` 与 [EcarxCarPlatform.id] 是两个
 * 独立字段——不复写的话，诊断页会一边显示"Ecarx 平台"、一边显示
 * `platformId=android`，排查线上问题时这种自相矛盾最费时间。
 *
 * `supportsRoot` 等其余能力保持父类默认（不支持 root、需要 VPN 授权），
 * 在真机上确认过再逐项放开，不提前假设车机给了什么权限。
 */
class EcarxCarSystemAdapter(context: Context) : AndroidCarSystemAdapter(context) {

    override val id: String = PLATFORM_ID

    override val platformName: String = "Ecarx / 吉利 OneOS"

    companion object {
        const val PLATFORM_ID = "ecarx"
    }
}
