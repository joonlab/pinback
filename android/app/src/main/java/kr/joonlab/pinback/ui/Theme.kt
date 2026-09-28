package kr.joonlab.pinback.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import kr.joonlab.pinback.R

/**
 * 준랩 폴드 패밀리 팔레트 인터페이스 — 관제실 core `CorePalette` 와 같은 역할 10개.
 * 앱마다 바뀌는 건 accent 하나(핀백 = 스카이→블루).
 */
interface Pal {
    val bg: Color
    val panel: Color
    val panel2: Color
    val raised: Color
    val border: Color
    val text: Color
    val dim: Color
    val accent: Color
    val accentTint: Color
    val onAccent: Color
}

/**
 * getter 로 [ThemeMode.dark] 를 읽는다 — 테마를 바꾸면 읽은 자리만 다시 그려진다.
 * 값은 DESIGN-GUIDE §1-3 「주차 위치 화면」 블루 표 그대로(핀백이 그 화면에서 독립한 앱이다).
 * 대비(WCAG 계산): accent/bg 6.42 · 7.60 · onAccent/accent 6.70 · 6.78 · dim/bg 6.11 · 7.64 — 전부 AA.
 */
object P : Pal {
    private fun t(l: Long, d: Long) = Color(if (ThemeMode.dark) d else l)
    override val bg get() = t(0xFFF9FAFC, 0xFF0B0E13)
    override val panel get() = t(0xFFF1F4F9, 0xFF11161D)
    override val panel2 get() = t(0xFFE5EAF2, 0xFF182029)
    override val raised get() = t(0xFFFFFFFF, 0xFF1C2530)
    override val border get() = t(0xFFD2DAE6, 0xFF28323F)
    override val text get() = t(0xFF111827, 0xFFE6ECF3)
    override val dim get() = t(0xFF556070, 0xFF98A4B3)
    override val accent get() = t(0xFF1D4ED8, 0xFF60A5FA)
    override val accentTint get() = accent.copy(alpha = if (ThemeMode.dark) .16f else .10f)
    override val onAccent get() = t(0xFFFFFFFF, 0xFF0A1B33)

    /** 아이콘 그라데이션 양 끝 — 스카이 #60A5FA → 블루 #1D4ED8 */
    val gradA = Color(0xFF60A5FA)
    val gradB = Color(0xFF1D4ED8)

    // 의미색(관제실 값) — 면 물들이기·작은 아이콘·점에만
    val ok get() = t(0xFF1A7F3C, 0xFF5ED18A)
    val warn get() = t(0xFF8A6100, 0xFFD9AE45)
    val warnText get() = t(0xFF6B4C00, 0xFFE6C97E)
    val danger get() = t(0xFFC4322C, 0xFFEF6157)
    val dangerTint get() = danger.copy(alpha = if (ThemeMode.dark) .12f else .10f)
    val warnTint get() = warn.copy(alpha = .16f)
    /** 사진 위에 까는 막 — 테마와 무관하게 검정 */
    val scrim = Color(0xFF000000)
    /** 검은 막 위의 글자·아이콘 */
    val onScrim = Color(0xFFFFFFFF)
}

/** 테마는 셋을 돈다: system(폰 설정 추종) → light → dark. 관제실 core ThemeMode 와 같다. */
object ThemeMode {
    const val PREFS = "ui"
    var mode by mutableStateOf("system")
    var sysDark by mutableStateOf(false)
    val dark get() = mode == "dark" || (mode == "system" && sysDark)

    /** composition 전에 불러 첫 프레임부터 저장된 테마로 그린다(깜빡임 방지). */
    fun load(ctx: Context) {
        mode = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", "system") ?: "system"
    }

    fun cycle(ctx: Context) {
        mode = when (mode) { "system" -> "light"; "light" -> "dark"; else -> "system" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
    }
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/** 뿌리에서 한 번 — 시스템 다크 추종 + 상태·내비 바 글자색 + 창 배경 + 기본 글꼴·글자색. */
@Composable
fun JlTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    ThemeMode.sysDark = isSystemInDarkTheme()
    val view = LocalView.current
    val dark = ThemeMode.dark
    LaunchedEffect(dark) {
        (ctx as? Activity)?.window?.let { w ->
            w.decorView.setBackgroundColor(P.bg.toArgb())   // 접을 때 잠깐 비치는 창 바탕도 테마색
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Pretendard, color = P.text)) {
        content()
    }
}
