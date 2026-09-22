package com.buaa.schedule.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学分显示与体育项目剥取的判据表（T58 内核）。
 *
 * 表驱动：每一行就是一个输入/输出对，边界成对钉 ——
 * `null` vs `0.0` 是学分那条最要紧的分界（缺失与真零必须长得不一样），
 * `大学物理(上)` 是项目剥取最要紧的一条（绝不能把「上」当项目名吐出来）。
 */
class CourseMetaFormatTest {

    // —— formatCredit ——

    @Test
    fun formatCreditTable() {
        val cases: List<Pair<Double?, String?>> = listOf(
            // 缺失就是缺失：null 进 null 出，调用方整行不画，不给任何占位串
            null to null,
            // 0.0 是真值（教务明说这门课不计学分），与上面的 null 分得开
            0.0 to "0",
            // 整数不带小数点
            5.0 to "5",
            1.0 to "1",
            100.0 to "100", // 恰在 MAX_CREDIT 上界：normalizeCredit 认，这里就得能画
            // 非整数最多两位小数、去尾随 0
            3.5 to "3.5",
            2.20 to "2.2",
            0.25 to "0.25",
            0.2 to "0.2",
            12.75 to "12.75",
            // 超百分位精度的四舍五入到两位；进成整的走整数档
            3.456 to "3.46",
            99.999 to "100",
            // 非法值一律判"没有学分数据"，不冒充 0（与 normalizeCredit 同一把尺子）
            -1.0 to null,
            -0.001 to null,
            100.1 to null,
            1e308 to null,
            Double.NaN to null,
            Double.POSITIVE_INFINITY to null,
            Double.NEGATIVE_INFINITY to null,
        )
        for ((input, expected) in cases) {
            assertEquals("formatCredit($input)", expected, formatCredit(input))
        }
    }

    @Test
    fun dirtyValuesNeverFormatToZero() {
        // 单独立一条：负数/NaN/越界若被印成 "0"，就是在界面上造"教务明说 0 学分"的假账
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 100.5)) {
            assertNull("脏值 $bad 不许格式化出任何文案", formatCredit(bad))
        }
    }

    // —— creditLabel ——

    @Test
    fun creditLabelAppendsUnitAndPassesNullThrough() {
        assertEquals("3.5学分", creditLabel(3.5))
        assertEquals("0学分", creditLabel(0.0))
        assertEquals("5学分", creditLabel(5.0))
        assertNull(creditLabel(null))
        assertNull(creditLabel(-2.0))
    }

    // —— peProjectOf ——

    @Test
    fun peProjectOfTable() {
        val cases: List<Pair<String, String?>> = listOf(
            // 基本形态：中英文括号都认
            "体育(田径)" to "田径",
            "体育（篮球）" to "篮球",
            // 主干带修饰词仍以「体育」开头
            "体育选项(武术)" to "武术",
            // 空格先归一再剥（全角空格走 normalizeWidths，半角空格直接去）
            "体育 ( 游泳 )" to "游泳",
            "体育(　排球　)" to "排球",
            // 多字项目名
            "体育(体能测试)" to "体能测试",
            // 非体育课的括号后缀绝不能被当成项目名
            "大学物理(上)" to null,
            "高等数学A(上)" to null,
            "数学分析（下）" to null,
            // 主干是「体育」但没有括号：没有项目信息，不猜
            "体育" to null,
            "体育 选项" to null,
            // 括号顶在最前（主干为空）
            "(篮球)" to null,
            // 括号里空白/只有标点 → 脏数据不出场
            "体育()" to null,
            "体育( )" to null,
            "体育(、。)" to null,
            // 超长（>12 字）判脏；恰 12 字放行 —— 边界两侧各钉一条
            "体育(一二三四五六七八九十一二三)" to null,
            "体育(一二三四五六七八九十一二)" to "一二三四五六七八九十一二",
            // 没有「体育」前缀的体育课识别不出来：已知边界，不猜
            "篮球" to null,
            "大学体育(篮球)" to null,
        )
        for ((input, expected) in cases) {
            assertEquals("peProjectOf($input)", expected, peProjectOf(input))
        }
    }

    @Test
    fun peProjectOfToleratesHalfClosedParen() {
        // 教务导出脏数据里有半截括号（对齐组件侧 parenInner 的既有口径）：取到结尾
        assertEquals("田径", peProjectOf("体育(田径"))
    }
}
