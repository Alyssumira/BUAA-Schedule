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
| `GLASS_TIER_OFF/STANDARD` | 0/1 | 玻璃档位。**只有两级**：增强档连同 `glassIntensity()` 已删——设置页只暴露开/关，`load()` 又把档位夹回 0..1，没有任何入口能到达第三档 |
| `iconSmall/Medium/Large/Hero` | 16/20/24/40dp | 图标四档（此前 9 种尺寸散落）。空态主图标用 Hero |
| `cardAlphaScale` | — | 用户"卡片透明度"滑条到各玻璃表面的映射；滑条最低 0.3，归一后只剩 0.34 倍，**注释要与实际钳制范围一致** |
| `weekTimeColumnWidth` / `weekRowHeight` / `weekHourHeight` / `weekCompactVisibleDays` | — | 周视图几何：紧凑档一屏可见几天也在这里 |
| `dayHeightPerMinute` / `dayBlockTintAlpha` | — | 日视图时间轴：每分钟占多高、时间块着色多浓 |
| `fabLift` | — | FAB 弹出菜单的开合抬升量 |

## 2. 动效（`MotionTokens`）

### 2.1 令牌

- 三级时长：`DURATION_SHORT 180`（按压/开关）、`MEDIUM 260`（页面进出场）、`LONG 380`（弹窗/大面板）。
- 两条标准缓动：`EasingStandard`（位移/淡入淡出）、`EasingEmphasized`（需要"弹出感"的场合）。

**与 Material 3 官方 token 的对照**（实测自 androidx `compose.material3.tokens.MotionTokens` v0_103）：

| 本项目 | 值 | 最接近的 M3 档位 | M3 值 | 偏差 |
| --- | --- | --- | --- | --- |
| `DURATION_SHORT` | 180ms | `DurationShort4` | 200ms | −20ms |
| `DURATION_MEDIUM` | 260ms | `DurationMedium1` | 250ms | +10ms |
| `DURATION_LONG` | 380ms | `DurationMedium4` | 400ms | −20ms |
| `EasingStandard` | `(0.2, 0, 0, 1)` | `EasingStandardCubicBezier` | **完全一致** | — |
| `EasingEmphasized` | `(0.16, 0.78, 0.18, 1)` | `EasingEmphasizedDecelerate` | `(0.05, 0.7, 0.1, 1)` | 自研曲线 |

两点结论，**不要误传**：

1. 三级时长**都不是 M3 官方值**，而是从 SleepDown 调校的体感值 —— 三个都落在官方档位的中间
   （M3 的 16 档是 50 的倍数：Short1-4 = 50/100/150/200，Medium1-4 = 250/300/350/400，
   Long1-4 = 450/500/550/600，ExtraLong1-4 = 700/800/900/1000）。
   偏差都在 10–20ms，**低于人眼对时长的分辨阈值（约 20–30ms）**，所以不为了"对齐"而改值；
   但也别把它当成"M3 推荐值"去引用。
2. `EasingStandard` 与 M3 **逐参数相同**；`EasingEmphasized` 是自研的，M3 里没有这条曲线。
   改造时不要用 M3 的 `Emphasized`（`(0.2, 0, 0, 1)`，与 Standard 同值）去"纠正"它 —— 那会丢掉弹出感。

### 2.2 时长怎么选：按"面积与距离"分档

M3 的 16 档时长不能凭感觉挑。一条可执行的规则（Material 官方原文）：

> duration should increase as the area/traversal of an animation increases
> —— 动画覆盖的面积越大、位移距离越长，时长就越长。守住这条，全站过渡才有统一的"速度感"。

翻译成本项目的三级：

| 动画的规模 | 用哪档 | 例子 |
| --- | --- | --- |
| **屏内小元素**（面积几十 dp） | `DURATION_SHORT` 180 | 按压反馈、开关、菜单项、FAB 显隐、提示条 |
| **区块级**（卡片/一组控件） | `DURATION_MEDIUM` 260 | 设置抽屉展开、卡片高度变化、列表增删、视图切换 |
| **整页 / 全屏** | `DURATION_LONG` 380 | 页面转场、大面板、需要"弹出感"的浮层 |

### 2.3 缓动怎么选

