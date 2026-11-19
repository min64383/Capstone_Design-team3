// Android SDK 없이 실제 앱의 순수 Kotlin 상태 정책/안전 설정과 같은 테스트를 실행한다.
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDir("../../app/src/main/java")
            kotlin.include("hearspace/app/ui/user/StatusSpeechPolicy.kt", "hearspace/app/runtime/TtsSafetyConfig.kt")
        }
        test {
            kotlin.srcDir("../../app/src/test/java")
            kotlin.include("hearspace/app/ui/user/StatusSpeechPolicyTest.kt", "hearspace/app/runtime/TtsSafetyConfigTest.kt")
        }
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test { useJUnitPlatform() }
