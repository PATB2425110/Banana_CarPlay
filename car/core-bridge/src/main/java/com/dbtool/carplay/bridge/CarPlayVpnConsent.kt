package com.dbtool.carplay.bridge

import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.network.CarPlayVpnService

/**
 * VPN 授权入口。
 *
 * 存在的唯一理由：**把 `CarPlayVpnService` 这个 upstream 类型挡在应用层之外。**
 * 有线 CarPlay 依赖 `VpnService` 建立 tun 设备承载 NCM，
 * 首次使用必须由用户授权。应用层只需要拿到一个"待启动的 Intent"，
 * 不需要知道它是谁的 Service，更不需要 import `:shared`。
 *
 * 这样应用层（`:car:app`）对 upstream 的可见依赖为零，
 * 上游替换 VPN 实现时也只影响本文件。
 */
object CarPlayVpnConsent {

    /**
     * @return 需要用户确认的授权 Intent；已授权时返回 null（可直接使用 VPN）。
     */
    fun prepareIntent(context: Context): Intent? = CarPlayVpnService.prepare(context)
}

/**
 * 当前二开版本所基于的上游信息。
 *
 * 硬编码而不是运行时读取，原因：这些值描述的是**构建时**的同步点，
 * 运行时读 `PackageManager` 只能读到本应用（`com.dbtool.carplay`）的版本，
 * 读不到上游的。硬编码 + 注释指向 UPSTREAM.md，是唯一诚实的做法。
 *
 * **同步上游后必须更新这里。**
 */
object CarPlayCoreInfo {
    const val UPSTREAM_REPOSITORY = "shilapi/xcertplay"

    /**
     * 上游版本。
     *
     * ⚠️ 这三个值必须与 `UPSTREAM.md` 的「当前同步状态」表格逐项一致。
     * 它们曾漏更新（长期停在 `1.2.1` / `3ac55e34`，而实际已快进到 `1.2.4`），
     * 结果"关于"页与诊断区的"核心版本"一直报错值——
     * 而这个数字正是现场排障时唯一用来判断"你装的是哪版协议栈"的依据。
     * **每次同步上游之后，第一件事就是改这里。**
     *
     * ⚠️ 上游**不一定打 tag**：2026-09-30 那次就是把 `master` 直接往前推了 5 个提交
     * 而没有新 tag ⇒ `UPSTREAM_COMMIT` 是比 `UPSTREAM_VERSION_NAME` 更可靠的判据，
     * 版本名在没有新 tag 时保持不变，靠 commit 短 hash 区分。
     */
    const val UPSTREAM_VERSION_NAME = "1.3.3"
    const val UPSTREAM_VERSION_CODE = 1303

    /**
     * 同步的上游 commit 短 hash，与 `UPSTREAM.md` 保持一致。
     *
     * 当前 = `17c9243`（2026-10-07 合并；上游 `1.3.2` / `1.3.3` 两次**都没打 tag**，
     * 直接推 `master`）。展示成 `1.3.3 (17c9243)`。
     *
     * ⚠️ 2026-10-08 修正：这三行曾**漏更**——2026-10-07 同步到 `17c9243` 时只动了
     * 别的文件，这里还停在上一轮的 `1.3.1 (3753867)`。正是本注释警告过的那个坑
     * （"关于"页与诊断区的「核心版本」报错值，而那个数字是现场判断协议栈版本的唯一依据）。
     */
    const val UPSTREAM_COMMIT = "17c9243"

    /** 展示用字符串。 */
    val displayVersion: String get() = "$UPSTREAM_VERSION_NAME ($UPSTREAM_COMMIT)"
}
