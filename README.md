# 懒狗放洋屁

面向中文母语者的私人英语学习 Android App。

产品通过能力测试、AI 内容生成、渐进式阅读、朗读反馈和间隔复习，形成一条低压力、可持续的学习闭环：

```text
能力测试 → 今日任务 → 学习新知识 → 渐进式阅读 → 朗读 → 测试 → 安排复习
```

当前状态：M1（知识库与复习内核）与提前实施的 M5 朗读（见 DECISIONS D-013）代码完成。知识库页接 Room 真数据：添加单词/语法点、到期筛选、四档自评复习（可替换调度接口）、删除。朗读页（学习 → 朗读）：Azure Speech TTS 示范 + 录音发音评估 + 逐词反馈，练习句优先取自知识库例句。设置页可分别测试 AI 与 Speech 连接。今日页仍是示例数据；朗读结果反写复习计划留在 M6。下一步：M3（AI 内容生成）或 M2（能力测试）。

## 构建与运行

- 要求：JDK 17+、Android SDK（compileSdk 35）。在仓库根目录创建 `local.properties` 写入 `sdk.dir`（Android Studio 会自动生成）。
- 服务配置：复制 `app/src/main/java/com/lazydog/english/core/config/LocalEnv.kt.example` 为同目录 `LocalEnv.kt` 并填入自己的密钥（该文件被 gitignore，见 DECISIONS D-012）。不填也能启动，只是 AI 连接测试会失败。
- 构建：`./gradlew assembleDebug`
- 验证：`./gradlew lintDebug testDebugUnitTest`
- 运行：Android Studio 直接运行 `app`，或 `adb install app/build/outputs/apk/debug/app-debug.apk`
- 说明：设置页点击「AI 服务」一行即可用内置配置做连接测试；朗读（Azure Speech）功能在 M5 才接入。

## 文档导航

- [AGENTS.md](AGENTS.md)：所有编码与设计 agent 必须遵守的仓库规则
- [PRODUCT.md](PRODUCT.md)：产品定位、用户流程与 MVP 范围
- [DESIGN.md](DESIGN.md)：提供给 Claude Design 的 UI/UX 设计任务书
- [ARCHITECTURE.md](ARCHITECTURE.md)：Android 技术架构和数据模型
- [AI_CONTRACTS.md](AI_CONTRACTS.md)：AI 使用边界、提示词与结构化输出契约
- [HANDOFF.md](HANDOFF.md)：ChatGPT、Claude 与人工之间的交接规范
- [ROADMAP.md](ROADMAP.md)：里程碑和推荐开发顺序
- [DECISIONS.md](DECISIONS.md)：已经确认的重要决策

## 已确定原则

- Android 原生 Kotlin，优先 Jetpack Compose 与 Material 3 默认组件。
- 无自建服务端，学习资料和记录优先保存在本地。
- AI 负责推荐与生成内容，本地程序负责状态、校验和复习调度。
- 首版先跑通一条 10～15 分钟的完整学习链路。
- 所有文本文件使用 UTF-8。
- 每个完成的开发任务都必须形成 Git 提交。
