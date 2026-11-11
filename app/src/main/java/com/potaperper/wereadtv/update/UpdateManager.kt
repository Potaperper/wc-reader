package com.potaperper.wereadtv.update

import android.content.Context
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

object UpdateManager {
    private const val API = "https://api.github.com/repos/Potaperper/wc-reader/releases/latest"

    /** 国内可用的 GitHub 资源加速前缀（失败会自动切换） */
    private val MIRROR_PREFIXES = listOf(
        "https://ghproxy.imciel.com/",
        "https://ghproxy.cfd/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

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

    private data class Source(val label: String, val url: String)

    private fun sources(info: UpdateInfo): List<Source> {
        val out = mutableListOf(Source("直连 GitHub", info.apkUrl))
        for (p in MIRROR_PREFIXES) {
            out.add(Source(p.removePrefix("https://").removeSuffix("/"), p + info.apkUrl))
        }
        return out
    }

    /**
     * 下载 APK：依次尝试直连与多个加速镜像；
     * 单条线路中断会从已下载的字节处续传，最多重试 3 次。
     */
    fun download(
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

        val list = sources(info)
        for ((i, src) in list.withIndex()) {
            onStatus("线路 ${i + 1}/${list.size}：${src.label}")
            if (trySource(src, file, info, onProgress, onStatus)) return file
        }
        return null
    }

    private fun trySource(
        src: Source,
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
                val conn = URL(src.url).openConnection() as HttpURLConnection
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
                // 校验：大小一致，且 APK 是 zip（PK 开头）
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
