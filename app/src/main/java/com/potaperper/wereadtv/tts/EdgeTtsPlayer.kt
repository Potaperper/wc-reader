package com.potaperper.wereadtv.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 免费 Edge TTS：通过微软 Edge 浏览器的在线朗读通道合成语音。
 * 非官方接口，无需 Key；可能因微软策略调整而失效。
 */
class EdgeTtsPlayer(
    private val ctx: Context,
    private val voice: String,
) {
    companion object {
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_BASE =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val CHROMIUM_VERSION = "143.0.3650.75"
        private const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"
        private val POISON = File("")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopFlag = false

    private var producer: Thread? = null
    private var consumer: Thread? = null

    @Volatile
    private var currentPlayer: MediaPlayer? = null

    fun speak(
        text: String,
        onStatus: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        stopFlag = false
        val chunks = chunk(text)
        if (chunks.isEmpty()) {
            onError("没有可朗读的文字")
            return
        }
        val dir = File(ctx.cacheDir, "edge_tts").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val queue = LinkedBlockingQueue<File>(3)
        val errorOnce = AtomicBoolean(false)
        fun fail(msg: String) {
            if (errorOnce.compareAndSet(false, true)) {
                stopFlag = true
                mainHandler.post { onError(msg) }
            }
        }

        onStatus("正在连接语音服务…")
        producer = Thread {
            try {
                for ((idx, chunk) in chunks.withIndex()) {
                    if (stopFlag) break
                    val file = File(dir, "part_$idx.mp3")
                    val ok = synthesizeWithRetry(chunk, file)
                    if (!ok) {
                        if (!stopFlag) fail("语音服务连接失败，请检查网络后重试")
                        break
                    }
                    if (stopFlag) break
                    queue.put(file)
                }
                queue.put(POISON)
            } catch (e: InterruptedException) {
                // stopped
            } catch (e: Exception) {
                if (!stopFlag) fail("语音合成失败：${e.message}")
            }
        }.apply { name = "edge-tts-synth"; start() }

        consumer = Thread {
            var started = false
            try {
                while (!stopFlag) {
                    val file = queue.take()
                    if (file === POISON) break
                    try {
                        if (!started) {
                            started = true
                            mainHandler.post { onStatus("开始播放") }
                        }
                        playFile(file)
                    } finally {
                        file.delete()
                    }
                }
                if (!stopFlag) mainHandler.post { onDone() }
            } catch (e: InterruptedException) {
                // stopped
            } catch (e: Exception) {
                if (!stopFlag) fail("语音播放失败：${e.message}")
            }
        }.apply { name = "edge-tts-play"; start() }
    }

    fun stop() {
        stopFlag = true
        try { currentPlayer?.stop() } catch (_: Exception) {}
        try { currentPlayer?.release() } catch (_: Exception) {}
        currentPlayer = null
        producer?.interrupt()
        consumer?.interrupt()
        producer = null
        consumer = null
        File(ctx.cacheDir, "edge_tts").listFiles()?.forEach { it.delete() }
    }

    private fun playFile(file: File) {
        if (stopFlag || !file.exists() || file.length() == 0L) return
        val mp = MediaPlayer()
        currentPlayer = mp
        val latch = CountDownLatch(1)
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ ->
                latch.countDown()
                true
            }
            mp.prepare()
            mp.start()
            while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                if (stopFlag) break
            }
        } finally {
            try { mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
            if (currentPlayer === mp) currentPlayer = null
        }
    }

    private fun synthesizeWithRetry(text: String, out: File): Boolean {
        repeat(2) { attempt ->
            if (stopFlag) return false
            if (synthesize(text, out)) return true
            if (attempt == 0) Thread.sleep(400)
        }
        return false
    }

    /** 单句合成：一个 WebSocket 请求，收集 MP3 字节写入文件 */
    private fun synthesize(text: String, out: File): Boolean {
        val connId = UUID.randomUUID().toString().replace("-", "")
        val token = EdgeTtsToken.generate(System.currentTimeMillis(), TRUSTED_TOKEN)
        val url = "$WSS_BASE?TrustedClientToken=$TRUSTED_TOKEN" +
            "&ConnectionId=$connId&Sec-MS-GEC=$token&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        val request = Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").uppercase() + ";")
            .build()

        val done = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val audio = ByteArrayOutputStream()
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val date = httpDate()
        val wsRef = arrayOfNulls<WebSocket>(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val config = "X-Timestamp:$date\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                    "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(config)
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                    "xml:lang='zh-CN'><voice name='$voice'><prosody rate='+0%' pitch='+0Hz'>" +
                    escapeXml(text) + "</prosody></voice></speak>"
                val msg = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:$date\r\n" +
                    "Path:ssml\r\n\r\n" + ssml
                webSocket.send(msg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    success.set(audio.size() > 0)
                    done.countDown()
                    webSocket.close(1000, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (data.size < 2 + headerLen) return
                val header = String(data, 2, headerLen, Charsets.UTF_8)
                if (header.contains("Path:audio")) {
                    audio.write(data, 2 + headerLen, data.size - 2 - headerLen)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }
        val ws = http.newWebSocket(request, listener)
        wsRef[0] = ws

        val timeoutTask = Runnable {
            try { ws.cancel() } catch (_: Exception) {}
            done.countDown()
        }
        mainHandler.postDelayed(timeoutTask, 20_000)
        return try {
            done.await(22, TimeUnit.SECONDS)
            mainHandler.removeCallbacks(timeoutTask)
            if (stopFlag || !success.get() || audio.size() == 0) {
                false
            } else {
                FileOutputStream(out).use { it.write(audio.toByteArray()) }
                true
            }
        } catch (e: Exception) {
            mainHandler.removeCallbacks(timeoutTask)
            false
        } finally {
            try { ws.cancel() } catch (_: Exception) {}
        }
    }

    private fun httpDate(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    private fun escapeXml(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun chunk(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val maxLen = 260
            var end = minOf(start + maxLen, text.length)
            if (end < text.length) {
                val idx = intArrayOf(
                    text.lastIndexOf('。', end - 1),
                    text.lastIndexOf('！', end - 1),
                    text.lastIndexOf('？', end - 1),
                    text.lastIndexOf('\n', end - 1),
                    text.lastIndexOf('；', end - 1),
                ).max()
                if (idx > start + 20) end = idx + 1
            }
            val s = text.substring(start, end).trim()
            if (s.isNotEmpty()) out.add(s)
            start = end
        }
        return out
    }
}
