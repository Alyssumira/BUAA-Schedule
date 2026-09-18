# 备份与口令格式（v2）

> 本文是格式约定，改动必须同步版本号并在恢复侧做兼容。

## 1. 备份文件（`BackupData`，version 2）

JSON，顶层字段（全部有默认值，向后兼容新增字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `version` | Int | 当前 `2`；恢复时若版本比本应用新，确认卡片会禁用「恢复」按钮 |
| `semester` | Object? | `termCode / termName / startDate / totalWeeks` |
| `timeSlots` | Array? | `number / startTime / endTime`（HH:mm） |
| `courses` | Array? | `name / teacher? / location? / campus? / dayOfWeek / periods[] / weeks[] / colorIndex / customColorArgb? / sourceGroupKey? / isManualOverride / credit?` |
| `reminders` | Array? | `courseKey / enabled / advanceMinutes`（按课程内容签名而非数据库 id，恢复后 id 重排也能对上） |

要点：

- 恢复走 `repository.replaceSemesterCourses`：**先清空该学期再写入**，
  因此恢复是有损覆盖；确认卡片展示的是内容摘要（学期名 / 开学日 / 总周数 /
  课程数 / 手动课程数 / 节次数 / 提醒数）与版本校验结果，而非逐条差异。
- 导出与导入必须是同一套 schema：`设置 → 导出备份` 写的是本文件的 `BackupData`，
  「导出 WakeUp 兼容 JSON」是**另一条独立功能**，产出的是 WakeUp 格式，不能当备份导入。
- `customColorArgb` 越界（>0xFFFFFFFF）会被丢弃并回退调色板，不会让界面渲染出随机色。
- `credit`（学分）是**带默认值的可选字段**，因此 `version` **不 +1**：涨到 3 会让老应用
  （`data.version > CURRENT_BACKUP_VERSION` 直接拒绝恢复）打不开新备份，
  而这个多出来的键老应用会因 `ignoreUnknownKeys` 直接忽略，两边都读得懂，
  涨版本只换来一条更窄的升级路。
- `credit` 缺键 = null = 「不知道这门课的学分」，与教务真的返回的 `0.0`（不计学分）
  是两回事；备份是用户可控输入，越界值（负数 / `1e308` / NaN）在写库前由
  `CourseConstraints.normalizeCredit` 一并判成 null。往返与老文件兼容由
  `app/src/test/.../data/backup/BackupCreditCompatTest.kt` 钉住。

## 2. 课表口令（`ScheduleShareCodec`）

- 格式：`Deflate(JSON) → Base64`，聊天工具可直接粘贴互导。
- **安全上限（不可移除）**：
  - 解压结果 `MAX_DECOMPRESSED_BYTES = 4MB`（防解压炸弹，`Inflater` 逐段读取并在超限时停止）；
  - 课程条数 `CourseConstraints.MAX_COURSE_COUNT`（超过直接判无效）。
- 口令里**只有**课程数据（不含提醒/壁纸/个性化），接收方导入后仍需自己设置提醒。

## 3. 兼容性清单

改动任一格式时需要：

1. **只有语义变了**（同名字段含义改变、必填字段增删、解析分支切换）才把
   `BackupData.version` / 口令前缀 +1；只是加一个带默认值的可选字段（如 `credit`）
   **不要 +1** —— 老应用会因 `data.version > CURRENT_BACKUP_VERSION` 拒绝新备份，
   而 `ignoreUnknownKeys` 本来就让老应用能无痛读掉多余键；
2. 恢复侧保留旧版本解析分支（`version` 小于当前值时按旧语义填充默认字段）；
3. `MigrationTest` 与备份往返单测各加一条；
4. 本文与 README 同步更新。
