package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.NO_PERIOD_GAP
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.toStartEndTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行副字段的可配置化（审查 3.7 + 3.2）。
 *
 * 最关键的一条是**默认值必须逐字等于改动前那行拼出来的文本**：
 * 一次升级不该把用户桌面上的组件内容悄悄换掉。其余用例钉住"勾选顺序即拼接顺序"
 * 这个约定（它替代了拖拽排序），以及「下一节课」里教师排在节次之前。
 */
class WidgetRowFieldsTest {

    private val row = WidgetRowFields(
        teacher = "王教授",
        location = "J3-101",
        periodsText = "1-2节",
        weeksText = "1-8周",
    )

    @Test
    fun `列表型默认口径逐字等于改动前的那一行`() {
        // 改动前是 listOfNotNull(dayTag, location ?: "教室未定", "1-2节")
        assertEquals("J3-101 · 1-2节", widgetRowMeta(WidgetAppearance.DEFAULT_LIST_ROW_FIELDS, row))
        assertEquals(
            "周三 · J3-101 · 1-2节",
            widgetRowMeta(WidgetAppearance.DEFAULT_LIST_ROW_FIELDS, row.copy(dayTag = "周三")),
        )
        // 默认里没有教师与周次：多一段就是一行截断
        assertEquals(-1, WidgetAppearance.DEFAULT_LIST_ROW_FIELDS.indexOf(WidgetRowField.TEACHER))
        assertEquals(-1, WidgetAppearance.DEFAULT_LIST_ROW_FIELDS.indexOf(WidgetRowField.WEEKS))
    }

    @Test
    fun `用户没勾过时沿用各组件类型自己的口径`() {
        assertNull("默认必须是 null 而不是某份列表，否则升级即改写所有组件", WidgetAppearance().rowFields)
        assertEquals(
            WidgetAppearance.DEFAULT_LIST_ROW_FIELDS,
            WidgetAppearance().effectiveRowFields(WidgetAppearance.DEFAULT_LIST_ROW_FIELDS),
        )
        assertEquals(
            WidgetAppearance.DEFAULT_NEXT_ROW_FIELDS,
            WidgetAppearance().effectiveRowFields(WidgetAppearance.DEFAULT_NEXT_ROW_FIELDS),
        )
        assertEquals(
            listOf(WidgetRowField.PERIODS),
            WidgetAppearance(rowFields = listOf(WidgetRowField.PERIODS))
                .effectiveRowFields(WidgetAppearance.DEFAULT_LIST_ROW_FIELDS),
        )
    }

    @Test
    fun `下一节课里教师排在节次之前`() {
        val fields = WidgetAppearance.DEFAULT_NEXT_ROW_FIELDS
        assertTrue(
            "节次在 14:00 里已经隐含，教师才是「是不是我该去的那个班」的判据",
            fields.indexOf(WidgetRowField.TEACHER) in 0 until fields.indexOf(WidgetRowField.PERIODS),
        )
        // 2×1 只有一行可写，周次由那个日期隐含
        assertTrue(WidgetRowField.WEEKS !in fields)
        assertEquals(fields, WidgetAppearance.NEXT_ROW_FIELD_CHOICES)
    }

    @Test
    fun `勾选顺序就是拼接顺序`() {
        val fields = listOf(WidgetRowField.TEACHER, WidgetRowField.WEEKS)
        assertEquals("王教授 · 1-8周", widgetRowMeta(fields, row))
        assertEquals("1-8周 · 王教授", widgetRowMeta(fields.reversed(), row))
    }

    @Test
    fun `toggle 追加在末尾且再点一次整段消失`() {
        val start = WidgetAppearance.DEFAULT_LIST_ROW_FIELDS
        val added = toggleRowField(start, WidgetRowField.WEEKS)
        assertEquals(start + WidgetRowField.WEEKS, added)
        assertEquals(start, toggleRowField(added, WidgetRowField.WEEKS))
        // 取消再勾回来会换到末尾——这是"点击顺序即显示顺序"的代价，不做拖拽
        val moved = toggleRowField(toggleRowField(start, WidgetRowField.LOCATION), WidgetRowField.LOCATION)
        assertEquals(listOf(WidgetRowField.TIME, WidgetRowField.PERIODS, WidgetRowField.LOCATION), moved)
    }

    @Test
    fun `缺字段的那一段直接缺席而不是留下分隔符`() {
        val fields = listOf(WidgetRowField.TEACHER, WidgetRowField.PERIODS)
        assertEquals("1-2节", widgetRowMeta(fields, WidgetRowFields(teacher = null, periodsText = "1-2节")))
        assertEquals("", widgetRowMeta(fields, WidgetRowFields(teacher = "  ", periodsText = "")))
        assertEquals("周三", widgetRowMeta(fields, WidgetRowFields(dayTag = "周三")))
    }

