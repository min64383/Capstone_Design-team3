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
    (project.findProperty("session") as String?)?.let { args(it) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
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
    val session = project.findProperty("session") as String?
    val overrides = project.findProperty("overridesFile") as String?
    val out = project.findProperty("out") as String?
    if (session != null) {
        args(rootProject.file(session).absolutePath, overrides?.let { rootProject.file(it).absolutePath } ?: "")
        if (out != null) args(rootProject.file(out).absolutePath)
    }
}
