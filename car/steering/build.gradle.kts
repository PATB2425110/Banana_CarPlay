plugins {
    id("com.android.library")
}

android {
    namespace = "com.dbtool.carplay.steering"
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

    // 去抖窗口是「一次按压被车机推两条 DOWN」这个实车缺陷的唯一防线，
    // 必须用单测钉死阈值行为（见 CarInputRouterTest）。
    // 注意：Kotlin DSL 里 `testImplementation(...)` 必须带括号。
    testImplementation(libs.junit)
}
