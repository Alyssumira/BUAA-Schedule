# 设计系统约束（琉光玻璃 Vivid Glass）

> 新界面 / 新组件开工前先读这篇。这里的数值不是建议，是既有界面已经在用的口径。

## 1. 令牌（`DesignTokens`）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `cornerPage` | 24dp | 页面级容器（底部栏、整页卡片） |
| `cornerPanel` | 18dp | 面板 / 分组卡片 |
| `cornerCourse` | 10dp | 课程卡、小色块容器 |
| `cornerPill` | 50 | 胶囊（分段的段内形状） |
| `spaceXS/S/M/L/XL` | 4/8/12/16/24dp | 间距刻度，**不要自造 10dp/18dp** |
| `minTouchTarget` | — | 可点击元素的最小触控尺寸 |
| `GLASS_TIER_OFF/STANDARD/ENHANCED` | 0/1/2 | 玻璃档位；增强档强度 ×1.3 |

## 2. 动效（`MotionTokens`）

- 三级时长：`DURATION_SHORT 180`（按压/开关）、`MEDIUM 260`（页面进出场）、`LONG 380`（弹窗/大面板）。
- 两条标准缓动：`EasingStandard`（位移/淡入淡出）、`EasingEmphasized`（需要"弹出感"的场合）。
- 一切动画都要包在 `if (LocalReduceMotion.current) … else …` 里，
  尊重系统「移除动画」/ 过渡动画缩放（`rememberReduceMotion()`）。
- 转场必须是 `EnterTransition.None` / `ExitTransition.None`，不能用透明度 0 兜底。

## 3. 玻璃表面（`GlassSurface`）

- 四种 `GlassVariant`：`CHROME`（底部操作栏/吸底栏）、`PANEL`（内容分组卡）、
  `COMPACT`（小控件）、`ALERT`（告警条，配合 `semanticTint`）。
- 选用规则：**吸底用 CHROME，内容块用 PANEL，行内小件用 COMPACT**；
  不要用 PANEL 去做吸底栏（增强档下 lens 高度会失真）。
- 语义色（错误/成功）只通过 `semanticTint` 传入，不要自己调 `surfaceTint`。
- 无 backdrop / API<31 时自动降级为 tint + 描边，**不要**为此在调用处写分支。

## 4. 排版

- `ScheduleTypography` 已补齐 10 级刻度；**禁止**直接写 `fontSize = 11.sp` 这类魔法数，
  否则会静默回落到 M3 默认值（此前的课程卡就是被这个坑过）。
- 强调用 `FontWeight`，不要用"换一个更小的字号"来表达层级。

## 5. 壁纸与玻璃的分层铁律

1. `SceneBackground` 的**录制层只放壁纸/渐变**；明度遮罩（scrim）画在录制层之外。
2. 壁纸模糊加在外层 `Modifier.blur`，同样不进录制层。
3. 违反上面两条的后果：降亮度/调模糊会把所有玻璃表面一起压暗或糊掉。
4. 个性化字段（模糊/亮度/取景缩放）存 SharedPreferences（`Personalization`），
   **不要**为此做 Room 迁移。

## 6. 设置/表单页布局（`SettingsStack.kt`）

参考 FolkPatch/APatch 的 SplicedColumnGroup 模式，本项目用玻璃材质重写为：

- **`SettingsGroup(title) { item { … } }`**：组内每条设置是**独立的玻璃片**，
  首尾 `cornerPanel`(18dp)、中间 6dp 圆角 + 2dp 间隙；组标题在卡片**外面**（`SectionHeader`）。
- **`SettingsRow` / `SettingsSwitchRow` / `SettingsValueRow`**：行内容器透明，
  背景由外层玻璃片提供；`SettingsSwitchRow` 整行可点（Role.Switch），Switch 的
  `onCheckedChange` 必须传 null，避免双重触发。
- 选型规则：
  - **选择类/开关类区块**（多课表列表、外观开关、组件说明）→ `SettingsGroup` 堆叠；
  - **表单类区块**（学期设置的多个输入框、节次时间的 14 行编辑、日历同步差异）→ 保持单张玻璃卡，
    因为输入框之间用 2dp 间隙切开反而更碎。
- 注意：`SettingsGroup` 的 scope 不是 composable 上下文，`remember`/`mutableStateOf`
  必须声明在 `SettingsGroup(...)` 调用之外（设置页 body 或上层），不能写在 `item {}` 之间。

## 7. 桌面组件

- 三种组件都是**列表型**（`RemoteViewsService` + `ListView` + `setEmptyView`），
  行布局 `widget_list_item.xml`：课程色条 + 名称 + 教室/节次 + 上课时间。
  不要再往里拼大段文本（截断、无色条、无法滚动）。
- RemoteViews 只有这几类可用调用：`setColorFilter` / `setAlpha` / `setImageResource` /
  `setTextColor` / `setRemoteAdapter` / `setPendingIntentTemplate` / `setOnClickFillInIntent`；
  背景必须是"纯白圆角 shape + 着色"的结构（见 `widget_bg_r*.xml`）。
- **配色来源**：`COLOR_MODE_CUSTOM`（自定义色）与 `COLOR_MODE_SYSTEM`
  （Material You 动态取色，Android 12+ 在更新时用 `Theme.DeviceDefault.DayNight`
  解析 `?android:attr/colorBackground`）。动态取色不需要单独一套布局。
- **背景层 id 必须是 `@android:id/background`**：官方要求，点组件启动应用才有平滑过渡动画。
- `notifyAppWidgetViewDataChanged` 必须在 `updateAppWidget` 之后调用，否则列表不会重拉。
- 刷新走事件驱动，不要加 `updatePeriodMillis` 轮询。
- provider 元数据：`previewLayout`（选择器实时预览）+ `widgetFeatures="reconfigurable|configuration_optional"`。
