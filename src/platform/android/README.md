# mGBA Android 中文移植

本项目在 [mGBA](https://github.com/mgba-emu/mgba) 核心和 SDL2 前端的基础上添加 Android 界面。支持 GBA、GB 和 GBC，当前构建目标为 arm64-v8a，最低 Android 5.0（API 21）。

## 使用

- 从「系统选择文件」打开自己的 ROM 或存档。ROM 导入后可在「游戏库」和「最近使用」中打开。
- 游戏库按 ROM 的 SHA-256 区分版本。默认将同一游戏的存档、快照和备份归在独立目录下。
- 游戏中打开「存档快照」可保存进度和画面、预览、重命名或导出。原有 1–9 号槽位位于「旧版存档位」。
- GBA Wi‑Fi 联机可在兼容游戏中模拟联机线。两台设备要使用相同协议版本。客机视频帧率随网络状况调整，当前没有客机音频，GB 和 GBC 暂不支持 Wi‑Fi 联机。
- 卸载或清除应用数据前，将进度导出到 Download 等公共目录。详见[存档使用说明](存档使用说明.md)和[金手指与联机说明](金手指与联机说明.md)。

## 从源码构建

使用 JDK 17、Gradle 8.7、Android SDK Platform 34 和 Build Tools 34.0.0、NDK 26.3.11579264、CMake 3.22.1。安装 SDK 组件后，把 `ANDROID_HOME` 指向 SDK 安装目录，并确保 `java` 和 `gradle` 可用。

在仓库根目录运行：

```sh
git clone --depth 1 --branch release-2.30.9 https://github.com/libsdl-org/SDL.git ../SDL2
SDL2_SRC="$(cd ../SDL2 && pwd)" ./src/platform/android/build-apk.sh
```

默认生成 `src/platform/android/app/build/outputs/apk/debug/app-debug.apk`。传入 `--release` 可构建 Release APK。默认 Release 产物是 `app-release-unsigned.apk`；安装前需要签名。需要用带签名的 Release 覆盖已有安装时，必须使用同一个签名证书。

Gradle 默认使用仓库中 `version.cmake` 的版本。可用 `MGBA_VERSION=0.10.5` 复现此前 Android APK 的版本号。

如需由 Gradle 签名，在本目录创建仅供本机使用的 `keystore.properties`：

```properties
storeFile=/absolute/path/to/keystore
storePassword=...
keyAlias=...
keyPassword=...
```

## 源码与许可证

mGBA 及本移植的许可证见仓库根目录的 [LICENSE](../../../LICENSE)。`app/src/main/java/org/libsdl/app/` 内的 SDL 与 HID 类源自 SDL 2.30.9，部分已调整以接入 Android 界面，许可证见 [LICENSE-SDL2.txt](LICENSE-SDL2.txt)。本项目使用外部 SDL2 源码构建共享库，不需要将 SDL2 依赖目录提交到仓库。
