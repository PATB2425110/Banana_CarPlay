plugins {
    id("com.android.library")
}

android {
    namespace = "com.dbtool.carplay.bridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":car:api"))

    // The only module allowed to touch upstream core. Every other extension module
    // must go through :car:api, so a car-specific change can never leak into Core.
    implementation(project(":shared"))

    // 在线更新要用 `FileProvider` 把下载好的 APK 交给系统安装器。
    //
    // ⚠️ Android 7 起 `file://` URI 不能跨进程传给安装器（`FileUriExposedException`），
    // 必须走 `content://`，而唯一现成的实现就在 androidx.core 里。
    // 刻意**不自己写一个 ContentProvider**：那要多维护一个 exported=false 的组件，
    // 只为省一个已经在 :car:app / :car:ui 里用着的依赖。
    //
    // 版本走上游的版本目录（`libs.androidx.core.ktx` 已存在），因此没有新增
    // 需要同步上游的版本别名。
    implementation(libs.androidx.core.ktx)

    // 「当前正在播放」客户端要连上游的 `CarPlayMediaSessionService`（Media3 的
    // `MediaSessionService`）。用标准 `MediaBrowser` / `MediaController` 去读它，
    // 是**唯一一条不需要改上游**的路子：
    //
    // 上游把 iAP2 的元数据与封面字节封在 `CarPlayMediaSessionBridge` 里，而它是
    // `internal` —— Kotlin 的 internal 是**模块级**可见性，`:car:core-bridge`
    // 即使能 import `com.shilapi.xcertplay.*` 也读不到。与其为它加一个上游补丁
    // （要登记 PATCHES.md、还会把足迹复核弄脏），不如走 Media3 已经公开的那扇门。
    //
    // ⚠️ 上游 `shared` 里声明的是 `implementation libs.androidx.media3.session`，
    // 不会传递到本模块，所以必须在这里显式再写一次。版本仍走上游版本目录，
    // 不会引入需要同步的版本别名。
    implementation(libs.androidx.media3.session)

    // 本地 MFi 的环回服务是按 upstream `RemoteMfiAuthenticationClient` 的 HTTP 契约写的，
    // 所以单测直接拿**上游那个真实客户端**去打它——这样"契约没对错"是被证明的，
    // 而不是靠注释声明的。
    //
    // 注意：Kotlin DSL 里必须写成 `testImplementation(...)` 带括号的形式。
    // 中缀写法 `testImplementation libs.junit` 在 Kotlin 里不合法（中缀调用的实参
    // 不允许带点号），Groovy 的 `:shared` 能那么写，这里不行。
    testImplementation(libs.junit)
}
