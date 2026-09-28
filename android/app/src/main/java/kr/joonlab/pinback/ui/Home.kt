package kr.joonlab.pinback.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joonlab.pinback.AppModel
import kr.joonlab.pinback.Car
import kr.joonlab.pinback.Maps
import kr.joonlab.pinback.Perms
import kr.joonlab.pinback.Place
import kr.joonlab.pinback.Store
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 숫자(분·m)가 흔들리지 않게 고정폭 숫자 */
internal val TNUM @Composable get() = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")

/**
 * 홈 목록: 「지금 위치 저장」 큰 버튼 → (권한·주차 경고) → 저장한 장소(최신순).
 * 목록 전체가 LazyColumn 하나 — 커버 화면·큰 글꼴에서도 잘리지 않는다.
 */
@Composable
fun ListPane(m: AppModel, asker: Asker, now: Long, selectedId: String?, modifier: Modifier, savePrimary: Boolean = true) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = m.permTick   // 권한이 바뀌면 다시 읽는다
    val places = Store.places
    val nav = WindowInsets.navigationBars.asPaddingValues()
    val state = rememberLazyListState()
    // 새 장소가 맨 위에 붙을 때 — 사람이 맨 위를 보고 있을 때만 따라간다
    LaunchedEffect(places.firstOrNull()?.id) {
        if (state.firstVisibleItemIndex <= 1) state.animateScrollToItem(0)
    }

    val hasLoc = Perms.location(ctx)
    val carWarn = run {
        val c = Car.choice(ctx)
        val active = when (c.mode) {
            "set" -> true
            "unset" -> Car.DEFAULT_NAME.isNotEmpty() && Car.paired(ctx)?.any { it.name == Car.DEFAULT_NAME } == true
            else -> false
        }
        if (!active || !hasLoc) null else buildList {
            if (!Perms.background(ctx)) add("백그라운드 위치(항상 허용)")
            if (!Perms.bluetooth(ctx)) add("근처 기기")
        }.takeIf { it.isNotEmpty() }
    }

    LazyColumn(modifier, state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = nav.calculateBottomPadding() + 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "save") { SaveBlock(m, asker, hasLoc, savePrimary) }
        if (carWarn != null) item(key = "carwarn") {
            NoticeBox(P.warn, P.warnTint, "car", "주차 자동 저장이 멈춰 있어요",
                "${carWarn.joinToString(" · ")} 권한이 없어요. 차에서 내릴 때 저장되지 않아요.",
                actions = { TextBtn("설정에서 허용", icon = "settings") { m.screen = AppModel.Screen.Settings } })
        }
        if (places.isEmpty()) {
            item(key = "empty") { EmptyState() }
        } else {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("저장한 장소 ${places.size}곳", Modifier.weight(1f))
                }
            }
            items(places, key = { it.id }) { p ->
                PlaceRow(p, now, Maps.distance(m.here, p), refining = p.id in Store.refining, selected = p.id == selectedId) {
                    m.selectedId = p.id
                }
            }
        }
    }
}

@Composable
/**
 * [primary] = accent 채움. 펼친 두 칸에서 옆 상세의 「길찾기」가 이미 채움이면 한 화면에 채움 버튼이 둘이 되므로
 * 이 버튼은 물든 면(accentTint + accent 테두리·글자)으로 한 단계 내린다(가이드 「한 화면에 채움 버튼은 하나」).
 */
