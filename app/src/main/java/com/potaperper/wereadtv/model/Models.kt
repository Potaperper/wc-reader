package com.potaperper.wereadtv.model

import org.json.JSONArray
import org.json.JSONObject

/** 书架上的书 */
data class Book(
    val bookId: String,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val readUpdateTime: Long = 0,
    val progress: Int = -1,
) {
    companion object {
        fun fromJson(o: JSONObject): Book = Book(
            bookId = o.optString("bookId"),
            title = o.optString("title"),
            author = o.optString("author"),
            cover = o.optString("cover"),
            readUpdateTime = o.optLong("readUpdateTime", 0),
        )
    }
}

/** 章节 */
data class Chapter(
    val chapterUid: Long,
    val chapterIdx: Int,
    val title: String,
    val level: Int = 1,
    val wordCount: Long = 0,
) {
    companion object {
        fun fromJson(o: JSONObject): Chapter = Chapter(
            chapterUid = o.optLong("chapterUid"),
            chapterIdx = o.optInt("chapterIdx"),
            title = o.optString("title"),
            level = o.optInt("level", 1),
            wordCount = o.optLong("wordCount", 0),
        )

        fun fromArray(arr: JSONArray): List<Chapter> {
            val list = mutableListOf<Chapter>()
            for (i in 0 until arr.length()) list.add(fromJson(arr.getJSONObject(i)))
            return list
        }
    }
}

/** 章节内容 */
data class ChapterContent(
    val html: String,
    val style: String,
    val format: String,
)

/** 阅读进度 */
data class ReadProgress(
    val bookId: String,
    val chapterUid: Long,
    val chapterOffset: Int,
    val progress: Int,
) {
    companion object {
        fun fromJson(o: JSONObject): ReadProgress {
            val book = o.optJSONObject("book") ?: JSONObject()
            return ReadProgress(
                bookId = o.optString("bookId"),
                chapterUid = book.optLong("chapterUid", -1),
                chapterOffset = book.optInt("chapterOffset", 0),
                progress = book.optInt("progress", 0),
            )
        }
    }
}

/** 热门划线 */
data class Underline(
    val range: String,
    val count: Int,
)

/** 热门想法 */
data class Thought(
    val reviewId: String,
    val abstract: String,
    val content: String,
    val userName: String,
    val likeCount: Int,
)
