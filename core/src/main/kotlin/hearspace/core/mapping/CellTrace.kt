package hearspace.core.mapping

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.types.Intrinsics
import hearspace.core.types.MapConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * 칸 관측 기록(M55 설계 3.5절, 평가 전용 `-PcellTrace=true`). 점수로 지우지 않는 두 번째 지도([shadow])에 실제 지도와 같은 점을 넣고,
 * 장마다 그 지도의 모든 칸이 받은 관측(맞음·가까움·빔)과 원시 신뢰도 구간을 기록한다. 지나감·오래 안 보임·반경 밖 삭제는 규칙과 무관해 그대로 한다.
 * 그러면 점유 규칙을 바꿔 가며 같은 기록 위에서 다시 돌릴 수 있다. 실제 지도의 점유 칸도 함께 적어, 기록으로 돌린 지금 규칙이 재생과 같은지
 * 확인한다(완전성 검사).
 *
 * 형식(큰 끝 바이트 순서, `tools/analysis/occupancy.py`가 읽음): 기록 종류 1바이트 다음 본문.
 * - `R`: 지도 비움(자세 불연속). 본문 없음.
 * - `S`: 점수 배율(추적 복귀) float.
 * - `F`: 지도를 갱신한 깊이 한 장. long 촬영 시각, float×3 카메라 위치, 그리고 네 목록(각각 int 개수 + 항목):
 *   맞음(int×3 칸, byte 구간, float 이번 장 표 무게), 관측(int×3 칸, byte 종류 1 = 가까움·2 = 빔, byte 구간),
 *   삭제된 칸(int×3), 실제 지도의 점유 칸(int×3).
 * 구간은 0 = 원시 없음, 1~3 = `depth.confidenceEdges` 위 구간(M54 [hearspace.core.types.DepthConfig.confidenceClass]).
 */
class CellTrace(cfg: MapConfig) {
    val shadow = VoxelMap(cfg)
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    /** 이번 장 맞은 칸마다 그 칸에 든 점들의 가장 높은 구간(칸 중심 화소에 깊이가 없을 때 쓴다). */
    private val pointClass = HashMap<Long, Int>()

    fun beginFrame() {
        shadow.beginFrame()
        pointClass.clear()
    }

    /** 실제 지도에 넣은 점과 같은 점을 넣는다. [cls]는 그 점의 원시 신뢰도 구간. */
    fun insert(p: Vec3, tNs: Long, weight: Float, cls: Int) {
        shadow.insert(p, tNs, weight)
        pointClass.merge(pack(shadow.index(p.x), shadow.index(p.y), shadow.index(p.z)), cls, ::maxOf)
    }

    fun noteReset() {
        shadow.clear()
        out.writeByte('R'.code)
    }

    fun noteScale(factor: Float) {
        out.writeByte('S'.code)
        out.writeFloat(factor)
    }

    /**
     * 이번 장을 기록한다. [depthMm]·[camFromWorld]는 실제 지도가 빈 곳 감쇠에 쓴 깊이와 자세, [classAt]은 깊이 화소 번호 → 원시 신뢰도 구간,
     * [real]은 갱신을 마친 실제 지도.
     */
    fun endFrame(
        tNs: Long, camW: Vec3, depthMm: ShortArray, k: Intrinsics, camFromWorld: Mat4, classAt: (Int) -> Int,
        userPosW: Vec3, headingW: Vec3, real: VoxelMap,
    ) {
        val hits = ByteArrayOutputStream()
        val h = DataOutputStream(hits)
        val obs = ByteArrayOutputStream()
        val o = DataOutputStream(obs)
        var nHit = 0
        var nObs = 0
        shadow.observe(depthMm, k, camFromWorld) { ix, iy, iz, view, px, vote ->
            val hasDepth = px >= 0 && (depthMm[px].toInt() and 0xFFFF) != 0
            when (view) {
                CellView.HIT -> {
                    h.writeInt(ix); h.writeInt(iy); h.writeInt(iz)
                    h.writeByte(if (hasDepth) classAt(px) else pointClass[pack(ix, iy, iz)] ?: 0)
                    h.writeFloat(vote)
                    nHit++
                }
                CellView.OCCLUDED, CellView.FREE -> {
                    o.writeInt(ix); o.writeInt(iy); o.writeInt(iz)
                    o.writeByte(if (view == CellView.OCCLUDED) 1 else 2)
                    o.writeByte(classAt(px))
                    nObs++
                }
                else -> Unit // 앞 가림·깊이 없음·시야 밖: 증거 0
            }
        }
        val pruned = ArrayList<Int>()
        shadow.prune(userPosW, headingW, tNs) { ix, iy, iz -> pruned += ix; pruned += iy; pruned += iz }
        val occ = real.occupied()
        out.writeByte('F'.code)
        out.writeLong(tNs)
        out.writeFloat(camW.x); out.writeFloat(camW.y); out.writeFloat(camW.z)
        out.writeInt(nHit); hits.writeTo(out)
        out.writeInt(nObs); obs.writeTo(out)
        out.writeInt(pruned.size / 3); for (v in pruned) out.writeInt(v)
        out.writeInt(occ.size); for (v in occ) { out.writeInt(v.ix); out.writeInt(v.iy); out.writeInt(v.iz) }
    }

    /** 쌓인 기록을 꺼내고 비운다. */
    fun drain(): ByteArray = bytes.toByteArray().also { bytes.reset() }

    private fun pack(ix: Int, iy: Int, iz: Int): Long =
        ((ix + OFFSET).toLong() shl 42) or ((iy + OFFSET).toLong() shl 21) or (iz + OFFSET).toLong()

    private companion object {
        const val OFFSET = 1 shl 20
    }
}
