# LumiFuse Android

安卓离线 HDR 曝光融合与景深合成测试项目，支持 2～7 张 JPG，包括 3、5、7 张。

## 下载源码

完整源码位于本仓库的 **LumiFuse-0.1.0-source.zip**，包含原始目录结构、Java 源码、Android 资源、Gradle 配置和官方 Gradle Wrapper。

解压后使用 Android Studio 打开，配置 JDK 17 和 Android SDK 35，执行 `gradlew.bat assembleDebug`。

## 当前状态

- 2026-10-14 已成功执行 assembleDebug，APK 签名校验通过。
- 面向 arm64 安卓设备，最低 Android 10；尚未进行小米 13 Pro 真机效果验证。
- 两种合成模式、自动对齐、1280 / 2048 / 3072 长边输出、原图对比、JPG 相册保存。
- HDR 输出为曝光融合 JPG，不是 HDR DNG；景深遮挡边缘可能存在光晕。
- 本仓库目前以源码压缩包形式保存，并非展开的 Gradle 项目目录。
- 不包含原片、APK、签名密钥、账号凭据、本机 local.properties 或 IDE 配置。

源码压缩包内 README 保留早期 MVP 状态说明；实际构建状态以本页面为准。
