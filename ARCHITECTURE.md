# 技术架构

## 1. 目标与约束

- Android 原生 Kotlin 应用。
- 无自建服务端，数据本地优先。
- 在线依赖包括 AI 服务和 Azure Speech。
- UI 优先 Jetpack Compose + Material 3。
- 网络不可用时，已有知识库、历史和可缓存任务仍应可查看。
- 架构需要允许以后替换 AI 提供商、复习算法和阅读来源。

## 2. 推荐技术栈

- Kotlin
- Jetpack Compose / Material 3
- Navigation Compose
- ViewModel + Kotlin Coroutines + StateFlow
- Room
- DataStore
- WorkManager
- Retrofit/OkHttp 或提供商官方兼容客户端
- Azure Speech SDK
- Kotlin Serialization
- Hilt（仅在依赖关系复杂到值得引入时）

具体依赖版本在创建工程时以当时的稳定官方版本为准，不在立项文档中锁死。

## 3. 分层

```text
Compose UI
    ↓
ViewModel / UI State
    ↓
Use Cases
    ↓
Repositories
    ├── Room / DataStore
    ├── AI Provider
    ├── Speech Provider
    └── Reading Sources
```

UI 不直接调用网络 SDK 或拼装 AI 提示词。领域层不依赖具体 Compose 页面。

首版建议保持单个 App 模块并按功能分包；只有在构建时间或边界确有需要时再拆 Gradle 多模块。

## 4. 推荐包结构

```text
app/
  core/
    database/
    network/
    model/
    designsystem/
  feature/
    onboarding/
    assessment/
    today/
    vocabulary/
    grammar/
    reading/
    speaking/
    scenario/
    quiz/
    library/
    settings/
  domain/
    scheduling/
    generation/
    validation/
```

## 5. 核心数据模型

### LearnerProfile

- 当前 CEFR 估计与置信度
- 词汇能力区间
- 语法领域能力
- 阅读和朗读能力摘要
- 学习目标、兴趣和每日时长
- 最近一次画像更新时间

### KnowledgeItem

统一身份和调度字段：

- `id`
- `type`: vocabulary / grammar
- `stage`: unseen / exposed / learning / familiar / mastered
- `difficulty`
- `stability`
- `lastReviewedAt`
- `nextReviewAt`
- `lapseCount`
- `createdAt` / `updatedAt`

单词和语法的详细内容分别存储，不强迫使用同一张超宽表。

`grammar_details` 对语法内容分层保存：

- `name`：兼容旧版本的唯一键；新记录与 `patternEn` 相同。
- `patternEn`：英文结构公式，也是记忆卡和记录列表的唯一主标题。
- `labelZh`：短中文语法标签。
- `summaryZh`：列表使用的一句话用途。
- `explanationZh`：完整使用说明。
- `exampleEn` / `exampleZh`：正确例句及翻译。
- `badExampleEn` / `badExampleNoteZh`：易错例及原因。
- `tipZh`：易混提醒。

数据库 v5 只为旧表增加带默认值的列。旧记录不强制重写：展示层会从混合 `name` 中提取开头的英文形式，并优先把剩余中文作为简洁用途；新生成内容必须完整填写分层字段。

### LearningSession

- 状态：planned / active / paused / completed / abandoned
- 计划步骤及当前步骤
- 预计与实际时长
- 本次新知识和复习知识 ID
- 创建、暂停和完成时间

### LearningEvent

使用追加式事件记录每次学习行为：

- 知识项 ID
- 来源：卡片、阅读、朗读、测试
- 题型或活动类型
- 结果与用户四档反馈
- 响应时间
- 时间戳

聚合后的掌握状态可以重算，不能只保存一个无法解释的最终分数。

### ReadingMaterial

- 标题、正文、难度和来源
- 目标知识、新知识与检测到的其他生词
- 生成参数和模型标识
- 校验结果
- 创建时间

## 6. 复习调度

定义可替换接口，例如：

```kotlin
interface ReviewScheduler {
    fun schedule(previous: MemoryState, rating: ReviewRating, at: Instant): MemoryState
}
```

首版可以实现简化间隔算法，但字段设计需要支持以后迁移到 FSRS。每日计划优先级：

1. 已到期且多次遗忘的项目
2. 普通到期项目
3. 最近新学、需要巩固的项目
4. 在剩余时间内加入少量新知识

## 7. 阅读生成管线

```text
读取用户画像与时间预算
  → 选择复习项和新知识
  → 组装结构化生成请求
  → 调用 AI
  → 解析 JSON
  → 本地分析正文
  → 验证目标词、篇幅和生词比例
  → 合格后保存，否则修复或重试
```

