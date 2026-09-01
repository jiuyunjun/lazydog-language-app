# AI 契约

## 1. 原则

- AI 是不可靠的外部依赖，返回值必须验证。
- 所有生产请求要求 JSON 结构化输出，不从自然语言中猜字段。
- 提示词版本和输出 schema 版本必须随生成记录保存。
- AI 不直接修改数据库、掌握状态或复习计划。
- 用户完成的客观题由程序判分；AI 主要用于生成、解释和评价开放表达。
- 对失败提供降级：重试、跳过、使用缓存任务或稍后继续。

## 2. Provider 边界

领域层使用项目自有接口，不直接暴露某一家服务的消息格式：

```kotlin
interface LearningContentGenerator {
    suspend fun generateReading(request: ReadingGenerationRequest): GeneratedReading
    suspend fun generateQuiz(request: QuizGenerationRequest): GeneratedQuiz
    suspend fun evaluateExpression(request: ExpressionEvaluationRequest): ExpressionFeedback
}
```

### 情景演练调用边界

情景演练使用四种结构化调用，不能合并成一次“边聊边评分”：

1. `generateScenario`：生成处境、对手、难度和 4～6 条目标。
2. `generateScenarioTurn`：只扮演对手并给下一轮四个选项，不纠错、不评分。
3. `judgeScenarioTurn`：独立判断本轮命中的目标和是否发生沟通失败，不生成对话。
4. `summarizeScenario`：结束后固定生成三条表达改进和 1～4 条待复习表达；这些内容保存为“表达”，不得混入单词列表。

### 摇一摇提问调用边界

`askAboutContext` 是单次调用，输入 = 当前页面注册的结构化上下文 + 本次抽屉内的追问历史 + 用户这次的问题：

```json
{"answerZh":"...","addable":[{"term":"...","meaningZh":"..."}]}
```

- 上下文是页面给的结构化字段（可信），用 `<context>` 包住；用户问题和追问历史是不可信输入，用 `<question>` / `<history>` 包住，其中的指令一律不执行。
- 上下文不含截图、不含整页文本，正文类字段在进提示词前截断。
- 追问只带本次抽屉里最近六轮，不带跨会话历史，也不带该词条的历史问答。
- `addable` 只是建议，最多 3 条；是否入库由用户点按钮决定，AI 不直接写知识库。
- 回答本身不限长（抽屉可滚动），但 `answerZh` 为空即判为失败。

### 听力训练调用边界

听力训练只有一种调用 `generateListeningSet`：一次生成一轮 10 句，每句附中文意思、两条干扰项、重点表达、听觉难点标签和两级提示文本。

- 请求必须是结构化条件（场景、二级场景、学习者等级、兴趣），不能只给一个 CEFR 等级——只给等级出来的是教科书英语，训练不到真实语流。
- `keyExpression.en` 必须是句子里原样出现的连续片段：揭晓页要高亮它，第三级提示要把它挖空，对不上就整题作废。
- 三条干扰项必须与正确意思互不重复（一共四个选项），否则四选一会退化。
- 每条干扰项要带 `mishearType` 和 `whyZh`：类型取自封闭集合且三条互不相同——答错后要统计「你栽在哪一类」，类型能自由写这个统计就没法聚合；`whyZh` 会原样显示给用户，必须讲到具体的音。
- 提示文本不得包含整句英文或正确中文原文，本地校验会拒绝。
- 后两级提示（挖空英文、完整英文）由本地从原句生成，不问 AI——挖空位置必须和关键表达严格一致。
- 影视和游戏场景写风格相同的原创台词，不照搬真实作品原话（`英语听力训练模块DESIGN.md` §15）。
- 逐条校验，坏的丢掉并记原因；能用的少于 5 句整体失败，不开一局残缺的训练。
- 十句一次性等下来是几十秒，所以这个调用**逐条交付**：流里每闭合一个句子对象就解析、校验、交给界面，
  界面攒够 3 句即可开练，剩下的边听边补。增量发出去的和最后返回的是同一批（同一个校验 session），
  顺序一致，不会出现"听过的句子又来一遍"。不要增量的调用方不传回调，此时仍走一次性解析。
