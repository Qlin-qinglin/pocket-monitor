# 掌上监看 · Pocket Monitor

把 Android 手机当作便携 HDMI 监视器，用于调试没有屏幕的服务器和其他设备。接线方式为 **设备 HDMI 输出 → UVC 采集卡 → USB OTG 转接头 → Android 手机**。

## 使用方法

1. 安装应用，允许应用使用相机权限。Android 通过这个权限控制应用访问 UVC 视频设备。
2. 接上 HDMI 信号和 USB 采集卡，在系统提示中允许 USB 访问。
3. 横屏预览时，点一下画面可隐藏或显示操作栏。可双指缩放、拖动，或点「复位画面」恢复完整画面。
4. 选定采集模式后，重新插拔采集卡以应用。选择「恢复自动」可清除本次预约。

MS2130 在 USB 3.x 链路上默认优先尝试 1080p YUY2 的最高报告帧率。在 USB 2.0 链路上优先尝试 1080p MJPEG，最高 50 fps。实际可选模式取决于设备报告和链路速度。1080p60 MJPEG 已在一部 USB 3.x Android 手机与 MS2130 组合上验证出画。

需要排查黑屏或帧率时，打开「诊断报告」，可以复制 USB 链路、请求模式和显示帧率。报告会保留最近一次连接的结果。

## 构建

需要 JDK 21、Android SDK Platform 35 Extension 15、Build Tools 35.0.0 和 Android NDK r28c（28.2.13676358）。将 Android SDK 路径设为 `ANDROID_HOME`，或通过 Android Studio 配置。

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug :app:lintDebug`。APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`。运行设备需要 Android 8.0 或更新版本，并支持 USB Host。

应用使用 Kotlin 和 UVCAndroid。USB 链路检测通过 Android 授权后获得的设备连接读取。第三方许可和源码链接随 APK 打包，亦可在 [app/src/main/assets/licenses](app/src/main/assets/licenses) 中查阅。