private fun SaveBlock(m: AppModel, asker: Asker, hasLoc: Boolean, primary: Boolean) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 한 화면에 accent 로 채운 버튼은 이것 하나
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(30.dp))
                .background(if (m.saving || !primary) P.accentTint else P.accent)
                .border(1.dp, P.accent, RoundedCornerShape(30.dp))
                .clickable(enabled = !m.saving, role = Role.Button, onClickLabel = "지금 위치 저장") { m.saveNow() }
                .semantics { if (m.saving) stateDescription = "위치 잡는 중" }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            if (m.saving) {
                Ic("loader", P.accent, 20.dp, spin = true)
                Text("위치 잡는 중…", color = P.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            } else {
                val fg = if (primary) P.onAccent else P.accent
                Ic("map-pin-plus", fg, 21.dp)
                Text("지금 위치 저장", color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (m.saving) Text("바로 잡히면 곧 저장돼요. 실내나 지하에선 30초까지 걸릴 수 있어요.",
            color = P.dim, fontSize = 12.5.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        when {
            m.saveError != null -> NoticeBox(P.warn, P.warnTint, "alert", "저장하지 못했어요", m.saveError!!,
                trailing = { IconBtn("x", "안내 닫기", P.dim) { m.saveError = null } },
                actions = {
                    when {
                        !hasLoc -> TextBtn("위치 허용", icon = "locate") { asker.location() }
                        !Perms.locationServiceOn(ctx) -> TextBtn("위치 설정 열기", icon = "external") { openLocationSettings(ctx) }
                        else -> TextBtn("다시 시도", icon = "map-pin-plus") { m.saveNow() }
                    }
                })
            !hasLoc -> NoticeBox(P.warn, P.warnTint, "locate", "위치 권한이 필요해요",
                "지금 있는 곳을 저장하려면 「정확한 위치」 허용이 필요해요.",
                actions = { TextBtn("위치 허용", icon = "locate") { asker.location() } })
        }
    }
}

@Composable
private fun PlaceRow(p: Place, now: Long, dist: Float?, refining: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth()
            .card(tint = if (selected) P.accentTint else androidx.compose.ui.graphics.Color.Transparent,
                outline = if (selected) P.accent.copy(alpha = .6f) else null)
            .clickable(onClickLabel = "${p.name} 열기", onClick = onClick)) {
        if (selected) Box(Modifier.matchParentSize()) { Box(Modifier.width(3.dp).fillMaxHeight().background(P.accent)) }
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(if (p.isParking) P.accentTint else P.panel2),
                contentAlignment = Alignment.Center) {
                Ic(if (p.isParking) "car" else "map-pin", if (p.isParking) P.accent else P.dim, 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = P.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(listOfNotNull(ago(p.at, now), dist?.let { distPhrase(it) }).joinToString(" · "),
                        color = P.dim, fontSize = 12.5.sp, lineHeight = 18.sp, style = TNUM, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (p.photos.isNotEmpty()) Ic("camera", P.dim, 13.dp)
                    if (refining) Ic("loader", P.dim, 13.dp, spin = true)
                }
            }
            // 종류는 왼쪽 타일(차/핀)이 이미 말한다 — 알약은 눈에 띄어야 할 «주차»에만 달아 이름 칸을 넓힌다
            if (p.isParking) { Spacer(Modifier.width(8.dp)); KindPill(p) }
        }
    }
}

@Composable
fun KindPill(p: Place) {
    if (p.isParking) Pill("주차", P.accent, P.accentTint)
    else Pill("직접 저장", P.dim, P.panel2)
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(P.panel2), contentAlignment = Alignment.Center) {
            Ic("map-pin", P.dim, 26.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text("아직 저장한 장소가 없어요", color = P.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("차를 댄 곳, 처음 와 본 가게, 약속 장소처럼\n다시 찾아갈 곳에서 「지금 위치 저장」을 눌러 보세요.\n아이콘을 길게 눌러도 바로 저장돼요.",
            color = P.dim, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun DetailEmpty() {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(P.panel2), contentAlignment = Alignment.Center) {
            Ic("navigation", P.dim, 24.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text("장소를 저장하면 여기서 길찾기·사진·메모를 볼 수 있어요", color = P.dim, fontSize = 14.sp, lineHeight = 21.sp,
            textAlign = TextAlign.Center)
    }
}

// ───────────────────────── 글 도우미 ─────────────────────────

fun ago(at: Long, now: Long): String {
    val d = (now - at).coerceAtLeast(0)
    val min = d / 60_000
    val c = Calendar.getInstance().apply { timeInMillis = now }
    val t = Calendar.getInstance().apply { timeInMillis = at }
    val sameYear = c.get(Calendar.YEAR) == t.get(Calendar.YEAR)
    c.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = c.get(Calendar.YEAR) == t.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
    return when {
        min < 1 -> "방금"
        min < 60 -> "${min}분 전"
        min < 24 * 60 && !yesterday -> "${min / 60}시간 전"
        yesterday -> "어제 " + SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(at))
        sameYear -> SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(at))
        else -> SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date(at))
    }
}

/** 목록·상세의 거리 말. 30m 안이면 숫자 대신 「지금 이 근처」 — 0m·3m 는 위치 오차보다 작아 뜻이 없다 */
fun distPhrase(m: Float): String = if (m < 30) "지금 이 근처" else "여기서 ${distText(m)}"

fun distText(m: Float): String = when {
    m < 100 -> "${m.toInt()}m"
    m < 1000 -> "${(m / 10).toInt() * 10}m"
    m < 100_000 -> "%.1fkm".format(Locale.KOREA, m / 1000)
    else -> "${(m / 1000).toInt()}km"
}

fun fullTime(at: Long): String = SimpleDateFormat("yyyy년 M월 d일 a h:mm", Locale.KOREA).format(Date(at))
