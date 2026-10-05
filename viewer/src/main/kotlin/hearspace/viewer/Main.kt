package hearspace.viewer

import hearspace.core.types.MiniJson
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer

/** `./gradlew :viewer:run [-Psession=<세션 ID 또는 저장소 기준 경로>]` (IMPROVE_SPEC §10). */
fun main(args: Array<String>) {
    SwingUtilities.invokeLater { MainWindow(args.firstOrNull()).isVisible = true }
}

/**
 * 평가 GUI 창. 세션을 열면 오프라인 재생 + 바이노럴 렌더를 백그라운드에서 돌리고, 끝나면 영상·위에서 본 그림·타임라인과
 * 소리를 같은 시각으로 맞춰 보여 준다. 설정 덮어쓰기를 바꿔 다시 돌리고, 평가(평점·시점 메모)를 저장한다.
 * core 실행은 Swing 이벤트 스레드 밖에서만 한다(IMPROVE_SPEC §15-1).
 */
class MainWindow(initialSession: String?) : JFrame("HEARSPACE 평가 GUI") {
    private val vm = ViewerModel()
    private val player = AudioPlayer()
    private val hrtf = ReplayRunner.loadHrtf()
    private var session: File? = null

    private val status = JLabel("세션을 여세요 (기본 폴더: testdata/sessions)")
    private val timeLabel = JLabel("0.00 / 0.00 s")
    private val playButton = JButton("▶ 재생")
    private val rerunButton = JButton("재실행")
    private val overrides = JTextArea("{}", 14, 30).apply { font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val variantName = JTextField("default", 12)
    private val variantBox = JComboBox<String>()
    private val ratings = RatingItem.entries.associateWith { JComboBox(arrayOf(1, 2, 3, 4, 5)).apply { selectedItem = 3 } }
    private val noteField = JTextField(18)
    private val noteList = DefaultListModel<String>()
    private val timer = Timer(30) { onTick() }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        val toolbar = JPanel().apply {
            add(JButton("세션 열기").apply { addActionListener { chooseSession() } })
            add(playButton.apply { addActionListener { togglePlay() } })
            add(JButton("⏮ 처음").apply { addActionListener { vm.result?.let { seek(it.t0Ns) } } })
            add(timeLabel)
            add(JCheckBox("깊이 겹침", true).apply { addActionListener { vm.showDepth = isSelected; vm.fire() } })
        }
        val views = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, CameraView(vm), TopView(vm)).apply { resizeWeight = 0.45 }
        val center = JSplitPane(JSplitPane.VERTICAL_SPLIT, views, TimelineView(vm) { seek(it) }).apply { resizeWeight = 0.72 }
        val tabs = JTabbedPane().apply {
            addTab("설정", configPanel())
            addTab("평가", feedbackPanel())
        }
        layout = BorderLayout()
        add(toolbar, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(tabs, BorderLayout.EAST)
        add(status.apply { border = BorderFactory.createEmptyBorder(4, 8, 4, 8) }, BorderLayout.SOUTH)
        rootPane.registerKeyboardAction({ togglePlay() }, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        vm.onChange { updateTimeLabel() }
        // 화면보다 크게 뜨면 오른쪽 탭이 화면 밖으로 나간다: 작업 영역에 맞춘다
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(minOf(1500, screen.width), minOf(920, screen.height))
        setLocationRelativeTo(null)
        refreshVariants()
        // 명령줄 값: 세션 ID(일부도 하나에만 맞으면), testdata/sessions 기준 상대경로, 저장소 기준 경로
        initialSession?.let { arg ->
            runCatching { Sessions.resolve(arg) }
                .onSuccess { open(it) }
                .onFailure { status.text = "${it.message} — [세션 열기]에서 고르세요" }
        }
    }

    private fun configPanel(): JComponent = JPanel(BorderLayout(4, 4)).apply {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        add(JLabel("<html>기본 설정(default.json)에 덮어쓸 JSON<br>예: {\"map\": {\"voxelSizeM\": 0.075}}</html>"), BorderLayout.NORTH)
        add(JScrollPane(overrides), BorderLayout.CENTER)
        add(Box.createVerticalBox().apply {
            add(row(rerunButton.apply { addActionListener { rerun() } }, JButton("기본값({})").apply { addActionListener { overrides.text = "{}" } }))
            add(row(JLabel("변형 이름"), variantName, JButton("저장").apply { addActionListener { saveVariant() } }))
            add(row(variantBox, JButton("불러오기").apply { addActionListener { loadVariant() } }))
        }, BorderLayout.SOUTH)
    }

    private fun feedbackPanel(): JComponent = JPanel(BorderLayout(4, 4)).apply {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        add(JPanel(GridLayout(0, 2, 4, 4)).apply {
            for ((item, box) in ratings) { add(JLabel(item.label)); add(box) }
        }, BorderLayout.NORTH)
        add(JScrollPane(JList(noteList)), BorderLayout.CENTER)
        add(Box.createVerticalBox().apply {
            add(row(noteField, JButton("메모 추가(현재 시각)").apply { addActionListener { addNote() } }))
            add(row(JButton("평가 저장").apply { addActionListener { saveFeedback() } }))
        }, BorderLayout.SOUTH)
    }

    private fun row(vararg c: JComponent) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        c.forEach { add(it); add(Box.createHorizontalStrut(4)) }
    }

