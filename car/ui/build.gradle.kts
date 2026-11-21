plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dbtool.carplay.ui"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Hard rule: the UI layer depends on the contract ONLY.
    //
    // It must never depend on :shared, :common, :car:core-bridge or any
    // platform adapter. That is what makes "重新设计整个车机 UI 而不动 Core"
    // structurally true instead of a convention people forget.
    api(project(":car:api"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    //
    // ⚠️ 用 libs.junit（版本目录里已存在的别名，与 core-bridge / platform-geely 一致），
    // 不新增外部 artifact。
    testImplementation(libs.junit)

    // Compose 动效（`rememberInfiniteTransition` / `AnimatedContent` / 补间）。
    //
    // ⚠️ 刻意用**裸坐标**而不是版本目录别名：`gradle/libs.versions.toml` 是上游文件，
    // 往里加一行别名就多出一处每次同步上游都要处理的冲突（见 `PATCHES.md` 的登记规则）。
    // 版本由上面的 compose BOM 统一管，所以这里不写版本号。
    implementation("androidx.compose.animation:animation")

    // NOTE: no material-icons dependency on purpose.
    // Icons are hand-drawn Compose vectors in `theme/CarIcons.kt` so that
    //   (a) we add zero new external artifacts (no upstream version-catalog edit),
    //   (b) the icon set is ours, not a stock Material look.
}
