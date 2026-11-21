package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **蓝牙身份与配对链路**（无线 CarPlay 掉线的关键区）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `transport/WirelessBluetoothIdentity.java`(10KB)、
 * `transport/LockdownCarKitClient.java`(13KB)、`transport/LockdownPairingClient.java`(19KB)、
 * `transport/BluetoothRfcommDuplexStream.java`(21KB) 提取。
 *
 * ## 🔥 为什么这块决定「连上又掉」
 *
 * 无线 CarPlay 的蓝牙身份是 **iPhone 判断「是不是同一台车」的依据**。
 * 车机 ROM 常把 `BluetoothAdapter.getAddress()` 返回值伪造/隐藏成
 * `02:00:00:00:00:00`（隐私保护）。**若用这个假地址当标识发给 iPhone，
 * iPhone 会认为换了设备 → 断开重连。**
 *
 * DiPlay 的解法是 **三级回退取真实地址**：
 *
 * ```
 * 1. ADAPTER          BluetoothAdapter.getAddress()
 * 2. SETTINGS         系统 Settings（adapter 返回假地址时用）
 * 3. CONFIG_FALLBACK  配置兜底
 * ```
 *
 * 并明确 **拒绝** 三个假地址：
 * `02:00:00:00:00:00` / `00:00:00:00:00:00` / `FF:FF:FF:FF:FF:FF`
 *
 * ## CarPlay 控制通道（LockdownCarKitClient）
 *
 * - `StartSession` **必须开启 TLS**：`StartSession did not enable TLS`（原文报错）
 * - `StartService` 返回端口：`StartService returned an invalid Port`
 *
 * ## 配对（LockdownPairingClient）
 *
 * - `lockdown trust pending; reconnecting before retry` —— 信任未决时**重连再试**
 * - `lockdown UntrustedHostBUID set` —— 设置不可信主机 BUID
 *
 * ## RFCOMM 流（BluetoothRfcommDuplexStream）
 *
 * 线程名 `xcertplay-bluetooth-rfcomm-reader`；关闭失败报
 * `Bluetooth RFCOMM reader did not stop after close`。
 *
 * ⚠️ 未实机验证。
 */
class LynkBluetoothIdentity {

    companion object {
        private const val TAG = "DBPlay-BtIdentity"

        /** 需要拒绝的假蓝牙地址（DiPlay 原文，全部大写）。 */
        val FAKE_ADDRESSES = setOf(
            "02:00:00:00:00:00",
            "00:00:00:00:00:00",
            "FF:FF:FF:FF:FF:FF",
        )

        const val SOURCE_ADAPTER = "ADAPTER"
        const val SOURCE_SETTINGS = "SETTINGS"
        const val SOURCE_CONFIG_FALLBACK = "CONFIG_FALLBACK"
        const val SOURCE_CONFIG_DERIVED = "CONFIG_DERIVED"

        const val FAIL_PERMISSION_DENIED = "permission-denied"
        const val FAIL_READ_FAILED = "read-failed"

        /** RFCOMM 读线程名（DiPlay 原文）。 */
        const val RFCOMM_READER_THREAD = "xcertplay-bluetooth-rfcomm-reader"

        /** 配对总超时上限（DiPlay: 1..300000）。 */
        const val PAIR_TOTAL_TIMEOUT_MIN = 1L
        const val PAIR_TOTAL_TIMEOUT_MAX = 300_000L
    }

    /**
     * 蓝牙身份解析结果（对应 DiPlay `WirelessBluetoothIdentity.Resolution`）。
     *
     * @param address      最终采用的地址
     * @param source       来源（ADAPTER / SETTINGS / CONFIG_FALLBACK）
     * @param adapterState adapter 读取状态
     * @param settingsState settings 读取状态
     */
    data class Resolution(
        val address: String,
        val source: String,
        val adapterState: String,
        val settingsState: String,
    ) {
        /** 是否为配置兜底（即前两条路都失败）。 */
        fun isConfigDerived(): Boolean = source == SOURCE_CONFIG_FALLBACK

        /** 诊断串（DiPlay 原文格式）。 */
        fun diagnosticSummary(): String =
            "Bluetooth identity source=$source adapter=$adapterState " +
                "settings=$settingsState configDerived=${isConfigDerived()}"
    }

    /** 判断地址是否可用（非假地址、格式正确）。 */
    fun isUsableAddress(addr: String?): Boolean {
        if (addr.isNullOrBlank()) return false
        val upper = addr.uppercase()
        if (upper in FAKE_ADDRESSES) return false
        if (!upper.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) return false
        return true
    }

    /**
     * 三级回退取蓝牙身份。
     *
     * @param adapterAddress  BluetoothAdapter.getAddress() 的结果（可能假）
     * @param settingsAddress 系统 Settings 里的地址（可能空）
     * @param configFallback  配置兜底
     */
    fun resolve(
        adapterAddress: String?,
        settingsAddress: String?,
        configFallback: String,
    ): Resolution {
        if (isUsableAddress(adapterAddress)) {
            Log.i(TAG, "Bluetooth identity source=$SOURCE_ADAPTER address=$adapterAddress")
            return Resolution(
                adapterAddress!!.uppercase(), SOURCE_ADAPTER, "ok", "not-probed",
            )
        }
        if (isUsableAddress(settingsAddress)) {
            Log.i(TAG, "Bluetooth identity source=$SOURCE_SETTINGS address=$settingsAddress")
            return Resolution(
                settingsAddress!!.uppercase(), SOURCE_SETTINGS, "fake-or-denied", "ok",
            )
        }
        Log.w(
            TAG,
            "Bluetooth identity source=$SOURCE_CONFIG_FALLBACK (adapter=$adapterAddress " +
                "settings=$settingsAddress both unusable)",
        )
        return Resolution(
            configFallback.uppercase(), SOURCE_CONFIG_FALLBACK,
            if (adapterAddress == null) FAIL_PERMISSION_DENIED else "fake",
            if (settingsAddress == null) FAIL_READ_FAILED else "fake",
        )
    }

    /** 校验配对超时是否合法（DiPlay: 1..300000）。 */
    fun validatePairTimeout(millis: Long): Boolean {
        if (millis < PAIR_TOTAL_TIMEOUT_MIN || millis > PAIR_TOTAL_TIMEOUT_MAX) {
            Log.w(TAG, "totalTimeoutMillis must be between 1 and 300000 (got $millis)")
            return false
        }
        return true
    }

    /** CarPlay 控制通道 TLS 校验（DiPlay 原文报错）。 */
    fun validateStartSession(enabledSsl: Boolean): String? =
        if (!enabledSsl) "StartSession did not enable TLS" else null

    /** StartService 端口校验（DiPlay 原文报错）。 */
    fun validateStartServicePort(port: Int): String? =
        if (port !in 1..65535) "StartService returned an invalid Port" else null

    /** 配对重试提示（DiPlay 原文）。 */
    fun trustPendingMessage(): String = "lockdown trust pending; reconnecting before retry"
}
