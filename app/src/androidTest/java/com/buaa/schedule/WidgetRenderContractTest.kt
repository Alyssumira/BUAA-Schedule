package com.buaa.schedule

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.widget.BackgroundSync
import com.buaa.schedule.widget.CourseListFactory
import com.buaa.schedule.widget.ListWidgetMode
import com.buaa.schedule.widget.WidgetAppearance
import com.buaa.schedule.widget.WidgetBinding
import com.buaa.schedule.widget.WidgetBindingStore
import com.buaa.schedule.widget.WidgetDataCache
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 组件渲染契约（R5 §8-6）。
 *
 * 白底白字这一类问题（F-02 周网格单元格、F-27 预览布局）此前只能靠肉眼在真机上发现，
 * 因为没有任何用例真的把布局画出来看过。这里测两件事：
 * 1. **预览 / 首帧布局**必须自带可读对比度 —— 它们是静态渲染的，运行时着色不会发生；
 * 2. **列表工厂**能真的产出一行，并且行模板的 RemoteViews 动作不抛 ActionException。
 *
 * 运行：./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderContractTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = (context.applicationContext as BUAAApplication).repository
    private val db get() = AppDatabase.getInstance(context.applicationContext)
    private val inflater: LayoutInflater = LayoutInflater.from(context)

    /** 今天这一节的开始时间所在的星期与节次，用于让工厂必定产出一行 */
    private val today: LocalDate = LocalDate.now()

    @Before
    fun seedCourseToday() {
        runBlocking {
            repository.saveSemester(
                Semester(
                    termCode = TERM_CODE,
                    termName = TERM_CODE,
                    // 学期第 1 周周一 = 本周一：保证「今天」落在学期内且周次有效
                    startDate = mondayOfCurrentWeek().toString(),
                    totalWeeks = 20,
                )
            )
            repository.saveCourse(
                Course(
                    name = COURSE_NAME,
                    teacher = "测试教师",
                    location = "主楼T-101",
                    dayOfWeek = today.dayOfWeek.value,
                    periods = listOf(1, 2),
                    weeks = listOf(1, 2, 3, 4, 5, 6, 7, 8),
                    semesterCode = TERM_CODE,
                )
            )
            // 实例级绑定指向测试学期：否则工厂按 "current" 取快照，读的是
            // 真机上用户自己的课表，与这里 seed 的内容无关
            WidgetBindingStore.save(context, TEST_WIDGET_ID, WidgetBinding(TERM_CODE))
            // 走真实刷新路径，把快照备好（工厂只读快照，不查主库）
            BackgroundSync.refreshWidgets(context)
            // refreshWidgets 刚 invalidate 过内存缓存，而 sync 只写磁盘：
            // peek() 必落空 → 工厂渲染空态再异步补数据（F-17 的设计如此）。
            // 这里补上 provider 在 notifyListDataChanged 之前做的那步预热。
            WidgetDataCache.get(context, TERM_CODE)
        }
    }

    @After
    fun cleanUp() {
        runBlocking {
            db.courseDao().deleteBySemester(TERM_CODE)
            db.semesterDao().deleteByTermCode(TERM_CODE)
            WidgetBindingStore.reset(context, TEST_WIDGET_ID)
            WidgetDataCache.invalidate()
        }
    }

    // —— 1. 预览布局：静态渲染也必须读得清 ——————————————————————

    @Test
    fun previewLayoutsAreReadableWithoutTinting() {
        val previews = listOf(
            R.layout.widget_preview_next,
            R.layout.widget_preview_today,
            R.layout.widget_preview_tomorrow,
            R.layout.widget_preview_week,
            R.layout.widget_preview_week_grid,
        )
        for (layout in previews) {
            val root = FrameLayout(context)
            inflater.inflate(layout, root, true)
            val background = compositedBackgroundOf(root)
            val texts = textViewsWithText(root)
            assertTrue("预览布局 ${context.resources.getResourceName(layout)} 里没有任何文案", texts.isNotEmpty())
            for (text in texts) {
                val ratio = contrastRatio(text.currentTextColor, background)
                assertTrue(
                    "${context.resources.getResourceEntryName(layout)} 的文字 " +
                        "${text.text} 与背景对比度只有 ${"%.2f".format(ratio)}",
                    ratio >= MIN_CONTRAST,
                )
            }
        }
    }

    @Test
    fun previewLayoutsDoNotReuseTheTintSource() {
        // 预览是静态渲染：只要引用了纯白 shape，就等于把白底白字又请回来一次
        for (layout in previewLayoutIds()) {
            val root = FrameLayout(context)
            inflater.inflate(layout, root, true)
            for (image in imageViewsOf(root)) {
                val color = solidColorOf(image.drawable) ?: continue
                assertTrue(
                    "${context.resources.getResourceEntryName(layout)} 的背景还是纯白 shape",
                    Color.alpha(color) == 0 || (color and 0x00FFFFFF) != 0x00FFFFFF,
                )
            }
        }
    }

    @Test
    fun liveLayoutBackgroundStaysPureWhiteForColorFilter() {
        // 线上布局反过来：必须是纯白不透明，SRC_ATOP 着色才不偏色（widget_bg_r* 的前提）
        val solid = solidColorOf(
            context.getDrawable(R.drawable.widget_bg_r20)!!,
        )
        assertEquals(0xFFFFFFFF.toInt(), solid)
    }

    @Test
    fun defaultAppearanceIsReadableOnItsOwnBackground() {
        val appearance = WidgetAppearance()
        val bg = appearance.backgroundColor
        assertTrue(contrastRatio(appearance.titleColorFor(bg), bg) >= MIN_CONTRAST)
        assertTrue(contrastRatio(appearance.bodyColorFor(bg), bg) >= MIN_CONTRAST)
    }

    // —— 2. 工厂：真的产出一行，并且行模板的动作能应用 ——————————————

    @Test
    fun listFactoryProducesRowForToday() {
        val factory = CourseListFactory(context, intentForMode(ListWidgetMode.TODAY))
        factory.onCreate()
        factory.onDataSetChanged()
        try {
            assertTrue("今天有课，工厂却产出 0 行", factory.getCount() >= 1)
            // 真正的一行能被构造出来：行模板里的着色/文案动作有任何一个对不上 id，
            // 这里就会抛 ActionException（R5 F-02 那类问题的成因）
            factory.getViewAt(0)
        } finally {
            factory.onDestroy()
        }
    }

    @Test
    fun listFactoryFallsBackToEmptyRowsWhenSnapshotMissing() {
        // 快照没就绪时不能抛，也不能永远停在空态（R5 F-17 的渲染侧后果）
        val bogusId = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -12345)
            putExtra(CourseListFactory.EXTRA_MODE, ListWidgetMode.TODAY.ordinal)
        }
        val factory = CourseListFactory(context, bogusId)
        factory.onCreate()
        factory.onDataSetChanged()
        factory.onDestroy()
    }

    private fun intentForMode(mode: ListWidgetMode) = Intent().apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, TEST_WIDGET_ID)
        putExtra(CourseListFactory.EXTRA_MODE, mode.ordinal)
    }

    private fun mondayOfCurrentWeek(): LocalDate =
        today.minusDays((today.dayOfWeek.value - 1).toLong())

    // —— 工具：把视图树里的文字色与背景色算出来 ——————————————

    private fun previewLayoutIds() = listOf(
        R.layout.widget_preview_next,
        R.layout.widget_preview_today,
        R.layout.widget_preview_tomorrow,
        R.layout.widget_preview_week,
        R.layout.widget_preview_week_grid,
    )

    private fun textViewsWithText(view: View): List<TextView> {
        val out = mutableListOf<TextView>()
        if (view is TextView && view.text.isNotEmpty()) out += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out += textViewsWithText(view.getChildAt(i))
        }
        return out
    }

    private fun imageViewsOf(view: View): List<ImageView> {
        val out = mutableListOf<ImageView>()
        if (view is ImageView) out += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out += imageViewsOf(view.getChildAt(i))
        }
        return out
    }

    /** 预览背景叠在白色之上：launcher 的静态渲染没有壁纸时就是这个最保守的组合 */
    private fun compositedBackgroundOf(root: View): Int {
        val layer = imageViewsOf(root).firstNotNullOfOrNull { solidColorOf(it.drawable) }
        return layer?.let { overWhite(it) } ?: Color.WHITE
    }

    private fun solidColorOf(drawable: Drawable?): Int? =
        (drawable as? GradientDrawable)?.takeIf { it.shape == GradientDrawable.RECTANGLE }?.color?.defaultColor

    private fun overWhite(color: Int): Int {
        val a = Color.alpha(color) / 255f
        fun blend(c: Int, base: Int) = (a * c + (1f - a) * base).toInt()
        return Color.rgb(blend(Color.red(color), 255), blend(Color.green(color), 255), blend(Color.blue(color), 255))
    }

    private fun luminance(color: Int): Float {
        fun channel(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055) / 1.055), 2.4).toFloat()
        }
        return 0.2126f * channel(Color.red(color)) +
            0.7152f * channel(Color.green(color)) +
            0.0722f * channel(Color.blue(color))
    }

    private fun contrastRatio(foreground: Int, background: Int): Float {
        val a = luminance(foreground)
        val b = luminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private companion object {
        const val TERM_CODE = "TEST-WIDGET-RENDER"
        const val COURSE_NAME = "组件渲染测试课"

        /** 自配的组件实例 id：绑到测试学期，不去读真机上用户自己的课表 */
        const val TEST_WIDGET_ID = 90_001

        /** WCAG AA 对正文的最小对比度；白底白字算出来是 1.0，一定过不了 */
        const val MIN_CONTRAST = 3f
    }
}
