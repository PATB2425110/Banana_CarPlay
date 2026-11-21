plugins {
    id("com.android.library")
}

android {
    namespace = "com.dbtool.carplay.api"
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
    testImplementation(libs.junit)
}

// Deliberately dependency-free: this module is the contract between the CarPlay
// core and every extension/adapter/UI module, so it must not depend on
// :shared, :common, :mobile, :automotive or any third-party library.
