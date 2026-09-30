import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 순수 Kotlin(JVM) 모듈. Android·ARCore 의존성을 두지 않아 해당 import는 컴파일 오류가 된다 (§14-2).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 설정의 유일한 원본은 app 자산의 default.json이다 (DECISIONS.md 참고)
    val defaultConfig = rootProject.file("app/src/main/assets/config/default.json")
    inputs.file(defaultConfig)
    systemProperty("walkassist.defaultConfig", defaultConfig.absolutePath)
    // M6: 앱 자산의 HRIR(SADIE II D1)과 스윕 WAV 출력 위치(헤드폰 확인용)
    val hrir = rootProject.file("app/src/main/assets/hrtf/sadie2_d1_48k.hrir")
    inputs.file(hrir)
    systemProperty("walkassist.hrtfAsset", hrir.absolutePath)
    systemProperty("walkassist.testOutput", layout.buildDirectory.dir("test-output").get().asFile.absolutePath)
    systemProperty("walkassist.coreSrc", project.file("src").absolutePath)
    inputs.dir("src/main")
}

// M8 오프라인 재생: ./gradlew :core:replay -Psession=<세션 폴더> [-Pout=..] [-PslowMs=10 | -PslowFrom=<slow_path.csv>] [-PoverridesFile=<json>]
tasks.register<JavaExec>("replay") {
    group = "walkassist"
    description = "녹화 세션을 PC에서 오프라인 재생해 실행 로그(§10.1)를 쓴다"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("walkassist.core.replay.OfflineReplayMainKt")
    systemProperty("walkassist.defaultConfig", rootProject.file("app/src/main/assets/config/default.json").absolutePath)
    systemProperty("walkassist.replayOut", layout.buildDirectory.dir("replay").get().asFile.absolutePath)
    val paths = setOf("session", "out", "slowFrom", "overridesFile")
    args = listOf("session", "out", "slowMs", "slowFrom", "overrides", "overridesFile").mapNotNull { k ->
        (project.findProperty(k) as String?)?.let { v -> "$k=${if (k in paths) rootProject.file(v).absolutePath else v}" }
    }
}
