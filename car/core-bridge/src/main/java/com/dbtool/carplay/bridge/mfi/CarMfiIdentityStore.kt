package com.dbtool.carplay.bridge.mfi

import android.content.Context
import android.net.Uri
import com.dbtool.carplay.api.mfi.CarMfiIdentityManager
import com.dbtool.carplay.api.mfi.CarMfiIdentityStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 本地认证身份的供给。
 *
 * ## 两条来源，优先级明确
 *
 * 1. **用户导入**：`noBackupFilesDir/offline-mfi/` 下的 `identity.pk8` + `certificate.p7b`。
 * 2. **随包内置**：APK 的 `assets/offline-mfi/` 下的同名文件。
 *
 * 用户导入优先。这样"自定义身份"不需要卸载重装，也不会被内置资产覆盖；
 * 设置页点"清除导入"就退回内置版本。
 *
 * ## 内置身份为什么不复制到磁盘
 *
 * DiPlay 的做法是把 assets 里的身份复制到 `noBackupFilesDir` 再用。
 * 这里不复制，直接从 assets 读，原因有两条：
 *
 * 1. **少一份明文私钥落盘**。密钥本来就在 APK 里（谁拿到 APK 都能取出来），
 *    但把它再复制一份到应用数据目录，只是多一个泄漏面，不多一分安全性；
 * 2. **来源天然可判定**。目录存在就是"用户导入"，不存在就是"内置"——
 *    不需要额外持久化一个 source 字段，也就不存在"字段与实际不一致"的状态。
 *
 * 顺带一提：`noBackupFilesDir` 而不是 `filesDir`，是为了让导入的私钥
 * 天然排除在 Android 备份之外，比依赖 `backup_rules.xml` 的正则排除更可靠。
 *
 * ## 导入为什么用 staging + rename
 *
 * 身份文件是"要么两个都在且配对，要么完全不可用"。直接往目标目录写两个文件，
 * 中途被杀会留下一个半套身份，之后每次连接都以一条难以理解的 MFi 超时告终。
 * 所以：先写 staging → 完整校验 → 原子改名就位；
 * 覆盖已有身份时先备份旧目录，改名失败还能回滚，不会出现"两个都没有"的窗口。
 *
 * 校验失败时**完全不动**现有身份——用户拿错文件不应该把能用的身份弄坏。
 */
class CarMfiIdentityStore(context: Context) : CarMfiIdentityManager {

    private val appContext = context.applicationContext

    /** 身份来源。 */
    enum class Source {
        /** 用户导入的。 */
        IMPORTED,

        /** 随 APK 内置的。 */
        BUNDLED,
    }

    /** 身份可用性。 */
    sealed interface Availability {
        data class Ready(val key: CarMfiAccessoryKey, val source: Source) : Availability

        /** @param reason 面向用户可读的原因，可直接显示在设置页。 */
        data class Missing(val reason: String) : Availability
    }

    private val directory: File get() = File(appContext.noBackupFilesDir, DIRECTORY)

    private val stagingDirectory: File get() = File(appContext.noBackupFilesDir, STAGING_DIRECTORY)

    private val backupDirectory: File get() = File(appContext.noBackupFilesDir, BACKUP_DIRECTORY)

    @Volatile
    private var cached: Availability? = null

    /**
     * 当前身份。
     *
     * 结果会被缓存——载入要做一次真实的 ECDSA 签名自检，
     * 每次开会话都重算是浪费；导入与清除时会主动失效。
     */
    fun availability(): Availability = cached ?: synchronized(this) {
        cached ?: resolve().also { cached = it }
    }

    /** 导入目录路径，供诊断展示。 */
    fun directoryPath(): String = directory.absolutePath

    /** 是否已有用户导入的身份（决定设置页显示"清除导入"还是只显示状态）。 */
    fun hasImportedIdentity(): Boolean = directory.isDirectory

