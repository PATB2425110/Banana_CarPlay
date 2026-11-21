package com.dbtool.carplay.bridge.update

/** Metadata read from an APK archive before any installer is invoked. */
internal data class CarUpdateApkIdentity(
    val packageName: String?,
    val versionCode: Long,
)

/** Pure policy shared by the archive reader and JVM tests. */
internal fun validateCarUpdateApkIdentity(
    actual: CarUpdateApkIdentity,
    expectedPackageName: String,
    expectedVersionCode: Long,
): String? = when {
    actual.packageName.isNullOrBlank() -> "安装包无法解析包名"
    actual.packageName != expectedPackageName ->
        "安装包包名不匹配：期望 $expectedPackageName，实际 ${actual.packageName}"
    actual.versionCode <= 0L -> "安装包 versionCode 无效"
    actual.versionCode != expectedVersionCode ->
        "安装包版本号不匹配：期望 $expectedVersionCode，实际 ${actual.versionCode}"
    else -> null
}
