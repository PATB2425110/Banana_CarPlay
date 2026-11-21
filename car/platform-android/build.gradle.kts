plugins {
    id("com.android.library")
}

android {
    namespace = "com.dbtool.carplay.android"
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
    // Only the contract layer. Deliberately does NOT depend on :shared or :common,
    // so a platform adapter can never reach into the CarPlay protocol core.
    api(project(":car:api"))

    // 本模块原先没有单测，测试类路径上也就没有 JUnit。去抖行为必须用
    // `:car:platform-android:testDebugUnitTest` 直接钉死，所以接上版本目录里
    // 已有的 `libs.junit`——没有引入新库，也不牵连任何模块依赖。
    testImplementation(libs.junit)
}
