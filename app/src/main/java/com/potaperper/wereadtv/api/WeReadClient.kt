package com.potaperper.wereadtv.api

import android.content.Context
import android.util.Log
import com.potaperper.wereadtv.util.Encoding
import com.potaperper.wereadtv.util.WereadCrypto
import com.potaperper.wereadtv.util.WereadDecrypt
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 微信读书 Web API 客户端。
 * 接口与签名/解密算法移植自 touchFish (https://github.com/ylw1997/touchFish)。
 */
class WeReadApi(context: Context) {

    companion object {
        private const val TAG = "WeReadApi"
        private const val BASE = "https://weread.qq.com"
        private const val UA = WereadCrypto.USER_AGENT
        private const val PS_DEVICE = "a2b325707a19e580g0186a2"
        private const val PC_DEVICE = "430321207a19e581g013ab0"
        private val AUTH_ERR_CODES = setOf(-2012, -2013, -12013)
    }

    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val longPollHttp = http.newBuilder()
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val renewMutex = Mutex()

    // ---------- Cookie ----------

    var cookie: String
        get() = appContext.getSharedPreferences("weread", Context.MODE_PRIVATE)
            .getString("cookie", "") ?: ""
        set(value) {
            appContext.getSharedPreferences("weread", Context.MODE_PRIVATE)
                .edit().putString("cookie", value).apply()
        }

    val hasCookie: Boolean get() = cookie.contains("wr_vid=") && cookie.contains("wr_skey=")

    private fun parseCookie(raw: String): Map<String, String> =
        raw.split(";").mapNotNull {
            val idx = it.indexOf('=')
            if (idx <= 0) null else it.substring(0, idx).trim() to it.substring(idx + 1)
        }.toMap()

    private fun stringifyCookie(c: Map<String, String>): String =
        c.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun extractSetCookies(headers: List<String>): Map<String, String> =
        headers.mapNotNull { h ->
            val item = h.substringBefore(";")
            val idx = item.indexOf('=')
            if (idx <= 0) null else item.substring(0, idx).trim() to item.substring(idx + 1)
        }.toMap()

    private fun updateCookie(newValues: Map<String, String>) {
        val merged = parseCookie(cookie).toMutableMap().apply { putAll(newValues) }
        cookie = stringifyCookie(merged)
    }

    // ---------- HTTP 基础 ----------

    private suspend fun get(
        path: String,
        params: Map<String, String> = emptyMap(),
        useCookie: Boolean = true,
        longPoll: Boolean = false,
    ): Pair<List<String>, JSONObject> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(BASE).append(path)
            if (params.isNotEmpty()) {
                append("?")
                append(params.entries.joinToString("&") {
                    "${Encoding.encodeURIComponent(it.key)}=${Encoding.encodeURIComponent(it.value)}"
                })
            }
        }
        val builder = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/web/")
        if (useCookie && cookie.isNotEmpty()) builder.header("Cookie", cookie)
        val client = if (longPoll) longPollHttp else http
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            val json = JSONObject(body)
            if (isAuthFailure(json)) throw AuthExpiredException(json.optString("errMsg"))
            resp.headers("Set-Cookie") to json
        }
    }

    private suspend fun postJson(
        path: String,
        body: JSONObject,
        useCookie: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<List<String>, JSONObject> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(BASE + path)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/web/")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
        if (useCookie && cookie.isNotEmpty()) builder.header("Cookie", cookie)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            val json = try { JSONObject(text) } catch (e: Exception) {
                JSONObject().put("rawText", text)
            }
            if (isAuthFailure(json)) throw AuthExpiredException(json.optString("errMsg"))
            resp.headers("Set-Cookie") to json
        }
    }

    /** 章节接口返回的是加密文本而非 JSON */
    private suspend fun postJsonSignedForText(path: String, payload: JSONObject): String =
        withContext(Dispatchers.IO) {
            val signed = payload.toMap()
            signed["s"] = WereadCrypto.sign(payload.toMap())
            val body = JSONObject(signed as Map<String, Any?>)
            val request = Request.Builder().url(BASE + path)
                .header("User-Agent", UA)
                .header("Referer", "$BASE/web/")
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                // 鉴权失败时服务端可能直接回 JSON 错误
                if (text.trimStart().startsWith("{")) {
                    val j = try { JSONObject(text) } catch (e: Exception) { null }
                    if (j != null && isAuthFailure(j)) throw AuthExpiredException(j.optString("errMsg"))
                }
                text
            }
        }

    private fun JSONObject.toMap(): MutableMap<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        for (key in keys()) m[key] = get(key)
        return m
    }


    // ---------- 鉴权 ----------

    class AuthExpiredException(msg: String) : Exception(msg)

    private fun isAuthFailure(j: JSONObject): Boolean {
        val code = j.optInt("errCode", Int.MIN_VALUE)
        if (code in AUTH_ERR_CODES) return true
        val msg = j.optString("errMsg", "") + j.optString("errLog", "")
        return msg.contains("鉴权失败") || msg.contains("登录授权已过期")
    }

    /**
     * 续期 Cookie。返回是否成功。
     */
    suspend fun renewCookie(): Boolean = renewMutex.withLock {
        try {
            if (cookie.isEmpty()) return false
            Log.i(TAG, "续期 Cookie...")
            val body = JSONObject().apply {
                put("rq", Encoding.encodeURIComponent("/web/book/read"))
                put("ql", false)
            }
            val builder = Request.Builder().url("$BASE/web/login/renewal")
                .header("User-Agent", UA)
                .header("Referer", "$BASE/web/")
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .post(body.toString().toRequestBody(jsonMediaType))
            http.newCall(builder.build()).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optInt("succ", 0) == 1) {
                    val setCookies = extractSetCookies(resp.headers("Set-Cookie"))
                    if (setCookies.isNotEmpty()) updateCookie(setCookies)
                    Log.i(TAG, "Cookie 续期成功")
                    true
                } else {
                    Log.w(TAG, "Cookie 续期失败: $json")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cookie 续期异常: ${e.message}")
            false
        }
    }

    /**
     * 执行 API 调用，鉴权失效时自动续期并重试一次。
     */
    private suspend fun <T> withRetry(call: suspend () -> T): T {
        return try {
            call()
        } catch (e: AuthExpiredException) {
            Log.i(TAG, "鉴权失效，尝试续期: ${e.message}")
            if (renewCookie()) call() else throw e
        }
    }

    // ---------- 扫码登录 ----------

    data class QrLoginSession(val uid: String, val qrUrl: String)

    suspend fun beginQrLogin(): QrLoginSession = withContext(Dispatchers.IO) {
        val (setCookies, json) = get("/api/auth/getLoginUid", useCookie = false)
        updateCookie(extractSetCookies(setCookies))
        val uid = json.optString("uid", json.optJSONObject("data")?.optString("uid") ?: "")
        if (uid.isEmpty()) throw Exception("微信读书登录接口未返回 uid")
        QrLoginSession(uid, "$BASE/web/confirm?uid=${Encoding.encodeURIComponent(uid)}")
    }

    sealed class QrPollResult {
        data object Waiting : QrPollResult()
        data object NeedOtp : QrPollResult()
        data object OtpMismatch : QrPollResult()
        data object Expired : QrPollResult()
        data object Success : QrPollResult()
    }

    suspend fun pollQrLogin(uid: String, otp: String = ""): QrPollResult {
        val params = if (otp.isNotEmpty()) {
            mapOf("uid" to uid, "otp" to otp)
        } else {
            mapOf("uid" to uid, "otp" to "")
        }
        val (setCookies, json) = get(
            "/api/auth/getLoginInfo", params,
            useCookie = cookie.isNotEmpty(), longPoll = true,
        )
        extractSetCookies(setCookies).let { if (it.isNotEmpty()) updateCookie(it) }
        if (json.optInt("errcode", 0) != 0 && json.has("errmsg")) return QrPollResult.Waiting

        val data = json.optJSONObject("data") ?: json
        return when {
            data.optBoolean("succeed") || data.optInt("succeed", 0) == 1 -> {
                val merged = parseCookie(cookie).toMutableMap()
                merged.putAll(extractSetCookies(setCookies))
                data.optString("webLoginVid").takeIf { it.isNotEmpty() }?.let { merged["wr_vid"] = it }
                data.optString("vid").takeIf { it.isNotEmpty() }?.let { merged["wr_vid"] = it }
                data.optString("accessToken").takeIf { it.isNotEmpty() }?.let { merged["wr_skey"] = it }
                data.optString("refreshToken").takeIf { it.isNotEmpty() }?.let { merged["wr_rt"] = it }
                if (!merged.containsKey("wr_vid") || !merged.containsKey("wr_skey")) {
                    throw Exception("扫码已确认，但登录凭据不完整")
                }
                cookie = stringifyCookie(merged)
                renewCookie()
                QrPollResult.Success
            }
            data.optString("logicCode") == "NEED_OTP" -> QrPollResult.NeedOtp
            data.optString("logicCode") == "OTP_NOT_MATCH" -> QrPollResult.OtpMismatch
            data.optString("logicCode") == "LOGIN_TIMEOUT" ||
                data.optString("logicCode") == "OTP_EXPIRED" -> QrPollResult.Expired
            else -> QrPollResult.Waiting
        }
    }

    // ---------- 书架 / 书籍 / 章节 ----------

    /** bookInfo 缓存：书籍的 format 不变，避免每次切章都请求 */
    private val bookInfoCache = mutableMapOf<String, JSONObject>()

    suspend fun shelfSync(): JSONArray = withRetry {
        val (_, json) = get("/web/shelf/sync")
        json.optJSONArray("books") ?: JSONArray()
    }

    /** 书籍详情（含 format），带缓存 */
    suspend fun bookInfo(bookId: String): JSONObject = withRetry {
        bookInfoCache.getOrPut(bookId) {
            val (_, json) = get("/web/book/info", mapOf("bookId" to bookId))
            json
        }
    }

    /** 章节目录 */
    suspend fun chapterInfos(bookId: String): JSONArray = withRetry {
        val body = JSONObject().put("bookIds", JSONArray().put(bookId))
        val (_, json) = postJson("/web/book/chapterInfos", body)
        val data = json.optJSONArray("data")
        if (data != null && data.length() > 0) {
            data.getJSONObject(0).optJSONArray("updated")
                ?: data.getJSONObject(0).optJSONArray("chapters")
                ?: JSONArray()
        } else JSONArray()
    }

    /** 阅读进度 */
    suspend fun getProgress(bookId: String): JSONObject = withRetry {
        val (_, json) = get("/web/book/getProgress", mapOf("bookId" to bookId))
        json
    }

    /** 章节划线（热度） */
    suspend fun underlines(bookId: String, chapterUid: Long): JSONArray = withRetry {
        val (_, json) = get(
            "/web/book/underlines",
            mapOf("bookId" to bookId, "chapterUid" to chapterUid.toString()),
        )
        json.optJSONArray("underlines") ?: JSONArray()
    }

    /** 章节热门想法 */
    suspend fun readReviews(bookId: String, chapterUid: Long, range: String): JSONArray =
        withRetry {
            val reviews = JSONArray().put(
                JSONObject().put("range", range).put("maxIdx", 0).put("count", 30).put("synckey", 0),
            )
            val body = JSONObject()
                .put("bookId", bookId)
                .put("chapterUid", chapterUid)
                .put("reviews", reviews)
            val (_, json) = postJson("/web/book/readReviews", body)
            json.optJSONArray("reviews") ?: JSONArray()
        }

    // ---------- 章节正文（加密接口 + 解密） ----------

    /** 章节内容缓存（bookId:uid -> 内容）：预加载与重复访问零等待 */
    private val chapterCache = object : LinkedHashMap<String, Triple<String, String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<String, String, String>>): Boolean =
            size > 12
    }
    private val chapterInFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Triple<String, String, String>>>()

    private fun chapterKey(bookId: String, uid: Long) = "$bookId:$uid"

    /** 取章节内容：命中缓存直接返回；并发请求同一章会合并 */
    suspend fun chapterContent(bookId: String, chapterUid: Long): Triple<String, String, String> {
        val key = chapterKey(bookId, chapterUid)
        chapterCache[key]?.let { return it }
        val running = synchronized(chapterInFlight) { chapterInFlight[key] }
        if (running != null) return running.await()
        val deferred = scope.async { fetchChapter(bookId, chapterUid) }
        synchronized(chapterInFlight) { chapterInFlight[key] = deferred }
        try {
            val result = deferred.await()
            synchronized(chapterCache) { chapterCache[key] = result }
            return result
        } finally {
            synchronized(chapterInFlight) { chapterInFlight.remove(key) }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private suspend fun fetchChapter(bookId: String, chapterUid: Long): Triple<String, String, String> =
        withRetry {
            val info = bookInfo(bookId)
            val format = info.optString("format", "epub")
            if (format == "epub" || format == "pdf") {
                val e0 = postJsonSignedForText("/web/book/chapter/e_0", chapterSignedPayload(bookId, chapterUid, 0))
                val e1 = postJsonSignedForText("/web/book/chapter/e_1", chapterSignedPayload(bookId, chapterUid, 0))
                val e2 = postJsonSignedForText("/web/book/chapter/e_2", chapterSignedPayload(bookId, chapterUid, 1))
                val e3 = postJsonSignedForText("/web/book/chapter/e_3", chapterSignedPayload(bookId, chapterUid, 0))
                val style = WereadDecrypt.dS(WereadDecrypt.chk(e2))
                val html = WereadDecrypt.dH(
                    WereadDecrypt.chk(e0) + WereadDecrypt.chk(e1) + WereadDecrypt.chk(e3),
                )
                if (html.isEmpty()) throw Exception("章节解密失败($bookId)")
                Triple(html, style, format)
            } else if (format == "txt") {
                val t0 = postJsonSignedForText("/web/book/chapter/t_0", chapterSignedPayload(bookId, chapterUid, 0))
                val t1 = postJsonSignedForText("/web/book/chapter/t_1", chapterSignedPayload(bookId, chapterUid, 1))
                val html = WereadDecrypt.dT(WereadDecrypt.chk(t0) + WereadDecrypt.chk(t1))
                if (html.isEmpty()) throw Exception("章节解密失败($bookId)")
                Triple(html, "", format)
            } else {
                throw Exception("暂不支持 $format 格式($bookId)")
            }
        }

    private fun chapterSignedPayload(bookId: String, chapterUid: Long, st: Int): JSONObject {
        val payload = mutableMapOf<String, Any?>(
            "b" to WereadCrypto.calcHash(bookId),
            "c" to WereadCrypto.calcHash(chapterUid),
            "r" to Math.pow(Random.nextInt(10_000).toDouble(), 2.0).toLong(),
            "st" to st,
            "ct" to WereadCrypto.currentTimeSec(),
            "ps" to PS_DEVICE,
            "pc" to PC_DEVICE,
        )
        return JSONObject(payload)
    }

    // ---------- 进度上报 ----------

    /**
     * 上报阅读进度。pc/ps 为模拟的阅读开始/结束时间（unix 秒），percent 0-100。
     */
    suspend fun reportProgress(
        bookId: String,
        chapterUid: Long,
        percent: Int,
        chapterOffset: Int,
        format: String = "epub",
        isInit: Boolean = false,
        readerToken: String = "",
    ): JSONObject {
        val pc = WereadCrypto.currentTimeSec() - 10
        val ps = pc - Random.nextInt(5) - 5
        val payload = mutableMapOf<String, Any?>(
            "appId" to WereadCrypto.getAppId(UA),
            "b" to WereadCrypto.calcHash(bookId),
            "c" to WereadCrypto.calcHash(chapterUid),
            "ci" to chapterUid,
            "co" to chapterOffset,
            "ct" to WereadCrypto.currentTimeSec(),
            "dy" to 0,
            "fm" to format,
            "pc" to WereadCrypto.calcHash(pc),
            "pr" to percent,
            "ps" to WereadCrypto.calcHash(ps),
            "sm" to "",
        )
        if (!isInit) {
            val ts = System.currentTimeMillis()
            val rn = Random.nextInt(1000)
            payload["rt"] = 60
            payload["ts"] = ts
            payload["rn"] = rn
            payload["sg"] = Encoding.sha256("$ts$rn$readerToken")
        }
        val json = JSONObject(payload)
        json.put("s", WereadCrypto.sign(payload))
        val (_, resp) = postJson("/web/book/read", json)
        return resp
    }
}
