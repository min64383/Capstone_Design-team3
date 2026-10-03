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

// ./gradlew :viewer:run [-Psession=testdata/sessions/<세션ID>]
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    (project.findProperty("session") as String?)?.let { args(rootProject.file(it).absolutePath) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("hearspace.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("java.awt.headless", "true")
}
