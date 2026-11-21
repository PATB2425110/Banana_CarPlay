plugins {
    id("com.android.application")
}

android {
    namespace = "io.dushu.car.hudprobe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.dushu.car.hudprobe"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
