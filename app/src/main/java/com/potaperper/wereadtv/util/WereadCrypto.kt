package com.potaperper.wereadtv.util

/**
 * 微信读书 Web 端请求签名算法（移植自 touchFish src/api/weread/utils/index.ts）
 */
object WereadCrypto {

    /** 根据 UA 生成 appId */
    fun getAppId(ua: String): String {
        var rnd1 = ""
        val uaParts = ua.split(" ")
        for (i in 0 until minOf(uaParts.size, 12)) {
            rnd1 += (uaParts[i].length % 10)
        }
        var num = 0L
        for (ch in ua) {
            num = (131L * num + ch.code) and 0x7fffffffL
        }
        var rnd2 = num.toString()
        if (rnd2.length > 16) rnd2 = rnd2.substring(0, 16)
        return "wb${rnd1}h$rnd2"
    }

    /** 内部签名：对 key 排序拼接后的串做卷积散列 */
    private fun rawSign(data: String): String {
        var n1 = 0x15051505L
        var n2 = 0x15051505L
        val strlen = data.length
        var i = strlen - 1
        while (i > 0) {
            // JS: charCode << shift 结果按 int32 截断，这里用 Int shl 再转 Long 保持一致
            val t1 = (data[i].code shl ((strlen - i) % 30)).toLong()
            val t2 = (data[i - 1].code shl (i % 30)).toLong()
            n1 = 0x7fffffffL and (n1 xor t1)
            n2 = 0x7fffffffL and (n2 xor t2)
            i -= 2
        }
        return (n1 + n2).toString(16)
    }

    /** JS 的 key=value&... 拼接（key 已排序） */
    fun stringify(data: Map<String, Any?>): String =
        data.entries.sortedBy { it.key }
            .joinToString("&") { "${it.key}=${Encoding.encodeURIComponent(it.value.toString())}" }

    /**
     * 计算 payload 的签名 s
     */
    fun sign(payload: Map<String, Any?>): String = rawSign(stringify(payload))

    /**
     * 计算参数 hash（b/c/pc/ps 字段）
     */
    fun calcHash(data0: Any): String {
        val data = data0.toString()
        val dataMd5 = Encoding.md5(data)
        val sb = StringBuilder()
        sb.append(dataMd5.substring(0, 3))

        val isAllDigits = Regex("^\\d*$").matches(data)
        val mode: Char
        val chunks: List<String>
        if (isAllDigits) {
            mode = '3'
            val list = mutableListOf<String>()
            var i = 0
            while (i < data.length) {
                val end = minOf(i + 9, data.length)
                // JS parseInt 是按 double，9 位数字 Long 足够
                list.add(data.substring(i, end).toLong().toString(16))
                i += 9
            }
            chunks = list
        } else {
            mode = '4'
            val hex = StringBuilder()
            for (ch in data) hex.append(ch.code.toString(16))
            chunks = listOf(hex.toString())
        }

        sb.append(mode)
        // JS: "2" + parseInt 结果其实是字符串拼接 "2" + hex 末两位
        sb.append("2")
        sb.append(dataMd5.substring(dataMd5.length - 2))

        for ((idx, chunk) in chunks.withIndex()) {
            var lenHex = chunk.length.toString(16)
            if (lenHex.length == 1) lenHex = "0$lenHex"
            sb.append(lenHex)
            sb.append(chunk)
            if (idx < chunks.size - 1) sb.append("g")
        }

        if (sb.length < 20) {
            sb.append(dataMd5.substring(0, 20 - sb.length))
        }
        sb.append(Encoding.md5(sb.toString()).substring(0, 3))
        return sb.toString()
    }

    fun currentTimeSec(): Long = System.currentTimeMillis() / 1000

    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
}