- 揭晓页的连读/弱读讲解**不问 AI**，由本地按拼写规则从原句算（`SoundChanges.kt`）：用户答完题正等着看讲解，
  不能再等一次网络。AI 只负责标 `audioFeatures`（这句难在哪一类），"哪两个词粘在了一起"由本地指出。

### 等待期间要报到哪一步

流式调用把进度分成三段（`GenerationStage`）：还没接通 / 模型在想 / 正文在写。

- 推理模型开口前会先想一阵，这段只有 reasoning 增量、没有正文。只报"已生成 N 字符"的话，
  界面在这段时间一动不动，看着就像没联网——而"没接通"和"在想"用户该做的反应完全不同。
- "在等模型"从**请求发出**那一刻算起，不是等响应头：实测推理模型想完之前连响应头都不发
  （一次压住 49 秒），而真正的接通只有几十毫秒。等响应头才切状态等于整个思考期都写着"接通中"。
- reasoning 各家字段名和形状都不一样（`reasoning_content` / `reasoning`，字符串或对象），
  按 `JsonElement` 收，取得出字符串才显示；取不出来就只说"模型在想"，不猜。
- **OpenAI 官方的 Chat Completions 一个字都不发**：streaming delta 只有 content / role /
  refusal / tool_calls，原始思维链任何接口都不给。上面那套解析是给 DeepSeek / vLLM /
  OpenRouter 这类加了扩展字段的服务商用的。要在等待期间显示 OpenAI 模型的思考，
  只能改用 Responses API 的 `reasoning.summary`（流式事件 `response.reasoning_summary_text.delta`），
  那是一次换接口的改动，没做。
- 增量块的 `content` 可能是 `null`（不少服务商第一块就是），解析必须容忍，否则整块被丢掉。
- 思考的字符数一样算进 `MAX_RESPONSE_CHARS`：转起圈来的推理同样是一直收、一直计费。

`explainWord` 与 `explainSentence` 支持 SSE 增量回调。增量文本只用于加载期间展示；只有完整 JSON 解析和校验成功后，保存按钮才可使用。

对话和判定可以并行请求，但只有两者都通过 schema 与业务校验后，该轮才进入本地状态。判定器返回的目标 id 必须属于场景目标；沟通失败提示必须同时包含“听成了什么、为什么走反、建议改写”。

首版可以只实现一个 provider，但切换 provider 不应要求重写页面和数据库。

### 模型按功能选

每次调用都带一个 `AiTask`，provider 用它去偏好里取该功能的模型：

- 这些调用性质差得很远：听力一次要写十句带干扰项的题，值得用最强的模型；摇一摇提问和点词讲解
  要的是马上出字，用大模型反而难受。一个全局模型没法同时满足两头。
- 没单独指定过的功能跟随默认模型，默认模型改了它们自动跟着改；只有显式设过的功能才存一条覆盖。
  默认模型本身也在同一处改（列表第一行），它是所有"跟随"项的兜底。
- 模型清单从 `GET {baseUrl}/models` 拉，用户只在拉回来的名字里挑——手打模型名要到真正生成时
  才报错，是这套配置最容易出错的地方。拉不到时保留当前值并给出服务端说的那句话。
- 清单先按 id 里的关键词筛掉不能对话的（生图、嵌入、语音、重排、审核……聚合网关几百个里
  真正能聊天的没几个）。这只是按名字猜，所以界面留「显示全部」的口子，且全被筛光时原样列出。
- 清单拉一次就缓存在应用层，两级页面共用，翻来翻去不重复发请求。

## 3. 阅读生成请求

请求至少包含：

- `schemaVersion`
- `learnerLevel`
- `topic`
- `genre`
- `targetLength`
- `knownVocabulary`
- `reviewVocabulary`
- `newVocabulary`
- `reviewGrammar`
- `newGrammar`
- `languageOfExplanation`: `zh-CN`

不要把整个知识库无上限塞入上下文。本地程序应先选择与本次任务有关的子集，并对“已掌握词汇”采用等级、频率边界或压缩摘要。

## 4. 语法输出契约

语法字段必须各司其职：

