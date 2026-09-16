package com.buaa.schedule.data.import

/**
 * 日志用 URL 脱敏。
 *
 * SSO 跳转链上的 `ticket=ST-…` / `token=…` 是**一次性有效凭证**，直接打进 logcat
 * 等于把登录票据写进系统日志（任何有 READ_LOGS 权限的应用或 ADB 都能读）。
 * 这里只保留 `scheme://host/path`，查询串整体裁掉。
 *
 * 放在 data 层是因为登录页与 [BuaaWebSession] 两侧都要用它，而后者不该反向依赖 UI 包。
 */
internal fun redactUrl(url: String?): String {
    if (url == null) return "null"
    val cut = url.indexOf('?')
    val base = if (cut >= 0) url.substring(0, cut) else url
    return if (base.length > 80) base.take(80) + "…" else base
}
