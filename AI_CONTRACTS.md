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

### 听力训练调用边界

听力训练只有一种调用 `generateListeningSet`：一次生成一轮 10 句，每句附中文意思、两条干扰项、重点表达、听觉难点标签和两级提示文本。

- 请求必须是结构化条件（场景、二级场景、学习者等级、兴趣），不能只给一个 CEFR 等级——只给等级出来的是教科书英语，训练不到真实语流。
- `keyExpression.en` 必须是句子里原样出现的连续片段：揭晓页要高亮它，第三级提示要把它挖空，对不上就整题作废。
- 两条干扰项必须与正确意思互不重复，否则三选一会退化成二选一。
- 提示文本不得包含整句英文或正确中文原文，本地校验会拒绝。
- 第三级提示（挖空英文）由本地从原句生成，不问 AI——挖空位置必须和关键表达严格一致。
- 影视和游戏场景写风格相同的原创台词，不照搬真实作品原话（`英语听力训练模块DESIGN.md` §15）。
- 逐条校验，坏的丢掉并记原因；能用的少于 5 句整体失败，不开一局残缺的训练。

`explainWord` 与 `explainSentence` 支持 SSE 增量回调。增量文本只用于加载期间展示；只有完整 JSON 解析和校验成功后，保存按钮才可使用。

对话和判定可以并行请求，但只有两者都通过 schema 与业务校验后，该轮才进入本地状态。判定器返回的目标 id 必须属于场景目标；沟通失败提示必须同时包含“听成了什么、为什么走反、建议改写”。

首版可以只实现一个 provider，但切换 provider 不应要求重写页面和数据库。

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

## 10. 待实现时确定

- 实际 AI provider 与模型选择
- 结构化输出能力和客户端库
- 单次请求上下文预算
- 修复重试次数及费用上限
- 内容安全规则
- 提示词版本管理方式
