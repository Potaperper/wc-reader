package com.potaperper.wereadtv.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * 基于 sherpa-onnx 的离线 TTS 播放器。
 * 模型文件由 TtsModel 下载到应用私有目录，不打包进 APK。
 */
class OfflineTtsPlayer(private val ctx: Context) {

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var stopFlag = false

    @Volatile
    var speaking = false
        private set

    fun speak(
        text: String,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        stopFlag = false
        speaking = true
        val chunks = chunk(text)
        if (chunks.isEmpty()) {
            speaking = false
            onError("没有可朗读的文字")
            return
        }
        thread = Thread {
            var at: AudioTrack? = null
            try {
                val engine = ensureEngine()
                val sampleRate = engine.sampleRate()
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )
                val bufferSize = maxOf(minBuf, sampleRate / 2 * 4)
                val attr = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(sampleRate)
                    .build()
                at = AudioTrack(
                    attr, format, bufferSize, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
                track = at
                at.play()
                for (chunk in chunks) {
                    if (stopFlag) break
                    val audio = engine.generate(chunk, 0, 1.0f)
                    var offset = 0
                    while (offset < audio.samples.size && !stopFlag) {
                        val n = at.write(
                            audio.samples, offset, audio.samples.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (n <= 0) break
                        offset += n
                    }
                }
                try { at.stop() } catch (_: Exception) {}
                at.release()
                track = null
                if (!stopFlag) {
                    speaking = false
                    onDone()
                }
            } catch (e: Throwable) {
                try { at?.stop() } catch (_: Exception) {}
                try { at?.release() } catch (_: Exception) {}
                track = null
                if (!stopFlag) {
                    speaking = false
                    onError(
                        if (e is UnsatisfiedLinkError) {
                            "当前设备架构不支持离线语音（需要 arm64-v8a）"
                        } else {
                            "语音合成失败：${e.message ?: e.javaClass.simpleName}"
                        },
                    )
                }
            }
        }.apply {
            name = "offline-tts"
            start()
        }
    }

    fun stop() {
        stopFlag = true
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        thread?.interrupt()
        thread = null
        speaking = false
    }

    fun release() {
        stop()
        try { tts?.free() } catch (_: Exception) {}
        tts = null
    }

    private fun ensureEngine(): OfflineTts {
        tts?.let { return it }
        val vits = OfflineTtsVitsModelConfig(
            model = TtsModel.modelPath(ctx),
            lexicon = TtsModel.lexiconPath(ctx),
            tokens = TtsModel.tokensPath(ctx),
        )
        val model = OfflineTtsModelConfig(
            vits = vits,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
        val config = OfflineTtsConfig(
            model = model,
            ruleFsts = TtsModel.ruleFsts(ctx),
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
        return OfflineTts(config = config).also { tts = it }
    }

    /** 按句子切成短段，降低首句延迟 */
    private fun chunk(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        val maxLen = 220
        while (start < text.length) {
            var end = minOf(start + maxLen, text.length)
            if (end < text.length) {
                val candidates = intArrayOf(
                    text.lastIndexOf('。', end - 1),
                    text.lastIndexOf('！', end - 1),
                    text.lastIndexOf('？', end - 1),
                    text.lastIndexOf('\n', end - 1),
                    text.lastIndexOf('；', end - 1),
                )
                val idx = candidates.max()
                if (idx > start + 30) end = idx + 1
            }
            val s = text.substring(start, end).trim()
            if (s.isNotEmpty()) out.add(s)
            start = end
        }
        return out
    }
}
