package walkassist.core.session

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

class Png16Test {

    /** 깊이 맵 형태: 부드러운 기울기 + 무효(0) 구멍 + uint16 상한 값. */
    private fun depthLike(w: Int, h: Int): ShortArray {
        val rnd = Random(7)
        return ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                rnd.nextFloat() < 0.05f -> 0
                x == w - 1 -> 65535
                else -> 500 + 20 * y + x
            }.toShort()
        }
    }

    @Test
    fun `16-bit round trip is exact`() {
        val data = depthLike(160, 90)
        val back = Png16.decode(Png16.encode(GrayImage.of16(160, 90, data)))
        assertEquals(16, back.bitDepth)
        assertEquals(160, back.width)
        assertEquals(90, back.height)
        assertArrayEquals(data, back.gray16)
    }

    @Test
    fun `8-bit round trip is exact`() {
        val data = ByteArray(33 * 17) { (it * 37).toByte() }
        val back = Png16.decode(Png16.encode(GrayImage.of8(33, 17, data)))
        assertEquals(8, back.bitDepth)
        assertArrayEquals(data, back.gray8)
    }

    @Test
    fun `16-bit output is readable by ImageIO with the same values`() {
        val w = 64
        val h = 48
        val data = depthLike(w, h)
        val img = ImageIO.read(ByteArrayInputStream(Png16.encode(GrayImage.of16(w, h, data))))
        assertEquals(BufferedImage.TYPE_USHORT_GRAY, img.type)
        val px = IntArray(w * h)
        img.raster.getPixels(0, 0, w, h, px)
        assertArrayEquals(data.map { it.toInt() and 0xFFFF }.toIntArray(), px)
    }

    @Test
    fun `decodes ImageIO-written PNG that uses other filters`() {
        val w = 50
        val h = 40
        val img = BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY)
        val px = IntArray(w * h) { (it * 131) % 65536 }
        img.raster.setPixels(0, 0, w, h, px)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val back = Png16.decode(bytes)
        assertArrayEquals(px, back.gray16!!.map { it.toInt() and 0xFFFF }.toIntArray())
    }

    @Test
    fun `rejects corrupted data`() {
        val bytes = Png16.encode(GrayImage.of16(8, 8, ShortArray(64) { it.toShort() }))
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        assertThrows<IllegalArgumentException> { Png16.decode(bytes) }
        assertThrows<IllegalArgumentException> { Png16.decode(byteArrayOf(1, 2, 3)) }
    }
}
