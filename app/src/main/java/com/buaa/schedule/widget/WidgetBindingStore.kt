package com.buaa.schedule.widget

import android.content.Context
import androidx.core.content.edit

/**
 * 单个桌面组件实例绑定的课表（学期）。
 *
 * 默认 `semesterCode == null` 表示“跟随应用当前学期”；用户可在组件配置页
 * 为每个实例单独绑定某个已导入学期，实现“外观逐实例 + 课表逐实例”。
 */
data class WidgetBinding(
    val semesterCode: String? = null,
)

/** 组件课表绑定的持久化：按 `appWidgetId` 实例级保存 */
object WidgetBindingStore {

    private const val PREFS_NAME = "widget_binding"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_SEMESTER = "semester"

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
        )
    }

    fun save(context: Context, appWidgetId: Int, binding: WidgetBinding) {
        prefs(context).edit {
            putBoolean(key(appWidgetId, KEY_CONFIGURED), true)
            putString(key(appWidgetId, KEY_SEMESTER), binding.semesterCode)
        }
    }

    fun reset(context: Context, appWidgetId: Int) {
        prefs(context).edit {
            remove(key(appWidgetId, KEY_CONFIGURED))
            remove(key(appWidgetId, KEY_SEMESTER))
        }
    }

    /** 实例被移除时清理绑定 */
    fun remove(context: Context, appWidgetIds: IntArray) {
        prefs(context).edit {
            appWidgetIds.forEach { id ->
                remove(key(id, KEY_CONFIGURED))
                remove(key(id, KEY_SEMESTER))
            }
        }
    }
}