// AGP 9는 Kotlin을 내장하므로 org.jetbrains.kotlin.android 플러그인을 적용하지 않는다.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "walkassist.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "walkassist.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
}
