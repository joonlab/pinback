package kr.joonlab.pinback

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * 주차 자동 저장 — 차 블루투스가 끊기는 순간 = 시동을 끈 순간으로 보고 그때 위치를 저장한다.
 * (ClipBridge 「주차 위치」에서 옮겨 왔다. 차 이름 하드코딩 대신 설정에서 고른다.)
 */
object Car {
    /**
     * 설정에서 아직 차를 고르지 않았을 때 쓸 기본 블루투스 이름.
     * 빌드 때 `pinback.defaultCarName`(local.properties 또는 gradle 속성)으로 넣는다. 비어 있으면
     * 설정 화면에서 고를 때까지 주차 자동 저장이 동작하지 않는다.
     */
    val DEFAULT_NAME: String = BuildConfig.DEFAULT_CAR_NAME

    private const val PREFS = "car"
    private const val DEBOUNCE_MS = 90_000L          // ACL 끊김은 BR/EDR·LE 로 두 번 올 수 있다

    /** mode: unset(아직 안 고름 → 기본 이름) · set(고른 기기) · off(자동 저장 끔) */
    data class Choice(val mode: String, val name: String, val address: String) {
        val on get() = mode != "off"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun choice(ctx: Context): Choice {
        val p = prefs(ctx)
        val mode = p.getString("mode", "unset") ?: "unset"
        return if (mode == "set") Choice("set", p.getString("name", "") ?: "", p.getString("address", "") ?: "")
        else Choice(mode, DEFAULT_NAME, "")
    }

    fun choose(ctx: Context, name: String, address: String) =
        prefs(ctx).edit().putString("mode", "set").putString("name", name).putString("address", address).apply()

    fun turnOff(ctx: Context) = prefs(ctx).edit().putString("mode", "off").apply()

    /** 끈 것을 다시 켤 때 — 전에 고른 기기가 있으면 그걸로, 없으면 기본 이름으로 */
    fun turnOn(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putString("mode", if (p.getString("address", "").isNullOrEmpty()) "unset" else "set").apply()
    }

    data class Paired(val name: String, val address: String)

    /** 페어링된 블루투스 기기(이름순). 근처 기기 권한이 없으면 null */
    @SuppressLint("MissingPermission")
    fun paired(ctx: Context): List<Paired>? {
        if (!Perms.bluetooth(ctx)) return null
        return try {
            val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
            ad.bondedDevices.orEmpty().map { Paired(it.name ?: it.address, it.address) }.sortedBy { it.name }
        } catch (e: SecurityException) { null }
    }

    fun matches(c: Choice, name: String?, address: String?): Boolean = when {
        !c.on -> false
        c.address.isNotEmpty() -> c.address.equals(address, ignoreCase = true)
        else -> c.name.isNotEmpty() && name != null && name == c.name
    }

    /** ACL_DISCONNECTED 한 건. 내 차가 아니면 무시. */
    fun onDisconnected(ctx: Context, dev: BluetoothDevice?) {
        if (dev == null) return
        val c = choice(ctx)
        if (!c.on) return
        val name = try { dev.name } catch (e: SecurityException) { null }
        val address = try { dev.address } catch (e: SecurityException) { null }
        if (!matches(c, name, address)) return
        synchronized(this) {
            val p = prefs(ctx)
            val now = System.currentTimeMillis()
            if (now - p.getLong("lastTrigger", 0) < DEBOUNCE_MS) {
                Log.i(TAG, "주차: 중복 끊김 무시")
                return
            }
            p.edit().putLong("lastTrigger", now).apply()
        }
        Log.i(TAG, "주차: ${c.name} 연결 끊김 → 위치 저장")
        Locator.capture(ctx, Place.KIND_PARKING) { r ->
            if (r is Locator.Result.Failed) Notify.failed(ctx, r.why)
        }
    }
}

/** 매니페스트 등록판. ACL_DISCONNECTED 는 암시적 브로드캐스트 예외라 앱이 죽어 있어도 온다. */
class ParkingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val dev = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        // 위치 콜백(getCurrentLocation 내부 타임아웃 약 30초)을 다 받을 때까지 프로세스가 살아 있게.
        // 백그라운드 브로드캐스트의 ANR 한도는 60초라 35초면 안전하다(9초면 다듬기 전에 죽을 수 있었다).
        val pr = goAsync()
        Car.onDisconnected(context.applicationContext, dev)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { pr.finish() } }, 35_000)
    }
}

/** 주차 알림 — 길찾기 · 사진 남기기 */
object Notify {
    private const val CHANNEL = "pinback-parking"
    private const val NOTI_ID = 2001

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "주차 자동 저장", NotificationManager.IMPORTANCE_DEFAULT))
        return nm
    }

    private fun navPi(ctx: Context, id: String) = PendingIntent.getActivity(ctx, 1,
        Intent(ctx, NavActivity::class.java).setAction(NavActivity.ACTION_NAVIGATE).putExtra(NavActivity.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun appPi(ctx: Context, action: String, id: String, code: Int) = PendingIntent.getActivity(ctx, code,
        Intent(ctx, MainActivity::class.java).setAction(action).putExtra(MainActivity.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun saved(ctx: Context, p: Place, quiet: Boolean = false) {
        if (!Perms.notifications(ctx)) return
        try {
            val nm = channel(ctx)
            val photo = p.photos.lastOrNull()
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_parking)
                .setContentTitle("주차 위치를 저장했어요 · ${p.name}")
                .setContentIntent(appPi(ctx, MainActivity.ACTION_OPEN, p.id, 3))
                .setOnlyAlertOnce(quiet)
                .setAutoCancel(false)
                .addAction(Notification.Action.Builder(null, "길찾기", navPi(ctx, p.id)).build())
                .addAction(Notification.Action.Builder(null,
                    if (photo == null) "사진 남기기" else "사진 더 찍기",
                    appPi(ctx, MainActivity.ACTION_PHOTO, p.id, 2)).build())
            val bmp = photo?.let { thumbnail(ctx, Uri.parse(it)) }
            if (bmp != null) {
                b.setContentText("오차 약 ${p.acc.toInt()}m · 사진 ${p.photos.size}장")
                    .setLargeIcon(bmp)
                    .setStyle(Notification.BigPictureStyle().bigPicture(bmp))
            } else {
                b.setContentText("오차 약 ${p.acc.toInt()}m · 층·기둥 번호 같은 표지를 사진으로 남겨 두세요")
            }
            nm.notify(NOTI_ID, b.build())
        } catch (e: Exception) {
            Log.w(TAG, "주차 알림 실패: ${e.message}")
        }
    }

    fun failed(ctx: Context, why: String) {
        if (!Perms.notifications(ctx)) return
        try {
            channel(ctx).notify(NOTI_ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle("주차 위치를 저장하지 못했어요")
                .setContentText("$why. 「핀백」 아이콘을 길게 눌러 「지금 위치 저장」을 고르세요.")
                .setContentIntent(appPi(ctx, MainActivity.ACTION_OPEN, "", 4))
                .build())
        } catch (_: Exception) {}
    }

    private fun thumbnail(ctx: Context, uri: Uri): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val s = 1080f / maxOf(info.size.width, info.size.height)
            if (s < 1f) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        Log.w(TAG, "사진 읽기 실패: ${e.message}"); null
    }
}
