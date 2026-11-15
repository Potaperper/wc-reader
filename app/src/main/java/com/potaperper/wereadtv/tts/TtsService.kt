package com.potaperper.wereadtv.tts

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

/**
 * 独立进程的离线 TTS 服务。
 * 把 sherpa-onnx / onnxruntime 放在 :tts 进程里，
 * 即使原生库崩溃或内存不足，阅读器主进程也不会被带走，进度不会丢。
 */
class TtsService : Service() {

    companion object {
        const val MSG_SPEAK = 1
        const val MSG_STOP = 2
        const val MSG_RELEASE = 3
        const val MSG_STATUS = 10
        const val MSG_DONE = 11
        const val MSG_ERROR = 12
        const val KEY_TEXT = "text"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
    }

    private val player by lazy { OfflineTtsPlayer(this) }
    private var client: Messenger? = null

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_SPEAK -> {
                client = msg.replyTo
                val text = msg.data?.getString(KEY_TEXT) ?: ""
                TtsDiag.log(this, "speak requested, chars=${text.length}")
                player.speak(
                    text,
                    onStatus = { s -> send(MSG_STATUS, Bundle().apply { putString(KEY_STATUS, s) }) },
                    onDone = { send(MSG_DONE, null) },
                    onError = { e -> send(MSG_ERROR, Bundle().apply { putString(KEY_ERROR, e) }) },
                )
            }
            MSG_STOP -> player.stop()
            MSG_RELEASE -> {
                player.release()
                stopSelf()
            }
        }
        true
    }

    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        TtsDiag.log(this, "service onCreate")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun send(what: Int, data: Bundle?) {
        val c = client ?: return
        try {
            val m = Message.obtain(null, what)
            if (data != null) m.data = data
            c.send(m)
        } catch (_: RemoteException) {
        }
    }

    override fun onDestroy() {
        try { player.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