- `EasingStandard` —— 位移、淡入淡出，任何"从 A 到 B"的位置变化。
- `EasingEmphasized` —— 需要"弹出感"的场合（FAB 图标旋转、菜单展开、大面板出现）。
- **关闭方向不要用打开方向的倒放**：`LiquidMenu` 的开/合用了两组独立曲线
  （`OpenPositionEasing` / `CloseEasing`），关闭更快更直接 —— 这是有依据的，别为了"统一"合并。

### 2.4 转场模式：Material 的四类，对应到本项目

不要每处自己发明转场。先判断两个界面之间是什么关系，再选模式：

| 模式 | 表达的关系 | 本项目的用法 | M3 参考时长 |
| --- | --- | --- | --- |
| **Container transform** | 同一实体在两种形态间变化（可见的容器连接） | 课程卡 → 编辑器（已用 `sharedElement`）✓ | 进入 300 / 退出 250 |
| **Shared axis** | 有空间或导航关系（前后、上下、层级） | 横滑翻周（`HorizontalPager`）、日视图翻日期、设置根页 → 分类子界面 | 300 |
| **Fade through** | **无强关联**的目的地切换 | 底栏三个 tab 之间 | 300 |
| **Fade** | 屏内元素的显隐 | 对话框、弹出菜单、提示条、FAB | 进入 150 / 退出 75 |

⚠️ **用 container transform 时，destination 的普通过进场要让位** ——
把该 `composable` 的 `enterTransition`/`exitTransition` 设为 `EnterTransition.None`。
否则整页在滑、共享元素也在飞，两个位移动作互相干扰，用户不知道该跟哪一个。

### 2.5 reduce-motion：Android 有**三个**独立开关，不是一个

`rememberReduceMotion()` 现在只读 `TRANSITION_ANIMATION_SCALE`。但 Android 把动画时长拆成了三个
**互不相干**的全局项：

| 开发者选项 | Settings key | 影响 |
| --- | --- | --- |
| 窗口动画缩放 | `WINDOW_ANIMATION_SCALE` | Activity/窗口切换 |
| 过渡动画缩放 | `TRANSITION_ANIMATION_SCALE` | Activity 转场、部分系统组件 |
| Animator 时长 | `ANIMATOR_DURATION_SCALE` | `ValueAnimator`，**Compose 的动画主要看它** |

无障碍设置里的「移除动画」（Android 13+）会把三者**一起置 0**，所以从无障碍入口进来是能生效的；
但开发者选项里用户可以只关其中一个，此时只读一项的检测会漏。

**约束**：判定改为"**任一项为 0 即为需要减少动画**"，不要依赖"哪个才是对的"这种判断。

### 2.6 高频交互：只要反馈，不要"表演"

Apple HIG 原文：

> In apps, generally avoid adding motion to UI interactions that occur frequently.
> —— 高频交互上加动效，等于让人每次都花额外时间注意它。

本项目的**高频交互**是：课程卡点击、底栏切页、列表滚动、周次切换、日视图翻日期。
这些地方的动效必须**即时、极短（≤150ms）、无需注意**；需要"被看见"的动效只能放在
低频场景（首次进入、保存成功、冲突出现）。

反过来说：**高频交互不能没有反馈**。课程卡走自定义手势路径时连 `clickable` 都不加，
按住的前 500ms 没有任何视觉变化 —— 这属于"反馈缺失"，不属于"避免动效"。

### 2.7 一切动画都要尊重 reduce-motion

```kotlin
// 唯一入口，新动画只写这一行
animateXAsState(..., animationSpec = motionSpec(), ...)
```

- 一切动画都要包在 `if (LocalReduceMotion.current) … else …` 里（或直接用 `motionSpec()` 收口），
  尊重系统「移除动画」/ 过渡动画缩放（`rememberReduceMotion()`）。
- **转场必须是 `EnterTransition.None` / `ExitTransition.None`，不能用透明度 0 兜底。**
- 用 `snap()` 而不是 `durationMillis = 0` 的 `tween`：后者仍会走一帧调度，
  并在 `AnimatedVisibility` 的 enter/exit 组合里留下一个"空动画"帧。

### 2.8 新动画开工自查

- [ ] 时长是从 §2.2 的三档里挑的，不是随手写的数（`220` / `300` 这类值要归档）
- [ ] 缓动是 `EasingStandard` 或 `EasingEmphasized`，没有自造曲线
- [ ] 写了 `motionSpec()`（或等价的 reduce-motion 分支）
- [ ] 关闭方向用了独立曲线，不是倒放
- [ ] 如果是高频交互：时长 ≤150ms 且不抢注意力
- [ ] 如果用了 `sharedElement`：destination 的进出场已设为 `None`
- [ ] 所有 `animateXAsState` 都带 `label =`，便于性能分析定位

