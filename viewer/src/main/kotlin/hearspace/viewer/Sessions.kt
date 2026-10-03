package hearspace.viewer

import hearspace.core.geometry.Vec3
import hearspace.core.truth.GroundTruth
import java.awt.BorderLayout
import java.awt.Frame
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/** 세션 하나의 목록 정보. [path]는 저장소 기준 상대경로(`testdata/sessions/<ID>`). */
data class SessionEntry(val dir: File, val path: String, val id: String, val scene: String, val truth: String)

/** 평가 GUI가 여는 세션 찾기: 저장소 기준 상대경로와 세션 ID로 고른다. */
object Sessions {
    /** 세션을 찾는 폴더. 앞쪽이 우선이다(같은 ID가 둘 다 있으면 git의 정답 세션). */
    val roots: List<File> get() = listOf(Repo.sessions, File(Repo.root, "data/sessions"))

    fun isSession(d: File) = File(d, "meta.json").isFile && File(d, "frames.csv").isFile

    /** [roots] 아래 모든 세션(폴더 순서, 이름 순). */
    fun list(): List<SessionEntry> =
        roots.flatMap { r -> r.listFiles()?.filter { it.isDirectory && isSession(it) }?.sortedBy { it.name }.orEmpty() }.map(::entry)

    fun entry(d: File): SessionEntry {
        // 장면·정답 여부만 보므로 좌표 보정(머리 오프셋)은 쓰지 않는다
        val truth = runCatching { GroundTruth.read(d, Vec3(0f, 0f, 0f)) }
        val t = truth.getOrNull()
        val label = when {
            truth.isFailure -> "정답 파일 오류"
            t == null -> "없음"
            t.estimated -> "추정 ${t.obstacles.size}개"
            else -> "실측 ${t.obstacles.size}개"
        }
        return SessionEntry(d, Repo.relative(d), d.name, t?.scene ?: d.name.substringAfterLast('_'), label)
    }

    /**
     * 명령줄·입력값 → 세션 폴더. 순서: 그 경로 그대로(절대 또는 저장소 기준 상대) → [roots] 아래 같은 ID →
     * ID 일부(예: `130815`)가 한 세션에만 맞으면 그 세션. 못 찾거나 여럿에 맞으면 [IllegalArgumentException].
     */
    fun resolve(arg: String): File {
        val a = arg.trim().trimEnd('/', '\\')
        require(a.isNotEmpty()) { "세션이 비었다" }
        val direct = File(a).let { if (it.isAbsolute) it else File(Repo.root, a) }
        if (direct.isDirectory && isSession(direct)) return direct.canonicalFile
        roots.map { File(it, a) }.firstOrNull { it.isDirectory && isSession(it) }?.let { return it.canonicalFile }
        val byId = list().filter { it.id.contains(a) }.groupBy { it.id }
        return when (byId.size) {
            1 -> byId.values.single().first().dir.canonicalFile
            0 -> throw IllegalArgumentException("세션 '$a'을(를) 찾지 못했다(찾은 곳: ${roots.joinToString { Repo.relative(it) }})")
            else -> throw IllegalArgumentException("'$a'에 맞는 세션이 여럿이다: ${byId.keys.joinToString()}")
        }
    }

    /** 세션 목록 창. 고른 세션 폴더를 돌려준다(취소하면 null). [다른 폴더…]로 목록 밖 세션도 연다. */
    fun choose(owner: Frame?, current: File?): File? {
        val entries = list()
        val model = object : DefaultTableModel(arrayOf("경로(저장소 기준)", "장면", "정답"), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        entries.forEach { model.addRow(arrayOf(it.path, it.scene, it.truth)) }
        val table = JTable(model).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            columnModel.getColumn(0).preferredWidth = 360
            entries.indexOfFirst { it.dir.canonicalFile == current?.canonicalFile }.takeIf { it >= 0 }?.let { setRowSelectionInterval(it, it) }
        }
        var chosen: File? = null
        val dialog = JDialog(owner, "세션 열기", true)
        fun pick() {
            val i = table.selectedRow
            if (i >= 0) { chosen = entries[i].dir; dialog.dispose() }
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) pick() }
        })
        val buttons = JPanel().apply {
            add(JButton("열기").apply { addActionListener { pick() } })
            add(JButton("다른 폴더…").apply {
                addActionListener {
                    val ch = JFileChooser(Repo.root).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                    if (ch.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) { chosen = ch.selectedFile; dialog.dispose() }
                }
            })
            add(JButton("취소").apply { addActionListener { dialog.dispose() } })
        }
        dialog.contentPane = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JLabel("세션을 두 번 클릭하거나 고르고 [열기]. 찾는 곳: ${roots.joinToString { Repo.relative(it) }}"), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        dialog.setSize(640, 420)
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true // 모달: 닫힐 때까지 기다린다
        return chosen
    }
}
