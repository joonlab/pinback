package kr.joonlab.pinback.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.joonlab.pinback.AppModel
import kr.joonlab.pinback.Store

/** 목록 | 상세 두 칸으로 나누는 폭(펼친 폴드 933dp). 그보다 좁으면 한 칸씩. */
private const val TWO_PANE_DP = 840

/**
 * 권한 묻기 — 한 번 거절돼 시스템 창이 더는 안 뜨는 권한은 앱 설정 화면으로 보낸다.
 * (Android 11+ 의 백그라운드 위치는 시스템이 원래 설정 화면으로 보낸다 — 거기서 「항상 허용」)
 */
class Asker(
    private val act: Activity,
    private val launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
) {
    private val prefs = act.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun ask(vararg perms: String) {
        val first = perms.first()
        val asked = prefs.getBoolean("asked_$first", false)
        if (asked && !act.shouldShowRequestPermissionRationale(first)) openAppSettings(act)
        else {
            prefs.edit().putBoolean("asked_$first", true).apply()
            try { launcher.launch(arrayOf(*perms)) } catch (e: Exception) { openAppSettings(act) }
        }
    }

    fun location() = ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun background() = ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun bluetooth() { if (Build.VERSION.SDK_INT >= 31) ask(Manifest.permission.BLUETOOTH_CONNECT) }
    fun notifications() { if (Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.POST_NOTIFICATIONS) }
}

fun openAppSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
    } catch (_: Exception) {}
}

fun openLocationSettings(ctx: Context) {
    try { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { openAppSettings(ctx) }
}

/**
 * 핀백 한 화면.
 *   커버(좁음) : 머리줄 → 목록 ↔ 상세(한 칸씩, 뒤로 가기로 돌아옴)
 *   펼침(840+) : 머리줄 → 목록 | 상세 두 칸
 * 칸마다 세로 스크롤은 하나. 시트·사진 보기·스낵바는 위에 뜬다.
 */
@Composable
fun PinbackApp(m: AppModel) {
    val ctx = LocalContext.current
    val act = ctx as Activity
    val view = LocalView.current
    val cfg = LocalConfiguration.current
    val twoPane = cfg.screenWidthDp >= TWO_PANE_DP

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        m.permTick++
        m.refreshHere()
        // 저장하려다 권한에 막혔던 거면 허용하자마자 이어서 저장
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true && m.saveError != null) m.saveNow()
    }
    val asker = remember(act) { Asker(act, permLauncher) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> m.onPhotoResult(ok) }
    val takePhoto: (String) -> Unit = { id ->
        val uri = m.newPhotoSlot(id)
        if (uri == null) m.show("사진을 저장할 자리를 만들지 못했어요")
        else try { camera.launch(uri) } catch (e: ActivityNotFoundException) {
            m.cancelPhotoSlot(uri); m.show("카메라 앱을 열지 못했어요")
        }
    }
    LaunchedEffect(m.photoRequest) { m.photoRequest?.let { m.photoRequest = null; takePhoto(it) } }

    // 저장되는 순간을 손으로 느끼게
    LaunchedEffect(m.sheet) { if (m.sheet?.fresh == true) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

    // 「n분 전」 시계
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }

    val places = Store.places
    val selected = Store.get(m.selectedId)
    val narrowDetail = !twoPane && selected != null

    BackHandler(enabled = m.viewer != null || m.sheet != null || m.screen == AppModel.Screen.Settings || narrowDetail) {
        when {
            m.viewer != null -> m.viewer = null
            m.sheet != null -> m.sheet = null
            m.screen == AppModel.Screen.Settings -> m.screen = AppModel.Screen.Home
            else -> m.selectedId = null
        }
    }

    // 인셋은 본문 열에만 준다 — 시트·사진 막은 상태 바까지 덮어야 위쪽 띠가 밝게 남지 않는다
    Box(Modifier.fillMaxSize().background(P.bg)) {
        Column(Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            when {
                m.screen == AppModel.Screen.Settings -> {
                    Header(title = "설정", back = { m.screen = AppModel.Screen.Home }, maxW = 680.dp)
                    SettingsScreen(m, asker, Modifier.weight(1f))
                }
                twoPane -> {
                    Header(onSettings = { m.screen = AppModel.Screen.Settings }, maxW = Dp.Unspecified)
                    val shown = selected ?: places.firstOrNull()
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        ListPane(m, asker, now, selectedId = shown?.id, modifier = Modifier.width(420.dp).fillMaxHeight(), savePrimary = shown == null)
                        Box(Modifier.width(1.dp).fillMaxHeight().background(P.border))
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            if (shown != null) DetailPane(m, shown, now, takePhoto, Modifier.widthIn(max = 640.dp).fillMaxWidth())
                            else DetailEmpty()
                        }
                    }
                }
                narrowDetail -> {
                    Header(title = "장소", back = { m.selectedId = null }, maxW = 600.dp)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        DetailPane(m, selected, now, takePhoto, Modifier.widthIn(max = 600.dp).fillMaxWidth())
                    }
                }
                else -> {
                    Header(onSettings = { m.screen = AppModel.Screen.Settings }, maxW = 600.dp)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        ListPane(m, asker, now, selectedId = null, modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth())
                    }
                }
            }
        }

        m.sheet?.let { s ->
            val p = Store.get(s.id)
            if (p != null) EditSheet(m, p, s.fresh, takePhoto)
            else LaunchedEffect(s) { m.sheet = null }   // 시트를 연 채로 그 장소가 지워졌다
        }
        m.viewer?.let { (id, uri) -> PhotoViewer(m, id, uri) }
        SnackHost(m, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
    }
}

