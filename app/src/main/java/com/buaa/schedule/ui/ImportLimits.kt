package com.buaa.schedule.ui

/**
 * 导入入口的统一限流（R3 审查 P2-16）。
 *
 * 备份 JSON / ICS / 文本课表都是用户可控输入，此前 `readText()` 无上限读取：
 * 一个几 MB 的"文件"可以来自任意 SAF 选择器（也可能被系统报告为普通文件的超大内容），
 * 无上限地吸进内存再交给解析器，是可被构造的 OOM/ANR 路径。
 * 备份条目数已有 `CourseConstraints.MAX_COURSE_COUNT` 兜底，这里是文件级的入口闸门。
 */
const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

/**
 * 最多读取 [maxBytes] 字节：超过立即返回 null（语义：文件过大），
 * 正常文件返回完整 UTF-8 文本。调用方必须区分 null 与空串。
 *
 * 之所以不用 `BufferedReader.readText()` 截断：按字符截断无法感知字节数，
 * 且"读到一半的 JSON"交给解析器只会得到一句含糊的解析失败；
 * 这里在字节层判停，超限直接拒绝，不给下游解析器机会。
 */
fun java.io.InputStream.readTextLimited(maxBytes: Int): String? {
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 1 shl 20))
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toString("UTF-8")
}
