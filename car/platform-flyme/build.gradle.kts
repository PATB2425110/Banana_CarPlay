plugins {
    id("com.android.library")
}

android {
    namespace = "com.dbtool.carplay.flyme"
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

    // Reuse the stock-Android baseline so the Flyme adapter only has to override
    // the handful of domains that actually differ.
    implementation(project(":car:platform-android"))
}
