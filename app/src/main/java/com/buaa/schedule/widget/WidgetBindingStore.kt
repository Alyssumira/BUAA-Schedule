package com.buaa.schedule.widget

import android.content.Context
import androidx.core.content.edit

/**
 * 单个桌面组件实例绑定的课表（学期）与浏览位置。
 *
 * 默认 `semesterCode == null` 表示“跟随应用当前学期”；用户可在组件配置页
 * 为每个实例单独绑定某个已导入学期，实现“外观逐实例 + 课表逐实例”。
 *
 * [weekOffset] 是「上一周 / 下一周」热区的浏览偏移（审查 3.5），同样逐实例保存：
 * 一个实例翻到下周不该影响桌面上另一个组件。取值在写入前已由
 * [clampWeekOffset] 夹在学期内，这里不再校验。
 */
data class WidgetBinding(
    val semesterCode: String? = null,
    val weekOffset: Int = 0,
    /**
     * [weekOffset] 是相对**哪一周**取的（教学周序号，null = 没有翻过）。
     *
     * 真实周推进时它必须一起失效：周日晚上点「下周」预览周一的课，周一本身就该
     * 是那一周了——还留着 +1 会让组件从此永久漂一周，而且没有任何入口能翻回来。
     */
    val weekOffsetBase: Int? = null,
)

/** 组件课表绑定的持久化：按 `appWidgetId` 实例级保存 */
object WidgetBindingStore {

    private const val PREFS_NAME = "widget_binding"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_SEMESTER = "semester"
    private const val KEY_WEEK_OFFSET = "week_offset"
    private const val KEY_WEEK_OFFSET_BASE = "week_offset_base"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(appWidgetId: Int, field: String) = "w$appWidgetId.$field"

    fun isConfigured(context: Context, appWidgetId: Int): Boolean =
        prefs(context).getBoolean(key(appWidgetId, KEY_CONFIGURED), false)

    fun load(context: Context, appWidgetId: Int): WidgetBinding {
        val p = prefs(context)
        if (!p.getBoolean(key(appWidgetId, KEY_CONFIGURED), false)) return WidgetBinding()
        return WidgetBinding(
            semesterCode = p.getString(key(appWidgetId, KEY_SEMESTER), null),
            weekOffset = p.getInt(key(appWidgetId, KEY_WEEK_OFFSET), 0),
            weekOffsetBase = p.getInt(key(appWidgetId, KEY_WEEK_OFFSET_BASE), NO_BASE)
                .takeIf { it != NO_BASE },
        )
    }

    fun save(context: Context, appWidgetId: Int, binding: WidgetBinding) {
        prefs(context).edit {
            putBoolean(key(appWidgetId, KEY_CONFIGURED), true)
            putString(key(appWidgetId, KEY_SEMESTER), binding.semesterCode)
            putInt(key(appWidgetId, KEY_WEEK_OFFSET), binding.weekOffset)
            putInt(key(appWidgetId, KEY_WEEK_OFFSET_BASE), binding.weekOffsetBase ?: NO_BASE)
        }
    }

    fun reset(context: Context, appWidgetId: Int) {
        prefs(context).edit {
            remove(key(appWidgetId, KEY_CONFIGURED))
            remove(key(appWidgetId, KEY_SEMESTER))
            remove(key(appWidgetId, KEY_WEEK_OFFSET))
            remove(key(appWidgetId, KEY_WEEK_OFFSET_BASE))
        }
    }

    /** 实例被移除时清理绑定 */
    fun remove(context: Context, appWidgetIds: IntArray) {
        prefs(context).edit {
            appWidgetIds.forEach { id ->
                remove(key(id, KEY_CONFIGURED))
                remove(key(id, KEY_SEMESTER))
                remove(key(id, KEY_WEEK_OFFSET))
                remove(key(id, KEY_WEEK_OFFSET_BASE))
            }
        }
    }

    private const val NO_BASE = -1
}