```json
{
  "schemaVersion": 1,
  "patternEn": "be going to + base verb",
  "labelZh": "计划将来表达",
  "summaryZh": "表示已有计划或打算",
  "explanationZh": "用于说话前已经决定的计划……",
  "goodExampleEn": "I am going to call her tonight.",
  "goodExampleZh": "我今晚打算给她打电话。",
  "badExampleEn": "I am going to calling her.",
  "badExampleNoteZh": "to 后接动词原形。",
  "tipZh": "临时决定通常用 will。"
}
```

- `patternEn` 是卡片唯一主标题，只能是含英文的可套用结构公式，不得混入中文说明或完整例句。
- `labelZh` 是短中文语法标签；`summaryZh` 是不超过 36 字的一句话用途，只用于列表和快速回忆。
- `explanationZh` 才承载使用条件、语气和易混区别，不得挤进标题或列表副标题。
- 本地校验拒绝中文混入 `patternEn`、缺少英文形式、用途过长、必要例句缺失以及已学结构重复。

### 语法练习题

`generateGrammarDrill` 针对一个语法点出挖空变形题，程序判分：

```json
{
  "schemaVersion": 1,
  "items": [
    {
      "sentenceEn": "She ___ Japanese since last winter.",
      "options": ["is learning", "has been learning", "learns", "learned"],
      "answerIndex": 1,
      "explanationZh": "since + 时间点要求这件事延续到现在。"
    }
  ]
}
```

- 一句只挖一个空，空一律写成三个下划线；选项只写填进空里的那部分，3～4 个、不重复、不超过 40 字符。
- 干扰项必须是中文母语者真会写错的形式；句子用词要简单，难点落在形式而不是生词。
- 本地逐题校验，坏题直接丢；剩下不足两道判整批失败，讲解已入库的部分不受影响。
- 判分和复习评分都在本地：正确率 → `ReviewGrade`（全对 Easy / 3-4 Good / 一半 Hard / 更低 Forgot），AI 不参与掌握状态判定。
- `errorTag` 只能取固定集合里的值（见 `GrammarErrorTag`），本地归一化，不认识的一律 `other`。它决定做错之后系统给你讲什么，所以不接受 AI 自创标签。
- 反过来，语法讲解请求会带上最近错得最多的几类形式，要求挑一个能直接解释这些错误的语法点；用户自己指定了语法点时不带这段。

### 中译英产出

`generateTranslationTasks` 出题、`gradeTranslation` 判定，分两次调用：

- 出题带上最近错得最多的形式、最近学过的语法点和刚复习过的词；`errorTag` 同样只能取 `GrammarErrorTag` 里的值。
- `hintZh` 只能点结构或关键词，不得把整句参考答案塞进提示。
- 判定返回 `verdict`（ok / minor / wrong）、在用户原句基础上改出的 `correctedEn`、一句 `noteZh` 和最多两个 `errorTags`。判定器不布置新任务、不重写成参考答案。
- 用户写的句子是不可信输入，用 `<user_answer>` 包住。
- 只有 verdict 不是 ok 时才记错题，避免"意思到了"也污染错误画像。

### 阅读理解题的口径

- 每篇必须至少有一道 `form`（为什么用这个形式）或 `reference`（指代）题，纯大意题靠猜词就能做对，练不到解析能力。
- 这两类题必须给 `evidenceFromText`，本地校验它确实是正文子串；对不上直接拒绝整篇。

## 5. 阅读输出草案

```json
{
  "schemaVersion": 1,
  "title": "A Small Change of Plan",
  "body": "...",
  "estimatedCefr": "A2",
  "targetVocabulary": [
    {
      "term": "eventually",
      "meaningZh": "最终",
      "exampleFromText": "...",
      "role": "review"
    }
  ],
  "targetGrammar": [
    {
      "id": "past-perfect-basic",
      "exampleFromText": "...",
      "explanationZh": "..."
    }
  ],
  "comprehensionQuestions": [
    {
      "id": "q1",
      "type": "single_choice",
      "promptZh": "...",
      "options": ["...", "...", "...", "..."],
      "answerIndex": 1,
      "explanationZh": "..."
    }
  ]
}
```

