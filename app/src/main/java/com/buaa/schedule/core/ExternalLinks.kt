package com.buaa.schedule.core

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * 用系统浏览器打开外部链接。
 *
 * runCatching + NEW_TASK：本应用没有任何"已安装浏览器"保证，抛 ActivityNotFound
 * 时静默即可，不能让一个跳转按钮把界面打崩；NEW_TASK 是因为调用方可能是
 * Application/非 Activity 的 Context。
 */
fun openExternalUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
