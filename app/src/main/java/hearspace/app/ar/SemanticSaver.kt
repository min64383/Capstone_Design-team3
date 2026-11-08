package hearspace.app.ar

import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.SemanticLabel
import hearspace.app.TAG
import hearspace.core.session.GrayImage
import hearspace.core.session.Png16
import hearspace.core.session.SessionFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ARCore 의미 영상을 세션 폴더 `semantic/`에 저장한다(M49, docs/FORMAT.md). 녹화 모드와 재생 모드("의미 영상 저장")가 같이 쓴다.
 * [onFrame]은 GL 스레드에서 프레임마다 부른다: 새 의미 영상이면 복사해 슬롯 하나에 넣고, 쓰기 스레드가 PNG와 목록을 쓴다.
 * 슬롯이 차 있으면 이번 영상은 건너뛰고 [nSkipped]에 센다(GL 스레드를 막지 않는다). 처음 [FRACTION_IMAGES]장은 ARCore가 낸
 * 라벨별 화소 비율도 적는다(화소 값 ↔ 라벨 대응 확인, `SemanticLabel` VERIFY).
 */
class SemanticSaver(private val dir: File) {
    private class Item(val frameTNs: Long, val copy: SemanticCopy, val fractions: List<Pair<String, Float>>?)

    private val slot = AtomicReference<Item?>(null)
    private val wake = Semaphore(0)

    @Volatile
    private var stopping = false
    private val writer: Thread

    // GL 스레드 전용
    private var lastTNs = Long.MIN_VALUE
    private var nSeen = 0

    val nSaved = AtomicLong()
    val nSkipped = AtomicLong()
    val nErrors = AtomicLong()

    init {
        File(dir, SessionFormat.SEMANTIC_DIR).mkdirs()
        writer = Thread(::writerLoop, "SemanticSaver").apply { start() }
    }

    /** GL 스레드. 새 의미 영상이면 복사해 넘긴다. */
    fun onFrame(frame: Frame) {
        if (stopping) return
        val free = slot.get() == null // 슬롯을 비우는 쪽은 쓰기 스레드뿐이라 여기서 비어 있으면 아래 set까지 비어 있다
        var fresh = false
        val copy = FrameAdapter.readSemantic(frame) { tNs ->
            fresh = tNs != lastTNs
            lastTNs = tNs
            fresh && free
        }
        if (copy == null) {
            if (fresh && !free) nSkipped.incrementAndGet()
            return
        }
        val fractions = if (nSeen++ < FRACTION_IMAGES) {
            SemanticLabel.values().map { l -> l.name to runCatching { frame.getSemanticLabelFraction(l) }.getOrDefault(Float.NaN) }
        } else {
            null
        }
        slot.set(Item(frame.timestamp, copy, fractions))
        wake.release()
    }

    /** 남은 영상을 쓰고 끝낸다(UI 스레드, GL 스레드가 멈춘 뒤). */
    fun close() {
        stopping = true
        wake.release()
        writer.join(CLOSE_WAIT_MS)
        Log.i(TAG, "semantic saved ${nSaved.get()} skipped ${nSkipped.get()} errors ${nErrors.get()}: $dir")
    }

    private fun writerLoop() {
        try {
            File(dir, SessionFormat.SEMANTIC_INDEX).bufferedWriter().use { index ->
                File(dir, SessionFormat.SEMANTIC_FRACTIONS).bufferedWriter().use { fr ->
                    index.write("frameTNs,imageTNs,width,height,file,confFile\n")
                    fr.write("imageTNs,label,fraction\n")
                    while (true) {
                        wake.tryAcquire(WAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        wake.drainPermits()
                        val done = stopping
                        slot.getAndSet(null)?.let { item -> write(item, index, fr) }
                        if (done) break
                    }
                }
            }
        } catch (e: IOException) {
            nErrors.incrementAndGet()
            Log.e(TAG, "semantic index write failed: $dir", e)
        }
    }

    private fun write(item: Item, index: java.io.BufferedWriter, fr: java.io.BufferedWriter) {
        val c = item.copy
        val file = SessionFormat.semanticFile(c.tNs)
        val confFile = c.conf?.let { SessionFormat.semanticConfFile(c.tNs) }
        try {
            File(dir, file).writeBytes(Png16.encode(GrayImage.of8(c.width, c.height, c.labels)))
            c.conf?.let { File(dir, confFile!!).writeBytes(Png16.encode(GrayImage.of8(c.width, c.height, it))) }
        } catch (e: Exception) {
            nErrors.incrementAndGet()
            Log.e(TAG, "semantic write failed: $file", e)
            return
        }
        index.write("${item.frameTNs},${c.tNs},${c.width},${c.height},$file,${confFile ?: ""}\n")
        item.fractions?.forEach { (name, f) -> fr.write("${c.tNs},$name,$f\n") }
        index.flush()
        fr.flush()
        nSaved.incrementAndGet()
    }

    private companion object {
        const val WAKE_TIMEOUT_MS = 100L
        const val CLOSE_WAIT_MS = 2000L

        /** 라벨별 비율을 적는 처음 영상 수(대응 확인에 충분한 장 수, 분석용 선택). */
        const val FRACTION_IMAGES = 30
    }
}
