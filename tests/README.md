# 镜 Camera - Appium 自动化测试

基于 Appium + pytest 的 Android 相机应用自动化测试模块。

## 环境准备

### 1. 安装依赖

```bash
cd tests
pip install -r requirements.txt
```

### 2. 安装 Appium Server

```bash
npm install -g appium
appium driver install uiautomator2
```

### 3. 启动 Appium Server

```bash
appium --address 127.0.0.1 --port 4723
```

### 4. 连接设备

```bash
# 验证设备连接
adb devices
# 应显示: 10ADBU1YLK0017S    device

# 安装 APK
adb -s 10ADBU1YLK0017S install -r ../app/build/outputs/apk/debug/app-debug.apk
```

## 运行测试

### 运行全部测试

```bash
cd tests
pytest -v --timeout=120
```

### 运行指定模块

```bash
# 仅测试拍照
pytest test_photo_capture.py -v --timeout=120

# 仅测试闪光灯
pytest test_flash.py -v --timeout=120

# 仅测试模式切换
pytest test_mode_switch.py -v --timeout=120
```

### 生成 HTML 报告

```bash
pytest -v --timeout=120 --html=report.html --self-contained-html
```

### 带截图运行

```bash
pytest -v --timeout=120 -s
```

## 测试文件说明

| 文件 | 测试内容 |
|------|----------|
| `test_app_launch.py` | 应用启动、权限、预览 |
| `test_photo_capture.py` | 拍照（HDR/人像/夜景/连拍） |
| `test_flash.py` | 闪光灯控制 |
| `test_zoom.py` | 变焦控制 |
| `test_mode_switch.py` | 模式切换 |
| `test_video.py` | 视频录制 |
| `test_thumbnail.py` | 缩略图显示 |
| `test_live_photos.py` | Live Photos 开关 |
| `test_pro_mode.py` | Pro 模式手动控制 |
| `test_ui_controls.py` | UI 控件交互 |

## 配置说明

在 `conftest.py` 中修改设备配置：

```python
DEVICE_UDID = "10ADBU1YLK0017S"      # 设备 ID
APPIUM_SERVER = "http://127.0.0.1:4723"  # Appium 服务地址
APP_PACKAGE = "com.jing.camera"       # 应用包名
APP_ACTIVITY = "com.jing.camera.MainActivity"  # 启动 Activity
```

## 注意事项

1. 首次启动会弹出权限对话框，测试会自动允许
2. HDR/夜景模式处理较慢，timeout 设置为 120 秒
3. 截图保存在 `tests/screenshots/` 目录
4. 测试失败时自动截图便于调试
