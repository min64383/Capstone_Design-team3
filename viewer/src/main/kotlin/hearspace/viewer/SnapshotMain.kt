package hearspace.viewer

import hearspace.core.truth.Substitution
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel

/**
 * 화면 없는 그림 저장(M30): 책갈피마다 사용자가 viewer에서 보는 것과 같은 패널(카메라 화면 + 위에서 본 그림, 지도 칸 겹침 켬·깊이 겹침 끔)을 PNG로.
 * 정답이 장면 전체(E 세션)면 아래 줄에 정답 깊이 대입(D)을 나란히 그린다(viewer [나란히]와 같음).
 *
 *     ./gradlew :viewer:snapshot [-Psession=<ID 일부>] [-Pvariant=<변형 이름>] [-Pcompare=<변형 이름>] [-Pout=<폴더>]
 *
 * `compare`를 주면 아래 줄에 D 대신 그 변형을 같은 정렬(위 줄의 정렬, 재생 `alignFrom`과 같음)로 그린다(M32: B 대 R3).
 * `alignDir`를 주면 두 줄 모두 `<alignDir>/<세션ID>/align.json`의 정렬을 쓴다(M32: 파이프라인 바닥과 독립된 정렬, tools/analysis/floor_ref.py).
 *
 * 세션을 주지 않으면 `viewer/bookmarks.json`의 모든 세션. 출력은 `<out>/<세션ID>_<번호>.png`(기본 build/snapshots).
 */
fun main(args: Array<String>) {
    val opt = args.associate { a -> a.substringBefore('=') to a.substringAfter('=', "") }
    val out = File(opt["out"]?.takeIf { it.isNotBlank() } ?: File(Repo.root, "viewer/build/snapshots").path).apply { mkdirs() }
    val variant = opt["variant"]?.takeIf { it.isNotBlank() }
    val overrides = variant?.let { File(Repo.variants, "$it.json").readText().trim() } ?: "{}"
    val hrtf = ReplayRunner.loadHrtf()
    val ids = opt["session"]?.takeIf { it.isNotBlank() }?.let { listOf(Sessions.resolve(it).name) } ?: Bookmarks.sessionIds()
    for (id in ids) {
        val dir = Sessions.resolve(id)
        val (marks, _) = Bookmarks.of(dir.name)
        if (marks.isEmpty()) { println("$id: 책갈피 없음"); continue }
        val alignFile = opt["alignDir"]?.takeIf { it.isNotBlank() }?.let { File(it, "${dir.name}/align.json") }?.takeIf { it.isFile }
        val a0 = ReplayRunner.run(dir, overrides, hrtf, label = "A: ${variant ?: "기본값"}")
        val a = alignFile?.let { f -> a0.truth?.let { t -> ReplayRunner.run(dir, overrides, hrtf, substitution = Substitution(t, hearspace.core.truth.Alignment.read(f)), label = a0.label) } } ?: a0
        // 정답 깊이 대입은 정답이 장면 전체인 세션만(정답이 물체뿐이면 벽이 없는 깊이가 된다)
        val compare = opt["compare"]?.takeIf { it.isNotBlank() }
        val b = a.truth?.takeIf { a.sceneTruth != null }?.let { t ->
            a.alignment?.let { al ->
                if (compare == null) ReplayRunner.run(dir, overrides, hrtf, substitution = Substitution(t, al, depth = true), label = "B: 정답 깊이 대입(D)")
                else ReplayRunner.run(dir, File(Repo.variants, "$compare.json").readText().trim(), hrtf, substitution = Substitution(t, al), label = "B: $compare")
            }
        }
        for ((i, m) in marks.withIndex()) {
            val f = File(out, "%s_%02d.png".format(dir.name, i + 1))
            ImageIO.write(render(a, b, a.t0Ns + (m.tS * 1e9).toLong(), "${dir.name}  %.2f s  ${m.label}".format(m.tS)), "png", f)
            println(f.path)
        }
    }
}

private const val CAM_W = 360
private const val TOP_W = 420
private const val PANEL_H = 480
private const val HEADER_H = 24

/** 한 시각의 그림: 위 줄 [a], 아래 줄 [b](없으면 한 줄). */
internal fun render(a: ReplayResult, b: ReplayResult?, tNs: Long, title: String): BufferedImage {
    val rows = if (b == null) 1 else 2
    val img = BufferedImage(CAM_W + TOP_W, HEADER_H + rows * PANEL_H, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Palette.bg
    g.fillRect(0, 0, img.width, img.height)
    g.color = Color.WHITE
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
    g.drawString(title, 8, 17)
    val vm = ViewerModel().apply { showVoxels = true; showDepth = false }
    vm.setResult(a)
    vm.compare = b
    vm.setTime(tNs)
    fun paint(p: JPanel, x: Int, y: Int, w: Int) {
        p.setSize(w, PANEL_H)
        val sub = g.create(x, y, w, PANEL_H)
        p.paint(sub)
        sub.dispose()
    }
    paint(CameraView(vm), 0, HEADER_H, CAM_W)
    paint(TopView(vm), CAM_W, HEADER_H, TOP_W)
    if (b != null) {
        paint(CameraView(vm, compare = true), 0, HEADER_H + PANEL_H, CAM_W)
        paint(TopView(vm, compare = true), CAM_W, HEADER_H + PANEL_H, TOP_W)
    }
    g.dispose()
    return img
}
