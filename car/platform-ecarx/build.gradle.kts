plugins {
    id("com.android.library")
}

android {
    namespace = "com.dbtool.carplay.ecarx"
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

    // Reuse the stock-Android baseline so this module only has to override
    // the domains that actually differ (here: input).
    implementation(project(":car:platform-android"))

    // ── 厂商 jar（亿咖通 ECARX）─────────────────────────────────────────────
    //
    // 车机 ROM 的 `/system/framework/` 里有这三个 jar（本机 dump 取自
    // `/vol02/1000-1-6253137b/OSN2.0-内置应用/framework/`）：
    //   ecarx.jar / ecarx.car.jar / ecarx.adaptapi.jar
    //
    // 为什么用 `compileOnly`：
    // - 真机上类由系统提供，打进 APK 只会增大体积、且可能与 ROM 版本冲突；
    // - 编译期能拿到**真实 ROM 的方法签名**，一旦 ROM 里换了实现类（例如
    //   `wifiap.impl.Wifi6ApImpl` 在本车缺失），编译期就能发现，而不是等到
    //   上车跑日志才发现 `ClassNotFoundException`。
    //
    // `-PsimulateMode=true` 时改为 `implementation`（打进 APK），供模拟器 /
    // 无厂商框架的环境使用；默认 false，走 compileOnly。
    //
    // ⚠️ jar 必须与目标车 ROM 版本一致；换 ROM 要同步换 jar。
    // ⚠️ 厂商 jar 只做内部编译用，不得随 APK 对外分发。
    val simulateMode = (findProperty("simulateMode") as String?)?.toBoolean() ?: false
    val vendorJars = files(
        "libs/ecarx.jar",
        "libs/ecarx.car.jar",
        "libs/ecarx.adaptapi.jar",
    )
    if (simulateMode) {
        implementation(vendorJars)
    } else {
        compileOnly(vendorJars)
    }

    // 帆书广播通道里那两处最容易错的判断（去重指纹、切歌时的状态跳变）是纯逻辑，
    // 抽在 `FdBookBroadcastPlan` 里就能在 JVM 上跑单测——不必上车试。
    testImplementation(libs.junit)
}