### 2.9 延伸阅读

- Material 3 动效规范：`m3.material.io/styles/motion`（转场模式、缓动与时长）
- androidx 权威值：`compose/material3/tokens/MotionTokens.kt`（本节的对照表就是从它实测的）
- Apple HIG · Motion：`developer.apple.com/design/human-interface-guidelines/motion`
- WWDC「Designing Fluid Interfaces」(2018) 与 WWDC24 的动效 session：
  手势跟随、可中断动画、"不要让人等动画播完"这三条原则的出处


## 3. 玻璃表面（`GlassSurface`）

- 四种 `GlassVariant`：`CHROME`（底部操作栏/吸底栏）、`PANEL`（内容分组卡，**透明磨砂**）、
  `COMPACT`（小控件）、`ALERT`（告警条，配合 `semanticTint`）。
- 选用规则：**吸底用 CHROME，内容块用 PANEL，行内小件用 COMPACT**；
  不要用 PANEL 去做吸底栏（`PANEL` 刻意不折射、tint 也最薄，吸底要的折射与高光它没有）。
- 语义色（错误/成功）只通过 `semanticTint` 传入，不要自己调 `surfaceTint`。
- 无 backdrop / API<31 时自动降级为 tint + 描边，**不要**为此在调用处写分支。
- **深色主题刻意关掉 vibrancy**（`GlassSurface`：`if (darkTheme) base.copy(useVibrancy = false)`）。
  vibrancy 会把采样到的背景提亮、增饱和 —— 浅色档下这正是"通透"的来源，深色档下等于往浅色正文底下
  垫一块亮斑，正文对比度被直接吃掉。**已知取舍**：于是深/浅两套玻璃不是同一块材质，深色更"实"，
  层次靠折射与内外阴影而不是色散。**不要为了观感统一把它加回来**。
- **`PANEL` 是透明磨砂，不是厚玻璃**（真机反馈「大块玻璃太多了有点丑」）：走
  `DesignTokens.panelMaterial(blurDp)` —— `lensHeight/lensAmount` 归零（折射那条亮暗带就是"一摞玻璃板"
  的来源）、`surfaceAlpha` 压到 `PANEL_SURFACE_ALPHA`(0.18) 一档、`useVibrancy = false`、内外阴影减淡。
  模糊半径是用户偏好 `Personalization.panelBlurDp`（设置页「面板模糊」滑杆：拖动期间只改本地草稿，
  松手才写全局 + 落盘 —— 材质是 effect 的 key，每帧写等于让整屏面板重算 blur）。
  底板薄到什么程度仍由 `legibilityAlphaFloor` 兜底，所以这条只是"意图值"，**不是**绕过对比度下限。
  `ALERT` 刻意保留 `dialog()`：提示条不能被糊成背景的一部分。
- **kyant 的 `Shadow` / `InnerShadow`：`alpha` 是图层倍率，`color` 才决定画刷浓度**
  （`ShadowNode` 记 `layer.alpha = shadow.alpha`，画刷用 `shadow.color`），而两个默认色自带 0.1 / 0.15。
  把浓度写进 `alpha` 会被乘第二次：`shadowAlpha = 0.14` 实际得到 ≈0.014，掉一个数量级——
  这就是"玻璃阴影画了但看不见"的来源。统一走 `LiquidGlassMaterial.outerShadow()` / `innerShadow()`，
  它们把浓度钉在 `color` 上；课程卡 `courseCard()`、弹出菜单 `popup()` 也从这里取值，**不要再手抄参数**。
- **降级底板只有一个**：`Modifier.degradedPlate(shape, tint, borderColor)` + `degradedPlateAlpha(h)`。
  三条降级路径（`GlassSurface` 面板、`liquidGlass` fallback、周视图课程卡）共用"底板 + 1dp 分界描边"，
  描边色按**底板亮度**取黑白两侧；只有拿得到 `ColorScheme` 的 `GlassSurface` 用语义色 `outlineVariant`。
  调用处**不要**为"有没有 backdrop"再写一套分支。
