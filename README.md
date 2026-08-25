# Required Plugins Manager (项目必需插件管理器)

[![Build](https://github.com/MirminaMirror/required-plugins-manager/workflows/Build/badge.svg)](https://github.com/MirminaMirror/required-plugins-manager/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Version](https://img.shields.io/badge/version-1.0.0-green.svg)](gradle.properties)

**Required Plugins Manager** 是一款专为 IntelliJ 平台打造的插件，直接融入 IDE 原生 **`Settings > Plugins`（插件管理）** 界面，用于便捷管理和配置当前项目的必需插件。

---

## ✨ 核心特性

- 🧩 **原生 Plugins 界面无缝增强**：直接在日常使用的 `Settings / Preferences > Plugins` 界面中操作；
- 🚀 **Marketplace 智能推荐与一键全装**：Marketplace 顶部置顶展示当前项目**“未安装的必需插件”**分组，支持一键 **“全部安装”**；
- ⚡ **Installed 缺失警示与一键全启**：Installed 顶部置顶展示当前项目**“未启用的必需插件”**分组，支持一键 **“全部启用”**；
- 🏷️ **卡片专属徽标与右键捷径**：
  - 列表卡片直观显示 **`Required` 蓝色标签**，鼠标悬停展示版本限制（Min/Max）；
  - 支持右键菜单：在插件卡片上右键即可快速“设为当前项目必需插件”或“取消标记”；
- ⚙️ **详情面板与版本区间约束**：
  - 详情页提供 `[☑] 设为当前项目必需插件` 复选框；
  - 支持配置**最低版本 (Min)** 与**最高版本 (Max)**，并提供 **“填入当前版本”** 快捷按钮；
- 🔍 **搜索栏专属标签过滤**：在搜索栏输入或选择 **`/tag:Required`** 即可快速筛选出当前项目的所有必需插件。

---

## 🚀 使用指南

### 1. 标记与配置必需插件
1. 打开 **Settings / Preferences > Plugins**；
2. **方式 A**：在 Installed 或 Marketplace 列表中，右键插件卡片选择 **“设为当前项目必需插件”**；
3. **方式 B**：点击插件进入详情页，勾选 **“设为当前项目必需插件”**，按需输入最低/最高版本限制；
4. 点击 **Apply** 或 **OK** 保存即可。

### 2. 快速过滤
- 在搜索框输入或选择 **`/tag:Required`**，即可仅展示当前项目的必需插件。

### 3. 一键装配
- 当克隆新项目或缺少必需插件时，打开 `Settings > Plugins`：
  - 未安装插件：在 **Marketplace** 顶部点击 **“全部安装”**；
  - 未启用插件：在 **Installed** 顶部点击 **“全部启用”**。

---

## 📦 安装方式

1. 前往 [GitHub Releases](https://github.com/MirminaMirror/required-plugins-manager/releases) 下载最新版本的插件安装包（`.zip`）；
2. 打开 IDE，进入 **Settings**（macOS 为 **Preferences**）> **Plugins**；
3. 点击右上角齿轮图标 ⚙️，选择 **Install Plugin from Disk...**（从磁盘安装插件...）；
4. 选择已下载的 `.zip` 文件完成安装，并按提示重启/重载 IDE。

---

## 🛠️ 构建与开发

本项目基于 **IntelliJ Platform Gradle Plugin (2.x)** 与 Kotlin 开发。

```bash
# 克隆仓库
git clone https://github.com/MirminaMirror/required-plugins-manager.git
cd required-plugins-manager

# 运行单元测试
./gradlew test

# 启动沙箱 IDE 调试运行插件
./gradlew runIde

# 构建插件分发包 (.zip)
./gradlew buildPlugin
```

---

## 📄 开源许可证

本项目采用 [Apache License 2.0](LICENSE) 开源许可证。
