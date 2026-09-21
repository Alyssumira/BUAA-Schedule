# 隐私与数据安全说明

本文说明本 App 收集、存储、传输哪些数据，以及各自的保护方式。适用于当前源码状态；
新增权限或数据流向时必须同步更新本文。

## 一句话总结

**课表数据只存在本机，网络只跟北航官方域名通信，没有任何第三方 SDK、遥测或云同步。**

## 数据清单

| 数据 | 存储位置 | 保护方式 |
| --- | --- | --- |
| 课程 / 学期 / 节次时间等课表数据 | Room 本地数据库 | 不排除出云备份（换机迁移是刚需），内容本身无敏感个人信息 |
| 教务会话 Cookie | SharedPreferences（`buaa_cookie_store.xml`） | AndroidKeyStore **AES-256/GCM** 加密后落盘，密钥不出设备安全硬件（`BuaaCookieStore.kt`） |
| 智学北航签到凭证（token / refreshToken / 学号 / rolecode） | SharedPreferences（`spoc_token_store.xml`） | 同上口径加密落盘（`SpocTokenStore.kt`），随备份排除；教务那边登出不会连带清它，反之亦然 |
| 会话保留标志 | SharedPreferences（`buaa_session.xml`） | 随备份排除 |
| 壁纸 URI、外观偏好 | SharedPreferences | 非敏感，随备份迁移 |
| 备份 / 口令导出 | 用户自己生成的文件 / 文本 | 见下方「导出与分享」 |

## 备份规则（换机 / 云备份时传什么）

`allowBackup` 开启（课表跨设备迁移是刚需），但鉴权材料被显式排除：

- **API 31+**：`res/xml/backup_rules.xml`（`dataExtractionRules`，覆盖云备份与设备迁移两个通道）
- **API < 31**：`res/xml/backup_rules_legacy.xml`（`fullBackupContent`）

两套规则都排除 `buaa_cookie_store.xml`（Cookie 密文）、`buaa_session.xml`
（会话保留标志）与 `spoc_token_store.xml`（签到凭证密文）。凭证密文离开本机后也无法解密
（密钥在设备安全硬件里），但把会话材料复制到别的设备没有任何正当用途，所以直接不迁移。
课表数据库**不**排除 —— 否则换机丢课表。

## 网络通信

- 只与北航官方域通信：`sso.buaa.edu.cn`（统一身份认证）、教务 `byxt` 页面及其接口、
  智学北航 `spoc.buaa.edu.cn`（签到）。不含任何第三方接口。
- 签到请求发往 `spoc.buaa.edu.cn/spocnewht/`，请求体只有 `{zjdm, czid, xh}`
  （班级标识 + 自己的学号），鉴权在请求头 `token` 上；除这一条以外不向该域发送课表数据。
- **扫码的图片不出本机**：二维码解码用的是打进 APK 的 MLKit 模型，帧数据全程在内存里，
  不落盘、不上传。相册识别走系统照片选择器（`PickVisualMedia`），所以本应用
  **不声明任何存储权限**。
- 不含任何统计 SDK、崩溃上报、广告、推送服务；App 自身没有任何遥测。
- 不主动发起 cleartext 明文流量（targetSdk 28+ 默认禁止，未声明豁免）。
- 凭证走 WebView 页面内 `fetch`（`credentials: include`），不会出现在原生网络栈的
  请求头里；Cookie 持久化前经 AndroidKeyStore 加密。
  （SPOC 相反：它的凭证本来就在请求头，原生直连。）

## 日志

- 登录跳转链上的 URL 统一经 `redactUrl()` 脱敏后才落 `Log`：
  `ticket=` / `token=` 等一次性凭证会被截断（`BuaaLoginScreen.kt`）。
- 页面内抓取只记录 `方法 + 路径 + 响应字节数`，不记录请求体、响应体与 Cookie。
- SPOC 登录页的 WebView console **只在 debug 构建打印**：release 下页面自己会把带
  `ticket` 的跳转地址打进 console。会话保存/清除只记「已填 / 已清除」这类状态，
  不记 token、refreshToken 与学号的值。

## 权限及用途

运行时权限的申请原则：**用到才申请，拒绝后给出路**——申请被拒（含用户勾选
「不再询问」）时，界面会给出对应的引导跳转（系统权限设置页 / 应用详情页），
不会反复骚扰，也不会让功能静默失效。

| 权限 | 用途 | 申请时机 |
| --- | --- | --- |
| `CAMERA` | 扫码签到（对着课堂二维码解 QR） | 进入扫码页时才申请；被拒时降级为「相册识别」（相册不需要相机权限）；解码库不在包里（MLKit 只带 arm64-v8a，进页面前由探针判掉）时相机与相册都不再承诺 —— 手输签到码入口已删除（现实里不存在可抄的码），这一页在该设备上用不了扫码签到，文案只说实话；每种情况都不静默失效 |
| `INTERNET` | 北航教务登录与课表抓取、智学北航签到 | 普通权限，安装即生效 |
| `POST_NOTIFICATIONS` | 上课提醒、上下课铃常驻通知 | 启动时自动申请一次（API 33+）；「提醒可靠性」页可再次申请，永久拒绝后跳转通知设置 |
| `SCHEDULE_EXACT_ALARM` | 课表提醒精确触发、桌面组件零点跨天刷新 | 特殊权限（API 31+，Android 14 默认拒绝）；「提醒可靠性」页与组件配置页提供授权跳转，未授权时提醒退化为非精确 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 课程实况前台服务（澎湃实况窗 / 流体云） | 普通权限，服务启动即用 |
| `ACCESS_NOTIFICATION_POLICY` | 上课自动勿扰、下课后恢复 | 首次开启勿扰功能时跳转系统「勿扰访问」授权页 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 「提醒可靠性」页引导关闭电池优化（仅跳转系统设置用） | 用户点击对应引导项时弹系统确认框 |
| `RECEIVE_BOOT_COMPLETED` | 开机后重排提醒 | 普通权限 |
| `READ_CALENDAR` / `WRITE_CALENDAR` | 日历同步（用户显式选择目标日历后才读写） | 点击「同步到系统日历」或「移除已同步日程」时申请；永久拒绝后提供「去系统设置」跳转 |

「常驻通知有没有被系统折叠」读的是 API 36 的 `canPostPromotedNotifications()`，
这是只读探针、**不需要权限**，所以本应用不声明 `POST_PROMOTED_NOTIFICATIONS`
（此前声明了却没有申请路径，属"文档承诺 > 代码行为"，R5 §7 / F-C10）。

桌面小组件本身不需要任何运行时权限；它依赖的精确闹钟授权状态在组件配置页
有诊断提示。

## 导出与分享

- **备份 JSON / WakeUp JSON / ICS**：由用户显式导出到自己选择的位置，内容为明文，
  含课程与教师信息 —— 请自行注意分享范围。
- **课表分享口令**（`BUAASCH1:...`）：**明文编码，无加密、无口令保护**。
  任何拿到口令的人都可以完整还原课表（课程、周次、教师、教室等全部字段）。
  口令只应发给明确的接收人；不需要保密性时（如公开群）建议只发截图。

## 联系

本项目为开源课程表工具，如对数据处理有疑问请提 issue。