- **两套对比度兜底按需求选**：要先定前景色再算底板 → `legibleTintPlate`；
  前景色已定、只求底板最小 alpha → `legibilityAlphaFloor`。二者收敛到同一实现，分开只为堵"随手挑一个、长期分叉"。
  黑/白取色的交点在 **luma ≈ 0.203**，不是 0.45；拿 0.45 当阈值会让 0.203–0.45 这一整带白字压浅背景。

### 3.1 浮层分两层：玻璃层 = 导航与浏览，M3 层 = 模态与决策

这款 App 的识别度在玻璃上，但**玻璃的透明度在决策场景里是负资产**，所以浮层刻意分两层，
不要为了"看起来统一"把两边合并：

| 层 | 用于 | 组件 |
|---|---|---|
| **玻璃层** | 导航与浏览：顶栏、底栏、FAB、FAB 弹出菜单、课程卡长按菜单、分段控件、提示条、面板 | `LiquidMenu` / `LiquidFab` / `GlassSurface` / `GlassSegmentedControl` |
| **M3 层** | 模态与决策：确认框、破坏性操作、表单弹层 | `AlertDialog` / `ModalBottomSheet` |

课程卡长按菜单原本是 `DropdownMenu`（M3 默认浮层）——用户最常调用的浮层反而掉在玻璃语言外面，
现在统一走 `LiquidMenu`（`WeekView.kt` 的 `CourseMenuOverlay`）。

**把浮层从 Popup 搬进组合里要自己补三件事**，`DropdownMenu`/`Popup` 本来是免费给的：

1. **点外面关闭**：自己铺一层透明、无涟漪的拦截区（`CourseMenuOverlay` 里那个 `matchParentSize` Box）；
2. **返回键关闭**：自己挂 `BackHandler(enabled = …)`；
3. **别弹出可视区**：菜单尺寸由组件公开（`LiquidMenuWidth` / `liquidMenuHeight(count)`），
   调用方拿它做夹取；下方放不下就翻到按压点上方（`menuOrigin` 跟着换角，
   否则展开动画会朝背离按压点的方向长）。

## 4. 排版

- `ScheduleTypography` 已补齐 10 级刻度；**禁止**直接写 `fontSize = 11.sp` 这类魔法数，
  否则会静默回落到 M3 默认值（此前的课程卡就是被这个坑过）。
- 强调用 `FontWeight`，不要用"换一个更小的字号"来表达层级。
- **页面级与区块级标题必须拉开**：`headlineSmall` 26sp / 行高 34，`titleLarge` 那一档留给区块。
  22 与 24 的差在玻璃上读起来是同一级，等于没有层级。
- `labelSmall` 现在是 `labelMedium` 的**同值别名**（12sp）。两个名字钉在一起是有意的：
  M3 组件内部（如 `NavigationBar`）会读 `labelSmall`，放任它回落到默认 11sp 就会重现
  "名义 12sp、实际 11sp"那条断链。**新代码一律写 `labelMedium`**，`labelSmall` 只留作那道守卫。

## 5. 壁纸与玻璃的分层铁律

1. `SceneBackground` 的**录制层只放壁纸/渐变**；明度遮罩（scrim）画在录制层之外。
2. 壁纸模糊加在外层 `Modifier.blur`，同样不进录制层。
3. 违反上面两条的后果：降亮度/调模糊会把所有玻璃表面一起压暗或糊掉。
4. 个性化字段（壁纸模糊/亮度/取景缩放，以及卡片底下那一块的 `panelBlurDp`）存 SharedPreferences
   （`Personalization`），**不要**为此做 Room 迁移。壁纸模糊糊的是背景本身，
   `panelBlurDp` 糊的是玻璃面板采到的那一块，两者互不替代。

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

- **五种组件、三种渲染机制**：今日 / 明日 / 本周是**列表型**（`RemoteViewsService` + `ListView` + `setEmptyView`，
  行布局 `widget_list_item.xml`：课程色条 + 名称 + 教室/节次 + 上课时间）；
  本周网格 4×2 是**网格型**（`GridView` 7 列，每格一天的摘要）；
  「下一节课」2×1 是**单卡型**（不走 `RemoteViewsService`，直接一次 `setTextViewText` 回答"下一节是什么、几点在哪"）。
  不要再往里拼大段文本（截断、无色条、无法滚动）。
