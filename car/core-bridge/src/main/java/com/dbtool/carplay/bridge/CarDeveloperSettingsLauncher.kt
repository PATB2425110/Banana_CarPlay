package com.dbtool.carplay.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.dbtool.carplay.bridge.hotspot.SystemPropertiesBridge

/**
 * 把用户送到**车机系统**的「开发者选项 / ADB 调试」页。
 *
 * ## 为什么需要它
 *
 * 自建热点链路的第 4、5 步（连本机 adb / 提权）失败时，用户能主动做的事里
 * 有一件是**去车机系统设置里确认「ADB 调试」是打开的**。这个开关不在 DBPlay 里，
 * 而 OneOS 的设置金字塔有三四层（设置 → 关于 → 连点版本号 → 开发者选项），
 * 现场让用户自己找基本等于没有出路——所以必须给一个直达入口。
 *
 * ⚠️ 但它不是**唯一**的出路，也不一定是这次的病因：
 * 实车诊断包 `1791279270080` 显示 ADB 本来就开着（`init.svc.adbd=running`），
 * 挂的是提权环节。所以界面那边把「重试」摆在第一位，本入口是备选。
 *
 * ## 为什么按"候选列表逐个试"而不是只发一个 Intent
 *
 * 车机 ROM 对"开发者选项"这个页面的暴露方式是**完全不统一**的：
 * - 原生 Android 是 `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`；
 * - 部分 ROM（含本车的 OneOS）把它藏起来，只留一个自定义 Component；
 * - 还有一些只让 `ACTION_SETTINGS` 进总入口。
 *
 * 没有一个 Intent 是"通用"的，所以只能**从最精确到最宽松**逐个尝试，
 * 第一个能 resolve 的就用。这不是"猜"，是把三种已知的官方入口都试一遍。
 *
 * ## ⚠️ 绝不静默失败
 *
 * 全部候选都 resolve 不到时必须返回 [Result.ok] = false 并带上人话说明。
 * 界面那侧同时给着图文步骤，但如果这里吞掉失败，用户会以为"按钮坏了"，
 * 而实际上他只是需要自己找——这两件事必须能区分开。
 *
 * ## 与 `SystemPropertiesBridge` 的关系
 *
 * 无直接关系，这里只是顺手复用同一层"能读系统属性"的能力来**判断
 * `ro.debuggable`**，用于回调里给一句更准确的说明（userdebug 固件才有
 * 开发者选项可开）。**不依赖它做跳转判据**——跳转成功与否只看 resolve。
 */
object CarDeveloperSettingsLauncher {

    /** 一次跳转尝试的结果。 */
    data class Result(
        /** true = 已经成功 startActivity。 */
        val ok: Boolean,
        /** 日志用的一行（含实际用的那个 Intent，排障时能看出命中了哪条候选）。 */
        val logLine: String,
        /** 失败时给用户看的中文提示；成功时为 null。 */
        val userHint: String? = null,
    )

    /**
     * 依次尝试各候选入口，返回第一个成功的。
     *
     * @param context 任意 Context（内部会用 `applicationContext`）。
     */
    fun open(context: Context): Result {
        val app = context.applicationContext

        // 候选顺序 = **精确 → 宽松**。理由：越靠前的越直接落在"开发者选项"页，
        // 用户少点几层；`ACTION_SETTINGS` 放最后是因为它只到设置首页，
        // 用户还得自己往下找。
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 有专门的"应用开发"页，比总的开发者选项更贴题。
                add("应用开发设置" to Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } else {
                add("开发者选项" to Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            // 部分 ROM 把开发者选项做成 DeviceInfoSettings 的一个子页（AOSP 老写法）。
            add(
                "设备信息→开发者" to Intent().apply {
                    component = ComponentName(
                        "com.android.settings",
                        "com.android.settings.DevelopmentSettings",
                    )
                },
            )
            // 最后兜底：设置总入口。这里只能保证"用户到了设置里"，找不找得到看他。
            add("系统设置首页" to Intent(Settings.ACTION_SETTINGS))
        }

        val attempts = mutableListOf<String>()
        for ((label, intent) in candidates) {
            // 从非 Activity 的 Context 启动必须带 NEW_TASK，否则抛
            // AndroidRuntimeException（"Calling startActivity() from outside of an
            // Activity context requires the FLAG_ACTIVITY_NEW_TASK flag"）。
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching {
                app.packageManager.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
            if (!resolvable) {
                attempts += "$label(无此页面)"
                continue
            }
            val started = runCatching { app.startActivity(intent) }
            if (started.isSuccess) {
                attempts += "$label(✓)"
                return Result(
                    ok = true,
                    logLine = "已打开车机系统设置：$label｜尝试记录：${attempts.joinToString("、")}",
                )
            }
            attempts += "$label(启动失败：${started.exceptionOrNull()?.javaClass?.simpleName})"
        }

        val debuggable = runCatching { SystemPropertiesBridge.get("ro.debuggable", "") }
            .getOrDefault("")
        return Result(
            ok = false,
            logLine = "打开车机系统设置失败（${attempts.joinToString("、")}）",
            userHint = "这台车机没有开放「开发者选项」入口（ro.debuggable=$debuggable）。" +
                "请手动操作：车机 设置 → 关于本机 → 连点「版本号」7 次解锁开发者选项，" +
                "再回到设置里确认「ADB 调试」是打开的。",
        )
    }
}
