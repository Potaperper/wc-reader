package com.potaperper.wereadtv.update

import android.content.Context
import com.potaperper.wereadtv.util.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** 下载线路：直连 + 国内镜像 */
data class UpdateSource(val id: String, val label: String, val prefix: String)

data class SpeedResult(val source: UpdateSource, val kbPerSec: Int, val ok: Boolean)

object UpdateManager {
    private const val API = "https://api.github.com/repos/Potaperper/wc-reader/releases/latest"

    val SOURCES = listOf(
        UpdateSource("direct", "直连 GitHub", ""),
        UpdateSource("imciel", "ghproxy.imciel.com", "https://ghproxy.imciel.com/"),
        UpdateSource("cfd", "ghproxy.cfd", "https://ghproxy.cfd/"),
        UpdateSource("ghproxy_com", "gh-proxy.com", "https://gh-proxy.com/"),
        UpdateSource("ghproxy_net", "ghproxy.net", "https://ghproxy.net/"),
    )

    fun sourceById(id: String): UpdateSource? = SOURCES.firstOrNull { it.id == id }

    /** 当前实际首选线路（auto 时用测速结果） */
    fun preferredSource(ctx: Context): UpdateSource {
        val mode = Theme.updateSource(ctx)
        if (mode != "auto") sourceById(mode)?.let { return it }
        sourceById(Theme.updateFastestSource(ctx))?.let { return it }
        return SOURCES.first()
    }

    fun sourceLabel(ctx: Context): String {
        val mode = Theme.updateSource(ctx)
        if (mode != "auto") return sourceById(mode)?.label ?: "自动"
        val fastest = sourceById(Theme.updateFastestSource(ctx))
        val speed = Theme.updateFastestSpeed(ctx)
        return if (fastest != null && speed > 0) {
            "自动（${fastest.label} · ${speed}KB/s）"
        } else if (fastest != null) {
            "自动（${fastest.label}）"
        } else {
            "自动（首次下载时测速）"
        }
    }