- **显示内容（列表型 + 下一节课）**：`WidgetAppearance.rowFields: List<WidgetRowField>?`，**null = 按组件类型沿用各自的历史默认口径**
  （新增字段不需要迁移）。可勾字段按 Provider 分档：列表型 `LIST_ROW_FIELD_CHOICES` / 默认
  `DEFAULT_LIST_ROW_FIELDS`，「下一节课」`NEXT_ROW_FIELD_CHOICES` / `DEFAULT_NEXT_ROW_FIELDS`。
  拼接顺序 = 配置页的勾选顺序；某天为空的字段**整段缺席**，
  不留悬空 `· `；教室单独选中时空地点兜底成「教室未定」。**预设配色不得清掉这份勾选**
  （`copy(rowFields = appearance.rowFields)`）。4×2 不给这个面板（勾选项为空列表即整块隐藏）：一格里放两行会把节数预算砍半。
- **行指纹（`getItemId`）必须折进每一个被画出来的值**：教师、周次摘要、是否今天、网格所在周、外观档位。
  漏一个，宿主就会继续供给缓存行——改动看着"没生效"。
- **翻周**：表头两侧的「上周 / 下一周」发 `WidgetNavigation.ACTION_BROWSE_WEEK`，
  用**显式组件意图**发给自己的 Provider（因此不需要在 manifest 加 intent-filter）。
  两个按钮的 `PendingIntent` requestCode 必须不同（判等不含 extras）。
  偏移存在 `WidgetBindingStore` 且**绑住它所属的真实周** `weekOffsetBase`：真实周一推进后偏移自动作废，
  否则周日晚"预览下周"会在周一凭空多跳一周。四处渲染点统一走 `displayWeekOf(semester, today, binding)`。
- **组件 → App 的 deeplink 键只有一个真源**：`WidgetNavigation`（`EXTRA_DAY_OF_WEEK`）
  与 `MainActivity.EXTRA_COURSE_ID`。4×2 的格子点击语义是**那一天**，不带课程 id
  （一格最多叠 5 门课，"第一节课"是任意的）；首页收到后切日视图并落到那一周的对应星期。
- **今天**：4×2 的当天列头有胶囊高亮，胶囊内墨色走 `onTodayHighlightFor(bg)` 取**反侧**，
  因此永远不会白胶囊压白底。
- **4×2 短名**：`weekGridShortName` 优先保留括号内首字（`大学物理(上)`→`大学上`）；
  无括号且末位是 ASCII 字母/数字（且主干含非 ASCII）时让 1 字给后缀（`高等数学A`→`高等A`）。
- RemoteViews 只有这几类可用调用：`setColorFilter` / `setAlpha` / `setImageResource` /
  `setTextColor` / `setRemoteAdapter` / `setPendingIntentTemplate` / `setOnClickFillInIntent` /
  `setTextViewTextSize`（**仅 Android 13+**，低版本改不了字号）；
  背景必须是"纯白圆角 shape + 着色"的结构（见 `widget_bg_r*.xml`）。
  ⚠️ 底图是纯白素材，所以**布局里的默认字色必须是深色**：着色前的那一帧（以及着色失败的场合）
  白字压白底等于内容凭空消失（`widget_week_grid_item.xml`）。
- **4×2 周网格的字号档位**：`WidgetAppearance.gridMaxLines`，密集档每格 5 节 / 9sp（默认，
  与历史显示一致），宽松档每格 3 节 / 10sp。列宽只有约 30dp，字号与节数只能取其一，
  所以交给用户在组件配置页权衡；两档都要与 `widget_week_grid_item.xml` 的 `maxLines` 对上。
- **配色来源**：`COLOR_MODE_CUSTOM`（自定义色）与 `COLOR_MODE_SYSTEM`
  （Material You 动态取色，Android 12+ 在更新时用 `Theme.DeviceDefault.DayNight`
  解析 `?android:attr/colorBackground`）。动态取色不需要单独一套布局。
- **背景层 id 必须是 `@android:id/background`**：官方要求，点组件启动应用才有平滑过渡动画。
- `notifyAppWidgetViewDataChanged` 必须在 `updateAppWidget` 之后调用，否则列表不会重拉。
- 刷新走事件驱动，不要加 `updatePeriodMillis` 轮询。
- provider 元数据：`previewLayout`（选择器实时预览）+ `widgetFeatures="reconfigurable|configuration_optional"`。

## 8. 触控与无障碍

2026-09 的 UI/UX 审查（U-04…U-16）收口后钉下来的口径：

