package com.dbtool.carplay.bridge

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.LockdownPairRecord

/**
 * AirPlay 身份、配对记录、Lockdown 配对记录的持久化。
 *
 * 这些数据必须跨进程重启保留，否则每次启动都要 iPhone 重新配对——
 * 那是"看起来很卡、每次都要确认"的典型症状。
 *
 * 使用**自己的** prefs 文件（`car_ui_identity`）。原因见
 * [SharedPreferencesCarSettingsStore] 的类注释：二开 App 与上游 `applicationId`
 * 不同、数据目录天然隔离，因此不需要复用 upstream 的 prefs 文件，
 * 也就不必为此引入对 `:common` 的依赖。
 *
 * 注意：`AirPlayIdentity` / `PairingStore` / `LockdownPairRecord` 都是 upstream
 * `:shared` 里的类型——本模块是唯一允许认识它们的模块，这是刻意的边界。
 * 应用层（`:car:app`）只通过本类的 getter 拿到对象并注入
 * [CarPlaySessionBridge]，不直接接触这些类型。
 */
class CarIdentityStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 载入（必要时生成）附件长期身份。
     *
     * 首次调用会生成 Ed25519 密钥对并落盘；之后每次返回同一份，
     * 这是 iPhone 能记住本机、不必反复配对的前提。
     */
    @Synchronized
    fun loadIdentity(): AirPlayIdentity {
        val privateKey = prefs.getString(KEY_IDENTITY_PRIVATE, null)
        val publicKey = prefs.getString(KEY_IDENTITY_PUBLIC, null)
        val pairingId = prefs.getString(KEY_IDENTITY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            runCatching { AirPlayIdentity(privateKey.hexToBytes(), publicKey.hexToBytes(), pairingId) }
                .getOrNull()
                ?.let { return it }
            // 数据损坏：清掉重新生成，而不是让应用起不来。
            prefs.edit()
                .remove(KEY_IDENTITY_PRIVATE)
                .remove(KEY_IDENTITY_PUBLIC)
                .remove(KEY_IDENTITY_PAIRING_ID)
                .apply()
        }
        return AirPlayIdentity.generate().also { identity ->
            prefs.edit()
                .putString(KEY_IDENTITY_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENTITY_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_IDENTITY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    /** 载入已配对控制器的长期公钥。 */
    @Synchronized
    fun loadPairings(): PairingStore {
        val store = PairingStore { identifier, key -> savePairing(identifier, key) }
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            prefs.getString(pairingKey(identifier), null)
                ?.let { runCatching { store.save(identifier, it.hexToBytes()) } }
        }
        return store
    }

    @Synchronized
    private fun savePairing(identifier: String, longTermPublicKey: ByteArray) {
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString(pairingKey(identifier), longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    /** 清空所有已配对控制器（设置页的"清除配对"）。 */
    @Synchronized
    fun clearPairings() {
        val editor = prefs.edit()
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            editor.remove(pairingKey(identifier))
        }
        editor.remove(KEY_PAIRING_IDS).apply()
    }

    /** 已配对控制器数量（设置页/设备页展示）。 */
    fun pairedControllerCount(): Int =
        prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().size

    /**
     * 载入有线 Lockdown 配对记录。
     *
     * 有线 CarPlay 首次需要跑一次 Lockdown 配对，结果存下来之后
     * 后续插 USB 可以跳过配对直接建链。记录不完整/损坏时返回 null（重新配对）。
     */
    @Synchronized
    fun loadPairRecord(): LockdownPairRecord? {
        val hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        fun bytes(key: String): ByteArray? =
            prefs.getString(key, null)?.let { runCatching { it.hexToBytes() }.getOrNull() }
        val devicePublic = bytes(KEY_LOCKDOWN_DEVICE_PUBLIC) ?: return null
        val deviceCert = bytes(KEY_LOCKDOWN_DEVICE_CERT) ?: return null
        val hostPrivate = bytes(KEY_LOCKDOWN_HOST_PRIVATE) ?: return null
        val hostCert = bytes(KEY_LOCKDOWN_HOST_CERT) ?: return null
        val rootPrivate = bytes(KEY_LOCKDOWN_ROOT_PRIVATE) ?: return null
        val rootCert = bytes(KEY_LOCKDOWN_ROOT_CERT) ?: return null
        return runCatching {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic,
                deviceCertificatePem = deviceCert,
                hostPrivateKeyPem = hostPrivate,
                hostCertificatePem = hostCert,
                rootPrivateKeyPem = rootPrivate,
                rootCertificatePem = rootCert,
            )
        }.getOrNull()
    }

    @Synchronized
    fun savePairRecord(record: LockdownPairRecord) {
        prefs.edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    @Synchronized
    fun clearPairRecord() {
        prefs.edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    private fun pairingKey(identifier: String): String = "$KEY_PAIRING_PREFIX$identifier"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        const val PREFS_NAME = "car_ui_identity"

        private const val KEY_IDENTITY_PRIVATE = "identity_private"
        private const val KEY_IDENTITY_PUBLIC = "identity_public"
        private const val KEY_IDENTITY_PAIRING_ID = "identity_pairing_id"
        private const val KEY_PAIRING_IDS = "pairing_ids"
        private const val KEY_PAIRING_PREFIX = "pairing."

        private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
        private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
        private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
        private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
        private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
        private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
        private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
        private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
        private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    }
}
