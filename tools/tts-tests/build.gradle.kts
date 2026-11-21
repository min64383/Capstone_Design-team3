// Android SDK 없이 실제 앱의 순수 Kotlin 상태 정책/안전 설정과 같은 테스트를 실행한다.
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDir("../../app/src/main/java")
            kotlin.srcDir("../../core/src/main/kotlin")
            kotlin.include("hearspace/app/ui/user/StatusSpeechPolicy.kt", "hearspace/app/runtime/TtsSafetyConfig.kt")
            kotlin.include("hearspace/core/audio/VirtualScenario.kt", "hearspace/core/types/MiniJson.kt")
            kotlin.include("hearspace/core/audio/SpeechCollisionTest.kt")
        }
        test {
            kotlin.srcDir("../../app/src/test/java")
            kotlin.srcDir("../../core/src/test/kotlin")
            kotlin.include("hearspace/app/ui/user/StatusSpeechPolicyTest.kt", "hearspace/app/runtime/TtsSafetyConfigTest.kt")
            kotlin.include("hearspace/core/synth/VirtualScenarioPolicyTest.kt")
            kotlin.include("hearspace/core/synth/SpeechCollisionTestTest.kt")
        }
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test {
    useJUnitPlatform()
    val scenarioConfig = file("../../app/src/main/assets/config/virtual-scenario.json")
    inputs.file(scenarioConfig)
    systemProperty("hearspace.virtualScenarioConfig", scenarioConfig.absolutePath)
    val collisionConfig = file("../../app/src/main/assets/config/speech-collision-test.json")
    inputs.file(collisionConfig)
    systemProperty("hearspace.collisionConfig", collisionConfig.absolutePath)
}


