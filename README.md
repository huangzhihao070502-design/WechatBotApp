# 微信AI助手 (WechatBotApp)

一键启动的微信 AI 聊天机器人 Android 应用。

## 功能

- 🤖 微信 AI 自动回复（支持 DeepSeek/OpenAI 兼容 API）
- 🎭 角色卡系统（自定义 AI 人设）
- 🖼️ 多媒体支持（图片、文件、语音、视频）
- 📱 内置 WebView 管理界面
- 🔄 开机自动启动
- 🔋 前台服务保活

## 构建

### GitHub Actions（推荐）

1. Fork 或创建新仓库
2. 推送代码到 `main` 分支
3. GitHub Actions 自动构建
4. 在 Actions → Artifacts 下载 APK

### 本地构建

```bash
gradle assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/`

## 前提条件

手机需要安装 [Termux](https://f-droid.org/en/packages/com.termux/) 并运行：

```bash
pkg install python
```

## 使用

1. 安装 APK
2. 首次启动会自动初始化脚本和依赖
3. 扫码登录微信
4. 开始使用 AI 自动回复

## 架构

```
WechatBotApp
├── MainActivity    → WebView 展示 Bot 管理界面
├── BotService      → 前台服务，运行 Python 脚本
├── BootReceiver    → 开机自启
└── ScriptManager   → 脚本初始化 + 依赖管理
```
