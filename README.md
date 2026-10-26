# Unbar

一个面向 Android 16 / HyperOS 3 的小型 LSPosed 模块。当用户选择的应用位于前台时，
它会在 `system_server` 中阻止顶部边缘手势显示临时状态栏，从而避免隐藏状态栏位置出现
黑色临时栏。设置界面支持搜索、多选应用和“仅横屏时启用”。

模块只处理顶部状态栏，不拦截底部导航栏、返回、主页或最近任务手势。

## 安装

1. 保持 KernelSU、Zygisk Next 和 LSPosed/Vector 已启用。
2. 安装构建得到的 APK（见下方“构建”）。
3. 在 LSPosed 中启用模块，作用域只选择“系统框架（android）”。
4. 重启手机。
5. 从桌面打开“Unbar 状态栏拦截”，选择应用并保存。

首次打开设置界面时默认选中 `com.tencent.tmgp.sgame`。保存后配置立即生效，无需再次重启。

## 构建

需要 JDK 17 或更新版本，以及安装了 Android API 35 的 Android SDK。首次构建时 Gradle Wrapper 会下载 Gradle 8.10.2，Android Gradle 插件及其依赖也需要联网获取。

在项目根目录运行：

```sh
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug`。生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，可用于本地安装验证。正式分发时应自行配置签名并构建 Release APK；仓库不包含签名密钥或旧版二进制文件。

## 验证和日志

启动应用并进入横屏后，从顶部边缘向内滑动。状态栏黑边应不再出现。

LSPosed 日志中正常启动时会包含：

```text
Unbar: active: hooked 1 requestTransientBars method(s)
Unbar: fallback active: hooked 1 InsetsPolicy.showTransient method(s)
```

成功拦截时会包含：

```text
Unbar: blocked via DisplayPolicy
```

如果仍有黑边，请导出包含 `Unbar` 的 LSPosed 日志，以便适配 HyperOS 私有实现。

## 卸载

先在 LSPosed 中停用模块，然后卸载 APK 并重启。