这是设计草案，不是最终 Kotlin schema。实现前必须为每种题型定义严格字段和长度限制。

## 6. 本地校验

阅读入库前至少检查：

- JSON 和 schema 版本合法。
- 标题、正文和题目非空，长度在允许范围内。
- 指定复习词和新词实际出现在正文中，允许经过明确的词形归一化。
- 指定语法的例句确实是正文子串。
- 新词比例没有明显超过目标。
- 单选题答案索引有效且选项不重复。
- 内容不存在明显的提示词泄露或系统字段。

语法是否真正符合目标很难用规则完全判断；首版可以采用规则检查加 AI 二次校验，但要记录其不确定性。

## 7. 能力测试

- AI 可以按难度约束生成题目，但标准答案必须在展示前通过格式和一致性检查。
- 自适应升降级由本地程序控制。
- CEFR 结果应附置信度，不把一次测试包装成精确结论。
- 开放表达反馈需要区分原文、建议修改、错误类型和简短解释。

## 8. 提示词安全与隐私

- 用户粘贴的内容属于不可信输入，应与系统指令明确分隔。
- 不允许网页正文或用户文章改变输出 schema、工具或应用行为。
- 默认不向 AI 发送真实姓名、密钥、完整学习数据库或录音。
- 调试日志应隐藏 Authorization、订阅密钥和用户长文本。

## 9. 请求上限

每次调用都必须带输出上限，没有上限的流式请求会一直收、一直计费：

- `max_tokens` 随请求发出（`DEFAULT_MAX_TOKENS`，听力这类最长的返回用 `LISTENING_MAX_TOKENS`）。
- 流式读取另有字符硬上限 `MAX_RESPONSE_CHARS`，用于服务端不认 `max_tokens` 或模型自己转圈的情况。超过就掐断并返回失败。
- OkHttp 的 `readTimeout` 拦不住这种情况：它是每次读的超时，只要一直有数据到达就会被不断重置。
- 上限字段有两个名字：老接口认 `max_tokens`，较新的 OpenAI 模型只认 `max_completion_tokens`，对前者直接回 400。客户端先发 `max_tokens`，收到提到该字段的 400 时换名重发一次，只换一次。
- 每次调用按 `AiTask` 带上 `reasoning_effort`：推理模型开口前的思考实测能占整次调用六成
  （听力一次实测 49 秒），而多数任务只是按模板填内容，不需要它想那么久。不认这个参数的服务端
  会回 400，届时去掉重发；带着它挨的任何 400 都先去掉重试一次，但只有服务端点名说了这个参数
  才记进偏好——否则一次无关的 400 会把这个模型的思考力度永久关掉。
- 换名的结论要**按模型记住**（存偏好，`AiConfig.useCompletionTokens`）。不记的话每一次调用都要先撞一个 400 再重发，白搭一整个往返，而且这一下全落在用户干等的那段时间里。

## 10. 失败信息与日志

- 失败原因必须带上服务端错误正文里的那句话（`{"error":{"message":...}}`），只报状态码等于什么都没说。
- AI 调用打日志到 `LazyDogAI` 标签：哪个调用、模型、主机、提示词长度、状态、耗时、重试原因。用 `adb logcat -s LazyDogAI` 看。
- 按 §8 脱敏：不打 Authorization、密钥、提示词正文和用户长文本，只打长度；服务端正文里的 `sk-*` 和 `Bearer *` 会被替换，超长正文截断。
- 另外两行是给"为什么等这么久"用的：`⏱ 响应头 200｜新建连接｜DNS 21ms｜建连 8140ms｜TLS 260ms｜发请求 8ms｜等响应头 950ms｜合计 9.4s`，以及 `⏱ 首个思考/首个正文｜请求发出后 N ms`。等待期间界面上那一段跨了 DNS、建连、TLS、上传和等响应头五件事，不拆开量就只能猜。

## 10. 待实现时确定

- 实际 AI provider 与模型选择
- 结构化输出能力和客户端库
- 单次请求上下文预算
- 修复重试次数及费用上限
- 内容安全规则
- 提示词版本管理方式
