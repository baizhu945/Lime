# Lime

Lime 是一个 Android 中文 / 英文 / 日文输入法。

它是 [Xime](https://github.com/ximeiorg/Xime) 与 [CyIME](https://github.com/Cyletix/CyIME) 的 fork：
**输入底层完整采用 CyIME**，**键盘外观与交互设计沿用 Xime**。

## 致谢

- [Xime](https://github.com/ximeiorg/Xime)（Kingz Cheung / kingzcheung 及贡献者）——本项目的键盘外观、布局、配色与交互设计来源，也是 CyIME 的上游。
- [CyIME](https://github.com/Cyletix/CyIME)（Cyletix 及贡献者）——本项目的输入运行时：Rime 接入与 JNI、输入命令队列、编辑器归属、候选发布、方案与语言模型、T9、插件框架，以及完整的日语 Rime 方案（rime-jaroomaji）。

没有这两个项目，就没有 Lime。感谢作者们的长期投入。

本项目还间接使用了 librime、OpenCC、Boost、雾凇拼音（rime-ice）、rime-jaroomaji、Mozc 词源等开源组件与词库，各自许可见 `app/src/main/assets/NOTICE.txt`、`app/src/main/assets/licenses/` 与各方案目录下的 NOTICE 文件。

## 特色

- **中日英三语，一键循环**：启用日语后，语言键按「中 → 英 → 日 → 中」循环；只启用中英时保持原来的中英互切。
- **两种日语键盘**：26 键罗马字（`japanese`）与九宫格假名（`japanese_kana`），都走 CyIME 的 Rime 日语方案，支持假名组成、转换预览与汉字候选。
- **Xime 的键盘外观**：底排是 `?123`、逗号、空格、地球、换行；`?123` 点按回到上次的数字/常用符号页，长按可选页；菜单为四列分页网格；内置 Xime 的 22 套配色主题。
- **退格直接删字**：提交文字后的联想词不再吞掉第一次退格。
- **输入事务安全**：所有输入动作经统一 FIFO 与编辑器归属校验，切换输入框或方案时不会把结果写进错误的编辑框。
- **可复现构建**：词典按固定版本与 SHA-256 下载校验，原生库与 Rime 补丁随仓库固定。

## 构建

需要 JDK 17+、Android SDK 平台 37（`platforms;android-37`）、NDK `29.0.14206865`。本仓库在 NixOS 上用 `nix-shell` 提供工具链：

```bash
# 完整四个 ABI 的 Debug 包
./gradlew assembleDebug

# 只在 arm64 手机上验证时，可只出 arm64（可选）
./gradlew -PmigrationArm64Only=true assembleDebug
```

首次启动或改动方案配置后会部署中日词典，真机上可能需要几分钟；期间键盘会显示「正在加载词库」，完成前按键不会补输入。

## 许可证

本项目沿用上游的 [GPLv3](LICENSE)。上游代码版权归其作者所有，本分支的修改由 Lime 维护者负责。「Xime」「CyIME」的名称与图标不属于本仓库授权范围。
