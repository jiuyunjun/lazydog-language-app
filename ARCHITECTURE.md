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

### 6.1 渐进拼写子状态

通用 `KnowledgeItem` 继续负责“这个知识项何时再出现”；单词另有本地 `spelling_progress`，负责“下次以多强的提示考拼写”。两者不能合并：选择题认得出，不能被解释成写得出。

- `SpellingEngine`（`domain/spelling/`）是纯 Kotlin 状态机，维护六维掌握分量、当前拼写阶段、成功日期和薄弱片段，不依赖 Compose，可单测。
- `spelling_attempts` 追加保存每次提交，字段包含题型、原答案、提示级别、耗时、错误类型、薄弱片段和 Mastery Credit。
- 一张卡可以有多次提交，但只在这张卡真正翻篇时（写对了，或提示已拉到 5 级、答案摆在脸上）调用一次通用 `ReviewScheduler`——否则来回试三次会被记成三轮复习，把 `lapseCount` 撑得虚高。提示要到底也记一次零分提交，不留“不留痕迹的绕路”。
- 请求提示不是一次作答：只抬提示等级，不写 attempt、不动阶段。
- **第 5 级之前任何一级都不给出完整拼写**，由 `SpellingEngineTest` 断言守着。第 1 级只说错的性质（双写 / 元音顺序 / 少几个字母），不说在哪；第 2 级给的是挖过空的错误区域（`en_____ment`），不是原词；第 3 级给薄弱片段的内芯，掐头去尾，严格窄于片段本身；第 4 级给词块骨架但弱块仍然空着（`en + _____ + ment`）。这样五级才是单调递增的，否则第 4 级就等于答案，逐级要提示退化成点四下看答案。
- 局部补全和引导回忆的题面是逐字母下划线格子（`LetterSlots`，设计稿 63 屏），每个缺字母一格、格间留空，不是一条通长横线。格子本身是 `BasicTextField`，底下不再另摆输入框。
- **手滑和忘了分开**：编辑距离 1 且用时 < 2 秒记为 `likelyTypo`，不计连续错误、不降级（设计稿「阶段升降级规则」最后一行）。
- 新词从 Seen 开始；v8 之前的旧词没有拼写行时，按通用阶段保守映射到 Seen / Partial / Guided / Free，首次提交后建立独立状态。
- 「音形对应」这一维只由题面靠声音给出的题型（识别、完整默写、延迟回忆）驱动，由调用方显式传 `audioPrompted`，引擎不猜。
- 用户级 `SpellingProfile` 不落库，每次进画像页由 `spelling_attempts` 重算：错误率的分母是「错过的次数」而不是「答过的次数」，否则正确率一上升，弱点就被稀释得看不见了。
- Room schema 为 v8，两张表都通过 `itemId` 外键随知识项级联删除；都包含在 schema v2 备份中，旧备份缺字段时按空列表恢复。

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

### 产出练习

- 今日第三步是中译英（`DailyStep.Production`，2 分钟，排在语法之后）：出题带上错题画像、最近学的语法点和刚复习过的词。
- 判定是独立调用，返回三档结论 + 在原句上改出的版本 + 最多两个形式错误类别；错误类别经 `MistakeRepository.recordMistake` 进同一张 `drill_mistakes` 表，和选择题错题不分来源。
- 单词复习到 Familiar 以上翻转成产出方向：`ProductionCheck` 本地判分（归一化 + 长词允许一处拼写差错），结果直接映射 `ReviewGrade`，不再自评。

### 拼写训练入口

- 拼写是「学习」下的独立入口，不是单词流程里的一步（设计稿 59～61、63 屏顶部是自成一轮的「拼写练习 · n / 12」，而 62 屏的词块拆分标的是「新词 · 1 / 12」）。这样单词页仍然管词义，拼写页只管写得出，两边不互相挤时间。
- S0 接触是唯一并进单词流程的一段：新词卡揭示答案后显示词块拆分并标出词干，和后面所有阶段用的是同一套 `SpellingEngine.chunkWord` 切分。
- 队列由 `KnowledgeRepository.spellingQueue()` 组，优先级 = 到期天数 + 薄弱片段分 + 连续错误 + 没练过的补一次。多词条目（表达、整句）不进拼写练习。
- 拼写能力档案（64 屏）是练习总结页和入口都能到的只读页，样本不足时明说不足，不拿两次错误画一张像模像样的分布图。

