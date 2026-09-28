package kr.joonlab.pinback.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joonlab.pinback.AppModel
import kr.joonlab.pinback.BuildConfig
import kr.joonlab.pinback.Car
import kr.joonlab.pinback.Perms

/**
 * 설정: 주차 자동 저장(내 차 고르기) → 권한 4개(상태 + 허용) → 어떻게 동작하나요 → 빌드.
 */
@Composable
fun SettingsScreen(m: AppModel, asker: Asker, modifier: Modifier) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = m.permTick
    var carTick by remember { mutableIntStateOf(0) }   // 고르기·켜고 끄기 뒤 다시 읽기
    val choice = remember(carTick, m.permTick) { Car.choice(ctx) }
    val paired = remember(carTick, m.permTick) { Car.paired(ctx) }
    val nav = WindowInsets.navigationBars.asPaddingValues()

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = nav.calculateBottomPadding() + 32.dp)) {

            // ── 주차 자동 저장 ──
            SectionLabel("주차 자동 저장")
            Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleRow("차에서 내리면 자동으로 저장",
                    "차 블루투스가 끊기는 순간(시동을 끈 순간) 그 자리를 「주차」로 저장하고 알림을 띄워요.",
                    on = choice.on) { on -> if (on) Car.turnOn(ctx) else Car.turnOff(ctx); carTick++ }
            }
            if (choice.on) {
                Spacer(Modifier.height(14.dp))
                SectionLabel("내 차 블루투스")
                when {
                    paired == null -> NoticeBox(P.warn, P.warnTint, "bluetooth", "페어링 목록을 보려면 권한이 필요해요",
                        "「근처 기기」를 허용하면 폰에 연결해 본 블루투스 기기 중에서 차를 고를 수 있어요.",
                        actions = { TextBtn("근처 기기 허용", icon = "bluetooth") { asker.bluetooth() } })
                    paired.isEmpty() -> NoticeBox(P.warn, P.warnTint, "bluetooth", "페어링된 블루투스 기기가 없어요",
                        "폰 설정에서 차와 먼저 블루투스로 연결한 뒤 여기로 돌아와 주세요.")
                    else -> {
                        val none = paired.none { Car.matches(choice, it.name, it.address) }
                        if (none) Text(
                            if (choice.mode == "unset") "아직 고른 차가 없어요. 아래에서 차를 골라 주세요."
                            else "고른 기기(${choice.name})가 페어링 목록에 없어요. 다시 골라 주세요.",
                            color = P.warnText, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                        Column(Modifier.fillMaxWidth().card()) {
                            paired.forEachIndexed { i, d ->
                                if (i > 0) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(P.border))
                                val sel = Car.matches(choice, d.name, d.address)
                                DeviceRow(d.name, d.address, sel) { Car.choose(ctx, d.name, d.address); carTick++ }
                            }
                        }
                        Text("같은 주차가 두 번 저장되지 않게, 끊김이 1분 30초 안에 또 오면 무시해요.", color = P.dim, fontSize = 12.sp,
                            lineHeight = 17.sp, modifier = Modifier.padding(start = 2.dp, top = 8.dp))
                    }
                }
            }

            // ── 권한 ──
            Spacer(Modifier.height(22.dp))
            SectionLabel("권한")
            if (!Perms.locationServiceOn(ctx)) {
                NoticeBox(P.warn, P.warnTint, "alert", "폰의 위치 서비스가 꺼져 있어요", "켜야 장소를 저장할 수 있어요.",
                    actions = { TextBtn("위치 설정 열기", icon = "external") { openLocationSettings(ctx) } })
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.fillMaxWidth().card()) {
                val loc = Perms.location(ctx)
                PermRow("locate", "정확한 위치",
                    if (Perms.coarseOnly(ctx)) "지금은 대략적인 위치만 허용돼 있어요. 「정확한 위치」를 켜 주세요." else "장소를 저장하려면 꼭 필요해요.",
                    loc) { asker.location() }
                Divider()
                PermRow("map-pin", "백그라운드 위치 · 항상 허용",
                    if (loc) "앱을 닫아 둬도 주차를 저장해요. 열리는 화면에서 「항상 허용」을 골라 주세요." else "먼저 「정확한 위치」를 허용해 주세요.",
                    Perms.background(ctx), enabled = loc) { asker.background() }
                if (Build.VERSION.SDK_INT >= 31) {
                    Divider()
                    PermRow("bluetooth", "근처 기기", "차 블루투스가 끊긴 걸 알아채고, 차를 고를 때 목록을 보여 줘요.",
                        Perms.bluetooth(ctx)) { asker.bluetooth() }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    Divider()
                    PermRow("bell", "알림", "주차를 저장하면 길찾기·사진 버튼이 달린 알림을 띄워요.",
                        Perms.notifications(ctx)) { asker.notifications() }
                }
            }

            Spacer(Modifier.height(22.dp))
            HowItWorks()

            Spacer(Modifier.height(20.dp))
            Text("사진은 사진첩 Pictures/핀백 폴더에 저장돼요 · 장소 목록은 이 폰 안에만 있어요\n빌드 ${BuildConfig.BUILD_TIME} · v${BuildConfig.VERSION_NAME}",
                color = P.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
private fun Divider() { Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(P.border)) }

