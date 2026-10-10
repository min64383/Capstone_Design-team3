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
    systemProperty("hearspace.defaultConfig", defaultConfig.absolutePath)
    // M6: 앱 자산의 HRIR(SADIE II D1)과 스윕 WAV 출력 위치(헤드폰 확인용)
    val hrir = rootProject.file("app/src/main/assets/hrtf/sadie2_d1_48k.hrir")
    inputs.file(hrir)
    systemProperty("hearspace.hrtfAsset", hrir.absolutePath)
    systemProperty("hearspace.testOutput", layout.buildDirectory.dir("test-output").get().asFile.absolutePath)
    systemProperty("hearspace.coreSrc", project.file("src").absolutePath)
    inputs.dir("src/main")
    // M13: 기존 테스트 전부를 설정 변형으로 다시 돌린다(명세 §6.1.1 "기존 SC 전부 통과"). -PtestOverrides=<json>이면 default.json에
    // 그 JSON을 깊게 합친 설정을 쓰고, 기본값 자체를 확인하는 ConfigLoaderTest는 뺀다.
    // 예: ./gradlew :core:test -PtestOverrides=core/src/test/config/frontend-on.json
    (project.findProperty("testOverrides") as String?)?.let { path ->
        val overrides = rootProject.file(path)
        inputs.file(overrides)
        val mergedText = mergeJson(defaultConfig.readText(), overrides.readText())
        val merged = layout.buildDirectory.file("test-config/default.json").get().asFile
        doFirst {
            merged.parentFile.mkdirs()
            merged.writeText(mergedText)
        }
        systemProperty("hearspace.defaultConfig", merged.absolutePath)
        filter { excludeTestsMatching("hearspace.core.types.ConfigLoaderTest") }
    }
}

/** [over]의 키로 [base]를 깊게 덮어쓴 JSON(객체는 키마다 합치고 그 밖의 값은 바꾼다). */
fun mergeJson(base: String, over: String): String {
    @Suppress("UNCHECKED_CAST")
    fun merge(a: Map<String, Any?>, b: Map<String, Any?>): Map<String, Any?> = a.toMutableMap().also { out ->
        for ((k, v) in b) {
            val old = out[k]
            out[k] = if (old is Map<*, *> && v is Map<*, *>) merge(old as Map<String, Any?>, v as Map<String, Any?>) else v
        }
    }
    val slurper = groovy.json.JsonSlurper()
    @Suppress("UNCHECKED_CAST")
    val merged = merge(slurper.parseText(base) as Map<String, Any?>, slurper.parseText(over) as Map<String, Any?>)
    return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(merged))
}

// M8 오프라인 재생: ./gradlew :core:replay -Psession=<세션 폴더> [-Pout=..] [-PslowMs=10 | -PslowFrom=<slow_path.csv>] [-PoverridesFile=<json>]
// M12.3 정답 대입: [-Psubstitute=depth,map,heading -PalignFrom=<align.json> -PdepthNoisePerM=0.03]
tasks.register<JavaExec>("replay") {
    group = "hearspace"
    description = "녹화 세션을 PC에서 오프라인 재생해 실행 로그(§10.1)를 쓴다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("hearspace.core.replay.OfflineReplayMainKt")
    systemProperty("hearspace.defaultConfig", rootProject.file("app/src/main/assets/config/default.json").absolutePath)
    systemProperty("hearspace.replayOut", layout.buildDirectory.dir("replay").get().asFile.absolutePath)
    val paths = setOf("session", "out", "slowFrom", "overridesFile", "alignFrom")
    args = listOf("session", "out", "slowMs", "slowFrom", "overrides", "overridesFile", "substitute", "alignFrom", "depthNoisePerM").mapNotNull { k ->
        (project.findProperty(k) as String?)?.let { v -> "$k=${if (k in paths) rootProject.file(v).absolutePath else v}" }
    }
}
