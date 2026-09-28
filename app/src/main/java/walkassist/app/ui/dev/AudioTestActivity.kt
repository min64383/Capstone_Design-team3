package walkassist.app.ui.dev

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import walkassist.app.AppConfig
import walkassist.app.runtime.AudioOutput
import walkassist.core.types.AudioCmd
import walkassist.core.types.Band
import walkassist.core.types.SoundKind

/**
 * 단순 공간음향 프로토타입 테스트 화면.
 */
class AudioTestActivity : Activity() {

    private lateinit var audioOutput: AudioOutput

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        val config =
            AppConfig.get(this)

        audioOutput =
            AudioOutput(config)

        audioOutput.start()

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    48,
                    96,
                    48,
                    48,
                )
            }

        root.addView(
            TextView(this).apply {
                text =
                    "이어폰을 연결하고 테스트하세요.\n" +
                            "방향 = 좌우 볼륨 + 두 귀 시간차\n" +
                            "뒤쪽 = 낮은 음\n" +
                            "거리 = 비프 간격"
                textSize = 20f
            }
        )

        /*
         * 방향 확인.
         * 모두 같은 1.5 m.
         */
        addButton(
            root,
            "왼쪽 -90° / 1.5 m",
        ) {
            play(
                azimuthDeg = -90f,
                distanceM = 1.5f,
            )
        }

        addButton(
            root,
            "왼쪽 -45° / 1.5 m",
        ) {
            play(
                azimuthDeg = -45f,
                distanceM = 1.5f,
            )
        }

        addButton(
            root,
            "정면 0° / 1.5 m",
        ) {
            play(
                azimuthDeg = 0f,
                distanceM = 1.5f,
            )
        }

        addButton(
            root,
            "오른쪽 +45° / 1.5 m",
        ) {
            play(
                azimuthDeg = 45f,
                distanceM = 1.5f,
            )
        }

        addButton(
            root,
            "오른쪽 +90° / 1.5 m",
        ) {
            play(
                azimuthDeg = 90f,
                distanceM = 1.5f,
            )
        }

        /*
         * 뒤쪽 확인. 낮은 음으로 바뀌어야 한다.
         */
        addButton(
            root,
            "뒤 오른쪽 +135° / 1.5 m",
        ) {
            play(
                azimuthDeg = 135f,
                distanceM = 1.5f,
            )
        }

        addButton(
            root,
            "뒤 180° / 1.5 m",
        ) {
            play(
                azimuthDeg = 180f,
                distanceM = 1.5f,
            )
        }

        /*
         * 거리 확인.
         * 모두 정면.
         */
        addButton(
            root,
            "가까움 / 0.5 m",
        ) {
            play(
                azimuthDeg = 0f,
                distanceM = 0.5f,
            )
        }

        addButton(
            root,
            "중간 / 1.5 m",
        ) {
            play(
                azimuthDeg = 0f,
                distanceM = 1.5f,
            )
        }

        addButton(
            root,
            "멀리 / 2.8 m",
        ) {
            play(
                azimuthDeg = 0f,
                distanceM = 2.8f,
            )
        }

        addButton(
            root,
            "정지",
        ) {
            audioOutput.clear()
        }

        setContentView(root)
    }

    private fun play(
        azimuthDeg: Float,
        distanceM: Float,
    ) {

        /*
         * 현재는 AudioCmd 중 방향/거리만 사용.
         * Band와 SoundKind는 이후 정식 guidance에서 사용한다.
         */
        audioOutput.submit(
            AudioCmd(
                obstacleId = 1,
                azimuthDeg = azimuthDeg,
                distanceM = distanceM,
                band = Band.WARN,
                sound = SoundKind.FLOOR_PULSE,
                infoAgeMs = 0f,
            )
        )
    }

    private fun addButton(
        root: LinearLayout,
        label: String,
        onClick: () -> Unit,
    ) {

        root.addView(
            Button(this).apply {

                text = label

                setOnClickListener {
                    onClick()
                }
            },
            LinearLayout.LayoutParams(
                MATCH_PARENT,
                WRAP_CONTENT,
            ),
        )
    }

    override fun onDestroy() {

        audioOutput.close()

        super.onDestroy()
    }
}