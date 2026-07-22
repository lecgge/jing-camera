# AGENTS.md — Camera X100 Pro

## 项目概述

Android 专业相机应用，目标设备 **vivo X100 Pro**（Android 16 / SDK 36 / 天玑 9300）。

核心诉求：绕过 vivo OTA 对相机算法的阉割，提供完整手动控制 + 更好画质。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose (BOM 2024.02.00) |
| 相机 API | Camera2 API（非 CameraX） |
| 构建 | AGP 8.7.3 + Gradle 8.11.1 |
| NDK | 27.0.12077973（native RAW 处理） |
| 最低 SDK | 21（Android 5.0） |
| 目标 SDK | 36（Android 16） |

---

## 项目目录

```
E:\work\
├── camera_android/          # 从零实现的项目（已编译通过）
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── kotlin/com/camerax100pro/app/
│           │   ├── MainActivity.kt          # Pixel Camera 风格 UI
│           │   ├── Camera2Controller.kt     # 相机控制逻辑
│           │   └── RawProcessor.kt          # JNI 封装
│           ├── cpp/
│           │   ├── CMakeLists.txt
│           │   └── native-lib.cpp           # demosaic / 色调 / 锐化
│           └── res/
│               ├── values/themes.xml
│               └── xml/file_paths.xml        # FileProvider 配置
│
├── camera_google/           # 基于 Google camera-samples 的项目（开发中）
│   └── app/src/main/
│       ├── kotlin/com/camerax100pro/google/
│       │   ├── MainActivity.kt              # 复用 Pixel Camera UI
│       │   └── Camera2ManualControlsController.kt  # Google 手动控制逻辑
│       └── cpp/            # 同上
│
└── camera-samples-main/     # Google 官方 camera-samples（只读参考）
    ├── samples/camera2-manualcontrols/   # 手动控制示例
    └── core-camera/                      # 核心相机库
```

---

## vivo X100 Pro 相机能力

| 镜头 | 方向 | 硬件级别 | RAW | ISO 范围 | 快门范围 |
|------|------|---------|-----|---------|---------|
| 主摄 (0) | Back | LEVEL_3 | ✅ | 50-19200 | 0.1ms~467ms |
| 前摄 (1) | Front | FULL | ❌ | 50-960 | 0.1ms~331ms |
| 长焦 (5) | Back | FULL | ✅ | 50-6400 | 0.1ms~467ms |
| 镜头 3 | Back | FULL | ✅ | 50-5270 | 0.1ms~**16s** |
| 镜头 4 | Back | FULL | ✅ | 50-7680 | 0.1ms~**16s** |
| 镜头 5 | Back | FULL | ❌ | 100-16000 | 0.1ms~400ms |

**系统限制：**
- `ro.camera.disableJpegR: true`（JpegR 禁用）
- `ro.camera.disableHeicUltraHDR: 1`（HEIC Ultra HDR 禁用）
- `persist.sys.nativecamera: 0`

**6 个相机设备，3 个对外可见**（device 0/1/2 → "0"/"1"/"5"）

---

## 功能清单

### 已实现 ✅
- [x] 全屏暗色预览（Pixel Camera 风格）
- [x] JPEG 拍摄 → 保存到 `DCIM/CameraX100Pro/`
- [x] RAW (DNG) 拍摄
- [x] MediaScanner 通知相册刷新
- [x] 缩略图显示最近拍摄照片
- [x] 点击缩略图 → FileProvider → 跳转到相册查看
- [x] 闪光灯控制（OFF / ON / AUTO 三态循环）
- [x] 手动模式开关（M 按钮 / Pro 模式）
- [x] ISO 滑块（设备支持范围内）
- [x] 快门速度滑块
- [x] 对焦距离滑块（设备支持时显示）
- [x] 手动参数面板从底部滑入/滑出动画

### 待实现 ⏳
- [ ] 实时直方图（native 计算）
- [ ] 峰值对焦
- [ ] 白平衡手动调节
- [ ] 多镜头切换
- [ ] vivo ISP 画质对比
- [ ] 网格线 / 水平仪

---

## 构建与安装

