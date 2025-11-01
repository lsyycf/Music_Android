<div align="center">

<h1 align="center">🎵 Music 音乐播放器</h1>

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="应用图标" width="120" />

### 一款简洁优雅的 Android 本地音乐播放器

[![Android](https://img.shields.io/badge/Android-26%2B-green?logo=android)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue?logo=kotlin)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.09-4285F4?logo=jetpack-compose)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**✨ 全 AI 开发 · 自用项目 ✨**

---

</div>

## 📖 项目简介

这是一款基于 Jetpack Compose 构建的现代化 Android 本地音乐播放器，采用 Material Design 3 设计语言，提供流畅优雅的用户体验。本项目完全由 AI 辅助开发，作为个人自用音乐播放应用。

### 🌟 核心特性

- 🎨 **现代化 UI**：基于 Jetpack Compose 和 Material Design 3，界面简洁美观
- 🎵 **强大播放引擎**：采用 AndroidX Media3 ExoPlayer，支持多种音频格式
- 🔄 **智能播放模式**：支持随机播放和顺序播放，满足不同聆听需求
- 📱 **后台播放**：前台服务支持，应用切换到后台也能继续播放
- 💾 **状态持久化**：自动保存播放进度、播放列表和用户设置
- 🎚️ **音量控制**：独立的应用音量控制，不影响系统音量
- 📂 **文件夹管理**：支持从文件夹加载音乐，智能记忆上次选择
- 🔒 **权限管理**：优雅的运行时权限请求处理

## 🏗️ 技术架构

### 技术栈

- **开发语言**：Kotlin 2.0.21
- **构建工具**：Gradle 8.13.0 (Kotlin DSL)
- **最低 SDK**：Android 8.0 (API 26)
- **目标 SDK**：Android 14 (API 34)
- **编译 SDK**：Android 15 (API 36)

### 核心依赖

| 库 | 版本 | 用途 |
|---|---|---|
| **Jetpack Compose** | 2024.09 | 声明式 UI 框架 |
| **Material 3** | latest | Material Design 3 组件 |
| **Media3 ExoPlayer** | 1.2.0 | 音频播放引擎 |
| **Media3 Session** | 1.2.0 | 媒体会话管理 |
| **Lifecycle ViewModel** | 2.7.0 | 状态管理 |
| **DataStore Preferences** | 1.0.0 | 数据持久化 |
| **Accompanist Permissions** | 0.32.0 | 权限管理 |
| **DocumentFile** | 1.0.1 | 文件访问框架 (SAF) |

### 架构设计

```
📦 com.example.music
├── 📂 data/              # 数据层
│   ├── MusicItem.kt      # 音乐数据模型
│   └── PreferencesManager.kt  # 偏好设置管理
├── 📂 service/           # 服务层
│   └── MusicService.kt   # 前台音乐服务
├── 📂 ui/                # UI 层
│   ├── MusicPlayerScreen.kt  # 主播放界面
│   └── theme/            # 主题配置
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
├── 📂 utils/             # 工具类
│   └── MusicUtils.kt     # 音乐相关工具方法
├── 📂 viewmodel/         # 视图模型
│   └── MusicViewModel.kt # 核心业务逻辑
├── MainActivity.kt       # 主活动
└── MusicApplication.kt   # 应用程序类
```

## 🚀 功能特性

### ✅ 已实现功能

- [x] 📂 文件夹音乐扫描与加载
- [x] ▶️ 音乐播放、暂停、切换
- [x] 🔄 随机播放 / 顺序播放模式切换
- [x] ⏮️⏭️ 上一曲 / 下一曲
- [x] 📊 播放进度显示与拖动
- [x] 🎚️ 全局音量控制
- [x] 💾 播放状态自动保存与恢复
- [x] 📝 多播放列表管理（按文件夹）
- [x] 🔔 前台服务通知栏控制
- [x] 🔐 运行时权限请求（存储、通知）
- [x] 🎨 Material Design 3 设计语言
- [x] 🌙 沉浸式状态栏

### 🎯 核心亮点

#### 1. 智能状态管理

- 自动保存当前播放位置、播放列表和播放模式
- 应用重启后自动恢复上次播放状态
- 多文件夹播放列表独立管理

#### 2. 优雅的权限处理

- 适配 Android 13+ 新权限模型
- 运行时动态权限请求
- 友好的权限说明提示

#### 3. 高性能音频处理

- ExoPlayer 提供卓越的音频解码性能
- 支持多种音频格式（MP3、FLAC、AAC 等）
- 无缝切歌，流畅播放

#### 4. 用户体验优化

- 全面的日志记录，便于问题排查
- 异常处理机制，避免应用崩溃
- 响应式 UI，适配不同屏幕尺寸

## 📦 安装与构建

### 环境要求

- **Android Studio**: Ladybug | 2024.3.1 或更高版本
- **JDK**: 11 或更高版本
- **Gradle**: 8.13.0（自动下载）

### 构建步骤

1. **克隆项目**
```bash
git clone https://github.com/yourusername/Music.git
cd Music
```

2. **打开项目**
- 使用 Android Studio 打开项目
- 等待 Gradle 同步完成

3. **配置签名（可选）**
```bash
# 在项目根目录创建 keystore.properties（如需发布）
storePassword=your_store_password
keyPassword=your_key_password
keyAlias=your_key_alias
storeFile=path/to/your/keystore
```

4. **构建 APK**
```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本
./gradlew assembleRelease
```

5. **安装到设备**
```bash
./gradlew installDebug
```

### 快速安装

从 [Releases](https://github.com/yourusername/Music/releases) 页面下载最新的 APK 文件，直接安装到设备即可。

## 📱 使用说明

### 首次使用

1. **授予权限**：启动应用后，根据提示授予存储和通知权限
2. **选择文件夹**：点击"选择文件夹"按钮，选择包含音乐文件的目录
3. **开始播放**：应用会自动扫描文件夹中的音乐文件并开始播放

### 基本操作

| 功能 | 操作 |
|---|---|
| **播放/暂停** | 点击中央播放按钮 |
| **上一曲/下一曲** | 点击左右切换按钮 |
| **切换播放模式** | 点击播放模式按钮（🔀/▶️） |
| **调整音量** | 拖动音量滑块 |
| **拖动进度** | 拖动播放进度条 |
| **更换文件夹** | 点击"选择文件夹"重新选择 |

### 高级功能

- **后台播放**：应用会创建前台服务，支持在通知栏控制播放
- **状态保存**：所有播放状态会自动保存，下次启动时恢复
- **多列表管理**：不同文件夹的播放列表独立管理，互不干扰

## 🔧 配置说明

### Gradle 配置

项目使用 Kotlin DSL 配置 Gradle，主要配置文件：

- `build.gradle.kts`：根项目配置
- `app/build.gradle.kts`：应用模块配置
- `gradle/libs.versions.toml`：版本目录配置
- `settings.gradle.kts`：项目设置

### 依赖管理

本项目使用 Gradle 版本目录（Version Catalog）统一管理依赖版本，所有版本号定义在 `gradle/libs.versions.toml` 文件中。

### ProGuard 规则

Release 版本默认不启用代码混淆，如需启用可修改 `app/build.gradle.kts`：

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true  // 启用混淆
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

## 🐛 已知问题

- [ ] 某些特殊格式的音频文件可能无法识别
- [ ] 大型音乐库（1000+ 文件）加载较慢
- [ ] 通知栏控制可能在某些 ROM 上显示异常

## 🗺️ 开发路线

### 近期计划

- [ ] 添加歌词显示功能
- [ ] 实现播放列表手动排序
- [ ] 添加收藏/喜欢功能
- [ ] 优化大型音乐库的加载性能
- [ ] 添加专辑封面显示

### 长期计划

- [ ] 支持在线音乐服务集成
- [ ] 添加均衡器功能
- [ ] 支持播客和有声书
- [ ] 云端备份播放列表
- [ ] 跨设备同步功能

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

### 提交 Issue

请使用 Issue 模板，提供以下信息：
- 问题描述
- 复现步骤
- 期望行为
- 实际行为
- 设备信息（型号、Android 版本）
- 日志信息

### 提交 PR

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 开源协议

本项目采用 [MIT License](LICENSE) 开源协议。

```
MIT License

Copyright (c) 2025 Music Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

## 💡 致谢

### 技术栈

- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Google 官方的现代化 UI 工具包
- [Media3](https://developer.android.com/media/media3) - AndroidX 媒体库
- [ExoPlayer](https://exoplayer.dev/) - 强大的媒体播放引擎

### AI 开发工具

本项目完全由 AI 辅助开发，使用的 AI 工具包括但不限于：
- 代码生成与优化
- 架构设计与建议
- 问题排查与调试
- 文档编写

## 📞 联系方式

- **项目主页**: [https://github.com/yourusername/Music](https://github.com/yourusername/Music)
- **Issue 反馈**: [https://github.com/yourusername/Music/issues](https://github.com/yourusername/Music/issues)

## ⭐ Star History

如果这个项目对你有帮助，请给个 Star ⭐️ 支持一下！

---

<div align="center">

**Made with ❤️ and 🤖 AI**

*一款简洁、优雅、强大的本地音乐播放器*

</div>

