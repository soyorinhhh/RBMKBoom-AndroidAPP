# 模拟器实测记录

**环境**：MuMu Player（Android 15 / API 35 / x86_64 / 960×540 横屏 / density 220）
**版本**：v2.0 之后的重构分支（含面板与尺寸修复），`com.example.reactor`，minSdk 26 / targetSdk 34
**方式**：adb install + appops 授权 + input 事件驱动，全程看 logcat 与截图

## 通过的项

| 项 | 结果 | 证据 |
|---|---|---|
| 安装启动 | ✅ | `am start` 后 `Displayed ... +2s475ms`，无 FATAL |
| 前台服务启动 | ✅ | `isForeground=true types=0x40000000`（API 34+ 走 specialUse 分支） |
| 小窗渲染 | ✅ | 截图可见 2.5D 模块阵列，绿色 + 琥珀点缀 |
| 大窗只在桌面 | ✅ | 按 HOME 后大窗出现，壁纸透出；回到 App 后隐藏 |
| 快捷面板 | ✅ | 长按弹出，标题 + ✕ + 全部选项 |
| 麦克风 | ✅ | 状态栏 `麦克风 · 运行中 · 44100Hz · RMS 0.049`，频谱随输入变化 |
| FGS 类型升级 | ✅ | 切麦克风后 `types=0x40000080`（specialUse \| microphone） |
| 崩溃 | ✅ 无 | 全程 logcat 无 FATAL / AndroidRuntime |

> 这是第一次在真实 Android 运行时上验证。之前「编译通过但未验证」的项目里，
> A01（构建）、A02（安装启动）、A06（音源切换）、A10（面板）现在有了实证。

## 实测发现并修掉的三个 bug

### 1. 面板窗口从没被加进 WindowManager（重构时漏的）

`onCreate` 里只加了大小窗，忘了 `wm.addView(panel.root, ...)`。面板只能等看门狗巡检时被补上，
日志实测晚了 **1.4 秒**：

```
16:11:51.037 Reactor/面板: 长按呼出，当前音源=演示信号
16:11:52.416 Reactor/窗口: 面板不见了，重新添加
```

真机上的表现就是「长按没反应，或面板闪一下就没了」——很可能就是反馈里「长按后选项锐减」的主因。

### 2. 面板比屏幕还高，最后几项看不到

960×540 横屏下 9 个选项 + 标题约 654px，超出屏幕，底部「退出悬浮窗」永远点不到。
改为固定高度上限 340dp（任何机型都放得下），超出部分交给 ScrollView 滚动。

### 3. Service 上下文拿到的屏幕尺寸可能是竖屏的

`resources.displayMetrics` 在 MuMu 上报 **540×960**，而实际显示是 960×540。
按它算出来的面板高度和窗口 x 坐标全偏。

- 服务侧改用 `WindowManager.currentWindowMetrics`
- 面板侧干脆不依赖屏幕尺寸，用固定 dp 上限

## 已知仍有

- **大窗闪现/短暂覆盖**：ColorOS 上仍偶发（本次模拟器未复现）。已加「连续两次检测到桌面才显示」的防抖，
  但使用情况访问的事件延迟不是应用能完全消除的。
- **机内音频捕获（MediaProjection）**：需要手动点系统授权弹窗，本次未自动化验证。
- **真机（OPPO PFUM10 / Android 12）**：仍未复测，上述结果来自 Android 15 模拟器。
