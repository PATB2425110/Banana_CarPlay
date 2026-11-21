plugins {
    id("com.android.library")
}

android {
    namespace = "com.dbtool.carplay.geely"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":car:api"))

    // 吉利车机本身就是 eCarx 平台（方控走 OneOS KeyInput binder），
    // 仪表 HDMI2 是**在此之上**多出来的一块能力 —— 见 GeelyCarPlatform 的注释。
    // 因此复用的是 eCarx 平台，而不是纯 Android 基线。
    //
    // ⚠️ `platform-ecarx` 用 `implementation` 依赖 `platform-android`，不会传递，
    // 而 GeelyCarSystemAdapter 要继承 AndroidCarSystemAdapter ⇒ 必须显式再声明一次。
    implementation(project(":car:platform-ecarx"))
    implementation(project(":car:platform-android"))

    // 副屏选择判据（eCarx 探测 / 非主屏 1920x1080 唯一命中）与 HUD turn 映射
    // 都是纯逻辑，抽成不依赖 Android 类型的对象就能在 JVM 上跑单测——不必上车试。
    testImplementation(libs.junit)
}
