package com.dbtool.carplay.api.settings

/**
 * 设置的持久化契约。
 *
 * 放在 `:car:api` 而不是直接让 UI 用 core-bridge 的具体类，原因有两个：
 * 1. UI 只允许依赖 `:car:api`；
 * 2. 未来要把设置存到车机专有位置（Flyme/OSN 的配置服务）时，
 *    只需要换一个实现，界面与 ViewModel 不动。
 *
 * 实现方应当是**线程安全 + 非阻塞**的：设置页会频繁读写。
 */
interface CarSettingsStore {
    /** 读取当前设置。任何异常都应回落到 [CarSessionSettings.default] 而不是抛出。 */
    fun load(): CarSessionSettings

    /** 保存设置。实现方负责合并与持久化。 */
    fun save(settings: CarSessionSettings)

    /** 恢复出厂设置并返回恢复后的值。 */
    fun reset(): CarSessionSettings = CarSessionSettings.default().also(::save)
}
