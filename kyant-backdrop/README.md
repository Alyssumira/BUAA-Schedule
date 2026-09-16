# kyant-backdrop（BUAA-Schedule 内嵌版）

液态玻璃渲染库，提供背景层采样（`LayerBackdrop`）与 AGSL 折射 / 高光 / 阴影
（`Modifier.drawBackdrop`）能力。

## 来源

- 上游：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) tag 2.0.0，Apache-2.0。
- 含 SleepDown-Schedule 项目的补丁（SharedBlurBackdrop 共享模糊等）。
- `com/kyant/shapes/` 目录内嵌自 [Kyant0/Shapes]（G2 连续曲率形状），Apache-2.0。
  Maven 上的 io.github.kyant0:shapes 全部版本均以 Kotlin 2.3 编译，与本项目
  Kotlin 2.0 不兼容，故直接内嵌源码。
- 本目录为 **纯 Android 库移植版**：将上游 KMP 结构（commonMain/androidMain）拍平，
  并把 Compose 1.11 / Kotlin 2.3 的 API 适配到本项目的 Compose 1.7 / Kotlin 2.0：
  - `GraphicsLayer.record(size)` → `record(density, layoutDirection, size)`；
  - `LayerRecorder` 的 context parameter 改为显式 `DrawScope` 接收者；
  - `expect/actual`（Platform / RuntimeShader / Paint / RenderEffect）合并为 Android 单实现。

完整许可证见 [LICENSE](LICENSE)。
