# ThinkSmart View · 鸡肋OS V5

[English](README.en.md)

鸡肋OS V5 是运行在兼容 Android 8.1（API 27、ARMv7）固件上的学习桌面。家长可以管理应用与网址白名单、查看前台用量和设置桌面。设备连接家庭 Wi-Fi 后，在本机家长端可查看局域网管理地址。

## 构建

准备 JDK 17、Gradle 9.8.2、Android SDK 37.2 与 Build Tools 36.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME`，然后运行 `python3 build.py`。可用 `JILE_GRADLE` 指定 Gradle 可执行文件。输出为 `dist/jile-os-v5.apk`，本地测试签名由构建机生成。

## 安装与恢复

1. 先确认设备固件、Android 版本及 ABI，保存原系统恢复材料。打开 USB 调试后使用 `adb devices -l` 核对目标。
2. 在已兼容的 Android 系统上运行 `adb -s <目标序列号> install -t dist/jile-os-v5.apk`。若已有同签名版本，加 `-r` 覆盖升级。签名不同的设备应先确定备份和迁移方式。
3. 首次在设备上输入初始设置码 `123456`，再创建自己的六位家长 PIN，按需选择默认桌面和应用、网址。应用内 Home 键依赖鸡肋OS的无障碍服务，桌面媒体按钮依赖通知访问权限，前台用量统计依赖使用情况访问权限。
4. 需要 Android Lock Task 时，先确认当前设备管理状态及退出路径。家长可以从本机家长端返回原系统桌面。在解除设备管理之后，可在 Android 的默认应用设置中选回原桌面。
5. 家长网页使用 HTTP，适用于可信家庭网络。屏幕查看应先取得使用者同意，通过设备上的系统授权开启。重启后能否保留授权取决于固件。

包内提供桌面源码和构建材料。兼容固件、Google Play 服务及其他应用由使用者自行准备。个人资料、实际设备配置、第三方应用 APK 和第三方学习图谱均排除在发布范围外。本站的旧 WebView 兼容脚本也未纳入源码包，站点渲染取决于设备 WebView。

© 2026 Samyeei。项目源码按 [PolyForm Noncommercial 1.0.0](LICENSE) 公开，限该许可规定的非商业用途。第三方依赖适用各自的许可证。

## OTA 更新

在设备启用设备管理后，可在家长设置里填写 HTTPS 更新清单并开启自动检查。运行 `python3 scripts/prepare_ota.py <apk> <输出目录>` 生成 `release.json` 和 APK。将两份文件放在同一 HTTPS 目录。当前客户端要求直接返回 HTTP 200。更新包须使用相同的应用签名及更高的 versionCode。

[V5 的 GitHub 更新清单](https://raw.githubusercontent.com/samyeei/thinksmart-view-jile-os-v5/refs/heads/ota/release.json)
