package com.potaperper.wereadtv.update

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

object UpdateManager {
    private const val API = "https://api.github.com/repos/Potaperper/wc-reader/releases/latest"

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

    /** 下载 APK 到应用目录，onProgress 回调 0-100 */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val file = File(dir, "wc-reader-${info.version}.apk")
        if (file.exists() && file.length() > 0 && (info.sizeBytes == 0L || file.length() == info.sizeBytes)) {
            return file
        }
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "WC-reader")
        conn.instanceFollowRedirects = true
        return try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            conn.inputStream.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = (copied * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (file.length() > 0) file else null
        } catch (e: Exception) {
            file.delete()
            null
        }
    }
}
