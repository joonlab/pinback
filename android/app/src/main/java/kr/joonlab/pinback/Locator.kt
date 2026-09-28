package kr.joonlab.pinback

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.URLEncoder
import java.util.Locale

/** 권한 확인 한 곳 */
object Perms {
    fun location(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun coarseOnly(ctx: Context) = !location(ctx) && granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun background(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    /** Android 11 은 근처 기기 권한이 없다(설치 권한 BLUETOOTH 로 충분) */
    fun bluetooth(ctx: Context) = Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun locationServiceOn(ctx: Context) =
        ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}

/**
 * 위치 잡기 — ClipBridge 「주차 위치」에서 검증된 두 단계 방식을 그대로 쓴다.
 *   1) 즉시: 마지막으로 알려진 위치. 지하 주차장에서는 이게 오히려 정확하다(입구 직전 GPS)
 *   2) 새로: getCurrentLocation. 정확도가 더 좋거나 1) 이 5분 넘게 묵었으면 교체
 *
 * 직접 저장은 조금 다르다: 1) 이 2분 넘게 묵었으면 엉뚱한 곳일 수 있어 먼저 저장하지 않고 2) 를 기다린다.
 */
object Locator {
    private const val STALE_MS = 5 * 60_000L
    private const val MANUAL_FRESH_MS = 2 * 60_000L
    /** 새 위치를 못 잡았을 때 직접 저장이 받아 줄 마지막 위치의 나이 한도 */
    private const val MANUAL_FALLBACK_MS = 30 * 60_000L

    sealed interface Result {
        data class Saved(val id: String) : Result
        data class Failed(val why: String) : Result
    }

    private fun providers(lm: LocationManager): List<String> {
        val all = lm.allProviders
        return listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in all }
    }

    /** 가장 최근에 알려진 위치(권한 없으면 null). 목록의 «여기서 거리» 에 쓴다. */
    fun lastKnown(ctx: Context): Location? {
        if (!Perms.location(ctx) && !Perms.coarseOnly(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return try {
            providers(lm).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }

    /**
     * 지금 위치를 장소로 저장한다. [onDone] 은 메인 스레드에서 한 번 불린다(저장된 순간 또는 실패).
     * 저장 뒤에도 더 정확한 위치를 기다리는 동안 [Store.refining] 에 id 가 들어 있다.
     */
    fun capture(ctx: Context, kind: String, onDone: (Result) -> Unit = {}) {
        val app = ctx.applicationContext
        Store.init(app)
        if (!Perms.location(app)) {
            Log.w(TAG, "위치 권한 없음")
            onDone(Result.Failed("위치 권한이 필요해요")); return
        }
        val lm = app.getSystemService(LocationManager::class.java)
        val ps = providers(lm)
        val startedAt = System.currentTimeMillis()
        val last = ps.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val parking = kind == Place.KIND_PARKING

        val id: String? = if (last != null && (parking || startedAt - last.time <= MANUAL_FRESH_MS))
            add(app, kind, last, startedAt).also { onDone(Result.Saved(it)) } else null

        val provider = ps.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) {
            if (id == null) onDone(Result.Failed("폰의 위치 서비스가 꺼져 있어요"))
            else finish(app, id)
            return
        }
        id?.let { Store.refining = Store.refining + it }
        try {
            // 내부 타임아웃(약 30초)이 있어 끝나면 null 로 온다
            lm.getCurrentLocation(provider, null, app.mainExecutor) { fresh ->
                val cur = Store.get(id)
                if (id != null) {
                    val better = fresh != null && (cur == null ||
                        fresh.accuracy <= cur.acc || (last != null && startedAt - last.time > STALE_MS))
                    if (better) {
                        Store.update(app, id) { it.copy(lat = fresh.latitude, lng = fresh.longitude, acc = fresh.accuracy.toDouble(), fixAt = fresh.time) }
                        Log.i(TAG, "위치 다듬음 ($provider, ±${fresh.accuracy.toInt()}m)")
                    } else Log.i(TAG, "새 위치가 더 부정확 — 기존 유지")
                    finish(app, id)
                } else {
                    val loc = fresh ?: last?.takeIf { startedAt - it.time <= MANUAL_FALLBACK_MS }
                    if (loc == null) onDone(Result.Failed("위치를 잡지 못했어요"))
                    else {
                        val nid = add(app, kind, loc, startedAt)
                        onDone(Result.Saved(nid))
                        finish(app, nid)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocation 실패 ${e.message}")
            if (id == null) onDone(Result.Failed("위치를 잡지 못했어요")) else finish(app, id)
        }
    }

    /**
     * 이미 저장한 장소의 위치를 다시 잡는다(오차가 클 때). 새 장소를 만들지 않으므로 목록에 중복이 안 생긴다.
     * [onDone] 은 메인 스레드에서 한 번: true = 더 정확한 위치로 고침, false = 못 잡았거나 더 나쁘다.
     */
    fun refine(ctx: Context, id: String, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        if (!Perms.location(app) || Store.get(id) == null || id in Store.refining) { onDone(false); return }
        val lm = app.getSystemService(LocationManager::class.java)
        val provider = providers(lm).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) { onDone(false); return }
        Store.refining = Store.refining + id
        try {
            lm.getCurrentLocation(provider, null, app.mainExecutor) { fresh ->
                val cur = Store.get(id)
                val better = fresh != null && cur != null && fresh.accuracy < cur.acc
                if (better) Store.update(app, id) {
                    it.copy(lat = fresh!!.latitude, lng = fresh.longitude, acc = fresh.accuracy.toDouble(), fixAt = fresh.time)
                }
                finish(app, id)
                onDone(better)
            }
        } catch (e: Exception) {
            Log.w(TAG, "다시 잡기 실패 ${e.message}")
            Store.refining = Store.refining - id
            onDone(false)
        }
    }

    private fun add(ctx: Context, kind: String, loc: Location, at: Long): String {
        val p = Place(
            id = Place.newId(), name = Place.timeName(at), nameAuto = true, kind = kind, at = at,
            lat = loc.latitude, lng = loc.longitude, acc = loc.accuracy.toDouble(), fixAt = loc.time,
        )
        Store.add(ctx, p)
        Log.i(TAG, "장소 저장 ($kind, ±${loc.accuracy.toInt()}m)")
        if (p.isParking) Notify.saved(ctx, p)
        return p.id
    }

    /** 위치가 확정됐다 — 다듬기 표시를 끄고, 주소를 받아 이름을 붙이고, 주차면 알림을 고친다 */
    private fun finish(ctx: Context, id: String) {
        Store.refining = Store.refining - id
        name(ctx, id)
        Store.get(id)?.let { if (it.isParking) Notify.saved(ctx, it, quiet = true) }
    }

    // ── 역지오코딩 ──────────────────────────────────────────────────────────

    /** 주소를 받으면(네트워크 필요) 이름을 주소로. 사람이 이름을 이미 고쳤으면 건드리지 않는다. */
    fun name(ctx: Context, id: String) {
        val p = Store.get(id) ?: return
        if (!p.nameAuto || !Geocoder.isPresent()) return
        val main = Handler(Looper.getMainLooper())
        val apply = { a: Address? ->
            val label = a?.let { shortAddress(it) }
            if (!label.isNullOrBlank()) main.post {
                Store.update(ctx, id) { if (it.nameAuto) it.copy(name = label) else it }
                Store.get(id)?.let { if (it.isParking) Notify.saved(ctx, it, quiet = true) }
            }
        }
        val g = Geocoder(ctx, Locale.KOREA)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.getFromLocation(p.lat, p.lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = apply(addresses.firstOrNull())
                    override fun onError(errorMessage: String?) { Log.i(TAG, "주소 못 받음: $errorMessage") }
                })
            } else Thread {
                @Suppress("DEPRECATION")
                val a = runCatching { g.getFromLocation(p.lat, p.lng, 1)?.firstOrNull() }.getOrNull()
                apply(a)
            }.start()
        } catch (e: Exception) {
            Log.i(TAG, "주소 못 받음: ${e.message}")
        }
    }

    /** 「대한민국 서울특별시 강남구 역삼동 123-4」 → 「서울특별시 강남구 역삼동 123-4」 */
    private fun shortAddress(a: Address): String? {
        var line = a.getAddressLine(0) ?: return a.featureName
        // 나라 이름(앞 「대한민국 …」·뒤 「…, 일본」)과 우편번호는 이름으로 쓸 때 군더더기다
        // Geocoder 는 나라 이름을 요청 언어(「일본」)와 현지 언어(「Japan」) 중 아무거나 붙인다 → 둘 다 떼어 낸다
        val countries = listOfNotNull(a.countryName, a.countryCode?.let { Locale("", it).getDisplayCountry(Locale.KOREA) },
            a.countryCode?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH) }).filter { it.isNotBlank() }.distinct()
        for (c in countries) line = line.trim().trim(',').trim().removePrefix(c).removeSuffix(c)
        a.postalCode?.takeIf { it.isNotBlank() }?.let { z -> line = line.replace(z, "") }
        line = line.replace(Regex("""\s*,\s*(,\s*)+"""), ", ").replace(Regex("""\s{2,}"""), " ")
            .trim().trim(',', ' ', '〒')
        return line.ifBlank { null }
    }
}

