# kyant-backdrop（BUAA-Schedule 内嵌版）

液态玻璃渲染库，提供背景层采样（`LayerBackdrop`）与 AGSL 折射 / 高光 / 阴影
（`Modifier.drawBackdrop`）能力。

## 来源

- 上游：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) tag 2.0.0，Apache-2.0。
- 含 SleepDown-Schedule 项目的补丁（SharedBlurBackdrop 共享模糊等）。
  SleepDown 与本项目一样只是上述 Kyant 库的使用者，补丁写在保留 Apache-2.0 声明的原文件里，
  因此这批代码整体仍按 Apache-2.0 使用；取用位置是它仓库的 `third-party/kyant-backdrop`。
- `com/kyant/shapes/` 目录内嵌自 [Kyant0/Shapes](https://github.com/Kyant0/Shapes)（G2 连续曲率形状），Apache-2.0。
  早先此处写的「Maven 制品与本项目 Kotlin 2.0 不兼容」系不实，已更正：本项目一直是 Kotlin 2.3.10，
  而 `io.github.kyant0:shapes:1.2.0` 的清单就是按 stdlib 2.3.10 编译、依赖 Compose ui 1.10.1，
  与本项目的钉版本组合兼容。内嵌源码只是引入时沿用的形态，随时可换回 Maven 依赖。
- 本目录为 **纯 Android 库移植版**：将上游 KMP 结构（commonMain/androidMain）拍平，
  并对齐到本项目的组合（Compose ui / foundation 钉 1.11.3，Kotlin 2.3.10）：
  - `GraphicsLayer.record(size)` → `record(density, layoutDirection, size)`；
  - `LayerRecorder` 的 context parameter 改为显式 `DrawScope` 接收者；
  - `expect/actual`（Platform / RuntimeShader / Paint / RenderEffect）合并为 Android 单实现。

完整许可证见 [LICENSE](LICENSE)。
