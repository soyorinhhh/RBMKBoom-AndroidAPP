# 审计与修复报告

对 `RBMKBoom-AndroidAPP` 首版（AI 生成）的复查结论、改动清单与验收状态。
行号均指**修改前**的文件。

---

## 一、检查结果总览

| 模块 | 首版状态 | 结论 |
|---|---|---|
| Gradle 根目录 | AGP 8.2.0 / Gradle 8.2 / compileSdk 34 配置正确 | 保留，未重建 |
| gradlew | **缺失**（没有 wrapper） | 已生成完整 wrapper |
| GitHub Actions | 用 `android-actions/setup-android@v3` + 已弃用的 `gradle-build-action@v3`，且无 gradlew 可执行 | 已重写 |
| MainActivity | 有权限与投影授权流程 | 部分实现，已重写 |
| OverlayService | 有服务主体 | **有致命缺陷**，已重写 |
| MicSource | 有 AudioRecord | **有致命缺陷**，已重写 |
| CaptureSource | 有 MediaProjection 捕获 | **有致命缺陷**，已重写 |
| SpectrumAnalyzer | 有 FFT + 48 频段 | 部分实现，已修复 |
| SynthSource | 有模拟信号 | 8 秒周期本身正确，仅做预计算优化 |
| ReactorView | 倾斜点阵 | 视觉不达标，已重写 |
| QuickPanel | 有面板 | 缺状态反馈，已完善 |
| BackgroundManager | SAF + 采样解码 | 基本可用，保留 |
| SettingsActivity | Preference 页面 | 可用，补上了范围设置 |

---

## 二、P0：Android 12 启动闪退的真实原因

**这是本次最重要的发现，可解释「一启动就闪退」。**

`OverlayService.kt:388-391`：

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {   // API 29+
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    startForeground(NOTI_ID, noti, type)
}
```

`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 是 **Android 14（API 34）** 才引入的常量（值 `0x40000000`）。
在 Android 12（API 31）上把它传给 `startForeground()`，系统会校验「必须是 Manifest 中声明过的类型」，
而 31 上根本没有这个类型 → 抛 `IllegalArgumentException`。
`onCreate()` 第一行就调它，所以服务一创建就崩，悬浮窗永远出不来。

同一段代码还有第二个洞：`if (SDK >= Q)` 才调用 `startForeground`，
意味着 **API 26–28 完全不调用**。用 `startForegroundService()` 启动后 5 秒必抛
`Context.startForegroundService() did not then call Service.startForeground()`。

### 修复

按版本裁剪运行时类型（`computeForegroundType()`）：

| API | startForeground 调用 |
|---|---|
| 26–28 | 两参版本（无类型） |
| 29–33 | 仅在实际需要时带 `microphone` / `mediaProjection`；**不带 specialUse** |
| 34+ | `specialUse` 托底，再按需 OR 上 `microphone` / `mediaProjection` |

Manifest 里的 `foregroundServiceType="specialUse|microphone|mediaProjection"` 保留 ——
声明阶段未知位会被低版本忽略，**只有运行时传值才是致命的**。

---

## 三、P0：音频采集层

### MicSource

| # | 问题 | 后果 | 修复 |
|---|---|---|---|
| 1 | `read()` 返回值只判断 `n < FFT_SIZE` 就 `continue` | `ERROR_DEAD_OBJECT`(-3) 等会**立即返回**，变成 100% CPU 死循环，且永远不出声 | 区分负值：记录 `lastError`、停止运行、回调上层切回演示信号 |
| 2 | `stop()` 先 `join(400)` 再 `AudioRecord.stop()` | 读线程卡在阻塞读里，join 必然超时；随后 release 时读线程可能仍在用已释放对象 | 改为 **先 stop() 解阻塞 → join → 最后 release** |
| 3 | 编码写死 `PCM_FLOAT` | 部分机型/ColorOS 上初始化失败 | 改 **PCM 16bit**，读取与转换逻辑同步改写 |
| 4 | 只用 44100 | 某些设备不支持则直接失败 | 44100 → 48000 → 44100 逐个尝试 |
| 5 | 未校验 `recordingState` | `startRecording()` 静默失败也返回 true | 校验 `RECORDSTATE_RECORDING`，否则带原因返回 false |
| 6 | `UNPROCESSED` 优先 | 很多机型拒绝该源 | 改为 MIC → VOICE_RECOGNITION → UNPROCESSED |

### CaptureSource