    /**
     * 导入用户自定义身份。
     *
     * @return 导入后的可用性。校验失败时返回 [Availability.Missing]，
     *         且现有身份保持不变。
     */
    fun importFrom(identityUri: Uri, certificateUri: Uri): Availability {
        val keyBytes = try {
            readBounded(identityUri, CarMfiAccessoryKey.IDENTITY_FILE)
        } catch (error: CarMfiIdentityException) {
            return Availability.Missing(error.message ?: "无法读取身份私钥")
        }
        val certificateBytes = try {
            readBounded(certificateUri, CarMfiAccessoryKey.CERTIFICATE_FILE)
        } catch (error: CarMfiIdentityException) {
            return Availability.Missing(error.message ?: "无法读取身份证书")
        }
        return install(keyBytes, certificateBytes)
    }

    // ------------------------------------------------- CarMfiIdentityManager

    override fun status(): CarMfiIdentityStatus = availability().toStatus()

    override fun importIdentity(
        identityUri: Uri,
        certificateUri: Uri,
    ): CarMfiIdentityStatus =
        // 刻意用 importFrom 的**返回值**而不是缓存去构造状态：
        // 导入失败时 install 不会动缓存（这是对的——旧身份仍然可用），
        // 若此时回读缓存，用户会看到"一切正常"，而实际上他刚导入的文件被拒了。
        importFrom(identityUri, certificateUri).toStatus()

    private fun Availability.toStatus(): CarMfiIdentityStatus = when (this) {
        is Availability.Ready -> CarMfiIdentityStatus(
            available = true,
            sourceLabel = when (source) {
                Source.IMPORTED -> "用户导入"
                Source.BUNDLED -> "随包内置"
            },
            detail = "配件证书公钥指纹 ${key.publicKeyFingerprint}",
            imported = source == Source.IMPORTED,
            bundled = source == Source.BUNDLED,
            fingerprint = key.publicKeyFingerprint,
        )

        is Availability.Missing -> CarMfiIdentityStatus(
            available = false,
            sourceLabel = "",
            detail = reason,
            // 磁盘上可能还留着上一次成功导入的身份（本次导入失败没动它），
            // 所以"有没有导入过"要以目录为准，而不是以本次结果为准。
            imported = hasImportedIdentity(),
            bundled = false,
            fingerprint = null,
        )
    }

    /** 删除用户导入的身份，退回随包内置版本。 */
    override fun clearImported(): Boolean = synchronized(this) {
        val removed = directory.deleteRecursively()
        cached = null
        removed
    }

    /** 清除缓存，强制下次 [availability] 重新载入。 */
    fun invalidate() {
        cached = null
    }

    // ------------------------------------------------------------------ internals

    private fun resolve(): Availability = when {
        directory.isDirectory -> loadFrom(directory, Source.IMPORTED)
        else -> loadFromAssets()
    }

    private fun loadFrom(directory: File, source: Source): Availability = try {
        Availability.Ready(CarMfiAccessoryKey.load(directory), source)
    } catch (error: CarMfiIdentityException) {
        Availability.Missing(error.message ?: "本地认证身份无效")
    } catch (error: Exception) {
        Availability.Missing("本地认证身份载入失败：${error.javaClass.simpleName}")
    }

    private fun loadFromAssets(): Availability {
        val keyBytes = readAsset(CarMfiAccessoryKey.IDENTITY_FILE)
            ?: return Availability.Missing("未安装本地认证身份")
        val certificateBytes = readAsset(CarMfiAccessoryKey.CERTIFICATE_FILE)
            ?: return Availability.Missing("内置认证身份缺少证书文件")
        return try {
            Availability.Ready(
                CarMfiAccessoryKey.fromBytes(keyBytes, certificateBytes),
                Source.BUNDLED,
            )
        } catch (error: CarMfiIdentityException) {
            Availability.Missing("内置认证身份无效：${error.message}")
        } catch (error: Exception) {
            Availability.Missing("内置认证身份载入失败：${error.javaClass.simpleName}")
        }
    }

