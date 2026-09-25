package walkassist.core.session

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** 흑백 PNG 한 장. 8비트면 [gray8], 16비트면 [gray16]만 채워진다(행 우선). */
class GrayImage private constructor(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val gray8: ByteArray?,
    val gray16: ShortArray?,
) {
    companion object {
        /** 16비트 흑백(깊이 mm 등). */
        fun of16(width: Int, height: Int, data: ShortArray): GrayImage {
            require(data.size == width * height) { "size mismatch: ${data.size} != $width x $height" }
            return GrayImage(width, height, 16, null, data)
        }

        /** 8비트 흑백(신뢰도 등). */
        fun of8(width: Int, height: Int, data: ByteArray): GrayImage {
            require(data.size == width * height) { "size mismatch: ${data.size} != $width x $height" }
            return GrayImage(width, height, 8, data, null)
        }
    }
}

/**
 * 흑백 PNG(8·16비트) 인코더·디코더. Android Bitmap은 16비트 흑백을 쓰지 못하므로 직접 구현한다.
 * 인코딩은 행마다 Sub 필터(깊이 맵 압축에 유리), 디코딩은 표준 필터 5종을 모두 지원한다. 인터레이스는 지원하지 않는다.
 */
object Png16 {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private const val COLOR_GRAY = 0
    private const val FILTER_NONE = 0
    private const val FILTER_SUB = 1
    private const val FILTER_UP = 2
    private const val FILTER_AVERAGE = 3
    private const val FILTER_PAETH = 4

    /** [image]를 PNG 바이트로 만든다. [level]은 [Deflater] 압축 수준. */
    fun encode(image: GrayImage, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val bpp = image.bitDepth / 8
        val stride = image.width * bpp
        val raw = ByteArray((stride + 1) * image.height)
        val row = ByteArray(stride)
        var o = 0
        for (y in 0 until image.height) {
            fillRow(image, y, row)
            raw[o++] = FILTER_SUB.toByte()
            for (x in 0 until stride) {
                val left = if (x >= bpp) row[x - bpp].toInt() else 0
                raw[o++] = (row[x].toInt() - left).toByte()
            }
        }

        val out = ByteArrayOutputStream(raw.size / 2 + 64)
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeInt(image.width)
                writeInt(image.height)
                writeByte(image.bitDepth)
                writeByte(COLOR_GRAY)
                writeByte(0) // compression
                writeByte(0) // filter method
                writeByte(0) // no interlace
            }
        }.toByteArray()
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", deflate(raw, level))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** PNG 바이트를 읽는다. 흑백 8·16비트, 비인터레이스만 허용한다. */
    fun decode(bytes: ByteArray): GrayImage {
        require(bytes.size >= SIGNATURE.size && bytes.copyOf(SIGNATURE.size).contentEquals(SIGNATURE)) { "not a PNG" }
        var p = SIGNATURE.size
        var width = -1
        var height = -1
        var bitDepth = -1
        val idat = ByteArrayOutputStream()
        while (p + 8 <= bytes.size) {
            val len = readInt(bytes, p)
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            val dataStart = p + 8
            require(len >= 0 && dataStart + len + 4 <= bytes.size) { "truncated chunk $type" }
            val crc = CRC32().apply { update(bytes, p + 4, 4 + len) }.value
            require(crc == readInt(bytes, dataStart + len).toLong() and 0xFFFFFFFFL) { "CRC mismatch in $type" }
            when (type) {
                "IHDR" -> {
                    width = readInt(bytes, dataStart)
                    height = readInt(bytes, dataStart + 4)
                    bitDepth = bytes[dataStart + 8].toInt()
                    val color = bytes[dataStart + 9].toInt()
                    val interlace = bytes[dataStart + 12].toInt()
                    require(color == COLOR_GRAY) { "only grayscale supported, color type $color" }
                    require(bitDepth == 8 || bitDepth == 16) { "only 8/16-bit supported, got $bitDepth" }
                    require(interlace == 0) { "interlaced PNG not supported" }
                }
                "IDAT" -> idat.write(bytes, dataStart, len)
                "IEND" -> break
            }
            p = dataStart + len + 4
        }
        require(width > 0 && height > 0) { "missing IHDR" }

        val bpp = bitDepth / 8
        val stride = width * bpp
        val raw = inflate(idat.toByteArray(), (stride + 1) * height)
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        val g8 = if (bitDepth == 8) ByteArray(width * height) else null
        val g16 = if (bitDepth == 16) ShortArray(width * height) else null
        var i = 0
        for (y in 0 until height) {
            val filter = raw[i++].toInt()
            for (x in 0 until stride) {
                val a = if (x >= bpp) cur[x - bpp].toInt() and 0xFF else 0
                val b = prev[x].toInt() and 0xFF
                val c = if (x >= bpp) prev[x - bpp].toInt() and 0xFF else 0
                val v = raw[i++].toInt() and 0xFF
                cur[x] = when (filter) {
                    FILTER_NONE -> v
                    FILTER_SUB -> v + a
                    FILTER_UP -> v + b
                    FILTER_AVERAGE -> v + (a + b) / 2
                    FILTER_PAETH -> v + paeth(a, b, c)
                    else -> throw IllegalArgumentException("bad filter $filter at row $y")
                }.toByte()
            }
            val base = y * width
            if (g8 != null) {
                cur.copyInto(g8, base)
            } else {
                for (x in 0 until width) {
                    g16!![base + x] = (((cur[2 * x].toInt() and 0xFF) shl 8) or (cur[2 * x + 1].toInt() and 0xFF)).toShort()
                }
            }
            cur.copyInto(prev)
        }
        return if (g8 != null) GrayImage.of8(width, height, g8) else GrayImage.of16(width, height, g16!!)
    }

    private fun fillRow(image: GrayImage, y: Int, row: ByteArray) {
        val base = y * image.width
        if (image.bitDepth == 8) {
            image.gray8!!.copyInto(row, 0, base, base + image.width)
        } else {
            val src = image.gray16!!
            for (x in 0 until image.width) {
                val v = src[base + x].toInt()
                row[2 * x] = (v shr 8).toByte() // PNG는 빅엔디언
                row[2 * x + 1] = v.toByte()
            }
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        d.write(typeBytes)
        d.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        d.writeInt(crc.value.toInt())
    }

    private fun deflate(data: ByteArray, level: Int): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(16 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(data)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected) {
                val r = inf.inflate(out, n, expected - n)
                if (r == 0 && (inf.finished() || inf.needsInput())) break
                n += r
            }
            require(n == expected) { "image data too short: $n < $expected" }
            return out
        } finally {
            inf.end()
        }
    }

    private fun readInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
}
