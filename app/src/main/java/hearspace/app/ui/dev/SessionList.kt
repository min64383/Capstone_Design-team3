package hearspace.app.ui.dev

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import hearspace.app.TAG
import hearspace.core.session.SessionFormat
import hearspace.core.session.SessionMeta
import java.io.File

/** 개발 모드 세션 목록(§11.3): 세션별 크기·길이·장면 ID, `adb pull` 경로, 삭제. 재생 선택 모드에서는 누르면 재생한다. */
class SessionList : Activity() {

    private data class Entry(val dir: File, val meta: SessionMeta?, val sizeBytes: Long) {
        override fun toString(): String {
            val m = meta
            val scene = m?.sceneId ?: "?"
            val dur = m?.stats?.durationS?.let { "%.0fs".format(it) } ?: "미완료"
            val frames = m?.stats?.nFrames?.let { "$it 프레임" } ?: ""
            return "${dir.name}\n$scene · $dur · %.1f MB $frames".format(sizeBytes / 1e6)
        }
    }

    private lateinit var list: ListView
    private lateinit var header: TextView
    private val pick get() = intent.getBooleanExtra(EXTRA_PICK, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header = TextView(this).apply { setPadding(32, 32, 32, 16); setTextIsSelectable(true) }
        list = ListView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(list)
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = list.adapter.getItem(pos) as Entry
            if (pick) play(e) else showEntry(e)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val root = RecordScreen.sessionsRoot(this)
        val entries = root.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }.map { dir ->
            val meta = try {
                SessionMeta.fromJson(File(dir, SessionFormat.META_FILE).readText())
            } catch (e: Exception) {
                Log.w(TAG, "cannot read meta in $dir", e)
                null
            }
            Entry(dir, meta, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }
        header.text = (if (pick) "재생할 세션을 고르세요\n" else "") +"세션 ${entries.size}개\n가져오기(PowerShell):\nadb pull ${root.absolutePath}/<세션ID> data/sessions/"
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
    }

    private fun play(e: Entry) {
        if (!File(e.dir, SessionFormat.MP4_FILE).isFile) {
            Toast.makeText(this, "arcore.mp4가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(LiveScreen.intent(this, e.dir))
    }

    private fun showEntry(e: Entry) {
        val pull = "adb pull ${e.dir.absolutePath} data/sessions/"
        AlertDialog.Builder(this)
            .setTitle(e.dir.name)
            .setMessage("$e\n\n$pull")
            .setPositiveButton("닫기", null)
            .setNegativeButton("삭제") { _, _ -> confirmDelete(e) }
            .show()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle("삭제할까요?")
            .setMessage("${e.dir.name}\n되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                if (!e.dir.deleteRecursively()) Log.w(TAG, "delete incomplete: ${e.dir}")
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    companion object {
        private const val EXTRA_PICK = "pick"

        /** 재생할 세션을 고르는 목록. */
        fun pickIntent(context: Context) = Intent(context, SessionList::class.java).putExtra(EXTRA_PICK, true)
    }
}