### 错题画像与选题

- 每道语法练习题带一个 `errorTag`（时态 / 主谓一致 / 单复数 / 冠词 / 介词 / 非谓语 / 语序 / 被动 / 比较级 / 其它），AI 只能从固定集合里选，不认识的标签本地归到 `other`。
- 做错时写一条 `drill_mistakes`（Room v6，自动迁移）。这张表不设外键、冗余存语法结构：错误画像不该因为那条语法被删掉就消失。
- `MistakeProfile` 按最近 21 天聚合出"错得最多的三类"，超过 90 天的记录在写入时顺手清掉。
- 用户没指定学什么时，语法讲解的生成请求带上这三类，提示词要求挑一个能直接解释这些错误的语法点；语法页也把它显示出来，让人看见"接下来讲的东西是从我的错里来的"。
- 错题随备份导出；恢复时知识项 id 重映射，对不上的记录保留但不再关联。

### 分技能能力画像

- 测评结束时除总等级外，另存五项连续能力值（`domain/assessment/SkillLevels.kt`）：词汇、语法（含纠错短答）、阅读、语用、开放表达。
- 估计方式：每道题给出"能力约在题目难度 ± 0.5"的一次观察，按技能取平均后向总等级收缩（样本少时更靠近总等级）；深度阅读只有正确率，用来对阅读估计做 ±0.3 的修正；开放表达按 5 维总分线性映射，只是粗估。
- 生成内容各取各的等级：挑新词用词汇等级，讲语法用语法等级，写短文用阅读等级，情景演练用表达等级；点词/句解释和摇一摇提问仍用总等级。样本不足的项自动回退到总等级。
- 用户在结果页手动改等级时（`overrideLearnerLevel`）清空分技能画像：既然人工推翻了结论，就不该继续用上一次测出来的偏科结果。
- 分技能值随备份导出（`BackupPreferences` 新增字段，旧备份解码为 null 后回退到总等级）。

### 摇一摇提问

- 触发在 `core/ask/ShakeDetector.kt`：只在学习页面注册 `TYPE_ACCELEROMETER`，合力超过灵敏度阈值即触发，300 ms 去抖、1.2 s 冷却；没有传感器或用户关掉摇一摇时，降级为学习页顶栏的问号 `AskTopBarAction`。
- 上下文由页面自己注册：`core/ask/AskController.kt` 的 `ProvideAskContext` 把结构化的 `AskContext`（词条 / 语法点 / 阅读材料 / 刚做的题 / 演练处境）挂到外层 `feature/ask/AskHost`。不截屏、不发整页文本，抽屉顶部的上下文卡展开后就是发给 AI 的全部内容。
- 页面状态不合适提问时注册 `null`（生成中、失败页、词卡未揭示答案时只给词形不给释义），摇了也不弹。
- 提问复用同一个 OpenAI 兼容接口（`askAboutContext`），SSE 流式；`AskStreaming.partialAnswer` 从未闭合的 JSON 里增量取出 `answerZh` 供展示，最终仍以完整解析加校验为准。
- 一次会话只活在抽屉里：关掉即清空，不落库、没有全局聊天历史。答案里的新词经用户点“加进复习”才写入知识库。

## 9. 数据备份

无账户服务时，长期学习记录是最重要资产。导出格式带 schema 版本（`core/backup/BackupModels.kt` 的 `BackupPayload`），不含密钥与录音。

实现方式见 D-014：用户在设置里通过 SAF（`ACTION_OPEN_DOCUMENT_TREE`）选一个外部文件夹并持久授权，`core/backup/BackupFileStore.kt` 在这个文件夹里读写固定文件名的 JSON；`core/backup/AutoBackupWorker.kt` 每天自动写一次；`core/backup/BackupRepository.kt` 负责整体导出/恢复（恢复是覆盖式的，知识项 id 会重新分配，通过旧 id → 新 id 的映射接回明细和事件）。首次启动的欢迎页也接入了同一套恢复流程，换手机或重装后选回同一个文件夹即可继续。

## 10. 测试策略

- 单元测试：调度算法、能力估计、阅读校验、学习计划生成。
- Repository 测试：Room 映射、事务和迁移。
- 契约测试：AI 合法、缺字段、错误类型和超长返回。
- UI 测试：首次测试、完整今日流程、中断恢复和网络失败。
- 人工验证：Azure Speech 权限、录音、音频焦点和真实设备行为。
