# Unbar

一个面向 Android 16 / HyperOS 3 的小型 LSPosed 模块。当用户选择的应用位于前台时，
它会在 `system_server` 中阻止顶部边缘手势显示临时状态栏，从而避免隐藏状态栏位置出现
黑色临时栏。设置界面支持搜索、多选应用和“仅横屏时启用”。

模块只处理顶部状态栏，不拦截底部导航栏、返回、主页或最近任务手势。

## 安装

1. 保持 KernelSU、Zygisk Next 和 LSPosed/Vector 已启用。
2. 从 [Releases](https://github.com/cr4n5/Unbar/releases) 下载并安装对应版本的 `Unbar-vX.Y.Z.apk`，或自行构建用于测试的 APK。
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

Windows 使用 `gradlew.bat :app:assembleDebug`。生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，可用于本地安装验证。推送到 `main` 或提交 Pull Request 时，GitHub Actions 也会自动构建 Debug APK，并在对应运行记录中提供下载。

## 自动发布

发布工作流使用仓库的 Actions Secrets 保存签名信息：

- `UNBAR_KEYSTORE_BASE64`：Release 密钥库文件的 Base64 内容。
- `UNBAR_KEYSTORE_PASSWORD`：密钥库密码。
- `UNBAR_KEY_ALIAS`：密钥别名。
- `UNBAR_KEY_PASSWORD`：密钥密码。

将 `app/build.gradle` 中的 `versionCode` 和 `versionName` 更新为新版本，再推送与 `versionName` 一致的 `vX.Y.Z` 标签。GitHub Actions 会构建已签名的 Release APK，验证签名，并创建附带 APK 和 SHA-256 校验文件的 GitHub Release。使用同一签名密钥，才能直接覆盖安装已有版本。仓库不包含签名密钥或旧版二进制文件。

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
