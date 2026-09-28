package kr.joonlab.pinback

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val TAG = "Pinback"

/** 저장한 장소 하나. kind = 직접 저장(manual) · 주차 자동(parking). */
data class Place(
    val id: String,
    val name: String,
    /** 사람이 이름을 아직 안 고쳤다 — 주소를 받아 오면 이름을 주소로 바꿔도 된다 */
    val nameAuto: Boolean,
    val kind: String,
    /** 저장한 시각(버튼을 누른 순간 · 블루투스가 끊긴 순간) */
    val at: Long,
    val lat: Double,
    val lng: Double,
    /** 오차 반경(m) */
    val acc: Double,
    /** 위치가 잡힌 시각 — at 보다 이르면 «마지막으로 알려진 위치» 를 쓴 것 */
    val fixAt: Long,
    val memo: String = "",
    /** MediaStore content:// 주소들(Pictures/핀백) */
    val photos: List<String> = emptyList(),
) {
    val isParking get() = kind == KIND_PARKING

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("nameAuto", nameAuto).put("kind", kind)
        .put("at", at).put("lat", lat).put("lng", lng).put("acc", acc).put("fixAt", fixAt)
        .put("memo", memo).put("photos", JSONArray(photos))

    companion object {
        const val KIND_MANUAL = "manual"
        const val KIND_PARKING = "parking"

        fun fromJson(j: JSONObject) = Place(
            id = j.getString("id"),
            name = j.optString("name"),
            nameAuto = j.optBoolean("nameAuto", false),
            kind = j.optString("kind", KIND_MANUAL),
            at = j.optLong("at"),
            lat = j.getDouble("lat"), lng = j.getDouble("lng"),
            acc = j.optDouble("acc", 0.0),
            fixAt = j.optLong("fixAt"),
            memo = j.optString("memo"),
            photos = j.optJSONArray("photos")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        )

        /** 기본 이름 — 「9월 27일 오후 1:05」 */
        fun timeName(at: Long): String = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(at))

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * 앱 내부 JSON 파일 하나(files/places.json). 목록은 Compose 상태라 바뀌면 화면이 따라 그려진다.
 * 쓰기는 전부 메인 스레드(버튼·브로드캐스트·위치 콜백이 다 메인)에서 한다.
 * 그래도 읽고-고치고-쓰기가 끼어들지 않게 add·update·remove·restore 를 통째로 잠근다.
 */
object Store {
    private const val FILE = "places.json"
    private var loaded = false

    /** 최신순 */
    var places by mutableStateOf<List<Place>>(emptyList())
        private set

    /** 위치를 더 정확하게 다듬는 중인 장소 id */
    var refining by mutableStateOf<Set<String>>(emptySet())

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        places = try {
            val f = file(ctx)
            if (!f.exists()) emptyList() else {
                val a = JSONObject(f.readText()).optJSONArray("places") ?: JSONArray()
                List(a.length()) { a.getJSONObject(it) }.mapNotNull { runCatching { Place.fromJson(it) }.getOrNull() }
                    .sortedByDescending { it.at }
            }
        } catch (e: Exception) {
            // 읽지 못한 파일을 그대로 두면 다음 저장이 빈 목록으로 덮어써 전부 잃는다 → 옆으로 떼어 둔다
            Log.w(TAG, "장소 파일 읽기 실패: ${e.message}")
            runCatching { file(ctx).renameTo(File(file(ctx).parentFile, "$FILE.bad-${System.currentTimeMillis()}")) }
            emptyList()
        }
    }

    @Synchronized
    private fun write(ctx: Context, list: List<Place>) {
        places = list.sortedByDescending { it.at }
        try {
            val j = JSONObject().put("v", 1).put("places", JSONArray(places.map { it.toJson() }))
            // 임시 파일에 쓰고 바꿔치기 — 쓰다 죽어도 이전 파일이 남는다
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            java.io.FileOutputStream(tmp).use { o -> o.write(j.toString().toByteArray()); o.fd.sync() }   // 전원이 나가도 반쯤 쓴 파일이 안 남게
            if (!tmp.renameTo(f)) { f.writeText(j.toString()); tmp.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "장소 파일 쓰기 실패: ${e.message}")
        }
    }

    fun get(id: String?): Place? = if (id == null) null else places.firstOrNull { it.id == id }

    fun latest(): Place? = places.firstOrNull()

    @Synchronized
    fun add(ctx: Context, p: Place) { init(ctx); write(ctx, places + p) }

    @Synchronized
    fun update(ctx: Context, id: String, f: (Place) -> Place) {
        init(ctx)
        if (places.none { it.id == id }) return
        write(ctx, places.map { if (it.id == id) f(it) else it })
    }

    @Synchronized
    fun remove(ctx: Context, id: String): Place? {
        init(ctx)
        val p = get(id) ?: return null
        write(ctx, places.filterNot { it.id == id })
        return p
    }

    /** 실행 취소 — 지운 것을 그대로 되살린다 */
    @Synchronized
    fun restore(ctx: Context, p: Place) { init(ctx); if (get(p.id) == null) write(ctx, places + p) }
}
