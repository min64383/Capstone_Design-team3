package hearspace.viewer

import hearspace.core.truth.Substitution
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonValue
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
    private var replayGeneration = 0

    private val status = JLabel("세션을 여세요 (기본 폴더: testdata/sessions)")
    private val timeLabel = JLabel("0.00 / 0.00 s")
    private val playButton = JButton("▶ 재생")
    private val cueCheck = JCheckBox("등장·소실 효과음", java.lang.Boolean.getBoolean("hearspace.objectCues"))
    private val cueLabel = JLabel("효과음 꺼짐")
    private val exportCheck = JCheckBox("CSV + WAV 저장(재실행)")
    private val rerunButton = JButton("재실행")
    private val overrides = JTextArea("{}", 14, 30).apply { font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val variantName = JTextField("default", 12)
    private val variantBox = JComboBox<String>()
    private val applied = JTextArea("세션을 열면 적용된 옵션이 여기 나온다", 7, 30).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val ratings = RatingItem.entries.associateWith { JComboBox(arrayOf(1, 2, 3, 4, 5)).apply { selectedItem = 3 } }
    private val noteField = JTextField(18)
    private val noteList = DefaultListModel<String>()
    private val timer = Timer(30) { onTick() }

    // 나란히 보기(M30): 같은 세션을 정답 깊이 대입(D) 또는 저장된 변형으로 한 번 더 돌려 두 번째 결과로 보인다
    private val compareBox = JComboBox<String>()
    private val compareButton = JButton("나란히")
    private val viewsHolder = JPanel(BorderLayout())
    private val singleViews = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, CameraView(vm), TopView(vm)).apply { resizeWeight = 0.45 }
    private val pairViews = JPanel(GridLayout(2, 2)).apply {
        add(CameraView(vm)); add(TopView(vm)); add(CameraView(vm, compare = true)); add(TopView(vm, compare = true))
    }

    // 책갈피(M30 정성 평가 시점): 고르면 그 시각으로 가고, 문항별 점수·메모를 책갈피마다 모아 평가와 함께 저장한다
    private var marks: List<Bookmark> = emptyList()
    private var truthColors = false
    private val bookmarkBox = JComboBox<String>()
    private val bookmarkScores = linkedMapOf<Double, BookmarkScore>()
    private val bmItems = BookmarkItem.entries.associateWith { JComboBox(arrayOf("-", "1", "2", "3", "4", "5")) }
    private val bmMemo = JTextField(18)
    private val bmList = DefaultListModel<String>()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        val toolbar = JPanel().apply {
            add(JButton("세션 열기").apply { addActionListener { chooseSession() } })
            add(playButton.apply { addActionListener { togglePlay() } })
            add(JButton("⏮ 처음").apply { addActionListener { vm.result?.let { seek(it.t0Ns) } } })
            add(timeLabel)
            add(exportCheck)
            add(cueCheck.apply {
                toolTipText = "추적 ID 등장·소실을 알립니다. 소실은 안전하다는 뜻이 아닙니다. 변경하면 재실행합니다."
                addActionListener { if (session != null) rerun() }
            })
            add(JCheckBox("깊이 겹침", true).apply { addActionListener { vm.showDepth = isSelected; vm.fire() } })
            add(JCheckBox("지도 칸").apply {
                toolTipText = "지도 칸을 겹친다(M30). 정답이 장면 전체인 세션(E)은 칸 분류 색: 초록 맞음, 회색 속, 빨강 헛, 흰 테 놓친 표면"
                addActionListener { vm.showVoxels = isSelected; vm.fire() }
            })
            add(bookmarkBox.apply { toolTipText = "책갈피(viewer/bookmarks.json): 고르면 그 시각으로 간다"; addActionListener { onBookmark() } })
            add(JComboBox(VoxelHeight.entries.toTypedArray()).apply { addActionListener { vm.voxelHeight = selectedItem as VoxelHeight; vm.fire() } })
            add(JCheckBox("정답 높이: RGB 바닥").apply {
                toolTipText = "카메라 화면의 정답 상자를 RGB·포즈로 잰 바닥 위에 놓는다(세션의 floor_rgb.json, tools/analysis/floor_rgb.py). 없으면 깊이 바닥"
                addActionListener { vm.truthOnRgbFloor = isSelected; vm.fire() }
            })
        }
        viewsHolder.add(singleViews, BorderLayout.CENTER)
        val center = JSplitPane(JSplitPane.VERTICAL_SPLIT, viewsHolder, TimelineView(vm) { seek(it) }).apply { resizeWeight = 0.72 }
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
        refreshCompare()
        // 명령줄 값: 세션 ID(일부도 하나에만 맞으면), testdata/sessions 기준 상대경로, 저장소 기준 경로
        initialSession?.let { arg ->
            runCatching { Sessions.resolve(arg) }
                .onSuccess { open(it) }
                .onFailure { status.text = "${it.message} — [세션 열기]에서 고르세요" }
        }
    }

    private fun configPanel(): JComponent = JPanel(BorderLayout(4, 4)).apply {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        add(JLabel("<html>앱 설정(default.json + 앱 음향 덮어쓰기)에 덮어쓸 JSON<br>예: {\"map\": {\"voxelSizeM\": 0.075}}</html>"), BorderLayout.NORTH)
        add(JPanel(BorderLayout(4, 4)).apply {
            add(JScrollPane(overrides), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(JLabel("지금 보고 있는 결과에 적용된 옵션"), BorderLayout.NORTH)
                add(JScrollPane(applied), BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        add(Box.createVerticalBox().apply {
            add(row(cueLabel))
            add(row(rerunButton.apply { addActionListener { rerun() } }, JButton("기본값({})").apply { addActionListener { overrides.text = "{}" } }))
            add(row(JLabel("변형 이름"), variantName, JButton("저장").apply { addActionListener { saveVariant() } }))
            add(row(variantBox, JButton("불러오기").apply { addActionListener { loadVariant() } }))
            add(row(JLabel("비교"), compareBox, compareButton.apply { addActionListener { runCompare() } }, JButton("한 화면").apply { addActionListener { setCompare(null) } }))
        }, BorderLayout.SOUTH)
    }

    private fun feedbackPanel(): JComponent = JPanel(BorderLayout(4, 4)).apply {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        add(JPanel(GridLayout(0, 2, 4, 4)).apply {
            for ((item, box) in ratings) { add(JLabel(item.label)); add(box) }
        }, BorderLayout.NORTH)
        add(JPanel(GridLayout(2, 1, 4, 4)).apply {
            add(JScrollPane(JList(noteList)))
            add(JPanel(BorderLayout(4, 4)).apply {
                add(JPanel(GridLayout(0, 2, 4, 2)).apply {
                    add(JLabel("책갈피 평가")); add(JLabel("(위 목록에서 고른 책갈피)"))
                    for ((item, box) in bmItems) { add(JLabel(item.label)); add(box) }
                    add(JLabel("메모")); add(bmMemo)
                }, BorderLayout.NORTH)
                add(JScrollPane(JList(bmList)), BorderLayout.CENTER)
                add(row(JButton("책갈피 점수 넣기").apply { addActionListener { addBookmarkScore() } }), BorderLayout.SOUTH)
            })
        }, BorderLayout.CENTER)
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
        setCompare(null)
        val (m, colors) = Bookmarks.of(dir.name)
        marks = m
        truthColors = colors
        bookmarkScores.clear()
        bmList.clear()
        bookmarkBox.removeAllItems()
        bookmarkBox.addItem(if (m.isEmpty()) "책갈피 없음" else "책갈피 ${m.size}개")
        m.forEach { bookmarkBox.addItem("%.2f s  %s".format(it.tS, it.label)) }
        // 칸 분류 색이 없는 장면(정답이 물체뿐)은 지도 모양 문항만 매긴다
        bmItems.forEach { (item, box) -> box.isEnabled = truthColors || !item.needsTruthColors }
        rerun()
    }

    private fun onBookmark() {
        val i = bookmarkBox.selectedIndex - 1
        val r = vm.result ?: return
        if (i !in marks.indices) return
        seek(r.t0Ns + (marks[i].tS * 1e9).toLong())
    }

    private fun addBookmarkScore() {
        val i = bookmarkBox.selectedIndex - 1
        if (i !in marks.indices) { status.text = "책갈피를 먼저 고르세요"; return }
        val m = marks[i]
        val scores = bmItems.filter { it.value.isEnabled }.mapNotNull { (item, box) -> (box.selectedItem as String).toIntOrNull()?.let { item to it } }.toMap()
        if (scores.isEmpty()) { status.text = "점수를 하나 이상 고르세요"; return }
        bookmarkScores[m.tS] = BookmarkScore(m.tS, m.label, scores, bmMemo.text.trim())
        bmList.clear()
        bookmarkScores.values.forEach { b -> bmList.addElement("%.2f s %s  %s  %s".format(b.tS, b.label, b.scores.entries.joinToString(" ") { "${it.key.name} ${it.value}" }, b.memo)) }
        bmMemo.text = ""
        bmItems.values.forEach { it.selectedIndex = 0 }
    }

    /** 나란히 보기 목록: 정답 깊이 대입(D)과 저장된 변형. */
    private fun refreshCompare() {
        compareBox.removeAllItems()
        compareBox.addItem(COMPARE_TRUTH_DEPTH)
        Repo.variants.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { compareBox.addItem(it.nameWithoutExtension) }
    }

    private fun setCompare(r: ReplayResult?) {
        vm.compare = r
        viewsHolder.removeAll()
        viewsHolder.add(if (r == null) singleViews else pairViews, BorderLayout.CENTER)
        viewsHolder.revalidate()
        viewsHolder.repaint()
        vm.fire()
    }

    /** 같은 세션을 고른 비교 대상으로 돌린다(백그라운드). 정답 깊이 대입은 지금 결과의 정렬을 그대로 쓴다. */
    private fun runCompare() {
        val dir = session ?: return
        val base = vm.result ?: return
        val name = compareBox.selectedItem as String? ?: return
        val sub = if (name == COMPARE_TRUTH_DEPTH) {
            val truth = base.truth ?: run { status.text = "정답이 없어 정답 깊이 대입을 할 수 없음"; return }
            val al = base.alignment ?: run { status.text = "정렬이 없어 정답 깊이 대입을 할 수 없음"; return }
            Substitution(truth, al, depth = true)
        } else {
            null
        }
        val text = if (sub != null) base.overridesJson else File(Repo.variants, "$name.json").readText().trim().ifEmpty { "{}" }
        compareButton.isEnabled = false
        status.text = "비교 재실행 중… $name"
        object : SwingWorker<ReplayResult, Unit>() {
            override fun doInBackground() = ReplayRunner.run(dir, text, hrtf, substitution = sub, label = "B: $name", objectCueSettings = base.objectCueSettings)
            override fun done() {
                compareButton.isEnabled = true
                val r = runCatching { get() }.getOrElse { e -> status.text = "비교 실패: ${(e.cause ?: e).message}"; return }
                setCompare(r)
                status.text = "나란히: 위 A(지금 결과), 아래 B($name). 소리는 A"
            }
        }.execute()
    }

    /** 현재 설정 덮어쓰기로 다시 돌린다(백그라운드). 보던 시각은 유지한다. */
    private fun rerun() {
        val dir = session ?: return
        val text = overrides.text.trim().ifEmpty { "{}" }
        val parseError = runCatching { MiniJson.parse(text) }.exceptionOrNull()
        if (parseError != null) { status.text = "설정 JSON 오류: ${parseError.message}"; return }
        val cueSettings = runCatching { if (cueCheck.isSelected) ObjectCueOptions.load() else null }
            .getOrElse { status.text = "효과음 설정 오류: ${it.message}"; return }
        val renderedLabel = "A: ${variantName.text.trim()}"
        val generation = ++replayGeneration
        val exportDir = if (exportCheck.isSelected) SonificationExport.newDirectory(dir) else null
        player.pause()
        timer.stop()
        rerunButton.isEnabled = false
        cueCheck.isEnabled = false
        playButton.isEnabled = false
        status.text = "재실행 중… ${Repo.relative(dir)}"
        object : SwingWorker<ReplayResult, Unit>() {
            override fun doInBackground() = ReplayRunner.run(dir, text, hrtf, exportDir, label = renderedLabel, objectCueSettings = cueSettings)
            override fun done() {
                if (generation != replayGeneration) return
                rerunButton.isEnabled = true
                cueCheck.isEnabled = true
                playButton.isEnabled = true
                val r = runCatching { get() }.getOrElse { e ->
                    cueCheck.isSelected = vm.result?.objectCueSettings != null
                    status.text = "실패: ${(e.cause ?: e).message}"
                    return
                }
                player.load(r.audio, r.sampleRate)
                vm.setResult(r)
                player.seek(r.frameOfTime(vm.tNs))
                playButton.text = "▶ 재생"
                status.text = summary(r) + (exportDir?.let { " · 저장: ${it.absolutePath}" } ?: "")
                applied.text = appliedOptionsText(r.overridesJson, listOf(Repo.appOverride, Repo.defaultConfig).map { MiniJson.parse(it.readText()) })
                applied.append("\n등장·소실 효과음: " + (r.objectCueSettings?.let { "켜짐 (등장 ${it.appearHoldMs} ms / 소실 ${it.lostHoldMs} ms)" } ?: "꺼짐"))
                applied.caretPosition = 0
            }
        }.execute()
    }

    private fun summary(r: ReplayResult): String {
        fun med(f: (hearspace.core.pipeline.StageTimes) -> Long) = r.slow.map { f(it.stageNs) }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] / 1e6 }
        val truth = r.truth?.let { "정답 ${it.obstacles.size}개${if (it.estimated) "(추정)" else ""}" } ?: "정답 없음"
        return "재실행 %.2f s · %s · 장면 %s · %.1f s · 블록 %d · 느린 경로 %d회(PC 중앙값 맵 %.1f · 군집 %.1f · 추적 %.2f ms) · %s · 설정 %s"
            .format(r.elapsedMs / 1000.0, Repo.relative(r.session), r.scene, r.durationS, r.blocks.size, r.slow.size,
                med { it.mapNs }, med { it.clusterNs }, med { it.trackNs }, truth, Feedback.configHash(r.overridesJson))
    }

    private fun togglePlay() {
        if (!playButton.isEnabled) return
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
        val event = r.objectCues.lastOrNull { it.tNs <= vm.tNs }
        cueLabel.text = if (r.objectCueSettings == null) "효과음 꺼짐" else if (event == null) "효과음 켜짐 · 이벤트 대기" else
            "%.2f s · ID %d %s · %s".format(r.secondsOf(event.tNs), event.obstacleId,
                if (event.kind == hearspace.core.audio.ObjectCueKind.APPEAR) "등장" else "소실",
                if (event.disposition == "PLAYED") "재생" else "생략: ${event.disposition.removePrefix("SUPPRESSED_")}")
    }

    private fun saveVariant() {
        // 파일 이름 = 목록에 보이는 이름: 한글·공백·+·괄호 허용, 파일 이름에 못 쓰는 글자만 바꾼다
        val typed = variantName.text.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (typed.isEmpty()) return
        // 앞 번호(두 자리 + 공백)가 없으면 다음 번호를 붙인다: 목록이 이름순이라 번호가 만든 순서, 큰 번호가 최신
        val name = if (NUMBERED.containsMatchIn(typed)) typed else "%02d %s".format(nextVariantNumber(), typed)
        val text = overrides.text.trim().ifEmpty { "{}" }
        runCatching { MiniJson.parse(text) }.onFailure { status.text = "설정 JSON 오류: ${it.message}"; return }
        Repo.variants.mkdirs()
        File(Repo.variants, "$name.json").writeText(text + "\n")
        refreshVariants()
        status.text = "변형 저장: data/viewer/variants/$name.json"
    }

    private fun nextVariantNumber(): Int =
        (Repo.variants.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { NUMBERED.find(it.name)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0) + 1

    private fun loadVariant() {
        val name = variantBox.selectedItem as String? ?: return
        overrides.text = File(Repo.variants, "$name.json").readText().trim()
        variantName.text = name
        status.text = "변형 불러옴: $name — [재실행]해야 반영된다"
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
            notes = vm.notes.toList() + Note(r.secondsOf(vm.tNs), "objectCues=${r.objectCueSettings ?: "OFF"}"),
            createdAt = Feedback.now(),
            compare = vm.compare?.label.orEmpty(),
            bookmarks = bookmarkScores.values.toList(),
        )
        val f = fb.save()
        status.text = "평가 저장: ${f.relativeTo(Repo.root).path}"
    }
}

/**
 * [overridesJson]의 항목을 한 줄씩, 기본값 [bases](앞의 것이 우선: 앱 음향 덮어쓰기, default.json)와 비교해 적는다. 변형은 누적되지 않는다:
 * 불러오기는 덮어쓰기 글을 통째로 바꾸고, 재실행은 그 글만 기본값 위에 얹는다.
 */
internal fun appliedOptionsText(overridesJson: String, bases: List<JsonValue>): String {
    fun lookup(root: JsonValue, path: List<String>): JsonValue? = path.fold<String, JsonValue?>(root) { v, k -> (v as? JsonObject)?.fields?.get(k) }
    val lines = ArrayList<String>()
    fun walk(v: JsonValue, path: List<String>) {
        if (v is JsonObject) { v.fields.forEach { (k, c) -> walk(c, path + k) }; return }
        val now = MiniJson.write(v, pretty = false)
        val was = bases.firstNotNullOfOrNull { lookup(it, path) }?.let { MiniJson.write(it, pretty = false) }
        lines += "• ${path.joinToString(".")} = $now  " + when (was) {
            null -> "(기본값에 없는 키)"
            now -> "(기본값과 같음, 효과 없음)"
            else -> "(기본값 $was)"
        }
    }
    walk(MiniJson.parse(overridesJson), emptyList())
    return "기본값 위에 아래 항목만 바꿨다(변형끼리 누적되지 않음).\n" + (if (lines.isEmpty()) "• 없음: 기본값 그대로" else lines.joinToString("\n"))
}

/** 나란히 보기 목록의 정답 깊이 대입 항목 이름. */
private const val COMPARE_TRUTH_DEPTH = "정답 깊이 대입(D)"

/** 변형 파일 이름의 앞 번호(만든 순서). */
private val NUMBERED = Regex("""^(\d+) """)