    /** 拉取最新 release 信息；失败返回 null */
    fun fetchLatest(): UpdateInfo? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "WC-reader")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val tag = json.optString("tag_name", "")
        if (tag.isEmpty()) return null
        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    size = a.optLong("size", 0)
                    break
                }
            }
        }
        if (apkUrl.isEmpty()) return null
        return UpdateInfo(
            tag = tag,
            version = tag.trimStart('v'),
            notes = json.optString("body", ""),
            apkUrl = apkUrl,
            sizeBytes = size,
        )
    }

    /** 语义化版本比较：remote 是否比 local 新 */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    // ---------- 测速 ----------

    /** 并行测试所有线路：每个线路最多下载 512KB，统计速度 */
    suspend fun testSources(
        apkUrl: String,
        onStatus: (String) -> Unit = {},
    ): List<SpeedResult> = coroutineScope {
        onStatus("正在测试下载线路…")
        SOURCES.map { src ->
            async(Dispatchers.IO) { testOne(src, apkUrl) }
        }.awaitAll()
    }

    private fun testOne(src: UpdateSource, apkUrl: String): SpeedResult {
        return try {
            val conn = URL(src.prefix + apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "WC-reader")
            conn.setRequestProperty("Range", "bytes=0-524287")
            val code = conn.responseCode
            if (code !in 200..299) return SpeedResult(src, 0, false)
            val start = System.currentTimeMillis()
            var total = 0L
            conn.inputStream.use { ins ->
                val buf = ByteArray(32 * 1024)
                while (total < 512 * 1024) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    if (System.currentTimeMillis() - start > 6000) break
                }
            }
            val seconds = ((System.currentTimeMillis() - start).coerceAtLeast(1)) / 1000.0
            SpeedResult(src, (total / seconds / 1024).toInt(), total > 0)
        } catch (e: Exception) {
            SpeedResult(src, 0, false)
        }
    }

    /** 测速并保存最快的线路 */
    suspend fun measureAndSave(
        ctx: Context,
        apkUrl: String,
        onStatus: (String) -> Unit = {},
    ): SpeedResult? {
        val results = testSources(apkUrl, onStatus)
        val best = results.filter { it.ok }.maxByOrNull { it.kbPerSec }
        if (best != null) {
            Theme.setUpdateFastestSource(ctx, best.source.id)
            Theme.setUpdateFastestLabel(ctx, best.source.label)
            Theme.setUpdateFastestSpeed(ctx, best.kbPerSec)
            Theme.setUpdateFastestTs(ctx, System.currentTimeMillis())
        }
        return best
    }

    // ---------- 下载 ----------

    /**
     * 下载 APK：优先使用已记住的最快线路，失败自动切换其他线路；
     * 单条线路中断会从已下载的字节处续传，最多重试 3 次。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onStatus: (String) -> Unit,
    ): File? {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val file = File(dir, "wc-reader-${info.version}.apk")
        if (file.exists() && file.length() > 0 &&
            (info.sizeBytes == 0L || file.length() == info.sizeBytes)
        ) {
            return file
        }
        file.delete()

        // 自动模式且还没有测速记录：先测速选最快
        var preferred = Theme.updateSource(context)
        if (preferred == "auto" && Theme.updateFastestSource(context).isEmpty()) {
            val best = measureAndSave(context, info.apkUrl) { onStatus(it) }
            preferred = best?.source?.id ?: "direct"
        }
        if (preferred == "auto") preferred = Theme.updateFastestSource(context)

        val ordered = orderedSources(preferred)
        for ((i, src) in ordered.withIndex()) {
            onStatus("线路 ${i + 1}/${ordered.size}：${src.label}")
            if (trySource(src, file, info, onProgress, onStatus)) return file
        }
        return null
    }

    private fun orderedSources(preferredId: String): List<UpdateSource> {
        val first = SOURCES.firstOrNull { it.id == preferredId }
        val rest = SOURCES.filter { it.id != preferredId }
        return if (first != null) listOf(first) + rest else SOURCES
    }

    private fun trySource(
        src: UpdateSource,
        file: File,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onStatus: (String) -> Unit,
    ): Boolean {
        var attempt = 0
        while (attempt < 3) {
            attempt++
            var downloaded = if (file.exists()) file.length() else 0L
            try {
                val conn = URL(src.prefix + info.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "WC-reader")
                if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val append = code == 206 && downloaded > 0
                if (!append && downloaded > 0) {
                    file.delete()
                    downloaded = 0
                }
                val len = conn.contentLengthLong
                val total = if (len > 0) downloaded + len else info.sizeBytes
                conn.inputStream.use { input ->
                    FileOutputStream(file, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastPct = -1
                        val start = System.currentTimeMillis()
                        var lastTick = start
                        var lastBytes = downloaded
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                            if (now - lastTick >= 500) {
                                val kb = (downloaded - lastBytes) * 1000f / (now - lastTick) / 1024f
                                val mb = downloaded / 1024f / 1024f
                                val totalMb = if (info.sizeBytes > 0) info.sizeBytes / 1024f / 1024f else 0f
                                val sizeText = if (totalMb > 0) "%.1f/%.1f MB".format(mb, totalMb) else "%.1f MB".format(mb)
                                onStatus("${src.label} · $sizeText · ${kb.toInt()} KB/s")
                                lastTick = now
                                lastBytes = downloaded
                            }
                        }
                    }
                }
                val ok = file.length() > 0 &&
                    (info.sizeBytes <= 0 || file.length() == info.sizeBytes) &&
                    isZip(file)
                if (ok) return true
            } catch (e: Exception) {
                // 连接中断/超时：保留已下载部分，下一轮续传
            }
            val have = if (file.exists()) file.length() else 0L
            if (info.sizeBytes > 0 && have >= info.sizeBytes && isZip(file)) return true
            if (have == 0L) continue
        }
        return false
    }

    private fun isZip(file: File): Boolean = try {
        file.inputStream().use { ins ->
            val b = ByteArray(2)
            ins.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        }
    } catch (e: Exception) {
        false
    }
}