/** 지도 앱 넘기기 — 길찾기·공유 */
object Maps {
    /** 대략 한반도 남쪽 사각형. 네이버 지도 길찾기는 국내 전용이다. */
    private fun inKorea(lat: Double, lng: Double) = lat in 33.0..38.7 && lng in 124.5..131.0

    private fun installed(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    /** 국내 = 네이버 지도 도보 길찾기 · 해외나 미설치 = Google 지도 도보 · 둘 다 없으면 geo: */
    fun navigateIntent(ctx: Context, p: Place): Intent {
        val name = URLEncoder.encode(p.name.ifBlank { "저장한 장소" }, "UTF-8")
        return when {
            inKorea(p.lat, p.lng) && installed(ctx, "com.nhn.android.nmap") ->
                Intent(Intent.ACTION_VIEW, Uri.parse(
                    "nmap://route/walk?dlat=${p.lat}&dlng=${p.lng}&dname=$name&appname=${ctx.packageName}"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            installed(ctx, "com.google.android.apps.maps") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lng}&mode=w"))
                    .setPackage("com.google.android.apps.maps")
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}($name)"))
        }
    }

    /** 공유용 글 — 받는 사람이 무슨 지도를 쓰든 열리게 링크 두 개 */
    fun shareText(p: Place): String {
        val ll = "%.6f,%.6f".format(Locale.US, p.lat, p.lng)
        return buildString {
            appendLine(p.name)
            if (p.memo.isNotBlank()) appendLine(p.memo)
            appendLine("https://www.google.com/maps/search/?api=1&query=$ll")
            append("https://map.naver.com/p/search/$ll")
        }
    }

    /** 지금 위치에서 장소까지(m). 모르면 null */
    fun distance(from: Location?, p: Place): Float? {
        if (from == null) return null
        val r = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, p.lat, p.lng, r)
        return r[0]
    }
}
