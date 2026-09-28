package kr.joonlab.pinback.ui

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.joonlab.pinback.AppModel
import kr.joonlab.pinback.Maps
import kr.joonlab.pinback.Place
import kr.joonlab.pinback.Store
import java.util.Locale

/**
 * 장소 상세: 이름·종류·시각 → 길찾기(주 버튼) → 사진·공유·고치기 → 메모 → 사진 → 위치 정보 → 지우기.
 * 세로 스크롤 하나.
 */
@Composable
fun DetailPane(m: AppModel, p: Place, now: Long, takePhoto: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val nav = WindowInsets.navigationBars.asPaddingValues()
    val dist = Maps.distance(m.here, p)
    val refining = p.id in Store.refining

    Column(modifier.verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = nav.calculateBottomPadding() + 96.dp)) {
        // 이름 · 종류
        Row(verticalAlignment = Alignment.Top) {
            Text(p.name, color = P.text, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
            Spacer(Modifier.width(10.dp))
            Box(Modifier.padding(top = 4.dp)) { KindPill(p) }
        }
        Spacer(Modifier.height(6.dp))
        Text(listOfNotNull("${ago(p.at, now)} 저장", dist?.let { distPhrase(it) }).joinToString(" · "),
            color = P.dim, fontSize = 13.sp, lineHeight = 19.sp, style = TNUM)
        if (refining) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Ic("loader", P.accent, 14.dp, spin = true)
            Spacer(Modifier.width(6.dp))
            Text("위치를 더 정확하게 다듬는 중…", color = P.dim, fontSize = 12.5.sp)
        } else if (p.acc >= POOR_ACC_M) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Ic("alert", P.warn, 14.dp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text("오차가 커요(약 ${p.acc.toInt()}m). 사진이나 메모를 남겨 두면 찾기 쉬워요.",
                color = P.warnText, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        }
        if (!refining && p.acc >= POOR_ACC_M && now - p.at < 30 * 60_000L)
            TextBtn("위치 다시 잡기", icon = "locate", modifier = Modifier.padding(top = 8.dp)) { m.refine(p.id) }

        Spacer(Modifier.height(18.dp))
        // 주 버튼
        TextBtn("길찾기", color = P.onAccent, border = P.accent, fill = P.accent, icon = "navigation",
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
            try { ctx.startActivity(Maps.navigateIntent(ctx, p)) }
            catch (e: Exception) { m.show("지도 앱을 열지 못했어요. 네이버 지도나 Google 지도가 있는지 확인해 주세요.") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextBtn("사진", icon = "camera", modifier = Modifier.weight(1f)) { takePhoto(p.id) }
            TextBtn("공유", icon = "share", modifier = Modifier.weight(1f)) { share(ctx, p) }
            TextBtn("고치기", icon = "pencil", modifier = Modifier.weight(1f)) { m.sheet = AppModel.Sheet(p.id, fresh = false) }
        }

        if (p.memo.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().card().padding(14.dp), verticalAlignment = Alignment.Top) {
                Ic("note", P.dim, 16.dp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(10.dp))
                SelectionContainer { Text(p.memo, color = P.text, fontSize = 14.5.sp, lineHeight = 22.sp) }
            }
        }

        // 사진
        Spacer(Modifier.height(22.dp))
        SectionLabel(if (p.photos.isEmpty()) "사진" else "사진 ${p.photos.size}장")
        if (p.photos.isEmpty()) {
            Row(Modifier.fillMaxWidth().card().clickable(onClickLabel = "사진 남기기") { takePhoto(p.id) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(P.panel2), contentAlignment = Alignment.Center) {
                    Ic("camera", P.dim, 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("사진 남기기", color = P.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("층·기둥 번호나 입구 간판을 찍어 두면 다시 찾기 쉬워요. GPS는 층을 모르거든요.",
                        color = P.dim, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.photos.asReversed().forEach { uri ->
                    Thumb(uri, 112) { m.viewer = p.id to uri }
                }
                Box(Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)).background(P.panel)
                        .border(1.dp, P.border, RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = "사진 더 찍기", role = Role.Button) { takePhoto(p.id) },
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Ic("plus", P.dim, 22.dp)
                        Text("더 찍기", color = P.dim, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // 위치 정보
        Spacer(Modifier.height(22.dp))
        SectionLabel("위치")
        Column(Modifier.fillMaxWidth().card().padding(horizontal = 14.dp, vertical = 10.dp)) {
            SelectionContainer {
                InfoLine("locate", "좌표", "%.6f, %.6f".format(Locale.US, p.lat, p.lng), mono = true)
            }
            InfoLine("map-pin", "오차", "약 ${p.acc.toInt()}m")
            InfoLine("clock", "저장", fullTime(p.at))
            if (p.at - p.fixAt > 60_000) {
                Text("저장하기 직전에 폰이 알던 위치를 썼어요(저장보다 ${ago(p.fixAt, p.at)} 위치). 지하 주차장에선 이게 더 정확해요.",
                    color = P.dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 23.dp, top = 4.dp, bottom = 2.dp))
            }
        }

        Spacer(Modifier.height(22.dp))
        TextBtn("이 장소 지우기", color = P.danger, border = P.border, icon = "trash", modifier = Modifier.fillMaxWidth()) {
            m.delete(p.id)
        }
        Text("지운 뒤 잠깐 동안 되돌릴 수 있어요. 사진은 사진첩(Pictures/핀백)에 남아요.", color = P.dim, fontSize = 12.sp,
            lineHeight = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

private fun share(ctx: Context, p: Place) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Maps.shareText(p))
        .putExtra(Intent.EXTRA_SUBJECT, p.name)
    try { ctx.startActivity(Intent.createChooser(i, "장소 공유")) } catch (_: Exception) {}
}

/** 이 이상이면 «오차가 크다» 고 알린다(m) — 주차장 한 칸 줄·건물 한 동을 넘는 수준 */
private const val POOR_ACC_M = 50.0

// ───────────────────────── 이름·메모 시트 ─────────────────────────

/**
 * 저장 직후 뜨는 시트(fresh) 또는 「고치기」. 주소가 늦게 도착하면 — 사람이 이름칸을 안 건드렸을 때만 — 칸도 따라 바뀐다.
 * fresh 시트는 막을 눌러 닫아도 적은 걸 저장한다(이미 저장된 장소를 다듬는 것이므로).
 */
@Composable
fun EditSheet(m: AppModel, p: Place, fresh: Boolean, takePhoto: (String) -> Unit) {
    var name by rememberSaveable(p.id) { mutableStateOf(p.name) }
    var memo by rememberSaveable(p.id) { mutableStateOf(p.memo) }
    var touched by rememberSaveable(p.id) { mutableStateOf(false) }
    LaunchedEffect(p.name) { if (!touched) name = p.name }

    val done = { m.rename(p.id, name, memo); m.sheet = null }
    Box(Modifier.fillMaxSize()) {
        Scrim(.45f) { if (fresh) done() else m.sheet = null }
        Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(P.raised).border(1.dp, P.border, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(P.border, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (fresh) {
                    Box(Modifier.size(28.dp).background(P.ok.copy(alpha = .14f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Ic("check", P.ok, 16.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (fresh) "저장했어요" else "이름·메모 고치기", color = P.text, fontSize = 17.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
            }
            if (fresh) {
                val refining = p.id in Store.refining
                val poor = !refining && p.acc >= POOR_ACC_M
                Text(when {
                        refining -> "오차 약 ${p.acc.toInt()}m · 더 정확한 위치를 잡는 중이에요"
                        poor -> "오차가 커요(약 ${p.acc.toInt()}m) · 하늘이 보이는 곳에서 위치를 다시 잡아 보세요"
                        else -> "오차 약 ${p.acc.toInt()}m · 이름과 메모는 나중에 고쳐도 돼요"
                    }, color = if (poor) P.warnText else P.dim, fontSize = 13.sp,
                    lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
                if (poor) TextBtn("위치 다시 잡기", icon = "locate", modifier = Modifier.padding(top = 10.dp)) { m.refine(p.id) }
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel("이름")
            Field(name, { name = it; touched = true }, "예: 회사 지하 2층", singleLine = true)
            Spacer(Modifier.height(12.dp))
            FieldLabel("메모 (선택)")
            Field(memo, { memo = it }, "예: B-14 기둥 옆, 엘리베이터 오른쪽", singleLine = false)

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fresh) TextBtn("사진 남기기", icon = "camera", modifier = Modifier.weight(1f)) { done(); takePhoto(p.id) }
                else TextBtn("취소", modifier = Modifier.weight(1f)) { m.sheet = null }
                TextBtn(if (fresh) "완료" else "저장", color = P.onAccent, border = P.accent, fill = P.accent, icon = "check",
                    modifier = Modifier.weight(1f)) { done() }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = P.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
}

/** 입력칸 — panel2 면, 모서리 10, 16sp(작으면 폰이 확대한다) */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, singleLine: Boolean) {
    BasicTextField(value, onChange, singleLine = singleLine, minLines = if (singleLine) 1 else 3,
        textStyle = TextStyle(color = P.text, fontSize = 16.sp, lineHeight = 23.sp, fontFamily = Pretendard),
        cursorBrush = SolidColor(P.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(P.panel2)
                    .border(1.dp, P.border, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
                // 여러 줄 칸은 글이 위에서 시작하므로 안내 글도 위에 둔다(가운데 두면 쓰기 시작할 때 글이 튄다)
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Text(hint, color = P.dim, fontSize = 16.sp, lineHeight = 23.sp)
                inner()
            }
        })
}

// ───────────────────────── 사진 ─────────────────────────

private val CACHE = LruCache<String, ImageBitmap>(32)

private data class Loaded(val done: Boolean, val bmp: ImageBitmap?)

/** 긴 변을 maxPx 로 줄여 읽는다(IO 스레드). 사진첩에서 지워졌으면 bmp=null */
@Composable
private fun rememberPhoto(uri: String, maxPx: Int): Loaded {
    val ctx = LocalContext.current
    val key = "$uri@$maxPx"
    return produceState(CACHE.get(key)?.let { Loaded(true, it) } ?: Loaded(false, null), key) {
        if (value.done) return@produceState
        val b = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))) { d, info, _ ->
                    val s = maxPx.toFloat() / maxOf(info.size.width, info.size.height)
                    if (s < 1f) d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                }.asImageBitmap()
            } catch (e: Exception) { null }
        }
        if (b != null) CACHE.put(key, b)
        value = Loaded(true, b)
    }.value
}

@Composable
private fun Thumb(uri: String, sizeDp: Int, onClick: () -> Unit) {
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val ph = rememberPhoto(uri, px)
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(10.dp)).background(P.panel2)
            .border(1.dp, P.border, RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "사진 크게 보기", role = Role.Image, onClick = onClick),
        contentAlignment = Alignment.Center) {
        when {
            ph.bmp != null -> Image(ph.bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            ph.done -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Ic("image", P.dim, 20.dp)
                Text("사진 없음", color = P.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            else -> Ic("loader", P.dim, 18.dp, spin = true)
        }
    }
}

/** 사진 크게 보기 — 검은 막 위에 맞춰 보이기. 「사진첩에서 열기」·「이 장소에서 빼기」 */
@Composable
fun PhotoViewer(m: AppModel, id: String, uri: String) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Scrim(.92f) { m.viewer = null }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val px = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }.coerceAtMost(2400)
            val ph = rememberPhoto(uri, px)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn("x", "사진 닫기", P.onScrim) { m.viewer = null }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    when {
                        ph.bmp != null -> Image(ph.bmp, "저장한 사진", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        ph.done -> Text("사진을 찾을 수 없어요. 사진첩에서 지워졌을 수 있어요.", color = P.onScrim, fontSize = 14.sp,
                            textAlign = TextAlign.Center)
                        else -> Ic("loader", P.onScrim, 22.dp, spin = true)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBtn("사진첩에서 열기", color = P.onScrim, border = P.onScrim.copy(alpha = .4f), icon = "external",
                        modifier = Modifier.weight(1f)) {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: Exception) { m.show("사진을 열 앱이 없어요") }
                    }
                    TextBtn("이 장소에서 빼기", color = P.onScrim, border = P.onScrim.copy(alpha = .4f), icon = "trash",
                        modifier = Modifier.weight(1f)) { m.removePhoto(id, uri) }
                }
            }
        }
    }
}
