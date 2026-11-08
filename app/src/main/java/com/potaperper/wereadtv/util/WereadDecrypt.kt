package com.potaperper.wereadtv.util

/**
 * 微信读书章节内容解密算法（移植自 touchFish src/api/weread/utils/decrypt.ts）
 */
object WereadDecrypt {

    /**
     * 校验响应：前 32 字节为 body 的 MD5 大写
     */
    fun chk(data: String): String {
        if (data.length <= 32) return data
        val header = data.substring(0, 32)
        val body = data.substring(32)
        return if (header == Encoding.md5(body).uppercase()) body else ""
    }

    /**
     * 核心解密：去头 -> 字符行交换 -> base64
     */
    private fun decrypt(data: String): String {
        if (data.length <= 1) return ""
        var result = data.substring(1)
        result = swapByDerivedIndexes(result)
        return Encoding.base64Decode(result)
    }

    private fun swapByDerivedIndexes(input: String): String {
        val len = input.length
        val swaps: MutableList<Int> = mutableListOf()

        if (len < 4) {
            swaps.clear()
        } else if (len < 11) {
            swaps.add(0)
            swaps.add(2)
        } else {
            val tailCount = minOf(4, Math.ceil(len / 10.0).toInt())
            val binary = StringBuilder()
            for (i in len - 1 downTo len - tailCount) {
                val code = input[i].code
                // JS: parseInt(code.toString(2), 4) —— 二进制串按四进制解析
                binary.append(java.lang.Long.parseLong(code.toString(2), 4).toString())
            }
            val range = len - tailCount - 2
            val digitCount = range.toString().length
            var i = 0
            while (swaps.size < 10 && i + digitCount < binary.length) {
                val a = binary.substring(i, i + digitCount).toLong()
                swaps.add((a % range).toInt())
                val end2 = minOf(i + 1 + digitCount, binary.length)
                val b = binary.substring(i + 1, end2).toLong()
                swaps.add((b % range).toInt())
                i += digitCount
            }
        }

        val chars = input.toCharArray()
        var i = swaps.size - 1
        while (i >= 1) {
            for (j in 1 downTo 0) {
                val x = swaps[i] + j
                val y = swaps[i - 1] + j
                if (x < chars.size && y < chars.size) {
                    val tmp = chars[x]
                    chars[x] = chars[y]
                    chars[y] = tmp
                }
            }
            i -= 2
        }
        return String(chars)
    }

    /** epub/pdf 正文解密 */
    fun dH(data: String): String = if (data.isNotEmpty()) decrypt(data) else ""

    /** epub/pdf 样式解密 */
    fun dS(data: String): String = if (data.isNotEmpty()) decrypt(data) else ""

    /** txt 正文解密 */
    fun dT(data: String): String = if (data.isNotEmpty()) decrypt(data) else ""
}
