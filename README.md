---
doc: "README.md"
tier: "L0 入口"
status: "生效"
version: "1.2"
updated: "2026-09-06"
authority: "项目是什么、怎么构建运行、从哪儿开始读"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
---

# 懒狗放洋屁

面向中文母语者的私人英语学习 Android App。

产品通过能力测试、AI 内容生成、渐进式阅读、朗读反馈和间隔复习，形成一条低压力、可持续的学习闭环：

```text
能力测试 → 今日任务 → 学习新知识 → 渐进式阅读 → 朗读 → 测试 → 安排复习
```

当前状态（逐条状态以 [ROADMAP.md](ROADMAP.md) 为准）：

- **MVP 学习闭环（M0～M6）已完成。** 今日页按每日时长和到期量生成当天计划（单词 → 语法 → 阅读 → 朗读，预算不够先砍后面的），每步完成后自动打勾，进度按天落盘、可跳过、隔天重置；四步走完出总结。单词/语法自评直接更新复习计划，阅读答完题会给文中复习词追加"语境遇见"事件。能力小测建立 CEFR 画像，所有 AI 生成按画像出题、走 SSE 流式。
- **MVP 之后也已落地**：情景演练（M8）、摇一摇提问（M9）、听力训练（M11）、渐进式拼写记忆（M12）、词汇记忆提示（M13）、持续学习的进步证据与动态难度与 FSRS（M14.1～M14.6）、值得读的阅读材料（M15.1～M15.2）。
- **进行中/未做**：M7 可靠性（数据导出导入已随 D-014 提前实现，仍缺清除数据入口、备份多版本处理、迁移测试、弱网与无障碍打磨）、M10 剩两条、M15.3 主题引擎与推荐。多个里程碑仍标注"需真机验收"。

## 构建与运行

- 要求：JDK 17+、Android SDK（compileSdk 35）。在仓库根目录创建 `local.properties` 写入 `sdk.dir`（Android Studio 会自动生成）。
- 服务配置：复制 `app/src/main/java/com/lazydog/english/core/config/LocalEnv.kt.example` 为同目录 `LocalEnv.kt` 并填入自己的密钥（该文件被 gitignore，见 DECISIONS D-012）。不填也能启动，只是 AI 连接测试会失败。
- 构建：`./gradlew assembleDebug`
- 验证：`./gradlew lintDebug testDebugUnitTest`
- 运行：Android Studio 直接运行 `app`，或 `adb install app/build/outputs/apk/debug/app-debug.apk`
- 说明：设置页点击「AI 服务」一行即可用内置配置做连接测试；朗读（Azure Speech）功能在 M5 才接入。

### GitHub 自动构建

向 GitHub 推送提交、创建或更新 Pull Request 时，`Build Debug APK` 工作流会自动运行测试、Lint 和 Debug 构建；也可以在仓库的 Actions 页面手动触发。构建成功后，在对应运行记录的 Artifacts 区域下载 `lazydog-debug-<commit SHA>`，其中包含可安装的 Debug APK，保留 14 天。

Push 和手动构建会从 GitHub Actions Repository Secrets 读取 `AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL`、`SPEECH_KEY`、`SPEECH_REGION` 并写入 APK；缺少任一配置时构建会明确失败。Pull Request 构建只使用无密钥占位配置，避免向 PR 代码暴露密钥。带密钥 APK 仅供私人使用，不要公开分发。

## 文档导航

**完整索引见 [DOCS.md](DOCS.md)**：19 份文档的层级、各自管什么、什么时候读、冲突了听谁的、现在是哪一版。下面只列最常用的几份。

| 文档 | 作用 |
| --- | --- |
| [DOCS.md](DOCS.md) | 文档地图与版本表。开工前和 `AGENTS.md` 一起读 |
| [AGENTS.md](AGENTS.md) | 所有编码与设计 agent 必须遵守的仓库规则 |
| [PRODUCT.md](PRODUCT.md) | 产品定位、用户流程与 MVP 范围 |
| [UI_BRIEF.md](UI_BRIEF.md) | 提供给设计 agent 的 UI/UX 任务书 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Android 技术架构、架构硬约束和数据模型 |
| [AI_CONTRACTS.md](AI_CONTRACTS.md) | AI 使用边界、提示词与结构化输出契约 |
| [ROADMAP.md](ROADMAP.md) | 里程碑、开发顺序和逐条落地状态 |
| [DECISIONS.md](DECISIONS.md) | 已经确认的重要决策 |
| [HANDOFF.md](HANDOFF.md) | agent 与人工之间的交接规范 |

另有 8 份模块专项设计（拼写训练、听力训练、阅读材料、语音服务等），它们描述的是**目标形态**而不是当前实现——清单和落地情况见 [DOCS.md](DOCS.md) §3 的 L4 一节。

文档元信息（层级 / 状态 / 版本 / 更新日期）写在每份文档开头的 frontmatter 里，与 `DOCS.md` 的版本表由 `python tools/check_docs.py` 强制保持一致。

## 已确定原则

- Android 原生 Kotlin，优先 Jetpack Compose 与 Material 3 默认组件。
- 无自建服务端，学习资料和记录优先保存在本地。
- AI 负责推荐与生成内容，本地程序负责状态、校验和复习调度。
- 首版先跑通一条 10～15 分钟的完整学习链路。
- 所有文本文件使用 UTF-8。
- 每个完成的开发任务都必须形成 Git 提交。