内容来源使用统一接口：

```kotlin
interface ReadingSource {
    suspend fun getContent(request: ReadingRequest): ReadingContent
}
```

计划实现顺序：AI 生成、粘贴文本、URL 提取、Web 搜索。

## 8. 服务和密钥

- 密钥由用户在设置页输入并保存在应用私有存储中。
- 禁止写入源码、资源文件、日志、崩溃报告或 Git。
- 对个人项目而言接受客户端持有密钥的风险，但界面必须明确说明 APK 无法彻底保护密钥。
- AI 与 Speech 各自通过 provider 接口封装，领域层不依赖具体 SDK。
- 网络请求应设置超时、有限重试和可取消机制。

### 情景演练状态

- 一轮对话只有在“角色回复”和“目标判定”两个独立调用均成功时才追加到界面状态，避免出现无法判定来源的半轮数据。
- 最近七天的 `scenarioId` 保存在 DataStore，用于生成阶段去重。
- 总结留下的表达复用 `vocabulary_details` 和现有复习调度，但通过 `pos=expression`（兼容旧值 `phrase`）在 UI 与单词学习中分流，不把完整句子当作单词。
- 情景简报、对话、目标状态、草稿、总结和重说进度以 JSON 快照保存在 Room 的 `scenario_sessions` 表；关键状态变化立即保存，文字草稿防抖保存。
- 学习页把情景会话和阅读材料按更新时间合并进“最近材料”。打开未完成会话时恢复到离开前阶段，完成会话仍可回看。
- 情景会话随统一 JSON 备份导出与恢复，不保存录音文件。

### 听力训练状态

- 一轮 10 句由一次 `generateListeningSet` 生成，全部通过 `ListeningValidation` 后才开局；能用的句子少于 5 句直接失败，不开一局残缺的训练。
- 评分、总结和挖空提示都是 `domain/listening` 里的纯函数（`listeningScore`、`summarizeListening`、`maskKeyExpression`），不依赖 Compose，可单测。
- 选项顺序按题目下标定死，避免重组时正确答案换位置。四个选项先选后确认，不是点一下就判定。
- 一轮的状态只活在页面里，不落库：MVP 不做跨轮次的听力画像和周对比（`英语听力训练模块DESIGN.md` §25），因此也没有需要长期保存的东西。中途退出会明确提示这一轮会丢。
- 翻到下一句时调 `stopSpeaking(keepLink = true)`：声音照掐，但保留已经热起来的蓝牙链路。不区分的话每翻一页都放手，下一句又从冷通路起播，等于白挂。
- 揭晓页留下的重点表达走 `KnowledgeRepository.addExpression`，和情景演练留下的表达共用 `pos=expression` 与同一套复习调度。

### 全局英文交互

- 学习内容中的英文统一复用 `InteractiveEnglishText`：快速双击定位单词并打开词汇讲解，快速三击定位所在句并打开句子讲解。
- 词汇讲解可写入“单词”，句子讲解可写入“表达”；两类仍共用知识项身份、备份和复习调度。词汇与句子讲解都可调用 Azure TTS 朗读。
- 解释接口使用 OpenAI 兼容 SSE；UI 从未闭合的结构化 JSON 中增量提取已到达字段，最终结果仍需完整解析和业务校验后才能保存。
- 组件保留可选单击动作，用于答案选择、打开知识详情等原有交互；多击判定窗口为 280 ms。

## 9. 数据备份

无账户服务时，长期学习记录是最重要资产。导出格式带 schema 版本（`core/backup/BackupModels.kt` 的 `BackupPayload`），不含密钥与录音。

实现方式见 D-014：用户在设置里通过 SAF（`ACTION_OPEN_DOCUMENT_TREE`）选一个外部文件夹并持久授权，`core/backup/BackupFileStore.kt` 在这个文件夹里读写固定文件名的 JSON；`core/backup/AutoBackupWorker.kt` 每天自动写一次；`core/backup/BackupRepository.kt` 负责整体导出/恢复（恢复是覆盖式的，知识项 id 会重新分配，通过旧 id → 新 id 的映射接回明细和事件）。首次启动的欢迎页也接入了同一套恢复流程，换手机或重装后选回同一个文件夹即可继续。

## 10. 测试策略

- 单元测试：调度算法、能力估计、阅读校验、学习计划生成。
- Repository 测试：Room 映射、事务和迁移。
- 契约测试：AI 合法、缺字段、错误类型和超长返回。
- UI 测试：首次测试、完整今日流程、中断恢复和网络失败。
- 人工验证：Azure Speech 权限、录音、音频焦点和真实设备行为。