### 构建命令
```powershell
$env:ANDROID_HOME = "D:\Program Files\Android\SDK"
$env:ANDROID_SDK_ROOT = "D:\Program Files\Android\SDK"
Set-Location "E:\work\camera_google"
cmd /c "C:\Users\Wisdplat_ZC_0044\.gradle\wrapper\dists\gradle-8.11.1-bin\eac4u065zwes5phgltp5f9b9e\gradle-8.11.1\bin\gradle.bat" assembleDebug
```

### APK 输出路径
```
app/build/outputs/apk/debug/app-debug.apk
```

### 安装到设备
```powershell
# 先卸载旧版本（vivo 需要）
adb -s 10ADBU1YLK0017S uninstall com.camerax100pro.google

# 推送到手机（如果 ADB 安装被拒绝）
adb -s 10ADBU1YLK0017S push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/camera.apk
# 然后在手机上用文件管理器打开安装
```

### 设备 ID
```
10ADBU1YLK0017S  (vivo X100 Pro)
```

---

## 关键设计决策

### 1. 为什么用 Camera2 而非 CameraX？
- Camera2 提供 `MANUAL_SENSOR`、`MANUAL_POST_PROCESSING`、`RAW` 等底层能力
- CameraX 封装层限制了对 vivo 手动控制的访问
- Google 官方 camera2-manualcontrols 示例验证了此路线

### 2. 为什么不用 Hilt / MVVM？
- 项目规模小，过度架构增加复杂度
- 直接 `mutableStateOf` + `lifecycleScope` 足够
- Google 示例的 Hilt 依赖在 vivo 上可能有兼容问题

### 3. 为什么用 TextureView 而非 CameraX Viewfinder？
- CameraX Viewfinder 依赖 `androidx.camera.viewfinder.core`（额外依赖）
- TextureView + SurfaceTextureListener 是 Camera2 标准做法
- 更少的依赖 = 更少的构建问题

### 4. 文件保存路径
```
Environment.getExternalStoragePublicDirectory(DIRECTORY_DCIM)/CameraX100Pro/
```
- 使用公共 DCIM 目录（非 app-private），确保相册 App 可见
- MediaScannerConnection.scanFile() 通知系统刷新

### 5. 缩略图跳转方案
- FileProvider 生成 `content://` URI → ACTION_VIEW 跳转
- 回退方案：`Uri.fromFile()` 直接打开
- `android:authorities="${applicationId}.fileprovider"`

---

## 国内镜像配置

### settings.gradle.kts
```kotlin
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/public") }
maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
```

### Gradle Wrapper
```
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip
```

### gradle.properties
```properties
systemProp.org.gradle.internal.http.connectionTimeout=120000
systemProp.org.gradle.internal.http.socketTimeout=120000
android.suppressUnsupportedCompileSdk=36
```

---

## 已知问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| ADB 安装被拒绝 | vivo 安全限制 | 文件管理器手动安装 |
| GitHub 无法访问 | 公司代理/防火墙 | 用户手动下载后放到工作目录 |
| `Theme.Material` 在 XML 中不可用 | compileSdk 36 变化 | 不指定 theme，Compose 处理 |
| `Range.start` 不存在 | `android.util.Range` 用 `lower`/`upper` | 使用 `.lower` / `.upper` |
| `map?.map{}` 作用域冲突 | 变量名与函数名冲突 | 改用 `streamMap?.let { sm -> }` |

---

## Google 参考代码

- **仓库**: https://github.com/android/camera-samples
- **关键项目**: `samples/camera2-manualcontrols/`
- **核心类**: `Camera2ManualControlsController.kt` — 手动 ISO/快门/对焦控制
- **适配方式**: 去掉 Hilt / CameraX Viewfinder / core-ui 依赖，改用 TextureView + Compose

---

## 下一步优先级

1. **P0**: 验证拍摄 + 缩略图 + 相册跳转（真机测试）
2. **P1**: 实时直方图（native 计算 + Canvas 绘制）
3. **P1**: 峰值对焦（native 边缘检测）
4. **P2**: 白平衡手动调节
5. **P2**: 多镜头切换

---

## 许可证

- 本项目代码：MIT
- Google camera-samples：Apache 2.0
- 仅使用 Google 官方开源代码，无第三方依赖
