package com.potaperper.wereadtv.tts

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 记录 TTS 关键阶段，便于定位原生崩溃/被系统杀死的位置 */
object TtsDiag {
    private const val FILE = "tts_diag.log"

    @Synchronized
    fun log(ctx: Context, msg: String) {
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            f.appendText("$ts  $msg\n")
        } catch (_: Exception) {
        }
    }

    fun tail(ctx: Context, lines: Int = 20): String {
        return try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return "暂无记录"
            f.readLines().takeLast(lines).joinToString("\n")
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
    }

    fun lastStage(ctx: Context): String {
        return try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return "无记录"
            f.readLines().lastOrNull() ?: "无记录"
        } catch (e: Exception) {
            "无记录"
        }
    }
}
