package hearspace.core.synth

import hearspace.core.situation.SurfaceIO
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.zip.CRC32

/**
 * M65 SP-15: core 노면 앞처리·뒤처리가 PC `infer_sessions.py`(Pillow)와 같다. 황금 값은 `tools/surface/golden_surface_io.py`로
 * 같은 합성 영상에서 만들었다(Pillow 13.0.0). 작은 크기는 바이트 전부, 실제 크기(640×480 → 320×256, → 320×240)는 CRC32로 본다.
 */
class SurfaceIOTest {
    private fun synthRgb(w: Int, h: Int) = ByteArray(w * h * 3).also { b ->
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) b[(y * w + x) * 3 + c] = ((x * 37 + y * 91 + ((x * y) % 17) * 11 + c * 53) % 256).toByte()
    }

    private fun synthLabels(w: Int, h: Int) = ByteArray(w * h).also { b ->
        for (y in 0 until h) for (x in 0 until w) b[y * w + x] = ((x * 3 + y * 5 + (x * y) % 7) % 8).toByte()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    @Test
    fun `SP-15 small preprocessing and postprocessing match Pillow byte for byte`() {
        val io = SurfaceIO(40, 30, 0.4f, 20, 16, 32, 24)
        val input = FloatArray(16 * 20 * 4)
        io.preprocess(synthRgb(40, 30), input)
        assertArrayEquals(hex(SMALL_RESIZED), io.resized)
        val first = floatArrayOf(0.896052778f, 0.222689196f, 0.321917266f, -0.645175099f, 0.100140058f, 1.05394351f)
        // NHWC: 화소 0의 R·G·B, 넷째 채널 0, 화소 1의 R·G
        val got = floatArrayOf(input[0], input[1], input[2], input[4], input[5], input[6])
        for (i in first.indices) assertEquals(first[i], got[i], 1e-3f)
        assertEquals(0f, input[3])
        val out = ByteArray(32 * 24)
        io.placeAndRotate(synthLabels(20, 16), out)
        assertArrayEquals(hex(SMALL_LABELS), out)
    }

    @Test
    fun `SP-15 full size matches Pillow by CRC32`() {
        val io = SurfaceIO(640, 480, 0.4f, 320, 256, 320, 240)
        io.preprocess(synthRgb(640, 480), FloatArray(256 * 320 * 4))
        assertEquals(0x107af18aL, crc(io.resized))
        val out = ByteArray(320 * 240)
        io.placeAndRotate(synthLabels(320, 256), out)
        assertEquals(0xf5205a9aL, crc(out))
    }

    @Test
    fun `SP-35 native size input is the rotated crop without resampling, as Pillow keeps a same-size image`() {
        // M69: 640×480 → 세로 아래 60% 480×384를 줄이지 않음(infer_sessions.native_input). 세로 (px, py) = 센서 (x = py, y = 479 − px), 위 256행 버림
        val rgb = synthRgb(640, 480)
        val io = SurfaceIO(640, 480, 0.4f, 480, 384, 640, 480)
        io.preprocess(rgb, FloatArray(384 * 480 * 4))
        val want = ByteArray(384 * 480 * 3)
        for (cy in 0 until 384) for (px in 0 until 480) for (c in 0 until 3) want[(cy * 480 + px) * 3 + c] = rgb[((479 - px) * 640 + (256 + cy)) * 3 + c]
        assertArrayEquals(want, io.resized)
        // 뒤처리: 세로 라벨이 크기 그대로 아래 384행에 놓이고, 센서 방향 640×480의 왼쪽 256열은 계산 안 함
        val labels = synthLabels(480, 384)
        val out = ByteArray(640 * 480)
        io.placeAndRotate(labels, out)
        for (yp in 0 until 480 step 37) for (xp in 0 until 640 step 41) {
            val expect = if (xp < 256) hearspace.core.situation.SurfaceClass.UNKNOWN_PIXEL.toByte() else labels[(xp - 256) * 480 + (479 - yp)]
            assertEquals(expect, out[yp * 640 + xp], "($xp, $yp)")
        }
    }

    @Test
    fun `SP-15 postprocess picks the largest logit`() {
        val io = SurfaceIO(40, 30, 0.4f, 20, 16, 32, 24)
        val labels = synthLabels(20, 16)
        val logits = FloatArray(20 * 16 * 8) { i -> if (i % 8 == labels[i / 8].toInt()) 1f else -1f }
        val viaLogits = ByteArray(32 * 24)
        io.postprocess(logits, viaLogits)
        assertArrayEquals(hex(SMALL_LABELS), viaLogits)
    }

    @Test
    fun `NV21 to RGB uses the JFIF full range formula`() {
        // 2×2 영상 하나: Y 128, V(Cr) 200, U(Cb) 60 → R = 128 + 1.402·72, G = 128 − 0.344136·(−68) − 0.714136·72, B = 128 + 1.772·(−68)
        val nv21 = byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 128.toByte(), 200.toByte(), 60)
        val rgb = ByteArray(12)
        SurfaceIO.nv21ToRgb(nv21, 2, 2, rgb)
        assertEquals(229, rgb[0].toInt() and 0xFF)
        assertEquals(100, rgb[1].toInt() and 0xFF)
        assertEquals(8, rgb[2].toInt() and 0xFF)
    }

    private companion object {
        const val SMALL_RESIZED =
            "b0817a567aa4a4804a8f7d72b2844e8181805d8787898989527cb17eb35a5a8fa7815e5d5d81ab8561618185afa96553" +
            "85885aac698c888c686d7eb36e98985c876474796f9b628d6a895f8a767c8f6b97626881949066989d93679c8b8aac78" +
            "9b716d6e9359939f7bb4716886805c8a53855c658a778c567978ad73789e446fa18a666c7d7294a0749f78777d7c8b7e" +
            "8e8b6174a0566fa46a80a1818e64908b7199746a8f8167999f668b68678c7b7d6492925c9d689d84669b758bc057897f" +
            "81779f91647a8786687f7e635c5e8486ab616ea070a57b64786ba0756ba08284b08e74a08e54807f7e5b71738f686892" +
            "bb3065847da8a66d7287906ca8845d87a3699c827ba158615e7aaca65378816e96a6736f7f689498757187a370a37d79" +
            "80956b947b708d95986e8383ab56807761736e86888b9471a78e5d6a9f8f959a60885d7381ad99bc59566851867fab9e" +
            "92985c6085787fb17d9b81796e948087ac788ea3796e83a6749f9e8fba71516a8f765c8166828479ae624671866688ad" +
            "aaaf40bf954530527e5287a986bb62a389856e93736186856c9e55977e70587d9f89be654e71a65779ae767bb08f659a" +
            "5e76ab73957b8dafa5aeba71d04a53c05e4757406c6a568b3f689d6783ae7ba48d6d869e627bb0829abfa7bf76b78953" +
            "a0475ca74f5e9a676c92689d8b6196845b8080567f6f526d6d4a7bc4285d992c61c82954806c55b167506d638fae558a" +
            "af568ba2487aa64c6857456fb4b43b8a906f688a8f617db2a48a9095786e8a9f7c5287b2477cb1563e73be3f3bb59e42" +
            "90bb9471977c588aa6866c9190866c877d60588a9f457aaf6a9fb8666c986561967e80b5668b9a728793a96f659bb480" +
            "79aeaa3f588daf563677ac7c5c91a79e838d8eb3998379a172918786586ea0965c80b59572679ca9755b6688ae6e9a80" +
            "548989866c7b6b74a0a75e50ad73665a739eb15871688aa399705972a78d5a8f84828780749f657a9597855760b8a62d" +
            "905085817d996c72748f658a5d898b769c726177738c8187976a705d7e7b9d737974669b8e5b86588d79829a6e5d7590" +
            "6a9f5f9c7978917d6388597cb34f728d69958bb0868c828894689d567e84737ca7858e745d8eaa4a7f8e71a64d8f9167" +
            "84709290867c849976915a8c867b84907d95756e74557f7fbb89659c6ca160628e5780aba76da280767b7f6675826871" +
            "85aa9063986573a84f7b986daf5c7eb38689897f9b847a9f92b17a7d5a7272679371719b869790b86a6a8a7777689393" +
            "97619678787772727d6a6a6da2a25f7b7b749c9c43756b8eae7854958a789b9b6d78789a85b06c54898c75aa866ba071"
        const val SMALL_LABELS =
            "ffffffffffffffffffffffffff01030606010306010406060104060104040701ffffffffffffffffffffffffff060701" +
            "01020405070102020405070002020405ffffffffffffffffffffffffff03030303040405050606060607070000000101" +
            "ffffffffffffffffffffffffff03030303040405050606060607070000000101ffffffffffffffffffffffffff000706" +
            "06050504030302020100000706060605ffffffffffffffffffffffffff05030101070503010006060402000604040301" +
            "ffffffffffffffffffffffffff02070404010603000502020704010603030005ffffffffffffffffffffffffff070206" +
            "06020602060205050105010501010500ffffffffffffffffffffffffff04060101040601040701010407010407070204" +
            "ffffffffffffffffffffffffff04060101040601040701010407010407070204ffffffffffffffffffffffffff010204" +
            "04050700020405050700020305050700ffffffffffffffffffffffffff06060606070700000101010102020303030404" +
            "ffffffffffffffffffffffffff03020101000007060605050403030201010100ffffffffffffffffffffffffff000604" +
            "04020006040301010705030107070604ffffffffffffffffffffffffff05020707040106030005050207040106060300" +
            "ffffffffffffffffffffffffff05020707040106030005050207040106060300ffffffffffffffffffffffffff020501" +
            "01050105010500000400040004040003ffffffffffffffffffffffffff07010404070104070204040702040702020507" +
            "ffffffffffffffffffffffffff04050707000203050700000203050600000203ffffffffffffffffffffffffff010101" +
            "01020203030404040405050606060707ffffffffffffffffffffffffff06050404030302010100000706060504040403" +
            "ffffffffffffffffffffffffff06050404030302010100000706060504040403ffffffffffffffffffffffffff030107" +
            "07050301070604040200060402020107ffffffffffffffffffffffffff00050202070401060300000502070401010603"
    }
}
