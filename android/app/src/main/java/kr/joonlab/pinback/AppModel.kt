package kr.joonlab.pinback

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

/** 화면 상태 한 곳. 액티비티는 접고 펴도 다시 안 만들어지지만(configChanges) 그래도 ViewModel 에 둔다. */
class AppModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()

    enum class Screen { Home, Settings }

    /** 이름·메모 시트. fresh = 방금 저장해서 연 것(제목이 「저장했어요」) */
    data class Sheet(val id: String, val fresh: Boolean)

    data class Snack(val seq: Long, val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

    var screen by mutableStateOf(Screen.Home)
    var selectedId by mutableStateOf<String?>(null)
    var sheet by mutableStateOf<Sheet?>(null)
    var saving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var snack by mutableStateOf<Snack?>(null)
    /** 사진 크게 보기(장소 id, 사진 주소) */
    var viewer by mutableStateOf<Pair<String, String>?>(null)
    /** 이 장소에 카메라를 띄워 달라는 요청(알림 「사진 남기기」) — 화면이 한 번 쓰고 비운다 */
    var photoRequest by mutableStateOf<String?>(null)
    /** 목록의 «여기서 거리» 기준 */
    var here by mutableStateOf<Location?>(null)
    /** 권한 상태를 다시 읽게 하는 박자(onResume·권한 창에서 돌아올 때 올린다) */
    var permTick by mutableIntStateOf(0)

    init { Store.init(ctx) }

    fun onResume() {
        permTick++
        refreshHere()
    }

    fun refreshHere() { here = Locator.lastKnown(ctx) }

    fun open(id: String?) {
        if (id.isNullOrEmpty() || Store.get(id) == null) return
        screen = Screen.Home
        selectedId = id
    }

    fun saveNow() {
        if (saving) return
        screen = Screen.Home
        saveError = null
        if (!Perms.location(ctx)) {
            saveError = if (Perms.coarseOnly(ctx)) "대략적인 위치만 허용돼 있어요. 「정확한 위치」를 허용해 주세요." else "위치 권한이 필요해요."
            return
        }
        if (!Perms.locationServiceOn(ctx)) { saveError = "폰의 위치 서비스가 꺼져 있어요."; return }
        saving = true
        Locator.capture(ctx, Place.KIND_MANUAL) { r ->
            saving = false
            when (r) {
                is Locator.Result.Saved -> {
                    selectedId = r.id
                    sheet = Sheet(r.id, fresh = true)
                    refreshHere()
                }
                is Locator.Result.Failed -> saveError = "${r.why}. 하늘이 보이는 곳에서 다시 눌러 보세요."
            }
        }
    }

    /** 오차가 큰 장소의 위치만 다시 잡는다(새 장소를 만들지 않는다) */
    fun refine(id: String) {
        show("위치를 다시 잡는 중이에요. 하늘이 보이는 곳이면 더 빨라요.")
        Locator.refine(ctx, id) { ok ->
            val acc = Store.get(id)?.acc?.toInt()
            show(if (ok) "더 정확한 위치로 고쳤어요 · 오차 약 ${acc}m" else "더 정확한 위치를 잡지 못했어요. 그대로 둘게요.")
            refreshHere()
        }
    }

    fun rename(id: String, name: String, memo: String) {
        Store.update(ctx, id) {
            val n = name.trim()
            if (n.isEmpty() || n == it.name) it.copy(memo = memo.trim())
            else it.copy(name = n, nameAuto = false, memo = memo.trim())
        }
    }

    fun delete(id: String) {
        val p = Store.remove(ctx, id) ?: return
        if (selectedId == id) selectedId = null
        show("「${p.name}」 장소를 지웠어요", "실행 취소") {
            Store.restore(ctx, p)
            selectedId = p.id
        }
    }

    fun removePhoto(id: String, uri: String) {
        Store.update(ctx, id) { it.copy(photos = it.photos - uri) }
        viewer = null
        show("사진을 이 장소에서 뺐어요 · 사진첩(Pictures/핀백)에는 남아 있어요", "실행 취소") {
            Store.update(ctx, id) { if (uri in it.photos) it else it.copy(photos = it.photos + uri) }
        }
    }

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        snack = Snack(System.nanoTime(), text, action, onAction)
    }

    // ── 사진: MediaStore Pictures/핀백 ─────────────────────────────────────

    private val uiPrefs get() = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** 카메라가 쓰는 동안 프로세스가 죽어도 잃지 않게 prefs 에 둔다 */
    private fun setPending(id: String?, uri: Uri?) =
        uiPrefs.edit().putString("camId", id).putString("camUri", uri?.toString()).apply()

    /** 카메라에 넘길 빈 사진 자리를 만든다. 실패하면 null */
    fun newPhotoSlot(id: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pinback_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/핀백")
        }
        val uri = try { ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) } catch (e: Exception) { null }
        setPending(if (uri != null) id else null, uri)
        return uri
    }

    fun onPhotoResult(ok: Boolean) {
        val id = uiPrefs.getString("camId", null)
        val uri = uiPrefs.getString("camUri", null)?.let(Uri::parse)
        setPending(null, null)
        if (id == null || uri == null) return
        // 성공 신호를 믿지 않는다 — 실제로 바이트가 써졌는지 본다
        val size = try { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L } catch (e: Exception) { 0L }
        if (ok && size > 0 && Store.get(id) != null) {
            Store.update(ctx, id) { it.copy(photos = it.photos + uri.toString()) }
            Store.get(id)?.let { if (it.isParking) Notify.saved(ctx, it, quiet = true) }
            show("사진을 남겼어요")
        } else {
            // 빈 자리는 치운다(우리 앱이 방금 만든 빈 항목이다)
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            Log.i(TAG, "사진 취소 (ok=$ok, size=$size)")
        }
    }

    fun cancelPhotoSlot(uri: Uri) {
        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        setPending(null, null)
    }
}