| # | 问题 | 修复 |
|---|---|---|
| 1 | 未注册 `MediaProjection.Callback` | 已注册；用户点「停止共享」时立即标记失效并回调上层，避免继续读死对象 |
| 2 | 构造异常被 `catch (_: Exception) { false }` 吞掉 | 逐段 try 并把真实原因（投影失效 / 类型不支持 / 状态异常）写进 `lastError` |
| 3 | 同样的 `stop()` 顺序问题 | 同样改为 stop → join → release |

### SpectrumAnalyzer

- 输入不足 `fftSize` 会 **AIOOBE**（`pcm[offset + i]` 无边界检查）→ 改为零补齐 + 长度钳制。
- 频段取**均值**，低频窄带里单根谱线容易被平均掉 → 改为取**峰值**。
- 无平滑，逐帧闪烁 → 加**快升慢降**（attack 0.62 / decay 0.16）。
- 归一化按经验常数 → 按「满幅正弦 ≈ -12 dBFS」标定，换设备不用重调。
- 三角/旋转因子每帧现算 → 预计算 Hann 窗、位反转表、旋转因子表，分析过程零分配。

### AudioEngine（新增）

首版三个源各自持有 FFT + 各自开线程，切换时容易两个 `AudioRecord` 并存、互相抢录音资源。
改为**单线程分析**：所有源都通过 `PcmSource` 提供 PCM，引擎统一做 FFT、自适应增益、
诊断快照；渲染层只读 `snapshot()`，与 UI 帧率解耦。

---

## 四、P0：Android 12 麦克风后台限制

首版在 `QuickPanel` 里直接 `mic.start()`，而那时应用处于后台（只有悬浮窗），
Android 12 会拒绝把前台服务升级成 `microphone` 类型，`AudioRecord` 也拿不到「使用中」权限。

现在的策略：

1. 先在服务里尝试升级类型，捕获 `SecurityException`；
2. 失败则**引导回 MainActivity**（应用回到可见状态），由 Activity 发起 `ACTION_TOGGLE_MIC`；
3. 仍然失败就带着具体原因（权限 / 后台限制 / AudioRecord 初始化）提示并切回演示信号。

不绕过系统限制，只做合法的前台发起。

---

## 五、P0：MediaProjection 生命周期

修复后的顺序：

```
Activity 拿授权  →  service
  → 先 startForeground(类型含 mediaProjection)   ← 必须在 getMediaProjection 之前
  → getMediaProjection(resultCode, data)
  → registerCallback（投影被系统/用户停止时立刻切回演示信号并释放）
  → engine.switchTo(CAPTURE, projection)
  → 失败则释放 projection、降回不带 mediaProjection 的类型
```

并且 `releaseProjection()` 是幂等的：先 unregister 再 stop，重复调用安全。

---

## 六、P2：视觉重构（REACTOR 声场）

首版 `ReactorView` 的问题：7 圈点阵、每圈只取 **1 个频段**（48 个频段里只用了 7 个）、
小方块贴图、每帧 `Math.random()` + 新建 `RadialGradient`、`nodes.sortWith` 每帧排序。

重写为 **2.5D 极坐标模块阵列**：

- 14 环 × 最多 52 扇区（约 516 个模块），铺在被压扁的椭圆平面上形成透视；
- 每个模块 = 正面矩形 + 顶面「盖子」矩形，高度随对应频段升降 → 立体感；
- 深度（屏幕 y）决定大小与明暗，近大远小；
- 中心低频 → 外围高频，叠加角向抖动与固定抖动，避免整环同高变成同心圆条纹；
- 暗绿灰绿为主色，峰值混入琥珀色，少量模块固定带暖色点缀；
- 几何在 `onSizeChanged` / 换密度时一次算好，绘制时**不排序、不建对象、不建 Path**；
- 消失的 `RadialGradient` 只建一次缓存。

`RodDensity` 现在真正参与渲染（决定环数/扇区数），大窗降一档密度避免全屏糊成一片。

### 关于「降级到像素画」

**没有降级。** 当前实现就是项目书里的「第一方案」：Canvas 2D + 自定义坐标映射 + 2.5D 多边形。
性能上有依据：每帧约 516 个模块 × 2 次 `drawRect` ≈ 1000 次绘制，15fps 下每秒 1.5 万次，
对骁龙 695 的硬件 Canvas 是轻负载（同级别应用普遍在 1 万~5 万次/秒量级）。
真实帧率仍需真机验证，见第九节。

与参考图的差距（诚实说明）：参考图是 WebGL 真 3D，有光照、阴影和材质反光；
当前是 Canvas 平涂，模块只有两种面、无真实光照。构图、配色、模块密度、透视方向一致，
质感上属于「还原风格」而非「逐像素一致」。

---

## 七、P1：其余修复

