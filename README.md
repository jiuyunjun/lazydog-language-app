# 懒狗放洋屁

面向中文母语者的私人英语学习 Android App。

产品通过能力测试、AI 内容生成、渐进式阅读、朗读反馈和间隔复习，形成一条低压力、可持续的学习闭环：

```text
能力测试 → 今日任务 → 学习新知识 → 渐进式阅读 → 朗读 → 测试 → 安排复习
```

当前状态：MVP 学习闭环（M0～M6）代码完成。今日页按每日时长和到期量生成当天计划（单词 → 语法 → 阅读 → 朗读，预算不够先砍后面的），每步完成后自动打勾，进度按天落盘、可跳过、隔天重置；四步走完出总结。单词/语法自评直接更新复习计划，阅读答完题会给文中复习词追加"语境遇见"事件。能力小测建立 CEFR 画像，所有 AI 生成按画像出题、走 SSE 流式。剩余：M7（数据导出/导入、迁移测试、弱网与无障碍打磨）与 MVP 后路线见 ROADMAP。

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
