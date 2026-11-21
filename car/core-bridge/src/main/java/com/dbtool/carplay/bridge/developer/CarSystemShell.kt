package com.dbtool.carplay.bridge.developer

/**
 * 一条**已经就绪**的高权限 shell（adb shell，uid = `shell`）。
 *
 * ## 为什么诊断包需要它
 *
 * 应用自己的 uid **读不到别人的日志**：Android 上 `READ_LOGS` 是
 * signature|privileged 权限，所以 `logcat -b system` 只会回**本进程**的行，
 * `dumpsys` 更是直接被 `Permission Denial` 拒掉。
 *
 * 2026-09-29 的实车包就是这么来的：`logcat.txt` 里只有本应用的 5 个 pid
 * （14237 / 7867 / 14283 / 8566 / 16480），于是"主线程在某 300 ms 内一行日志
 * 都没有"这件事**无法区分**是「应用内计算」还是「等 binder / 等 vsync」——
 * 缺的正是 system_server / SurfaceFlinger 那一侧。当时只能把结论收紧成
 * "这份包读不出来"，白等一轮。
 *
 * `shell` uid 有 `DUMP` 权限、能读**全部** logcat 缓冲区 ⇒ 走这条通道才有系统视角。
 *
 * ## 契约
 *
 * - **只在已经就绪时使用**：调用方取 `AdbShellSession.peek()`，**绝不**为了采集
 *   去 `ensure()` —— 那会顺手把车机的「ADB 调试」打开，属于用户没同意的系统设置改动。
 * - 拿不到通道**不是错误**：返回 null，由调用方回落到应用自己的 uid，
 *   并**如实**在清单里写明"本次没有系统视角"。诊断包宁可少一份文件，
 *   也不能出现"看起来采到了、其实是空的"。
 */
internal fun interface CarSystemShell {

    /**
     * @param command 设备上执行的 shell 命令（不含 `adb shell` 前缀）。
     * @return stdout 文本；通道不可用 / 超时 / 退出码非 0 一律返回 **null**
     *   —— 让调用方走回落，而不是把空串当成"命令成功但没有输出"。
     */
    fun run(command: String, timeoutMillis: Long): String?
}
