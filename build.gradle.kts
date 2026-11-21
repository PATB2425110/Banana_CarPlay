// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---------------------------------------------------------------------------
// API 级别防线（2026-10-10）
// ---------------------------------------------------------------------------
// **背景**：本产品运行在 **Android 9（SDK 28）** 车机上，而 `compileSdk` 是 37。
// 中间差着 9 个版本的框架 API，而 `assembleRelease` 只卡 fatal 级检查 ——
// 于是"用了一个运行机上根本不存在的类/方法"能一路编译、打包、上车，然后才炸。
//
// **真车事故**（本防线就是为了它）：`GeelyClusterPresentation` 里用了
// `ViewTreeObserver.addOnWindowVisibilityChangeListener`（API 34 / Android 14）。
// Android 9 的 `ViewTreeObserver` 里没有这个内部类 ⇒ 类校验阶段
// `NoClassDefFoundError` ⇒ **仪表窗口一次都建不起来**，现场表现是
// 「投屏完全不工作」，而构建全程绿灯。
//
// **做法**：把 `NewApi` 提到 fatal，并且只作用在本工程自己的 `:car:*` 模块上
// （不碰上游 `:shared` / `:common` 的历史 lint 债 —— 那两库有既存 NewApi/InlinedApi
// 记录，属于另一件事，见 `设置页-仪表导航分区-20261010.md` 的已知边界）。
//
// 生效链路：各 `:car:*` 库自身的 lint 会报错（`lintDebug` 失败），
// `:car:app:lintVitalRelease` 也会因此中止构建 —— 已实测（故意留一个
// API 34 调用时 release 出包直接失败，错误定位到具体文件行）。
//
// ⚠️ 判据是本模块的 `minSdk = 28`：**带 `Build.VERSION.SDK_INT` 守卫**的
// 新 API 调用不会误报（lint 会做数据流判断）。真要在 28 上跑新 API，
// 必须写版本分支 —— 这也是我们想要的写法。
subprojects {
    if (!path.startsWith(":car:")) return@subprojects
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            lint { fatal += "NewApi" }
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
            lint { fatal += "NewApi" }
        }
    }
}
