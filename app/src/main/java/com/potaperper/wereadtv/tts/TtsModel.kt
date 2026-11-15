package com.potaperper.wereadtv.tts

import android.content.Context
import com.potaperper.wereadtv.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 离线语音模型（sherpa-onnx VITS，小雅 int8，约 14MB 下载 / 21MB 解压）。
 * 模型不随 APK 打包，首次使用朗读时提示下载。
 */
object TtsModel {
    const val MODEL_ID = "xiaoya-int8"
    const val MODEL_URL =
        "https://github.com/Potaperper/wc-reader/releases/download/v1.0.16/WCReader-tts-xiaoya-int8.zip"
    const val SIZE_BYTES = 13_770_350L

    private const val MODEL_FILE = "zh_CN-xiao_ya-medium.onnx"
    private const val TOKENS = "tokens.txt"
    private const val LEXICON = "lexicon.txt"
    private const val PHONE_FST = "phone.fst"
    private const val DATE_FST = "date.fst"
    private const val NUMBER_FST = "number.fst"

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "tts/$MODEL_ID")

    fun isReady(ctx: Context): Boolean {
        val d = dir(ctx)
        return File(d, MODEL_FILE).let { it.exists() && it.length() > 10_000_000 } &&
            File(d, TOKENS).exists() &&
            File(d, LEXICON).exists() &&
            File(d, PHONE_FST).exists()
    }

    fun modelPath(ctx: Context): String = File(dir(ctx), MODEL_FILE).absolutePath
    fun tokensPath(ctx: Context): String = File(dir(ctx), TOKENS).absolutePath
    fun lexiconPath(ctx: Context): String = File(dir(ctx), LEXICON).absolutePath
    fun ruleFsts(ctx: Context): String {
        val d = dir(ctx)
        return listOf(PHONE_FST, DATE_FST, NUMBER_FST)
            .map { File(d, it).absolutePath }
            .joinToString(",")
    }

    /**
     * 下载并解压模型：优先使用更新下载里测速选中的线路，失败自动换线路，支持断点续传。
     */
    suspend fun download(
        ctx: Context,
        onProgress: (Int) -> Unit,
        onStatus: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val zip = File(ctx.cacheDir, "wc-tts-$MODEL_ID.zip")
        val okZip = if (zip.exists() && zip.length() == SIZE_BYTES) true else {
            zip.delete()
            val preferred = UpdateManager.preferredSource(ctx)
            val ordered = listOf(preferred) + UpdateManager.SOURCES.filter { it.id != preferred.id }
            var ok = false
            for ((idx, src) in ordered.withIndex()) {
                onStatus("线路 ${idx + 1}/${ordered.size}：${src.label}")
                if (downloadOne(src.prefix + MODEL_URL, zip, onProgress)) { ok = true; break }
            }
            ok
        }
        if (!okZip) return@withContext false

        onStatus("正在解压模型…")
        onProgress(92)
        val target = dir(ctx)
        val tmp = File(ctx.getExternalFilesDir(null), "tts/${MODEL_ID}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (name.contains("..")) continue
                    val outFile = File(tmp, File(name).name)
                    FileOutputStream(outFile).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) {
                // rename 失败时退化为复制
                target.mkdirs()
                tmp.listFiles()?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
                tmp.deleteRecursively()
            }
            onProgress(100)
            isReady(ctx)
        } catch (e: Exception) {
            onStatus("解压失败：${e.message}")
            tmp.deleteRecursively()
            false
        }
    }

    private fun downloadOne(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        var attempt = 0
        while (attempt < 3) {
            attempt++
            var downloaded = if (dest.exists()) dest.length() else 0L
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "WC-reader")
                if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
                val code = conn.responseCode
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                val append = code == 206 && downloaded > 0
                if (!append && downloaded > 0) {
                    dest.delete()
                    downloaded = 0
                }
                val total = if (conn.contentLengthLong > 0) downloaded + conn.contentLengthLong else SIZE_BYTES
                conn.inputStream.use { input ->
                    FileOutputStream(dest, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            val pct = (downloaded * 90 / total.coerceAtLeast(1)).toInt().coerceIn(0, 90)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
                if (dest.length() == SIZE_BYTES || (SIZE_BYTES <= 0 && dest.length() > 0)) return true
            } catch (e: Exception) {
                // 保留断点，继续重试
            }
            if (dest.exists() && dest.length() == SIZE_BYTES) return true
        }
        return false
    }
}
