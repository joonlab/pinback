package kr.joonlab.pinback

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import kr.joonlab.pinback.ui.JlTheme
import kr.joonlab.pinback.ui.PinbackApp
import kr.joonlab.pinback.ui.ThemeMode

/**
 * 홈 = 저장한 장소 목록. 들어오는 길:
 *   런처 아이콘              → 목록
 *   바로가기 「지금 위치 저장」 → 목록을 열고 곧바로 저장(ACTION_SAVE)
 *   주차 알림 본문            → 그 장소 상세(ACTION_OPEN)
 *   주차 알림 「사진 남기기」   → 그 장소 상세 + 카메라(ACTION_PHOTO)
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_SAVE = "kr.joonlab.pinback.SAVE"
        const val ACTION_OPEN = "kr.joonlab.pinback.OPEN"
        const val ACTION_PHOTO = "kr.joonlab.pinback.PHOTO"
        const val EXTRA_ID = "id"
    }

    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 제스처 바 뒤에 시스템이 까는 반투명 막을 끈다 — 내비바 뒤도 bg 한 색(가이드 §5)
        window.isNavigationBarContrastEnforced = false
        model = ViewModelProvider(this)[AppModel::class.java]
        ThemeMode.load(this)   // 첫 프레임부터 저장된 테마로
        setContent { JlTheme { PinbackApp(model) } }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() { super.onResume(); model.onResume() }

    private fun handle(i: Intent?) {
        val id = i?.getStringExtra(EXTRA_ID)
        when (i?.action) {
            ACTION_SAVE -> model.saveNow()
            ACTION_OPEN -> model.open(id)
            ACTION_PHOTO -> { model.open(id); if (Store.get(id) != null) model.photoRequest = id }
        }
    }
}
