package hearspace.core.tracking

import hearspace.core.geometry.Mat4
import hearspace.core.session.Png16
import hearspace.core.types.ConfigLoader
import hearspace.core.types.Intrinsics
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.LabelImage
import hearspace.core.types.MiniJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * M53 이식 확인(README §4.8 골든 벡터, 표 9 '이식'): 합성 의미 영상에서 Kotlin [FootContact]가 `prototypes/python/foot_contact/golden.py`(foot.py)와
 * 같은 발 접점을 내는지. 덩어리 다섯 중 버릴 넷(작은 덩어리, 땅 쪽 변·옆 변에 닿음, 8 m 밖)을 버리고 남은 셋의 월드 점·거리·σ를 비교한다.
 */
class FootContactGoldenTest {
    private val dir = "/golden/tracking"

    @Test
    fun `foot contact matches the python prototype`() {
        val case = MiniJson.parse(javaClass.getResource("$dir/foot_contact.json")!!.readText()) as JsonObject
        fun obj(o: JsonObject, k: String) = o.fields[k] as JsonObject
        fun num(o: JsonObject, k: String) = (o.fields[k] as JsonNumber).value
        val input = obj(case, "input")
        val k = obj(input, "K")
        val w = num(input, "width").toInt()
        val h = num(input, "height").toInt()
        val png = Png16.decode(javaClass.getResource("$dir/${(input.fields["labelsPng"] as JsonString).value}")!!.readBytes())
        val m = (input.fields["worldFromCamRowMajor"] as JsonArray).items.map { (it as JsonNumber).value.toFloat() }.toFloatArray()
        val img = LabelImage(
            num(input, "tCaptureNs").toLong(), png.gray8!!,
            Intrinsics(num(k, "fx").toFloat(), num(k, "fy").toFloat(), num(k, "cx").toFloat(), num(k, "cy").toFloat(), w, h), Mat4(m),
        )
        val over = MiniJson.write(JsonObject(linkedMapOf("vision" to obj(obj(case, "configOverride"), "vision"))))
        val cfg = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), over).vision
        val pixels = (input.fields["labels"] as JsonArray).items.map { (it as JsonNumber).value.toInt() }.toSet()
        val got = FootContact.measure(img, pixels, num(input, "floorY").toFloat(), cfg).sortedWith(compareBy({ it.label }, { it.distM }))
        val want = ((obj(case, "expected").fields["meas"]) as JsonArray).items.map { it as JsonObject }
            .sortedWith(compareBy({ num(it, "label") }, { num(it, "distM") }))
        val tol = num(obj(case, "tolerance"), "abs").toFloat()
        assertEquals(want.size, got.size, "measurement count: $got")
        for ((g, e) in got.zip(want)) {
            assertEquals(num(e, "label").toInt(), g.label)
            assertEquals(num(e, "nPx").toInt(), g.nPx)
            for ((a, b) in listOf(g.pW.x to num(e, "x"), g.pW.y to num(e, "y"), g.pW.z to num(e, "z"), g.distM to num(e, "distM"), g.sigmaM to num(e, "sigmaM"))) {
                assert(abs(a - b.toFloat()) <= tol) { "got $a want $b ($g)" }
            }
        }
    }
}
