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

    // M61: 기기 처리 시간은 디버깅을 끈 빌드로 잰다(디버그 빌드는 느린 경로 시간의 약 절반이 ART 인터프리터·해석 경로였다, simpleperf).
    // bench = 디버그 서명·디버깅 끔. 개발 도구(자동 시작·기기 설정 덮어쓰기)는 DEV_TOOLS로 켠다(release는 끔).
    buildTypes {
        getByName("debug") { buildConfigField("boolean", "DEV_TOOLS", "true") }
        getByName("release") { buildConfigField("boolean", "DEV_TOOLS", "false") }
        create("bench") {
            initWith(getByName("debug"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // M65: 노면 모델(AI Hub 학습 가중치)은 공개 저장소에 넣지 않는다(배포 조항 미확인, docs/LICENSES.md). git 밖 data/models/app/의
    // surface.tflite가 있으면 빌드 때 자산으로 넣고, 없으면 앱이 상황 판단을 끈다. 메모리 맵으로 읽도록 압축하지 않는다.
    sourceSets.getByName("main").assets.srcDir("$rootDir/data/models/app")
    androidResources { noCompress += "tflite" }

    // M45: 화면 상태 변환(UserScreen)·안내 글의 JVM 단위 시험(JUnit 5, core와 같은 버전)
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.arcore)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.gpu.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
