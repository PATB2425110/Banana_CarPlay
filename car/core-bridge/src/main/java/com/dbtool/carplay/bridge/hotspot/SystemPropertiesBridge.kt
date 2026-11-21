package com.dbtool.carplay.bridge.hotspot

/**
 * 对 `android.os.SystemProperties` 的**最小反射桥**（2026-10-06 加）。
 *
 * ## 为什么不直接 `import android.os.SystemProperties`
 *
 * 它是 `@hide` API —— 编译期虽有类，但直接引用会在 lint / 编译期报
 * "hidden API" 且被 `greylist` 策略在运行时拦（Android 9+ 对非 SDK 接口有名单）。
 * 反射调用则与 `AndroidCarSystemAdapter.systemProperty()` 走的是**同一条已验证可行的路**，
 * 保持全项目一致（不引入第二套写法）。
 *
 * ## 为什么不学参考实现把 `android.fxbridge.FxBridge` 抄进 dex
 *
 * 参考 APK 里那个类（反编译见 `_decompile/fuck-lynkco-adb_smali/android/fxbridge/FxBridge.smali`）
 * 方法体就是 `SystemProperties.get/set` 的纯转发。抄一个同签名的类**技术上可行**，
 * 但相比反射没有任何额外收益（最终都落到同一个 `@hide` 方法），
 * 却要多维护一份"伪装成 `android.*` 包名"的源码 —— 那是它的个人签名绕过手段，
 * 不是我们需要的。
 *
 * ## ⚠️ 关于 `set` 的权限
 *
 * `SystemProperties.set` 对**非 `persist.*` 的普通属性**不查身份，但：
 *
 * - `service.adb.tcp.port` / `ctl.*` —— 普通应用可写（参考实现实证能开 ADB）；
 * - **`persist.*` 由 SELinux 的 `property_contexts` 单独划区**，
 *   三方应用常被拒。这是本桥**必须 best-effort** 的根本原因；
 * - 被拒时抛 `SecurityException`（AOSP 新版是 `SystemProperties` 内部的
 *   `BootReceiver`/`property_set` 校验），由调用方 [DeviceAdbEnabler] 统一降级。
 *
 * ## 测试
 *
 * 纯反射 + `runCatching`，单测里 JVM 上没有 `android.os.SystemProperties`
 * ⇒ [get] 必然走到 `getOrDefault`、[set] 必然抛 —— 正好用来断言"降级不炸"。
 */
internal object SystemPropertiesBridge {

    private const val CLASS_NAME = "android.os.SystemProperties"

    /**
     * `SystemProperties.get(key, def)`。
     *
     * 读不到（类不存在 / 方法被改 / 无权限）⇒ 返回 [def]。
     * 与参考实现 `FxBridge.getProperties(key, "")` 的默认值语义一致。
     */
    fun get(key: String, def: String): String = runCatching {
        val clazz = Class.forName(CLASS_NAME)
        val method = clazz.getMethod("get", String::class.java, String::class.java)
        (method.invoke(null, key, def) as? String) ?: def
    }.getOrDefault(def)

    /**
     * `SystemProperties.set(key, value)`。
     *
     * ⚠️ **不吞异常**：`ClassNotFoundException` / `NoSuchMethodException` /
     * `InvocationTargetException`（内含 `SecurityException`）原样抛出，
     * 由 [DeviceAdbEnabler.enable] 用一个大 try 包住并把**真实失败类型**
     * 写进诊断 detail —— 这比在这里静默掉更有排障价值。
     */
    fun set(key: String, value: String) {
        val clazz = Class.forName(CLASS_NAME)
        val method = clazz.getMethod("set", String::class.java, String::class.java)
        method.invoke(null, key, value)
    }
}