    @Test
    fun `地点单独选中时兜底成教室未定`() {
        // 与改动前一致：没有教室的行不能整段变空
        assertEquals(
            "教室未定",
            widgetRowMeta(listOf(WidgetRowField.LOCATION), WidgetRowFields(location = null)),
        )
    }

    @Test
    fun `时间不拼进 meta 那一段`() {
        // 列表组件的"时间"是右侧那一列（widget_item_time），拼进 meta 会重复显示
        assertEquals("", widgetRowMeta(listOf(WidgetRowField.TIME), row))
    }

    @Test
    fun `可勾选项覆盖默认口径里的每一项`() {
        listOf(
            WidgetAppearance.LIST_ROW_FIELD_CHOICES to WidgetAppearance.DEFAULT_LIST_ROW_FIELDS,
            WidgetAppearance.NEXT_ROW_FIELD_CHOICES to WidgetAppearance.DEFAULT_NEXT_ROW_FIELDS,
        ).forEach { (choices, defaults) ->
            assertTrue("默认口径里有字段没被摆上配置页：$defaults", choices.containsAll(defaults))
            assertEquals("可勾选项不能重复", choices.size, choices.toSet().size)
        }
        assertTrue(WidgetRowField.entries.all { it.label.isNotBlank() })
    }

    @Test
    fun `改勾选必须换宿主看到的行指纹`() {
        // 外观指纹决定 hasStableIds 下宿主是否重新向工厂取行：
        // rowFields 会改变每行画出来的文字，不参与指纹的话保存后组件还是旧内容
        val base = WidgetAppearance()
        val custom = base.copy(rowFields = listOf(WidgetRowField.TEACHER))
        assertNotEquals(base.viewIdStamp(), custom.viewIdStamp())
        assertEquals(custom.viewIdStamp(), WidgetAppearance(rowFields = listOf(WidgetRowField.TEACHER)).viewIdStamp())
    }

    @Test
    fun `节次摘要与改动前逐字一致`() {
        // NO_PERIOD_GAP = 只按节次号相邻切段，正是组件改动前的实际口径
        assertEquals("1-2节", widgetPeriodsText(listOf(1, 2), NO_PERIOD_GAP))
        assertEquals("3节", widgetPeriodsText(listOf(3), NO_PERIOD_GAP))
        assertEquals("1-2,9-10节", widgetPeriodsText(listOf(1, 2, 9, 10), NO_PERIOD_GAP))
        // 兜底：口径来自 periodLabel，只是去掉「第」这个在窄行里没有信息量的前缀
        assertEquals(
            "1-2,9-10节",
            periodLabel(listOf(1, 2, 9, 10), NO_PERIOD_GAP).removePrefix("第").removeSuffix("节") + "节",
        )
    }

    @Test
    fun `没有节次时不写一个光秃秃的节字`() {
        // 空节次 = 这一项缺席：以前 `unwrapped + "节"` 会在窄行上留一个「节」字
        assertEquals("", widgetPeriodsText(emptyList(), NO_PERIOD_GAP))
        assertEquals(
            "",
            widgetPeriodsText(emptyList(), periodGapMinutesOf(TimeSlotProfile.DEFAULT.toStartEndTimes())),
        )
        // 段落缺席、分隔符也跟着缺席：这一行只剩地点
        assertEquals(
            "J3-101",
            widgetRowMeta(
                listOf(WidgetRowField.PERIODS, WidgetRowField.LOCATION),
                WidgetRowFields(
                    location = "J3-101",
                    periodsText = widgetPeriodsText(emptyList(), NO_PERIOD_GAP),
                ),
            ),
        )
        // 有节次时还是原样（PERIODS 在前，所以它在先）
        assertEquals(
            "1-2节 · J3-101",
            widgetRowMeta(
                listOf(WidgetRowField.PERIODS, WidgetRowField.LOCATION),
                WidgetRowFields(
                    location = "J3-101",
                    periodsText = widgetPeriodsText(listOf(1, 2), NO_PERIOD_GAP),
                ),
            ),
        )
    }

    @Test
    fun `有节次表时按墙钟间隔切段`() {
        // 真机取数走的是 periodGapMinutesOf(slotTimes)：第 5 节下课到第 6 节上课隔着 105 分钟午饭，
        // 组件必须写「5,6节」，不能写那张根本不存在的连堂（P1-2）
        val gap = periodGapMinutesOf(TimeSlotProfile.DEFAULT.toStartEndTimes())
        assertEquals("5,6节", widgetPeriodsText(listOf(5, 6), gap))
        assertEquals("1-2,9-10节", widgetPeriodsText(listOf(1, 2, 9, 10), gap))
    }
}