    private fun chooseSession() {
        Sessions.choose(this, session)?.let { open(it) }
    }

    private fun open(dir: File) {
        session = dir
        title = "HEARSPACE 평가 GUI — ${Repo.relative(dir)}"
        rerun()
    }

    /** 현재 설정 덮어쓰기로 다시 돌린다(백그라운드). 보던 시각은 유지한다. */
    private fun rerun() {
        val dir = session ?: return
        val text = overrides.text.trim().ifEmpty { "{}" }
        val parseError = runCatching { MiniJson.parse(text) }.exceptionOrNull()
        if (parseError != null) { status.text = "설정 JSON 오류: ${parseError.message}"; return }
        player.pause()
        timer.stop()
        rerunButton.isEnabled = false
        status.text = "재실행 중… ${Repo.relative(dir)}"
        object : SwingWorker<ReplayResult, Unit>() {
            override fun doInBackground() = ReplayRunner.run(dir, text, hrtf)
            override fun done() {
                rerunButton.isEnabled = true
                val r = runCatching { get() }.getOrElse { e ->
                    status.text = "실패: ${(e.cause ?: e).message}"
                    return
                }
                player.load(r.audio, r.sampleRate)
                vm.setResult(r)
                player.seek(r.frameOfTime(vm.tNs))
                playButton.text = "▶ 재생"
                status.text = summary(r)
            }
        }.execute()
    }

    private fun summary(r: ReplayResult): String {
        fun med(f: (hearspace.core.pipeline.StageTimes) -> Long) = r.slow.map { f(it.stageNs) }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] / 1e6 }
        val truth = r.truth?.let { "정답 ${it.obstacles.size}개${if (it.estimated) "(추정)" else ""}" } ?: "정답 없음"
        val map = r.mapEvalSummary?.let { s -> " · 헛 복셀 평균 %.1f".format(s.phantomMean) + (s.objectSeenFraction?.let { " · 물체 보임 %.0f%%".format(it * 100) } ?: "") } ?: ""
        return "재실행 %.2f s · %s · 장면 %s · %.1f s · 블록 %d · 느린 경로 %d회(PC 중앙값 맵 %.1f · 군집 %.1f · 추적 %.2f ms) · %s%s · 설정 %s"
            .format(r.elapsedMs / 1000.0, Repo.relative(r.session), r.scene, r.durationS, r.blocks.size, r.slow.size,
                med { it.mapNs }, med { it.clusterNs }, med { it.trackNs }, truth, map, Feedback.configHash(r.overridesJson))
    }

    private fun togglePlay() {
        val r = vm.result ?: return
        if (player.isPlaying) {
            player.pause()
            timer.stop()
            playButton.text = "▶ 재생"
            vm.setTime(r.timeOfFrame(player.positionFrame()))
        } else {
            player.play()
            timer.start()
            playButton.text = "❚❚ 멈춤"
        }
    }

    private fun onTick() {
        val r = vm.result ?: return
        vm.setTime(r.timeOfFrame(player.positionFrame()))
        if (!player.isPlaying) {
            timer.stop()
            playButton.text = "▶ 재생"
        }
    }

    private fun seek(tNs: Long) {
        val r = vm.result ?: return
        player.seek(r.frameOfTime(tNs))
        vm.setTime(tNs.coerceIn(r.t0Ns, r.timeOfFrame(player.totalFrames)))
    }

    private fun updateTimeLabel() {
        val r = vm.result ?: return
        timeLabel.text = "%.2f / %.2f s".format(r.secondsOf(vm.tNs), r.durationS)
    }

    private fun saveVariant() {
        val name = variantName.text.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_")
        if (name.isEmpty()) return
        val text = overrides.text.trim().ifEmpty { "{}" }
        runCatching { MiniJson.parse(text) }.onFailure { status.text = "설정 JSON 오류: ${it.message}"; return }
        Repo.variants.mkdirs()
        File(Repo.variants, "$name.json").writeText(text + "\n")
        refreshVariants()
        status.text = "변형 저장: data/viewer/variants/$name.json"
    }

    private fun loadVariant() {
        val name = variantBox.selectedItem as String? ?: return
        overrides.text = File(Repo.variants, "$name.json").readText().trim()
        variantName.text = name
    }

    private fun refreshVariants() {
        variantBox.removeAllItems()
        Repo.variants.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { variantBox.addItem(it.nameWithoutExtension) }
    }

    private fun addNote() {
        val r = vm.result ?: return
        val text = noteField.text.trim()
        if (text.isEmpty()) return
        val n = Note(r.secondsOf(vm.tNs), text)
        vm.notes += n
        noteList.addElement("%.2f s  %s".format(n.tS, n.text))
        noteField.text = ""
        vm.fire()
    }

    private fun saveFeedback() {
        val r = vm.result ?: return
        val fb = Feedback(
            sessionId = r.sessionId,
            sessionPath = Repo.relative(r.session),
            scene = r.scene,
            variant = variantName.text.trim(),
            overridesJson = r.overridesJson, // 실제로 들은 결과의 설정(편집 중인 글이 아님)
            ratings = ratings.mapValues { it.value.selectedItem as Int },
            notes = vm.notes.toList(),
            createdAt = Feedback.now(),
        )
        val f = fb.save()
        status.text = "평가 저장: ${f.relativeTo(Repo.root).path}"
    }
}
