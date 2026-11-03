package hearspace.viewer

import hearspace.core.session.SessionReader
import hearspace.core.truth.Click
import hearspace.core.truth.ClickAnnotations
import hearspace.core.truth.ClickObject
import hearspace.core.truth.ClickPlane
import hearspace.core.truth.ClickTruth
import java.awt.BorderLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel

/**
 * 클릭 정답 주석(M47, IMPROVE_SPEC §9.5). 물체를 만들어 고르고, 클릭 모드에서 카메라 화면을 누르면 그 RGB 장의 자세로 세계 점을 계산해
 * 더한다(계산은 core `ClickTruth`). 바꿀 때마다 세션의 `annotations/events.json`에 쓴다. 바닥면은 `floor_rgb.json` → 파일 값 → 재생의 깊이 바닥 순.
 */
class ClickPanel(
    private val vm: ViewerModel,
    private val seek: (Long) -> Unit,
    private val status: (String) -> Unit,
) : JPanel(BorderLayout(4, 4)) {
    private var dir: File? = null
    private val list = DefaultListModel<String>()
    private val jlist = JList(list).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val mode = JCheckBox("클릭 모드(카메라 화면을 누르면 고른 물체에 더함)")
    private val plane = JComboBox(arrayOf("발(바닥면)", "허리(높이는 발 짝으로 맞춤)"))
    private val label = JTextField("person", 8)
    private val heightBox = JTextField("1.00", 4)
    private val radius = JTextField("0.20", 4)
    private val added = JComboBox(arrayOf("blind", "review", "check"))  // check = 클릭 검증 전용(같은 점을 여러 거리에서), 채점에서 뺌
    private val situation = JComboBox(arrayOf("-", "S1", "S2", "S3"))
    private val action = JComboBox(arrayOf("-", "STOP", "LEFT", "RIGHT", "PASS"))
    private val fallback = JTextField("", 6)

    init {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        add(JPanel(GridLayout(0, 2, 4, 2)).apply {
            add(JLabel("이름")); add(label)
            add(JLabel("허리 높이 m(어림, 맞추면 바뀜)")); add(heightBox)
            add(JLabel("반지름 m(움직임)")); add(radius)
            add(JLabel("더한 단계")); add(added)
            add(JLabel("상황")); add(situation)
            add(JLabel("정답 행동")); add(action)
            add(JButton("새 사람(움직임)").apply { addActionListener { newObject("moving", "point") } })
            add(JButton("새 고정물(점)").apply { addActionListener { newObject("static", "point") } })
            add(JButton("새 고정물(선)").apply { addActionListener { newObject("static", "line") } })
            add(JButton("고른 물체에 상황 적용").apply { addActionListener { applySituation() } })
            add(JLabel("바닥면 y(floor_rgb 없을 때)")); add(fallback)
        }, BorderLayout.NORTH)
        add(JScrollPane(jlist), BorderLayout.CENTER)
        jlist.addListSelectionListener { vm.clickSelected = vm.clicks.objects.getOrNull(jlist.selectedIndex)?.id; vm.fire() }
        add(JPanel(GridLayout(0, 2, 4, 2)).apply {
            add(mode.apply { addActionListener { vm.clickMode = isSelected } }); add(plane)
            add(JButton("◀ RGB 장 (←)").apply { addActionListener { step(-1) } })
            add(JButton("RGB 장 ▶ (→)").apply { addActionListener { step(1) } })
            add(JButton("마지막 클릭 지우기").apply { addActionListener { undo() } })
            add(JButton("물체 지우기").apply { addActionListener { removeObject() } })
            add(JButton("허리 높이 맞추기(같은 장 발·허리 짝)").apply { addActionListener { fitHead() } })
            add(JLabel("바꿀 때마다 annotations/events.json에 저장"))
        }, BorderLayout.SOUTH)
    }

    /** 세션을 열 때 그 세션의 클릭 정답을 읽는다. */
    fun load(session: File) {
        dir = session
        val f = File(session, ClickTruth.FILE)
        vm.clicks = if (f.isFile) runCatching { ClickTruth.load(f) }.getOrElse { status("클릭 정답 읽기 실패: ${it.message}"); ClickAnnotations(emptyList()) }
        else ClickAnnotations(emptyList())
        fallback.text = vm.clicks.floorYFallback?.toString() ?: ""
        vm.clickSelected = null
        refresh()
    }

    /** 이전·다음 RGB 장으로. */
    fun step(n: Int) {
        val r = vm.result ?: return
        r.stepRgb(vm.tNs, n)?.let(seek)
    }

    /** 카메라 화면 클릭(센서 좌표). */
    fun onSensorClick(u: Float, v: Float) {
        val r = vm.result ?: return
        val i = vm.clicks.objects.indexOfFirst { it.id == vm.clickSelected }
        if (i < 0) return status("먼저 물체를 만들거나 고르세요")
        val row = r.rgbRowAt(vm.tNs) ?: return status("이 시각에 RGB 장이 없습니다")
        val o = vm.clicks.objects[i]
        val p = if (plane.selectedIndex == 1) ClickPlane.HEAD else ClickPlane.FEET
        if (p == ClickPlane.HEAD && o.heightM <= 0f) return status("허리 클릭에는 어림 허리 높이가 필요합니다(맞추면 바뀜)")
        val k0 = r.reader.meta.camera.imageIntrinsics
        // 발이 화면 끝에 걸리면 끝 화소를 누르게 돼 실제보다 멀게 나온다(2026-11-02 첫 주석: 1.9 m로 고정): 받지 않는다
        if (p == ClickPlane.FEET && (u < EDGE_PX || v < EDGE_PX || u > k0.width - EDGE_PX || v > k0.height - EDGE_PX))
            return status("발이 화면 끝(${EDGE_PX.toInt()} px 안)에 걸렸습니다. 발이 다 보이지 않으면 허리를 누르세요")
        val fileFloor = fallback.text.trim().toFloatOrNull()
        val (floorY, from) = r.rgbFloorAt(row.tNs)?.let { it.toFloat() to "rgb" }
            ?: fileFloor?.let { it to "file" }
            ?: r.slowAt(vm.tNs)?.snapshot?.floorY?.let { it to "depth" }
            ?: return status("바닥면이 없습니다: floor_rgb.py를 돌리거나 바닥면 y를 적으세요")
        val k = r.reader.meta.camera.imageIntrinsics
        val w = ClickTruth.planePoint(SessionReader.toPoseFrame(row).worldFromCam, k, u, v, ClickTruth.planeY(p, floorY, o.heightM))
        val c = Click(row.tNs, u, v, p, floorY, from, w)
        // 움직이는 물체는 한 장에 면마다 클릭 하나(발·머리 끝을 같은 장에 누르면 검증 짝): 같은 장·면을 다시 누르면 바꾼다
        val clicks = if (o.kind == "moving") o.clicks.filter { it.tNs != row.tNs || it.plane != p } + c else o.clicks + c
        replace(i, o.copy(clicks = if (o.kind == "moving") clicks.sortedBy { it.tNs } else clicks))
        val cam = SessionReader.toPoseFrame(row).worldFromCam.translation()
        status(if (w == null) "광선이 면과 카메라 앞에서 만나지 않습니다(바닥 위쪽을 눌렀나요?)"
        else "%s 클릭 %d: 수평 거리 %.2f m, 바닥면 %s".format(o.id, clicks.size, (w - cam).horizontal().norm(), from))
    }

    /**
     * 고른 사람의 같은 장 발·허리 짝으로 허리 면 높이를 맞추고(core `ClickTruth.fitHeadPlaneY`) 모든 허리 클릭을 다시 계산한다.
     * 허리 높이는 몰라도 발(바닥면) 기준으로 맞춰진다. 높이 칸에는 맞춘 '바닥 위 허리 면 높이'가 들어간다.
     */
    private fun fitHead() {
        val r = vm.result ?: return
        val i = jlist.selectedIndex.takeIf { it >= 0 } ?: return status("사람을 고르세요")
        val o = vm.clicks.objects[i]
        val rows = r.reader.rows.associateBy { it.tNs }
        val k = r.reader.meta.camera.imageIntrinsics
        val byT = o.clicks.groupBy { it.tNs }
        val pairs = byT.values.mapNotNull { cs ->
            val feet = cs.firstOrNull { it.plane == ClickPlane.FEET }?.pointW ?: return@mapNotNull null
            val head = cs.firstOrNull { it.plane == ClickPlane.HEAD } ?: return@mapNotNull null
            val row = rows[head.tNs] ?: return@mapNotNull null
            Triple(ClickTruth.HeadPair(feet, SessionReader.toPoseFrame(row).worldFromCam, head.u, head.v), head.floorY, feet)
        }
        if (pairs.size < 2) return status("같은 장에 발·허리를 둘 다 누른 짝이 2개 이상 필요합니다(지금 ${pairs.size}개)")
        val y = ClickTruth.fitHeadPlaneY(pairs.map { it.first }, k) ?: return status("맞출 수 없습니다")
        val h = y - pairs.map { it.second }.average().toFloat()
        val clicks = o.clicks.map { c ->
            if (c.plane != ClickPlane.HEAD) c else rows[c.tNs]?.let { row ->
                c.copy(pointW = ClickTruth.planePoint(SessionReader.toPoseFrame(row).worldFromCam, k, c.u, c.v, ClickTruth.planeY(ClickPlane.HEAD, c.floorY, h)))
            } ?: c
        }
        val fitted = o.copy(heightM = h, clicks = clicks)
        replace(i, fitted)
        // 맞춘 뒤 짝의 수평 차(같은 짝으로 맞춰 조금 낙관적)
        val diffs = fitted.clicks.groupBy { it.tNs }.values.mapNotNull { cs ->
            val f = cs.firstOrNull { it.plane == ClickPlane.FEET }?.pointW
            val hd = cs.firstOrNull { it.plane == ClickPlane.HEAD }?.pointW
            if (f == null || hd == null) null else (f - hd).horizontal().norm()
        }.sorted()
        heightBox.text = "%.3f".format(h)
        status("%s 허리 면 높이 %.3f m(바닥 위, 어림 %.2f), 짝 %d개, 맞춘 뒤 차 최대 %.2f m".format(o.id, h, o.heightM, pairs.size, diffs.lastOrNull() ?: 0f))
    }

    companion object {
        /** 발 클릭을 받지 않는 화면 가장자리 폭(px). 발이 잘리면 가장자리를 누르게 된다. */
        const val EDGE_PX = 20f
    }

    private fun newObject(kind: String, shape: String) {
        val prefix = if (kind == "moving") "p" else "o"
        val n = (vm.clicks.objects.filter { it.id.startsWith(prefix) }.mapNotNull { it.id.drop(prefix.length).toIntOrNull() }.maxOrNull() ?: 0) + 1
        val o = ClickObject(
            "$prefix$n", kind, shape, label.text.trim(),
            if (kind == "moving") radius.text.toFloatOrNull() ?: 0.2f else 0f,
            if (kind == "moving") heightBox.text.toFloatOrNull() ?: 0f else 0f,
            added.selectedItem as String, null, null, emptyList(),
        )
        vm.clicks = vm.clicks.copy(objects = vm.clicks.objects + o)
        autosave()
        refresh()
        jlist.selectedIndex = vm.clicks.objects.size - 1
    }

    private fun applySituation() {
        val i = jlist.selectedIndex.takeIf { it >= 0 } ?: return
        fun pick(b: JComboBox<String>) = (b.selectedItem as String).takeIf { it != "-" }
        replace(i, vm.clicks.objects[i].copy(situation = pick(situation), action = pick(action)))
    }

    private fun undo() {
        val i = jlist.selectedIndex.takeIf { it >= 0 } ?: return
        val o = vm.clicks.objects[i]
        if (o.clicks.isEmpty()) return
        // 움직이는 물체는 이 장의 클릭이 있으면 그것을, 없으면 마지막 것을 지운다
        val here = vm.result?.rgbRowAt(vm.tNs)?.tNs
        val drop = o.clicks.indexOfLast { it.tNs == here }.takeIf { it >= 0 } ?: (o.clicks.size - 1)
        replace(i, o.copy(clicks = o.clicks.filterIndexed { j, _ -> j != drop }))
    }

    private fun removeObject() {
        val i = jlist.selectedIndex.takeIf { it >= 0 } ?: return
        vm.clicks = vm.clicks.copy(objects = vm.clicks.objects.filterIndexed { j, _ -> j != i })
        autosave()
        refresh()
    }

    private fun replace(i: Int, o: ClickObject) {
        vm.clicks = vm.clicks.copy(objects = vm.clicks.objects.mapIndexed { j, x -> if (j == i) o else x })
        autosave()
        refresh()
        jlist.selectedIndex = i
    }

    /** 바꿀 때마다 저장한다(창을 닫아도 클릭을 잃지 않게). 실패만 알린다. */
    private fun autosave() {
        val d = dir ?: return
        val a = vm.clicks.copy(floorYFallback = fallback.text.trim().toFloatOrNull())
        runCatching { ClickTruth.save(File(d, ClickTruth.FILE), a) }
            .onSuccess { vm.clicks = a }
            .onFailure { status("클릭 정답 저장 실패: ${it.message}") }
    }

    private fun refresh() {
        val keep = jlist.selectedIndex
        list.clear()
        vm.clicks.objects.forEach { o ->
            list.addElement("${o.id} ${o.kind}/${o.shape} ${o.label} · 클릭 ${o.clicks.size}" +
                (o.situation?.let { " · $it ${o.action ?: ""}" } ?: "") + " · ${o.added}")
        }
        if (keep in 0 until list.size()) jlist.selectedIndex = keep
        vm.fire()
    }
}
