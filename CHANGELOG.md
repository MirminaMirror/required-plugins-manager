<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# 更新日志 / Changelog

## [Unreleased]

### Added
- **Native Plugins UI Integration**: Directly integrates with the native IntelliJ `Settings > Plugins` interface to manage and configure project-required plugins seamlessly.
- **Marketplace Batch Installation**: Displays uninstalled required plugins pinned at the top of the Marketplace tab with an "Install All" one-click action.
- **Installed Batch Enabling**: Displays disabled required plugins pinned at the top of the Installed tab with an "Enable All" one-click action.
- **Card Badges & Context Menu**: Shows a distinct `Required` badge on plugin cards with Min/Max version tooltips, plus right-click context menu options to quickly mark or unmark required status.
- **Detailed Version Constraints**: Allows configuring minimum (Min) and maximum (Max) version constraints in the plugin details pane, with a quick button to fill the installed version.
- **Dedicated Search Filter**: Supports `/tag:Required` in the search bar to filter and count project-required plugins instantly.
- **Built-in Internationalization**: Full built-in English and Simplified Chinese (简体中文) localization support.

### 新增
- **原生 Plugins 界面无缝增强**：直接在日常使用的 `Settings / Preferences > Plugins` 界面中管理与配置项目必需插件。
- **Marketplace 置顶与一键安装**：在 Marketplace 顶部置顶展示当前项目“未安装的必需插件”分组，支持一键全部安装。
- **Installed 置顶与一键启用**：在 Installed 顶部置顶展示当前项目“未启用的必需插件”分组，支持一键全部启用。
- **卡片专属徽标与右键菜单**：列表卡片直观展示 `Required` 徽标与版本区间提示，支持右键快捷标记或取消标记。
- **版本区间精准约束**：支持在详情页配置最低 (Min) 与最高 (Max) 版本限制，并提供一键填入当前版本。
- **搜索栏专属过滤**：支持在搜索框输入或选择 `/tag:Required` 快速筛选所有必需插件。
- **内置国际化支持**：完整支持简体中文与英语双语环境。
