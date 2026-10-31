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
        // M41: 녹화 meta.json에 남길 코드 판(루트 build.gradle.kts)
        buildConfigField("String", "GIT_HASH", "\"${rootProject.extra["gitHash"]}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // M45: 화면 상태 변환(UserScreen)·안내 글의 JVM 단위 시험(JUnit 5, core와 같은 버전)
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.arcore)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