    private fun install(keyBytes: ByteArray, certificateBytes: ByteArray): Availability =
        synchronized(this) {
            // 1. 先校验。校验不过就不碰磁盘上任何东西。
            val key = try {
                CarMfiAccessoryKey.fromBytes(keyBytes.copyOf(), certificateBytes.copyOf())
            } catch (error: CarMfiIdentityException) {
                return Availability.Missing(error.message ?: "身份校验失败")
            } catch (error: Exception) {
                return Availability.Missing("身份校验失败：${error.javaClass.simpleName}")
            }

            // 2. 写 staging 并收紧权限。
            stagingDirectory.deleteRecursively()
            if (!stagingDirectory.mkdirs()) {
                return Availability.Missing("无法创建身份暂存目录")
            }
            restrictToOwner(stagingDirectory)
            val stagedKey = File(stagingDirectory, CarMfiAccessoryKey.IDENTITY_FILE)
            val stagedCertificate = File(stagingDirectory, CarMfiAccessoryKey.CERTIFICATE_FILE)
            if (!write(stagedKey, keyBytes) || !write(stagedCertificate, certificateBytes)) {
                stagingDirectory.deleteRecursively()
                return Availability.Missing("无法写入身份文件")
            }
            restrictToOwner(stagedKey)
            restrictToOwner(stagedCertificate)

            // 3. 目标目录让位：有旧身份时先改名备份，而不是直接删。
            backupDirectory.deleteRecursively()
            val hadPrevious = directory.isDirectory
            if (hadPrevious && !directory.renameTo(backupDirectory)) {
                stagingDirectory.deleteRecursively()
                return Availability.Missing("无法替换已有身份（备份失败）")
            }

            // 4. staging 就位。
            if (!stagingDirectory.renameTo(directory)) {
                stagingDirectory.deleteRecursively()
                // 回滚：把备份改回去，用户原来的身份继续可用。
                if (hadPrevious) backupDirectory.renameTo(directory)
                return Availability.Missing("无法安装身份文件")
            }
            backupDirectory.deleteRecursively()

            return Availability.Ready(key, Source.IMPORTED).also { cached = it }
        }

    private fun write(file: File, bytes: ByteArray): Boolean =
        runCatching { file.outputStream().use { it.write(bytes) } }.isSuccess

    /**
     * 把目录/文件的读写权限收紧到只有属主。
     *
     * 返回值刻意忽略：Android 10 起这些调用对应用私有目录是空操作，
     * `noBackupFilesDir` 本身已经是 0700。这里只是多一层"万一"。
     */
    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setExecutable(false, false)
        if (file.isDirectory) file.setExecutable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private fun readAsset(name: String): ByteArray? = runCatching {
        appContext.assets.open("$ASSET_DIRECTORY/$name").use { it.readBounded(name) }
    }.getOrNull()

    private fun readBounded(uri: Uri, label: String): ByteArray {
        val stream: InputStream = appContext.contentResolver.openInputStream(uri)
            ?: throw CarMfiIdentityException("无法读取 $label")
        return stream.use { it.readBounded(label) }
    }

    /** 带上限读取，避免用户误选一个几百 MB 的文件把内存吃掉。 */
    private fun InputStream.readBounded(label: String): ByteArray {
        val output = ByteArrayOutputStream(1024)
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > CarMfiAccessoryKey.MAX_FILE_BYTES) {
                throw CarMfiIdentityException(
                    "$label 超过 ${CarMfiAccessoryKey.MAX_FILE_BYTES} 字节上限，请确认选对了文件",
                )
            }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        if (bytes.isEmpty()) throw CarMfiIdentityException("$label 为空")
        return bytes
    }

    companion object {
        /** 与 DiPlay 的目录名一致，两边可以直接互换身份文件。 */
        const val DIRECTORY = "offline-mfi"

        private const val STAGING_DIRECTORY = "offline-mfi-staging"
        private const val BACKUP_DIRECTORY = "offline-mfi-backup"

        /** APK 内的资产目录：`assets/offline-mfi/`。 */
        const val ASSET_DIRECTORY = "offline-mfi"
    }
}
