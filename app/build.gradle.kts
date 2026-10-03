// AGP 9는 Kotlin을 내장하므로 org.jetbrains.kotlin.android 플러그인을 적용하지 않는다.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "hearspace.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "hearspace.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ARCORE_SDK_VERSION", "\"${libs.versions.arcore.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.arcore)
}