@Composable
private fun DeviceRow(name: String, address: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .background(if (selected) P.accentTint else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Ic(if (selected) "car" else "bluetooth", if (selected) P.accent else P.dim, 20.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = P.text, fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(address, color = P.dim, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        // 라디오 — 켜짐 = accent 채움 + 체크
        Box(Modifier.size(24.dp).clip(CircleShape)
                .background(if (selected) P.accent else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.5.dp, if (selected) P.accent else P.border, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Ic("check", P.onAccent, 15.dp)
        }
    }
}

@Composable
private fun PermRow(icon: String, title: String, sub: String, granted: Boolean, enabled: Boolean = true, onAsk: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Ic(icon, P.dim, 20.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = P.text, fontSize = 15.sp, lineHeight = 20.sp)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (granted) P.ok else P.warn)
                Spacer(Modifier.width(6.dp))
                Text(if (granted) "허용됨" else "꺼져 있음", color = if (granted) P.text else P.warnText, fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium)
            }
            if (!granted) Text(sub, color = P.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (!granted) TextBtn("허용", enabled = enabled, onClick = onAsk)
    }
}

@Composable
private fun HowItWorks() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().card()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(onClickLabel = if (open) "설명 접기" else "설명 펼치기") { open = !open }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("어떻게 동작하나요?", color = P.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Ic("down", P.dim, 18.dp, modifier = Modifier.rotate(if (open) 180f else 0f))
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step("1", "「지금 위치 저장」을 누르면 폰이 방금 알던 위치로 바로 저장하고, 몇 초 안에 더 정확한 위치가 잡히면 그걸로 고쳐요.")
                Step("2", "주차 자동 저장이 켜져 있으면, 고른 차의 블루투스가 끊기는 순간을 시동을 끈 순간으로 보고 저장해요. 지하에선 입구 직전 위치가 오히려 정확해요.")
                Step("3", "길찾기는 국내면 네이버 지도 도보 길찾기, 해외이거나 네이버 지도가 없으면 Google 지도로 열어요.")
                Step("4", "GPS는 층과 기둥을 몰라요. 사진을 남겨 두면 알림과 상세 화면에서 바로 볼 수 있어요.")
                Step("5", "아이콘을 길게 누르면 「지금 위치 저장」, 「마지막 장소로 길찾기」 바로가기가 있어요.")
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp).size(20.dp).background(P.panel2, CircleShape), contentAlignment = Alignment.Center) {
            Text(n, color = P.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = P.text, fontSize = 13.5.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}