- **modifier 顺序法则**：`defaultMinSize` / `padding` 必须写在 `clickable` / `toggleable`
  **之前**。写后面的撑大的是内容区，点不到的还是点不到 —— 这是六处触控目标修完仍不生效的原因。
- 开关行的语义走 `toggleable(value=…, role=Role.Switch)`，尾部 `Switch` 的
  `onCheckedChange` 传 `null` 只做视觉；普通行 `clickable(role=Role.Button)`；
  分段/视图切换用 `selectable(role=Role.Tab)`；折叠分组头用 `Role.DropdownList`
  并给 `stateDescription`（"已展开/已收起"）。
- **展开/收起这类状态只能读逻辑布尔值**，不要拿动画中间值判等（`chevronRotation == 0f`
  在动画期间会翻脸）。
- 只有拖拽能完成的动作，必须另给一条非拖拽路径（课程卡的自定义动作
  "移动到其他时间" + 长按菜单"移动到…"），且两条路径要汇到同一个确认弹窗。
- 表单：纯整数字段才用数字键盘；能出现 `-`、`,`、`:`、汉字的字段保持默认键盘。
  字段校验用 `isError` + `supportingText` 就地给出，不要只在保存时弹一条汇总。
- **读屏文案与视觉文案同源**：课程卡的 TalkBack description 复用 `courseCardMeta()`，
  所以"看到教师"与"听到教师"不可能不一致。副信息被偏好切走时，读屏也一起切。

## 9. 信息呈现口径（课表与组件共用）

- **给用户看的课程名一律 `Course.displayName`**（别名非空即生效）：周视图卡片、日视图（含进行中/下一节）、
  课程详情卡、撤销与删除提示、拖拽落点确认弹窗、冲突向导（清单与"建议 X 移到…"）、**五种组件**、
  上课铃通知、明日预览、课程进行中实况。
  课程管理页同样显示 `displayName`，但别名生效时补一句「原名 X」——那一页是管数据的，得看出别名挂在哪门课上。
  **继续用 `name` 的只有五类场合**：JSON/日历导出的数据真源、导入去重键、冲突处理的版本指纹、
  管理页的分组键（`groupKeyOf`）与原名提示、导入预览的冲突对与逐行明细和编辑器的「课程名称」输入框
  （预览对象是即将写入的教务数据，那一刻别名还不存在）——
  别名是显示层的事，不该改库、也不该改"这两行是不是同一门课"的判定。
  管理页的搜索框两个名字都匹配（用户只会打自己见过的那个）。
- **卡片副信息是二选一，不是两行都要**：`Personalization.courseCardMetaPreference`
  （教室优先 / 教师优先，设置页「课程卡副信息」）→ `courseCardMeta()`。
  选中侧为空时**自动回落另一侧**，所以永远不会出现"教师没登记 → 副信息凭空少一行"。
  行数预算（按卡片高度分配 1/2/3 行标题）不受这个偏好影响，别为它加行。
- **空状态用 `EmptyState(icon, title, description, actions)`**（`GlassSurface` PANEL 档），
  首页首启 / 周视图本周无课 / 日视图三处共用。要求：说清**为什么是空的**（假期中 vs 这天没课），
  并给一条**当下就能点的出路**（回到本周 / 查看明天 / 从教务导入）。
  叠在内容上的空态用 `matchParentSize()`，覆盖整屏的引导态才用 scrim。
- **周次要看得见，分三层**：顶部异动条（本周与上周按课程 id **集合**比对，两段课只算一门；
  措辞是"与上周不同"，不是告警）、卡片右上角的极小三角（`weeks` 比学期短；画在文本预算之外，不吃内容空间，
  读屏补一句"不是每周都有"）、组件与长按菜单的周次文案（只有确实非全学期时才出现）。
- **冲突要能分辨"和谁冲突"**：同一时段的课用 `conflictOverlapRanks()` 算出层级，
  卡片起始边按 `conflictStagger(rank)` 错开（8dp/级，最多 4 级）——只靠红色描边的话，两张卡重叠时描边互相盖住。
  拖拽进行中错开量置 0，否则落点视觉与吸附目标不一致。警告图标用 `iconSmall`（16dp）。
- **地点为空时也要占一行**：日视图与时间轴统一显示「教室未定」（0.55 alpha 的淡墨，别用正常正文浓度，
  否则会读成"真有一个叫未定的教室"），卡片因此等高，两视图口径也一致。
