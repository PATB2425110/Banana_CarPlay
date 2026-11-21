package com.dbtool.carplay.geely

import android.content.Context
import com.dbtool.carplay.android.AndroidCarSystemAdapter

/**
 * 吉利车机的系统适配。
 *
 * 只做一件事：把平台身份改成 `geely`。
 *
 * 与 Ecarx 那份同因（见 `EcarxCarSystemAdapter` 注释）：父类
 * `AndroidCarSystemAdapter.info.platformId` 取的是它自己的 `id`，与
 * [GeelyCarPlatform.id] 是两个独立字段；不复写就会出现"平台显示吉利、
 * platformId 却是 android"的自相矛盾，线上排查最费时间。
 *
 * ⚠️ 命名区分：`ecarx` 是**吉利系的一级平台名**（OneOS 服务总线），
 * 本模块的 `geely` 专指**仪表副屏这一类吉利专属能力**。本机若同时命中两者
 * （既有 OneOS 又有 HDMI2 仪表），由 `CarPlayApplication.selectPlatform`
 * 的优先级决定装哪个——见那里的说明。
 */
class GeelyCarSystemAdapter(context: Context) : AndroidCarSystemAdapter(context) {

    override val id: String = PLATFORM_ID

    override val platformName: String = "Geely (仪表副屏)"

    companion object {
        const val PLATFORM_ID = "geely"
    }
}
