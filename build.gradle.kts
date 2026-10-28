plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// M41: 앱 빌드(BuildConfig.GIT_HASH → meta.json)와 PC 재생(replay_info.json)에 남길 코드 판. git 커밋 짧은 해시이고 작업 트리가
// 바뀌었으면 -dirty가 붙는다(--exclude=*: 태그 이름 대신 해시만). git이 없거나 저장소가 아니면 unknown.
val gitHash by extra(
    runCatching {
        providers.exec {
            commandLine("git", "describe", "--always", "--dirty", "--exclude=*")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown",
)
