package kr.joonlab.pinback

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * 화면 없는 길찾기. 바로가기 「마지막 장소로 길찾기」와 주차 알림 「길찾기」가 여기로 온다.
 * 사용자가 누른 것이라 포그라운드에서 뜬다 — 백그라운드 액티비티 시작 제한과 무관하다.
 */
class NavActivity : Activity() {

    companion object {
        const val ACTION_NAVIGATE = "kr.joonlab.pinback.NAVIGATE"
        const val ACTION_NAVIGATE_LAST = "kr.joonlab.pinback.NAVIGATE_LAST"
        const val EXTRA_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val p = intent?.getStringExtra(EXTRA_ID)?.let { Store.get(it) } ?: Store.latest()
        if (p == null) {
            toast("저장한 장소가 없어요. 「핀백」 아이콘을 길게 눌러 「지금 위치 저장」을 고르세요.")
        } else try {
            startActivity(Maps.navigateIntent(this, p))
            // 사진이 있는 주차면 알림을 다시 올려 둔다 — 지도 위에서 끌어내려 기둥 사진을 본다
            if (p.isParking && p.photos.isNotEmpty()) Notify.saved(this, p, quiet = true)
        } catch (e: Exception) {
            toast("지도 앱을 열지 못했어요. 네이버 지도나 Google 지도가 있는지 확인해 주세요.")
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
