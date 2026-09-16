package com.buaa.schedule

import android.app.Activity

/**
 * 仅 debug 构建存在的空宿主 Activity。
 *
 * 用途：仪器测试需要一个「已 attach 到窗口、但内容为空」的宿主来验证视图挂载类行为
 * （例如 `BuaaWebSession.retain` 之后 WebView 是否仍在窗口内）。
 *
 * 不直接用 `MainActivity` 的原因：主界面有持续运行的 Compose 动画与玻璃渲染，
 * `ActivityScenario` 等待主线程 idle 会拖很久（实测单条测试 11 分钟）。
 * release 变体不含本类与对应的 manifest 声明。
 */
class DebugHostActivity : Activity()
