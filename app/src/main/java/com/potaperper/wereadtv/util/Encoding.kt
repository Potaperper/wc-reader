package com.potaperper.wereadtv.util

import java.security.MessageDigest
import java.util.Base64

object Encoding {
    fun md5(raw: String): String =
        MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8)).toHex()

    fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    /** 与 Node Buffer.from(input, "base64") 类似的宽松解码（忽略非法字符） */
    fun base64Decode(input: String): String {
        val cleaned = input.filter { it.isLetterOrDigit() || it == '+' || it == '/' }
        val bytes = try {
            Base64.getMimeDecoder().decode(cleaned)
        } catch (e: Exception) {
            return ""
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun base64Encode(input: ByteArray): String =
        Base64.getEncoder().encodeToString(input)

    /**
     * 精确复刻 JS encodeURIComponent：
     * 不转义 A-Z a-z 0-9 - _ . ! ~ * ' ( )
     */
    fun encodeURIComponent(s: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in safe) {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }
}
