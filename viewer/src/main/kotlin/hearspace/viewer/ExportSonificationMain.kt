package hearspace.viewer

import java.io.File

/** GUIなしで、GUIと同じReplayRunnerの一回の実行を保存する。 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Use -Psession=testdata/sessions/<session>" }
    val session = File(args[0])
    val overrides = if (args.size > 1 && args[1].isNotEmpty()) File(args[1]).readText()
        else "{}" // 앱 음향 덮어쓰기는 ReplayRunner가 항상 쌓는다
    val dir = if (args.size > 2) File(args[2]) else SonificationExport.newDirectory(session)
    ReplayRunner.run(session, overrides, ReplayRunner.loadHrtf(), dir)
}