| 文件 | 修改 |
|---|---|
| `MainActivity` | 诊断面板（采样率/编码/AudioRecord 状态/read 返回值/RMS/峰值/最近错误）；权限检查入口；`registerForActivityResult` 移到字段初始化（原写法在点击时注册会抛 `IllegalStateException`） |
| `QuickPanel` | 按当前音源高亮；弹出位置做屏幕边界收敛（原版可能弹到屏幕外）；`setColor(background)` 在 `apply{}` 里会解析成 `View.background`，已改名 |
| `SettingsActivity` | `SeekBarPreference` 的 `app:max`/`app:min` 在当前 androidx 版本不存在，aapt 链接阶段报 `attribute max not found`，改为代码里 `min=2 / max=30` |
| `ReactorConfig` | 读取时对非法值回落（帧率只允许 15/20/30）；新增 `bandCount`；`RodDensity` 改为 (rings, sectors) |
| `AndroidManifest` | 加 `POST_NOTIFICATIONS`；`specialUse` 相关加 `tools:targetApi="34"`；`allowBackup` 等保留 |
| `.github/workflows/build.yml` | 换 `gradle/actions/setup-gradle@v4`；不再用 `android-actions/setup-android`（它会装已废弃的 `tools` 包，实测 exit 1），改用 runner 自带 SDK + `sdkmanager` 补装 |
| `.gitignore` | 新增，避免把 `build/` 推上去 |

---

## 八、构建结果

```
Gradle 8.2 / AGP 8.2.0 / Kotlin 1.9.20 / JDK 17 / compileSdk 34
BUILD SUCCESSFUL
app/build/outputs/apk/debug/app-debug.apk
```

只有警告，无警告级以上的错误（未使用参数、`MOVE_TO_FOREGROUND` 与 `VIBRATOR_SERVICE` 的弃用提示）。

---

## 九、验收对照

| 编号 | 项目 | 状态 | 说明 |
|---|---|---|---|
| A01 | 工程构建 | ✅ 已验证 | 本地 Gradle 8.2 构建成功；CI 见 Actions |
| A02 | 安装启动 | ⚠️ 未验证 | 无真机/模拟器 |
| A03 | Synth 8 秒循环 | ✅ 代码验证 | 所有分量均为 1/8 Hz 整数倍，`t` 与 `t+8` 输出相同 |
| A04 | Mic 持续读取 | ⚠️ 未验证 | 逻辑已修（PCM16、错误码、stop 顺序），需真机确认 |
| A05 | Capture 捕获 | ⚠️ 未验证 | 同上，且取决于目标 App 是否允许被捕获 |
| A06 | 三种模式切换 | ✅ 代码验证 / ⚠️ 未验证 | 统一走 `AudioEngine.switchTo`，先停后起 |
| A07 | 资源释放 | ✅ 代码验证 | stop 顺序修正 + `@Synchronized` 幂等；未验证长期稳定性 |
| A08 | 小窗拖动吸附 | ⚠️ 未验证 | 逻辑保留并加固了异常处理 |
| A09 | 大窗仅桌面显示 | ⚠️ 未验证 | 依赖使用情况访问权限 |
| A10 | QuickPanel | ⚠️ 未验证 | 功能齐全，位置收敛已加 |
| A11 | 设置实时生效 | ✅ 代码验证 | `OnSharedPreferenceChangeListener` → 重载配置 → 重建几何 |
| A12 | 背景图仅小窗 | ✅ 代码验证 | 只有 `smallReactor` 接收 background |
| A13 | 视觉还原 | 🟡 部分 | 构图/配色/密度/透视还原，材质质感不及 WebGL 参考图 |
| A14 | 15/20/30 FPS | ⚠️ 未验证 | 帧循环按配置调度；真机帧率未知 |
| A15 | 异常不崩溃 | ✅ 代码验证 | 所有 `addView`/`startForeground`/音频初始化都有兜底 |

---

## 十、上真机后建议优先确认

1. 启动后是否还闪退（P0-1 是否真的修掉了 Android 12 上的 `IllegalArgumentException`）。
2. 主界面诊断面板里，麦克风模式下 `AudioRecord` 是否 `RECORDING`、`read 返回` 是否持续正数、RMS 是否随说话变化。
3. 机内音频：先在别的 App 播歌，再开捕获，看 RMS 是否非 0；若为 0 但 `read` 正常，多半是该 App 未允许被捕获。
4. 桌面大窗是否只在 Launcher 前台出现（需要「使用情况访问」权限）。
5. 15fps 下手机是否发热明显；如果掉帧，先把「模块密度」调到稀疏。
