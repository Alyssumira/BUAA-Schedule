# 第三方声明

本文件写全每一段随本项目分发的第三方代码、以及每个"只是参考过"的项目：用了谁的什么、
用到哪里、按什么许可证用、从哪儿取的。README 里的「第三方与致谢」是这里的名单版；
改依赖或署名口径时，以本文件为准。

## 随安装包分发的代码

| 项目 | 许可证 | 用到哪里 |
| --- | --- | --- |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（tag 2.0.0） | Apache-2.0 | 液态玻璃的全部底子：`:kyant-backdrop` 子工程（背景采样与 AGSL 折射 / 高光 / 阴影），以及 `core/designsystem/liquid/` 的 `DampedDragAnimation`、`DragGestureInspector`、`InteractiveHighlight`、`LiquidBottomTab`（上游 catalog 里的同名组件）。本地改动：把上游的 KMP 结构（commonMain / androidMain）拍平成纯 Android 库，并把 Compose 1.11 / Kotlin 2.3 的 API 适配到本项目的组合 |
| 同一份库的 SleepDown 补丁 | Apache-2.0（沿用各文件头的声明） | `:kyant-backdrop` 里 14 个文件带着 `Modified for SleepDown …; upstream 2.0.0, Apache-2.0` 的头（共享模糊 `SharedBlurBackdrop`、取景录制缓存等）—— 我们是经 [SleepDown课程表](https://github.com/xiaomanjun233/SleepDown-Schedule) 仓库里的 `third-party/kyant-backdrop` 取得这份副本的，完整说明见下文「关于 Modified for SleepDown」 |
| [Kyant0/Shapes](https://github.com/Kyant0/Shapes) | Apache-2.0 | G2 连续曲率圆角形状，以源码形式内嵌在 `kyant-backdrop/…/com/kyant/shapes/`。曾记的「Maven 制品与本项目 Kotlin 2.0 不兼容」已核实为误（本项目一直用 Kotlin 2.3.10；`io.github.kyant0:shapes:1.2.0` 按 stdlib 2.3.10 编译、依赖 Compose ui 1.10.1，与本项目钉版本组合兼容），内嵌只是引入时沿用的形态，可换回 Maven 依赖，详见 `kyant-backdrop/README.md` |
| AndroidX / Jetpack：Compose BOM 2024.12.01（ui / foundation 1.11.3、material3 1.3.1）、Room 2.8.3、Navigation 2.8.5、WorkManager 2.9.1、Lifecycle 2.8.7、Core KTX 1.18.0 | Apache-2.0 | 常规运行库 |
| AndroidX CameraX 1.4.2（core / camera2 / lifecycle / view） | Apache-2.0 | 扫码页的取景与逐帧分析 |
| [Google ML Kit `barcode-scanning` 17.3.0（bundled）](https://developers.google.com/ml-kit/vision/barcode-scanning) | Apache-2.0 | 二维码解码。模型打进安装包，**不依赖 GMS**；解码库只保留 arm64-v8a，见 `RELEASE.md`「包体与 ABI」 |
| Kotlin 运行时与 kotlinx-coroutines 1.9.0、kotlinx-serialization-json 1.8.1 | Apache-2.0 | 常规运行库 |
| JUnit 4.13.2、androidx.test / Espresso / UiAutomator / benchmark | EPL-1.0 / Apache-2.0 | 只在测试里，不进 APK |

## 只参考过、没有取代码的项目

- [xingheyuzhuan/shiguang_warehouse](https://github.com/xingheyuzhuan/shiguang_warehouse)（拾光，MIT）：
  24 小时连续时间轴与"多个组件共用水源"的目标形态。
- [lingion/sleepy](https://github.com/lingion/sleepy)（GPL-3.0）：只借了「一键外观预设」的思路。
  已按文件名 + 相似度逐一比对过，**没有任何重合代码**；特此写明以免误会。
- [1812z/HyperIsland](https://github.com/1812z/HyperIsland)（MIT）：只读源码取证澎湃超级岛的
  载荷键名与白名单（结论与证据记在 `VENDOR_NOTES.md`），未取代码。
- **WakeUp 课程表**：只提供「导出 WakeUp 兼容 JSON」这一条格式兼容，无代码依赖。

## 关于 `Modified for SleepDown`

`:kyant-backdrop` 这批文件里，有 14 个带着 `Modified for SleepDown` 的头部注释，很容易读成
"这些代码属于 SleepDown"。实际情况是：**Kyant0/AndroidLiquidGlass 是一个独立的开源库（Apache-2.0），
SleepDown课程表 和我们一样是它的使用者**。它对该库的改动也写在这些保留 `com.kyant.*` 包名、
保留上游 Apache-2.0 声明的文件里（SleepDown 自己的 `THIRD_PARTY_NOTICES.md` 同样这么标注），
所以我们带出去的这一层始终在 Apache-2.0 之下，只需像现在这样保留来源注释。

SleepDown课程表 另有其自研的应用代码，那份许可（「署名-非商业、源码可见 1.1」）约束的是它。
把 `:app` 与 `:kyant-backdrop` 的每个 Kotlin 文件与它的仓库逐一比对过：重合的只有上面那批
`com.kyant.*` 文件，**没有一行来自它自研的业务代码**，因此本应用不是"基于 SleepDown 修改的版本"。
按它许可的精神，这里仍然显著注明取用位置并致谢：

> 液态玻璃库取自 <https://github.com/xiaomanjun233/SleepDown-Schedule> 的
> `third-party/kyant-backdrop`（其上游为 Kyant0/AndroidLiquidGlass，Apache-2.0）。

另外，澎湃超级岛的实况通知形状（哪些 extras 必填、chip 要多短、为什么不能用系统 chronometer）
是逐条对照 SleepDown 的真机做法定下来的，证据链记在 [`VENDOR_NOTES.md`](VENDOR_NOTES.md)。
本应用与 SleepDown 无关，不由其作者维护，也不代表其官方版本。