// ───────────────────────── 머리줄 ─────────────────────────

/**
 * 56dp. 홈이면 [표식][핀백] … [설정][테마], 하위 화면이면 [뒤로][제목] … [테마].
 * 안쪽 폭을 본문 열과 맞춰 표식 왼끝·테마 버튼 오른끝이 카드 가장자리와 한 줄에 선다.
 */
@Composable
private fun Header(title: String? = null, back: (() -> Unit)? = null, onSettings: (() -> Unit)? = null, maxW: Dp) {
    Column(Modifier.fillMaxWidth().background(P.bg), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.then(if (maxW != Dp.Unspecified) Modifier.widthIn(max = maxW) else Modifier).fillMaxWidth().height(56.dp)
                .padding(start = if (back != null) 4.dp else 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (back != null) {
                IconBtn("arrow-left", "뒤로", P.text, back)
                Spacer(Modifier.width(2.dp))
            } else {
                Mark(28.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(title ?: "핀백", color = P.text, fontSize = if (title == null) 20.sp else 18.sp, lineHeight = 26.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() })
            if (onSettings != null) IconBtn("settings", "설정 열기", P.text, onSettings)
            Spacer(Modifier.width(4.dp))
            ThemeButton()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
    }
}

// ───────────────────────── 스낵바 ─────────────────────────

@Composable
private fun SnackHost(m: AppModel, modifier: Modifier) {
    val s = m.snack ?: return
    // 되돌리기가 달린 알림은 8초(누를 틈), 글만 있는 건 4초
    LaunchedEffect(s.seq) { delay(if (s.action != null) 8_000 else 4_000); if (m.snack?.seq == s.seq) m.snack = null }
    Row(modifier.padding(horizontal = 16.dp, vertical = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(P.raised).border(1.dp, P.border, RoundedCornerShape(14.dp))
            .heightIn(min = 52.dp).padding(start = 16.dp, end = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically) {
        Text(s.text, color = P.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (s.action != null) {
            Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = s.action) { s.onAction?.invoke(); m.snack = null }
                    .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(s.action, color = P.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 시트·사진 보기 뒤의 막 — 누르면 닫힌다(이름을 준다) */
@Composable
fun Scrim(alpha: Float, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(P.scrim.copy(alpha = alpha))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "닫기", onClick = onDismiss))
}
