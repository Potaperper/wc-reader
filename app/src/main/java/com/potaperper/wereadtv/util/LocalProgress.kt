package com.potaperper.wereadtv.util

import android.content.Context
import org.json.JSONObject

/**
 * 本地阅读进度：记录到章节内的字符偏移，退出/换排版后可以精确回到原位置。
 */
object LocalProgress {
    data class Pos(
        val chapterUid: Long,
        val offset: Int,
        val percent: Float,
        val ts: Long,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reading_pos", Context.MODE_PRIVATE)

    fun save(ctx: Context, bookId: String, chapterUid: Long, offset: Int, percent: Float) {
        if (bookId.isEmpty() || chapterUid <= 0) return
        val json = JSONObject().apply {
            put("chapterUid", chapterUid)
            put("offset", offset)
            put("percent", percent.toDouble())
            put("ts", System.currentTimeMillis())
        }
        prefs(ctx).edit().putString("pos_$bookId", json.toString()).apply()
    }

    fun load(ctx: Context, bookId: String): Pos? {
        val raw = prefs(ctx).getString("pos_$bookId", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Pos(
                chapterUid = o.optLong("chapterUid", -1),
                offset = o.optInt("offset", -1),
                percent = o.optDouble("percent", 0.0).toFloat(),
                ts = o.optLong("ts", 0),
            ).takeIf { it.chapterUid > 0 }
        } catch (e: Exception) {
            null
        }
    }
}
