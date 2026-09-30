package walkassist.core.replay

import walkassist.core.types.ConfigLoader
import java.io.File

/**
 * `./gradlew :core:replay -Psession=<세션 폴더> [-Pout=<출력>] [-PslowMs=10 | -PslowFrom=<slow_path.csv>] [-PoverridesFile=<json 파일>]`
 * 설정 덮어쓰기는 파일로 준다(Windows에서 명령줄 JSON의 따옴표가 사라진다, M8 검토 중 발견). `-Poverrides`도 받지만 따옴표 없는 셸에서만 쓴다.
 * 기본 출력: `core/build/replay/<세션ID>/<overrides 요약 또는 default>/`.
 */
fun main(args: Array<String>) {
    val opts = args.associate { a -> a.substringBefore('=') to a.substringAfter('=', "") }
    val session = File(opts["session"] ?: error("session=<dir> required"))
    val overrides = opts["overridesFile"]?.takeIf { it.isNotBlank() }?.let { File(it).readText().trim() }
        ?: opts["overrides"]?.takeIf { it.isNotBlank() } ?: "{}"
    val base = File(System.getProperty("walkassist.defaultConfig")).readText()
    val config = ConfigLoader.load(base, overrides)
    val slowMs = opts["slowMs"]?.takeIf { it.isNotBlank() }?.toFloat() ?: 10f
    val slowFrom = opts["slowFrom"]?.takeIf { it.isNotBlank() }?.let(::File)
    val tag = if (overrides == "{}") "default" else overrides.replace(Regex("[^A-Za-z0-9.]+"), "_").trim('_').take(60)
    val out = opts["out"]?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("walkassist.replayOut"), "${session.name}/$tag")
    val slow = slowFrom?.let { OfflineReplay.recorded(it, slowMs) } ?: OfflineReplay.fixed(slowMs)
    val note = slowFrom?.let { "recorded:${it.path.replace('\\', '/')}" } ?: "fixed:${slowMs}ms"
    OfflineReplay(config, slow).run(session, out, overrides, note)
    println("replay log: ${out.absolutePath}")
}
