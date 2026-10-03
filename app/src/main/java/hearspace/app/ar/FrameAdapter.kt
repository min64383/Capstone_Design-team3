package hearspace.app.ar

import android.media.Image
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import hearspace.core.session.PoseGl
import hearspace.core.types.Intrinsics
import hearspace.core.types.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.google.ar.core.TrackingState as ArTrackingState

/** 16비트 깊이 이미지 복사본. [mm]이 null이면 시각만 읽고 픽셀은 복사하지 않은 것이다. */
class DepthCopy(val tNs: Long, val width: Int, val height: Int, val mm: ShortArray?)

/** 8비트 신뢰도 이미지 복사본. */
class ConfidenceCopy(val tNs: Long, val width: Int, val height: Int, val values: ByteArray)

/** CPU 카메라 이미지를 NV21로 복사한 것. 센서 방향 그대로(회전하지 않음). */
class Nv21Copy(val tNs: Long, val width: Int, val height: Int, val bytes: ByteArray)

/**
 * ARCore 타입 → core·세션 형식 타입 변환, ARCore 이미지 획득·복사.
 * 이미지는 호출한 스레드(GL 스레드)에서 복사한 뒤 즉시 닫는다(§9.1).
 */
object FrameAdapter {

    /** ARCore 추적 상태를 core 타입으로. */
    fun toCore(state: ArTrackingState): TrackingState = when (state) {
        ArTrackingState.TRACKING -> TrackingState.TRACKING
        ArTrackingState.PAUSED -> TrackingState.PAUSED
        ArTrackingState.STOPPED -> TrackingState.STOPPED
    }

    /** ARCore Pose를 규약 변환 없이 그대로 옮긴다(§8.1: 원본 저장). */
    fun toPoseGl(p: Pose): PoseGl = PoseGl(p.tx(), p.ty(), p.tz(), p.qx(), p.qy(), p.qz(), p.qw())

    /** ARCore 내부 파라미터(회전하지 않은 센서 방향)를 core 타입으로. */
    fun toIntrinsics(k: CameraIntrinsics): Intrinsics {
        val f = k.focalLength
        val c = k.principalPoint
        val d = k.imageDimensions
        return Intrinsics(f[0], f[1], c[0], c[1], d[0], d[1])
    }

    /**
     * 일반([raw]=false) 또는 원시([raw]=true) 깊이 이미지를 얻는다. 없으면 null.
     * 픽셀은 [copyIf]가 이미지 시각에 대해 true를 돌려줄 때만 복사한다.
     */
    fun readDepth(frame: Frame, raw: Boolean, copyIf: (tNs: Long) -> Boolean): DepthCopy? {
        val image = acquireOrNull { if (raw) frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits() } ?: return null
        image.use {
            val tNs = it.timestamp
            val mm = if (copyIf(tNs)) copyU16(it) else null
            return DepthCopy(tNs, it.width, it.height, mm)
        }
    }

    /** 원시 깊이 신뢰도 이미지를 복사한다. 없으면 null. */
    fun readConfidence(frame: Frame): ConfidenceCopy? {
        val image = acquireOrNull { frame.acquireRawDepthConfidenceImage() } ?: return null
        image.use { return ConfidenceCopy(it.timestamp, it.width, it.height, copyU8(it)) }
    }

    /** CPU 카메라 이미지(YUV_420_888)를 NV21로 복사한다. 없으면 null. */
    fun readCameraNv21(frame: Frame): Nv21Copy? {
        val image = acquireOrNull { frame.acquireCameraImage() } ?: return null
        image.use { return Nv21Copy(it.timestamp, it.width, it.height, toNv21(it)) }
    }

    private inline fun acquireOrNull(acquire: () -> Image): Image? = try {
        acquire()
    } catch (_: NotYetAvailableException) {
        null
    } catch (_: NotTrackingException) {
        null
    } catch (_: ResourceExhaustedException) {
        null
    } catch (_: DeadlineExceededException) {
        null
    }

    /**
     * 평면 하나를 행 단위 일괄 읽기로 촘촘한 바이트 배열(행 우선, 픽셀당 [bytesPerPixel]바이트)로 복사한다.
     * 픽셀마다 ByteBuffer.get을 부르면 640x480에서 100 ms 이상 걸려 GL 스레드가 밀렸다(M1 계측).
     */
    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, bytesPerPixel: Int): ByteArray {
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val packed = width * bytesPerPixel
        val out = ByteArray(packed * height)
        if (pixelStride == bytesPerPixel) {
            for (y in 0 until height) {
                buf.position(y * rowStride)
                buf.get(out, y * packed, packed)
            }
        } else {
            // 픽셀 사이에 간격이 있는 평면(예: YUV의 교차 크로마): 행을 통째로 읽고 필요한 바이트만 고른다.
            val rowBytes = (width - 1) * pixelStride + bytesPerPixel
            val row = ByteArray(rowBytes)
            for (y in 0 until height) {
                buf.position(y * rowStride)
                buf.get(row, 0, rowBytes)
                var o = y * packed
                var i = 0
                for (x in 0 until width) {
                    for (b in 0 until bytesPerPixel) out[o++] = row[i + b]
                    i += pixelStride
                }
            }
        }
        return out
    }

    /** 단일 평면 uint16. ARCore 문서: little-endian. */
    private fun copyU16(image: Image): ShortArray {
        val bytes = copyPlane(image.planes[0], image.width, image.height, 2)
        val out = ShortArray(image.width * image.height)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun copyU8(image: Image): ByteArray = copyPlane(image.planes[0], image.width, image.height, 1)

    /** YUV_420_888 → NV21(Y 평면 + VU 교차). */
    private fun toNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val y = copyPlane(image.planes[0], w, h, 1)
        val u = copyPlane(image.planes[1], w / 2, h / 2, 1)
        val v = copyPlane(image.planes[2], w / 2, h / 2, 1)
        val out = ByteArray(w * h * 3 / 2)
        y.copyInto(out)
        var o = w * h
        for (i in u.indices) {
            out[o++] = v[i]
            out[o++] = u[i]
        }
        return out
    }
}
