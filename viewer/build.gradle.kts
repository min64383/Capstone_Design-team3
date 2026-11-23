import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 평가 GUI (IMPROVE_SPEC §10). JDK 내장 Swing·javax.sound만 쓴다(새 의존성 없음). core에만 의존하고 app에는 의존하지 않아
// Android SDK가 없는 PC에서도 빌드된다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(project(":core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("hearspace.viewer.MainKt")
}

// ./gradlew :viewer:run [-Psession=<세션 ID | ID 일부 | testdata/sessions 기준 경로 | 저장소 기준 경로>] — 찾기는 GUI가 한다(Sessions.resolve)
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("hearspace.objectCues", (project.findProperty("objectCues") as String?) ?: "false")
    (project.findProperty("session") as String?)?.let { args(it) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    // M66 중계 부하 시험 길이(s). CI는 5, 10분 실행은 -PrelayLoadS=600
    systemProperty("relay.loadS", (project.findProperty("relayLoadS") as String?) ?: "5")
    systemProperty("java.awt.headless", "true")
}

// 같은 렌더링에서 CSV와 WAV 저장. Android 기기 불필요.
tasks.register<JavaExec>("exportSonification") {
    group = "hearspace"
    description = "PC 녹화 재생의 음향 CSV와 WAV를 같은 실행에서 저장"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("hearspace.viewer.ExportSonificationMainKt")
    workingDir = rootProject.projectDir
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("hearspace.objectCues", (project.findProperty("objectCues") as String?) ?: "false")
    val session = project.findProperty("session") as String?
    val overrides = project.findProperty("overridesFile") as String?
    val out = project.findProperty("out") as String?
    if (session != null) {
        args(rootProject.file(session).absolutePath, overrides?.let { rootProject.file(it).absolutePath } ?: "")
        if (out != null) args(rootProject.file(out).absolutePath)
    }
}

// M70 사운드팩: 앱이 낼 수 있는 소리를 앱 설정 층·렌더러로 WAV + index.html에. ./gradlew :viewer:soundPack [-Pout=<폴더>] [-PoverridesFile=<덮어쓰기 json>]
tasks.register<JavaExec>("soundPack") {
    group = "hearspace"
    description = "앱 소리를 모두 담은 사운드팩(WAV, index.html)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("hearspace.viewer.SoundPackMainKt")
    workingDir = rootProject.projectDir
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    args(providers.gradleProperty("out").map { rootProject.file(it).absolutePath }.getOrElse(""),
        providers.gradleProperty("overridesFile").map { rootProject.file(it).absolutePath }.getOrElse(""))
}

// M30 화면 없는 그림 저장: 책갈피(viewer/bookmarks.json)마다 카메라 화면 + 위에서 본 그림(지도 칸 겹침), 아래 줄은 정답 깊이 대입(D).
// ./gradlew :viewer:snapshot [-Psession=<ID 일부>] [-Pvariant=<변형 이름>] [-Pcompare=<변형 이름>](아래 줄을 그 변형으로, M32) [-Pout=<폴더>]
tasks.register<JavaExec>("snapshot") {
    group = "hearspace"
    description = "책갈피 시점의 viewer 화면을 PNG로 저장(화면 없이)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("hearspace.viewer.SnapshotMainKt")
    workingDir = rootProject.projectDir
    maxHeapSize = "4g"
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("java.awt.headless", "true")
    args = listOf("session", "variant", "compare", "alignDir", "out", "times").mapNotNull { k ->
        (project.findProperty(k) as String?)?.let { v -> "$k=${if (k == "out" || k == "alignDir") rootProject.file(v).absolutePath else v}" }
    }
}

// M66 데모 중계 서버: ./gradlew :viewer:relay [-PrelayConfig=<덮어쓰기 JSON>]. 설정은 app/src/bench/assets/relay.json, 열쇠는 git 밖 data/relay/server.json
tasks.register<JavaExec>("relay") {
    group = "hearspace"
    description = "데모 중계 서버(M66): 업로드를 받아 소리·화면·채팅을 페이지로"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("hearspace.viewer.RelayServerKt")
    workingDir = rootProject.projectDir
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("hearspace.gitHash", rootProject.extra["gitHash"].toString())
    providers.gradleProperty("relayConfig").orNull?.let { args(rootProject.file(it).absolutePath) }
}

// M66 PC 녹화 재생기(업로더 대역): ./gradlew :viewer:relayReplay -Psession=<ID 일부> [-Pjitter=<ms>] [-Ploop=false] [-Pcheck=true] [-Purl=<서버>]
tasks.register<JavaExec>("relayReplay") {
    group = "hearspace"
    description = "녹화를 앱 설정 층으로 재생해 중계 서버에 실제 시간으로 올림(M66)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("hearspace.viewer.RelayReplayMainKt")
    workingDir = rootProject.projectDir
    maxHeapSize = "4g"
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("hearspace.gitHash", rootProject.extra["gitHash"].toString())
    // -P 값만 읽는다(findProperty는 같은 이름의 작업, 예: check를 돌려준다)
    args = listOf("session", "jitter", "loop", "check", "url").mapNotNull { k -> providers.gradleProperty(k).orNull?.let { "$k=$it" } }
}
