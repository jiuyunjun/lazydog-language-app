---
doc: "母语阅读DESIGN.md"
tier: "L4 专项设计"
status: "部分落地"
version: "1.0"
updated: "2026-09-07"
authority: "母语阅读的内容生成、英语替换、知识点绑定、交互与学习闭环"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
depends_on:
  - "引人入胜的阅读材料DESIGN.md"
---

# 母语阅读（Native Bridge Reading）专项设计

## 0. 一句话定义

**母语阅读不是“中英夹杂文章生成器”，而是一种以母语保证内容理解与阅读欲望、用英语逐步替换可理解语义单元的桥接式阅读模式。**

用户体验应该是：

> 我本来在读一篇真的有意思的中文文章，读着读着自然遇到一些我已经学过、正在复习、或者刚好可以学会的英语。

而不是：

> 系统为了塞单词，把一篇中文文章机械地换成了半中半英。

---

# 1. 产品目标

母语阅读解决传统英文阅读的一个核心矛盾：

- 全英文文章对初中级用户认知负担过高；
- 全中文文章虽然好读，但几乎没有英语输入；
- 逐句翻译类双语阅读又容易让用户始终只看中文；
- 单词卡 / SRS 能记词，却缺少真实语境；
- 强行把待复习词塞进英文文章会降低文章质量。

母语阅读的目标是把“内容价值”和“语言输入”解耦：

```text
高质量母语内容
+
可理解的英语局部替换
+
用户词库 / SRS / 语法模型
+
点击即学
=
低阻力、高频率、可持续的英语输入
```

### 1.1 核心目标

1. 用户愿意主动点开。
2. 即使英语能力较弱，也能顺畅读完整篇。
3. 已学词在真实语境中反复被看到。
4. 生词能够依赖上下文被猜到，而不是必须查词。
5. 语法通过完整短语、从句、句子出现，而不是只在知识页里出现。
6. 点击任何英语片段都能解释“这里为什么这样说”。
7. 阅读行为反向更新词汇 mastery 和用户兴趣模型。
8. 随着能力增长，文章可以从“中文为主”自然迁移到“英文为主”。

---

# 2. 与“引人入胜的阅读材料”模块的关系

母语阅读不重新发明内容系统，而是复用现有高吸引力阅读内容架构：

```text
Topic Engine
↓
Research / Fact Pack
↓
Article Planner
↓
Chinese Canonical Writer
↓
Interest Critic
↓
Learning Target Selector
↓
Replacement Planner
↓
Mixed-Language Renderer
↓
Learning / Fact / Fluency Validator
↓
Publish
```

继承以下原则：

- 内容本身必须值得读；
- Hook → Curiosity Gap → Progressive Discovery → Payoff；
- 一篇文章只有一个主要 reader payoff；
- 热点内容先检索再写，Writer 不自行编事实；
- 主题从用户兴趣、热点、evergreen、相邻探索中产生；
- 不为学习目标牺牲文章自然度；
- 点击、读完、保存、继续阅读等真实行为用于推荐闭环。

母语阅读新增的重点不是“怎么写文章”，而是：

> **如何把已经好读的母语文章转换成学习价值高、但仍然顺滑的混合语言文章。**

---

# 3. 一个必须明确的比例问题

最初设想：

```text
70–80% 词库里的词
20–30% 生词 / 新语法
```

这个方向是对的，但不能直接按“整篇文章的所有词”统计。

中文和英文的 token/词边界不同，而且如果整篇文章 20–30% 都是英语生词，阅读负担会过大。

因此拆成两个独立指标。

## 3.1 English Surface Ratio

表示最终可见文本中，有多少语义内容显示为英语。

```text
EnglishSurfaceRatio =
EnglishSpanSemanticWeight / TotalArticleSemanticWeight
```

MVP 不需要真的计算 semantic weight，可先用字符/分词后的近似值。

推荐档位：

| 模式 | 英语表层占比 | 适用 |
|---|---:|---|
| 轻松 | 8–15% | A1–A2 / 第一次体验 |
| 平衡 | 18–28% | A2–B1，默认 |
| 强化 | 30–40% | B1–B2 |
| 高沉浸 | 45–60% | B2+，向全英阅读过渡 |
| 全英 | 90–100% | 直接进入英文阅读模式 |

**默认建议：22–28%。**

不建议一开始就做到 50% 以上，否则“母语保证阅读流畅”的优势会迅速消失。

## 3.2 English Knowledge Composition

只统计已经显示为英语的学习单元：

```text
Known / Review: 70–80%
New Target:     20–30%
```

进一步拆分：

```text
60–70% mastered vocabulary / chunks
10–20% due-review vocabulary
15–25% new vocabulary
0–10% grammar-focused spans
```

注意：

- grammar target 不应该机械占满 20–30%；
- 每篇主要 grammar target 建议只有 1–2 个；
- 一个 grammar span 往往是完整 phrase / clause / sentence，认知负担高于单词。

### 3.3 Unique Target Ratio 与 Exposure Ratio 分开

一个新词可以出现 2 次。

因此必须区分：

```text
unique target units
!=
target occurrences
```

建议每篇 400–700 中文字的文章：

```text
new vocabulary: 4–8 个
review vocabulary: 6–15 个
grammar targets: 0–2 个
new-target repetitions: 1–3 次 / 个，按自然度决定
```

---

# 4. 核心概念：不要“替换单词”，要替换 Semantic Span

最危险的实现方式：

```text
生成中文
↓
找中文词
↓
查字典
↓
直接替换成英文
```

这会产生大量不自然结果，因为中英文并不是词对词映射。

正确抽象是：

```text
SemanticSpan
```

即：

> 在当前句子中承担一个完整意义、可以自然切换语言的最小单元。

## 4.1 Span 类型

### A. Lexical Span

一个词或短语：

```text
这个设计可以明显 reduce 用户的等待焦虑。
```

`reduce` 是核心词义，并且在此句中自然。

### B. Chunk / Collocation Span

优先级高于孤立单词：

```text
真正的问题不是速度，而是 how long it feels。
```

或者：

```text
系统需要 leave room for uncertainty，而不是假设预测永远正确。
```

用于：

- collocation
- phrasal verb
- idiom
- 固定表达
- 高频沟通表达

### C. Grammar Span

用目标语法替换一个完整从句：

```text
Even if the prediction is wrong，系统仍然有缓冲空间。
```

点击后进入 grammar knowledge item：

```text
even if + clause
让步：即使……
```

### D. Sentence Span

整个句子切换成英语：

```text
Most people notice uncertainty before they notice actual waiting time.
```

适合：

- 高 mastery 用户；
- 非关键难点句；
- 已掌握词汇覆盖率很高的句子；
- 用来训练连续英语阅读。

### E. Named Entity / Technical Term Span

有些词天然更适合保留英语：

```text
rate limiting
cache
latency
trade-off
Pokémon GO
GPS
```

这类不一定算“学习目标”，但可以参与英语表层比例。

---

# 5. “语言岛”原则

最终文章应该像一个个小的 English Island，而不是每个中文短语中间都夹一个英文词。

差：

```text
这个 system 通过 reduce 用户的 waiting time 来 improve experience。
```

虽然每个单词都对，但读起来非常碎。

更好：

```text
这个系统真正想降低的，并不是 actual waiting time，而是人对等待的不确定感。
```

或者：

```text
The trick is to make the wait feel predictable，而不是单纯让它变短。
```

原则：

```text
semantic coherence > vocabulary coverage
```

### 5.1 Span 邻接合并

如果同一句中连续 2–4 个可替换目标属于同一个英语短语，应优先合并。

例如词库中有：

```text
make
decision
harder
```

不要生成：

```text
让 decision 变得 harder
```

如果用户具备相关能力，可直接：

```text
make decisions harder
```

这同时训练：

- make + object + adjective
- decision 的复数
- 自然搭配

---

# 6. 内容生成模式

## 6.1 模式 A：用户选主题

入口提供：

- AI / 科技
- 日本社会
- 商业 / 金融
- 游戏 / 动漫
- 汽车 / 摩托
- 科学
- 心理
- 历史
- 生活知识
- 随机惊喜
- 自定义主题

用户可以输入：

```text
“为什么日本便利店很少缺货”
“讲讲 GPS 为什么在高楼附近会漂”
“今天 AI 有什么值得看的”
```

## 6.2 模式 B：热点

用户选择：

```text
热点
```

系统：

```text
实时搜索
↓
候选新闻聚类
↓
去掉低价值 / 重复 / 纯情绪新闻
↓
生成 5–10 个“值得读”的角度
↓
用户选 / 自动选择
↓
Fact Pack
↓
文章
```

热点不是把新闻摘要改写成教材。

应优先寻找：

- 为什么这件事重要；
- 背后的机制；
- 一个容易被忽略的细节；
- 对普通人的影响；
- 前因后果；
- 与已有知识的连接。

例如不要只写：

```text
某公司今天发布了新模型。
```

而是：

```text
为什么这次模型升级真正值得注意的不是 benchmark，而是推理成本下降？
```

## 6.3 模式 C：系统推荐

基于：

```text
InterestFit
Novelty
ReaderPayoff
RecentFatigue
LanguageFit
LearningOpportunity
Freshness
```

选题评分：

```text
TopicScore =
0.25 InterestFit
+ 0.18 Curiosity
+ 0.18 ReaderPayoff
+ 0.10 Novelty
+ 0.10 Freshness
+ 0.09 LanguageFit
+ 0.05 LearningOpportunity
+ 0.05 Diversity
```

注意：

`LearningOpportunity` 权重不能太高，否则主题会被今天待复习的单词劫持。

---

# 7. Canonical Article：先写“纯中文母版”

先生成一篇完整、自然、有价值的纯中文文章：

```text
ChineseCanonicalArticle
```

原因：

1. 内容质量可以单独评价。
2. 学习目标不会影响最初写作。
3. 可以随时重新生成不同沉浸度的混合版本。
4. 同一篇文章可用于 A/B 测试不同 replacement plan。
5. 能避免 Writer 一边写故事一边机械想着“这个词必须塞进去”。

Canonical Article 必须通过：

```text
InterestCritic
FactValidator
PayoffValidator
```

之后才进入英语转换。

---

# 8. Learning Target Selector

输入：

```typescript
interface LearnerLanguageState {
  level: CEFR
  vocabulary: VocabularyMastery[]
  grammar: GrammarMastery[]
  recentExposures: LearningExposure[]
  dueReviews: string[]
  recentlyLearned: string[]
  avoidedItems?: string[]
}
```

每个词汇至少包含：

```typescript
interface VocabularyMastery {
  vocabularyId: string
  lemma: string
  senses: VocabularySenseMastery[]
  mastery: number        // 0..1
  stability?: number
  retrievability?: number
  lastSeenAt?: string
  dueAt?: string
}
```

必须按 `sense` 追踪，而不是只按 lemma。

例如：

```text
charge
```

可能是：

- 收费
- 充电
- 指控
- 冲锋
- 负责

用户认识“充电”不代表认识其他意义。

## 8.1 Candidate Pool

为文章提取可学习概念：

```text
Article Concepts
↓
Vocabulary Mapper
↓
Known / Review / New Candidate Pool
```

筛选优先级：

```text
semantic fit
> frequency / usefulness
> current learner value
> review urgency
> ease of contextual inference
```

## 8.2 新词选择

一个新词适合作为母语阅读 target，需要满足：

- 在当前主题里非常自然；
- 用户大概率未来还会遇到；
- 上下文可以猜出大意；
- 不依赖大量额外背景；
- 不与同篇其他新词过度相似；
- 不是只在该新闻里出现一次的罕见专有词。

可定义：

```text
NewWordScore =
0.30 SemanticFit
+ 0.20 GeneralUtility
+ 0.15 Frequency
+ 0.15 Inferability
+ 0.10 LearnerGap
+ 0.10 ReusePotential
```

---

# 9. Replacement Planner

这是母语阅读最核心的模块。

它不负责写文章，而负责输出：

```text
哪里换
换成什么
为什么换
属于什么知识点
难度多少
可否撤回
```

## 9.1 Planner 输入

```json
{
  "article": {},
  "learnerState": {},
  "targets": {
    "reviewVocabulary": [],
    "newVocabulary": [],
    "grammar": []
  },
  "settings": {
    "englishSurfaceRatio": 0.25,
    "knownShareWithinEnglish": 0.75,
    "allowSentenceSpans": true
  }
}
```

## 9.2 Planner 输出

```json
{
  "spans": [
    {
      "paragraphId": "p2",
      "sourceText": "降低",
      "renderText": "reduce",
      "type": "vocabulary",
      "knowledgeId": "vocab_reduce_01",
      "masteryClass": "review",
      "confidence": 0.96,
      "semanticFit": 0.98
    },
    {
      "paragraphId": "p4",
      "sourceText": "即使预测错了",
      "renderText": "Even if the prediction is wrong",
      "type": "grammar",
      "knowledgeId": "grammar_even_if",
      "masteryClass": "target",
      "confidence": 0.92
    }
  ]
}
```

---

# 10. Replacement Rules

## 10.1 不替换关键事实中的高风险部分

例如数字、法律结论、医学警告、新闻关键事实，不应该因为学习目标导致意义漂移。

关键事实句可以有英语，但必须：

- 翻译置信度极高；
- 不改变 modality；
- 不改变数字和时间；
- 不改变因果关系；
- 必要时整个事实保留中文。

## 10.2 不在标题里大量替换

Feed 首先是内容产品。

默认：

```text
标题：纯中文
teaser：最多 1 个英语 span
正文：正常替换
```

可以实验“标题中 1 个高熟悉度英语关键词”，但不作为默认。

## 10.3 开头降低陌生度

Hook 的任务是让人读下去。

前 1–2 段：

```text
new-target density <= article average
```

避免一打开就连续 5 个生词。

## 10.4 Payoff 附近减少阻塞词

文章最关键的 insight 必须被理解。

因此：

- payoff 句允许英语；
- 但优先使用熟词；
- 生词不能成为理解 payoff 的唯一钥匙。

## 10.5 相邻未知项限制

默认禁止：

```text
两个 unknown spans 紧邻
```

建议至少隔开：

```text
8–20 个中文字符 / 一个自然语义单位
```

或者使用一个完整 grammar island，让它变成一个学习单元。

## 10.6 同一新词首次出现与复现

首次：

```text
轻提示 + 可点击
```

再次：

```text
不再额外解释
```

第三次：

```text
可根据 mastery 不显示提示标记
```

形成自然的 scaffolding fade-out。

---

# 11. 不只是 Dictionary Link：Knowledge Link

点击英语 span 后，不应该永远进入“单词页”。

统一抽象：

```typescript
type KnowledgeItem =
  | VocabularyKnowledge
  | PhraseKnowledge
  | GrammarKnowledge
  | SentencePatternKnowledge
  | NamedEntityKnowledge
  | ConceptKnowledge
```

每个 span：

```typescript
interface LearningSpan {
  id: string
  paragraphId: string

  sourceStart: number
  sourceEnd: number

  sourceZh: string
  renderedEn: string

  kind:
    | 'vocabulary'
    | 'phrase'
    | 'grammar'
    | 'sentence'
    | 'technical_term'

  knowledgeId?: string
  masteryClass:
    | 'mastered'
    | 'review'
    | 'target'
    | 'incidental'

  difficulty: number
  inferability: number
}
```

点击行为：

```text
vocabulary → Word Detail
phrase → Phrase / Chunk Detail
grammar → Grammar Detail
sentence → Sentence Breakdown
technical term → Term Explanation
```

---

# 12. 点击后的 Bottom Sheet

为了不打断阅读，第一次点击不直接跳整页。

建议：

```text
[reduce]
/rɪˈduːs/

降低；减少

这里：
reduce uncertainty
= 减少不确定性

[播放] [查看单词页] [我认识] [加入学习]
```

grammar：

```text
[Even if the prediction is wrong]

even if + clause
即使……也……

这里表达：
“预测错误”不会改变后面的结论。

[查看语法页] [再看一个例子]
```

### 12.1 单击 / 长按

建议：

```text
单击：Bottom Sheet
再次点击“详情”：知识页
长按：显示原始中文 / 逐词分析
```

不要单击直接整页跳转，容易破坏阅读流。

---

# 13. Reveal / Rescue 机制

母语阅读的目标是“基本不中断”。

因此提供极低成本 rescue。

### 13.1 Tap Reveal

点击英语片段：

```text
reduce
↓
降低
```

### 13.2 Sentence Reveal

当整句是英语时：

```text
长按 / 双击
↓
显示该句中文母版
```

### 13.3 Paragraph Rescue

如果用户短时间内连续点击多个英语 span：

```text
系统推断当前段过难
↓
显示：
“这一段英语有点密，要切回轻松模式吗？”
```

不要立刻永久改设置，可以只对本篇降档。

---

# 14. 动态难度

不要只在开始时固定 25%。

用户阅读过程中可以实时调整下一段。

## 14.1 Difficulty Signals

```text
span click rate
unknown reveal rate
reading slowdown
back-scroll
paragraph dwell
abandonment
explicit “too hard / too easy”
```

例如：

```text
过去 2 段：
英语点击率 45%
+
阅读速度下降 55%
+
连续 3 个 target reveal
↓
下一段 EnglishSurfaceRatio 25% → 15%
```

反之：

```text
0 click
+
快速顺畅读完
↓
下一篇 25% → 30%
```

MVP 可以先只做“下一篇自适应”，不做段内动态。

---

# 15. 熟词不是越多越好：Retrieval Opportunity

已学词出现的价值不是“看到了就算复习”。

系统应优先选择：

```text
用户认识
+
近期需要复习
+
在这个上下文中自然
```

而不是随机找 30 个已知词换成英语。

定义：

```text
ReviewOpportunityScore =
SemanticFit
× ReviewUrgency
× ContextQuality
× Naturalness
```

这样 SRS 与内容系统的关系仍然是：

```text
内容优先
↓
在自然位置寻找复习机会
```

不是：

```text
今天到期 20 个词
↓
必须全塞进这篇文章
```

---

# 16. Grammar 设计

语法不适合“换一个词”学习。

必须以 pattern span 出现。

例如目标：

```text
not because A, but because B
```

文章中：

```text
The system works not because it predicts perfectly, but because it leaves room for error.
```

点击：

```text
not because A, but because B
不是因为 A，而是因为 B
```

## 16.1 Grammar Target 数量

建议：

```text
A1–A2: 0–1
B1:    1
B2+:   1–2
```

## 16.2 Grammar Span 选择

必须满足：

- 语义本身值得表达；
- 不为了语法而制造别扭句；
- 最好在同篇自然复现 1 次；
- 不在同一句叠加多个陌生 grammar target。

---

# 17. Phrase-first Vocabulary

长期目标不应只是 word mastery。

大量英语能力来自 chunks：

```text
in the long run
as a result
turn out to be
leave room for
be likely to
rather than
make sense
on average
```

因此 Vocabulary Selector 应允许：

```text
Word
Phrase
Collocation
Pattern
```

推荐学习价值优先级：

```text
natural chunk
> isolated lemma
```

如果用户已经认识：

```text
leave
room
```

但没学过：

```text
leave room for uncertainty
```

系统仍可把它当新学习单元。

---

# 18. 一个完整示例

纯中文母版：

> 日本便利店最厉害的地方，也许不是“什么都有”，而是它们很少让你感觉某样东西真的缺了。直觉上，这似乎需要极其准确的需求预测。但现实里的需求永远会被天气、节假日、附近活动甚至一场突然的大雨打乱。
>
> 真正有效的系统通常并不追求完美预测，而是给错误留下空间。门店会根据时间、销量和配送频率不断进行小幅修正。预测错一点并不可怕，只要系统能快速纠正。
>
> 这也是很多大型软件系统的设计逻辑：可靠性并不来自“永远不出错”，而来自“出错后仍然能恢复”。

平衡模式：

> 日本便利店最厉害的地方，也许不是“什么都有”，而是它们很少让你感觉某样东西真的缺了。直觉上，这似乎需要极其准确地 **predict demand**。但现实里的需求永远会被天气、节假日、附近活动甚至一场突然的大雨打乱。
>
> 真正有效的系统通常并不追求 perfect prediction，而是 **leave room for error**。门店会根据时间、销量和配送频率不断进行小幅 **adjustments**。预测错一点并不可怕，**as long as the system can recover quickly**。
>
> 这也是很多大型软件系统的设计逻辑：reliability 并不来自“永远不出错”，而来自“出错后仍然能恢复”。

这里包含：

```text
predict demand
- phrase
- review

perfect prediction
- familiar chunk
- mastered

leave room for error
- phrase target

adjustments
- new vocabulary

as long as ...
- grammar target

reliability
- review/new depending on learner
```

这比：

```text
便利店会 predict demand，然后 make adjustments 来 improve reliability
```

自然得多。

---

# 19. UI：Feed

卡片仍然首先卖“内容”。

```text
[日本社会 · 4 min]

便利店真正擅长的，也许不是预测需求

为什么一个永远会预测错的系统，反而可以很少缺货？

母语阅读 · 平衡
```

次要 metadata：

```text
约 24% English
5 个新表达
1 个语法
```

不要显示：

```text
今日任务 7/20
必须学习 8 个词
```

作为主要视觉。

---

# 20. UI：阅读页

示意：

```text
────────────────────
便利店真正擅长的，
也许不是预测需求

日本社会 · 4 min
[轻松 | 平衡 | 强化]

日本便利店最厉害的地方……
似乎需要极其准确地 predict demand。
但现实里的需求……

真正有效的系统通常并不追求
perfect prediction，而是
leave room for error。

...
────────────────────

One thing worth remembering

可靠系统不一定更少犯错，
而可能只是更擅长从错误中恢复。
────────────────────
```

## 20.1 Span Visual

不要把所有英语画成荧光笔。

推荐：

```text
mastered: 无特殊高亮
review:   极轻 dotted underline
target:   轻 underline / small dot
grammar:  一个连续可点击 span
```

允许用户关闭所有提示。

---

# 21. 阅读结束后的学习闭环

不要马上 10 道题。

推荐：

```text
文章
↓
1 个核心理解题
↓
reader payoff
↓
2–4 个英语 span 回忆
↓
可选进入深度学习
```

## 21.1 Comprehension

问文章本身：

```text
为什么文中的系统不需要做到“完美预测”？
```

## 21.2 Context Recall

显示中文：

```text
“给错误留下空间”
```

让用户回忆：

```text
leave room for error
```

## 21.3 Recognition

```text
adjustment 在这里最接近：
A 调整
B 库存
C 预测
D 损失
```

## 21.4 Grammar Transfer

```text
用 as long as 表达：
“只要系统能恢复，就没关系。”
```

---

# 22. 从母语阅读向全英文阅读迁移

这是这个功能最大的长期价值。

用户可以形成路径：

```text
Stage 0
中文 95% / 英文 5%

Stage 1
中文 85% / 英文 15%

Stage 2
中文 75% / 英文 25%

Stage 3
中文 60% / 英文 40%

Stage 4
中文 40% / 英文 60%

Stage 5
英文 90%+，中文仅按需辅助
```

升级不按 CEFR 强制，而按真实行为：

```text
low reveal rate
high completion
stable comprehension
high target recall
```

系统提示：

> 你最近 8 篇文章里几乎不需要查看英文释义。要不要把默认英语比例从 25% 提到 30%？

---

# 23. Generation Pipeline

完整推荐：

```text
1. Learner State
2. Topic Candidate Generator
3. Topic Ranker
4. Optional Web Search
5. Fact Pack
6. Article Planner
7. Chinese Canonical Writer
8. Interest Critic
9. Fact Validator
10. Learning Candidate Extractor
11. Target Selector
12. Replacement Planner
13. Mixed Language Renderer
14. Semantic Equivalence Validator
15. Fluency Critic
16. Difficulty Validator
17. Knowledge Link Validator
18. Rewrite / Re-plan
19. Exercise Generator
20. Publish
```

不要把 7–17 合成一个 Prompt。

---

# 24. Semantic Equivalence Validator

这是必须新增的 Validator。

因为英文替换后最容易发生：

- 语义变窄；
- 语义变宽；
- 时态变了；
- modality 变了；
- 因果关系变了；
- 中文和英语拼接后语法断裂；
- 英语虽然“翻译正确”，但语境不自然。

输出：

```json
{
  "spanId": "s_023",
  "semanticEquivalence": 0.96,
  "naturalness": 0.91,
  "grammarFit": 0.98,
  "risk": "low",
  "action": "keep"
}
```

阈值：

```text
semanticEquivalence >= 0.95
naturalness >= 0.85
grammarFit >= 0.90
```

低于阈值：

```text
rephrase span
or
expand span boundary
or
revert to Chinese
```

**revert to Chinese 永远是合法 fallback。**

---

# 25. Fluency Critic

检查最终文章是不是“夹生”。

评分：

```text
LanguageSwitchNaturalness
SpanFragmentation
ReadingFlow
UnknownDensity
CodeSwitchCoherence
PunctuationFit
```

特别检测：

```text
连续过多单词级切换
冠词/单复数错误
英文词性不适配
专有名词被误判为学习项
同一句多个独立 target
英文 span 与中文标点连接奇怪
```

可以定义：

```text
FragmentationPenalty =
isolatedEnglishSpanCount
+ adjacentSwitchCount × 1.5
+ oneWordSwitchChains × 2
```

Planner 优先减少碎片切换。

---

# 26. Difficulty Validator

不要只看“生词占比”。

综合：

```text
DifficultyScore =
UnknownLexicalLoad
+ GrammarLoad
+ EnglishSpanLength
+ SwitchFrequency
+ ConceptDifficulty
+ SentenceComplexity
```

例如：

- 25% 英语、全是熟词，可能很轻松；
- 15% 英语、但全是复杂从句，可能更难。

输出：

```json
{
  "englishSurfaceRatio": 0.24,
  "knownCoverageWithinEnglish": 0.78,
  "newTargets": 5,
  "grammarTargets": 1,
  "switchesPer100Chars": 3.2,
  "estimatedDifficulty": 0.46
}
```

---

# 27. 数据结构

```typescript
interface NativeReadingArticle {
  id: string

  sourceArticleId?: string

  title: string
  teaser: string

  canonicalLanguage: 'zh-CN'
  canonicalParagraphs: CanonicalParagraph[]
  renderedParagraphs: RenderedParagraph[]

  category: string
  tags: string[]
  archetype: ArticleArchetype
  valueType: ValueType

  centralQuestion: string
  readerPayoff: string

  contentMode:
    | 'factual'
    | 'fictional_scenario'
    | 'adapted_story'

  sourceMode:
    | 'evergreen'
    | 'personalized'
    | 'current_event'
    | 'user_prompt'

  learningProfile: {
    englishSurfaceTarget: number
    englishSurfaceActual: number

    knownShareTarget: number
    knownShareActual: number

    targetVocabularyIds: string[]
    reviewVocabularyIds: string[]
    grammarTargetIds: string[]
  }

  learningSpans: LearningSpan[]

  scores: {
    hook: number
    curiosity: number
    payoff: number
    informationGain: number
    canonicalNaturalness: number

    semanticEquivalence: number
    codeSwitchNaturalness: number
    difficultyFit: number
    learningValue: number

    overall: number
  }

  generationMetadata: {
    generatorVersion: string
    promptVersion: string
    replacementPlannerVersion: string
    generatedAt: string
  }
}
```

---

# 28. Rendered Text 不建议直接存一整个 Markdown String

推荐 segment model：

```typescript
interface RenderedParagraph {
  id: string
  segments: ReadingSegment[]
}

type ReadingSegment =
  | {
      type: 'zh'
      text: string
    }
  | {
      type: 'learning'
      text: string
      spanId: string
    }
```

UI：

```tsx
paragraph.segments.map(segment => {
  if (segment.type === 'zh') {
    return <Text>{segment.text}</Text>
  }

  return (
    <LearningSpan
      spanId={segment.spanId}
      text={segment.text}
    />
  )
})
```

优点：

- 点击定位稳定；
- 不需要在 Markdown 里做复杂 range；
- 后续可切换“纯中文 / 混合 / 全英”；
- 可动态改变 span；
- 支持埋点；
- 支持 TTS。

---

# 29. Knowledge Link 数据

```typescript
interface KnowledgeLink {
  knowledgeId: string
  kind: 'word' | 'phrase' | 'grammar' | 'pattern'

  displayText: string
  canonicalForm: string

  meaningInContextZh: string
  pronunciation?: string

  sourceRoute:
    | `/vocabulary/:id`
    | `/phrase/:id`
    | `/grammar/:id`

  contextualExampleId?: string
}
```

如果一个生成出的表达词库里还不存在：

```text
temporary knowledge item
↓
用户点击“加入学习”
↓
promote to persistent vocabulary/phrase item
```

不要因为知识库没有现成 ID 就禁止生成一个非常自然的新表达。

---

# 30. TTS

母语阅读的 TTS 有两个模式。

## 30.1 Natural Mixed TTS

中英混合朗读。

需要按 segment 切 voice / language：

```text
zh segment → zh-CN voice
en segment → en-US / en-GB voice
```

## 30.2 English-only Practice

只播放英语 spans：

```text
predict demand
leave room for error
as long as the system can recover quickly
```

形成 30–90 秒的“本文英语回顾”。

这个功能学习价值很高，成本也低。

---

# 31. 用户设置

不要给太多参数。

主设置只暴露：

### 英语量

```text
少
适中
多
自动
```

内部映射：

```text
少   12%
适中 24%
多   38%
自动 adaptive
```

### 生词量

```text
少
适中
多
```

内部：

```text
少   10–15% of English learning units
适中 20–25%
多   25–35%
```

### 显示学习提示

```text
目标词轻提示 ON/OFF
```

高级设置可隐藏：

- 是否允许整句英语；
- 是否优先复习词；
- 美式 / 英式；
- grammar span；
- 技术词保留英文。

---

# 32. 行为埋点

新增：

```typescript
interface NativeReadingBehavior {
  articleId: string

  opened: boolean
  completed: boolean
  readingTimeMs: number
  scrollDepth: number

  englishSurfaceRatio: number

  spanImpressions: number
  spanClicks: number
  targetSpanClicks: number
  reviewSpanClicks: number

  revealCount: number
  sentenceRevealCount: number

  markedKnownCount: number
  addedToLearningCount: number

  comprehensionCorrect?: boolean

  difficultyFeedback?:
    | 'too_easy'
    | 'good'
    | 'too_hard'

  switchedDifficulty?: boolean

  liked: boolean
  saved: boolean
  nextArticleOpened: boolean
}
```

---

# 33. 学习状态更新

不能把“看到一次”直接当学会。

不同事件权重不同：

```text
span impression
< completed sentence containing span
< explicit meaning reveal
< context question correct
< active recall correct
```

示例：

```text
impression:       exposure +1
clicked meaning:  evidence of uncertainty
marked known:     weak positive signal
recognition pass: medium positive
active recall:    strong positive
```

如果用户看到一个 supposed-mastered 词却点击释义：

```text
mastery confidence ↓
```

这能纠正用户词库中的错误“已掌握”状态。

---

# 34. 内容和学习两个 KPI 必须分开

## Content KPI

```text
Open Rate
Completion Rate
Median Reading Time
Save Rate
Next Article Rate
```

## Bridge Learning KPI

```text
English Span Read-through Rate
Meaning Reveal Rate
Target Recall D+1
Target Recall D+7
Grammar Recognition
English Surface Growth
```

## 核心长期指标

推荐：

```text
Native-to-English Progression
```

例如：

> 连续 30 天中，用户在 comprehension 不下降的情况下，平均可接受英语表层比例从 18% 提升到 31%。

这是这个功能真正独特的价值指标。

---

# 35. 推荐 North Star

```text
Weekly Meaningful Bridge Reads
```

定义：

```text
完成阅读
AND
核心理解正确
AND
有效暴露 >= N
AND
至少一个学习目标产生有效记忆行为
```

另外跟踪：

```text
Voluntary Next Read Rate
```

防止产品变成“学习打卡工具”。

---

# 36. Anti-patterns

## 36.1 随机查中文词然后替换

禁止。

## 36.2 一句话里塞 5 个英语单词

禁止。

## 36.3 每个待复习词必须出现

禁止。

## 36.4 生词第一次出现没有可推断语境

尽量避免。

## 36.5 为了 grammar target 改坏原文

禁止。

## 36.6 点击单词直接跳走

默认不做。

## 36.7 把热点文章交给模型凭记忆写

禁止。

## 36.8 英语越多就算越高级

错误。

高级意味着：

```text
在理解率不下降时，可以处理更长、更自然、更连续的英语语义块。
```

而不是单纯更多 code-switch。

---

# 37. Prompt 设计

## 37.1 Chinese Canonical Writer

```text
You write high-value Chinese reading material.

The article must be genuinely worth reading even if no language-learning
features are later added.

Optimize for:
1. curiosity
2. clarity
3. information gain
4. pacing
5. a memorable payoff

Use the supplied ArticlePlan and FactPack.
Do not insert English.
Do not think about vocabulary coverage.
Do not write like a textbook.
```

## 37.2 Learning Candidate Extractor

```text
Analyze the Chinese canonical article and identify semantic spans that
could naturally be expressed in English for this learner.

Do not translate mechanically word-by-word.

Prefer:
- useful vocabulary
- collocations
- reusable phrases
- grammar patterns
- semantically coherent clauses

For each candidate return:
- source span
- natural English rendering
- semantic role
- knowledge mapping
- contextual inferability
- naturalness
- difficulty
```

## 37.3 Replacement Planner

```text
Select a set of English replacement spans.

Primary objective:
Preserve a smooth, compelling reading experience.

Secondary objectives:
- achieve the target English surface ratio
- reuse learner-known vocabulary
- create high-quality review opportunities
- introduce a small number of inferable new items
- include at most the requested grammar targets

Never choose a span only to satisfy coverage.
If a replacement would feel unnatural, leave it in Chinese.
Prefer coherent phrase/clause islands over many isolated one-word switches.
```

## 37.4 Fluency Critic

```text
Read the final mixed-language article as a real bilingual reader.

Find places where language switching feels fragmented, artificial,
grammatically awkward, or cognitively disruptive.

Do not reward the article for containing more English.
Recommend reverting spans to Chinese whenever that improves reading flow.
```

---

# 38. 生成成本优化

不需要每一步都使用最大模型。

可分层：

```text
Topic generation       → fast model
Topic ranking          → fast / rules
Fact search            → search
Article plan           → strong model
Chinese writer         → strong model
Interest critic        → medium / strong
Candidate extraction   → fast / medium
Replacement planner    → medium
Semantic validator     → medium
Exercise generation    → fast
```

缓存：

- same article 的 canonical 版本；
- Fact Pack；
- semantic candidates；
- knowledge mappings。

用户只切换“少/适中/多”时，不要重新搜索和重写文章，只重跑 Replacement Planner 或使用预生成 plans。

---

# 39. MVP

第一版不要做实时动态难度和复杂 grammar mastery。

## MVP v1

必须实现：

1. 复用 Topic Engine。
2. 支持用户自选主题。
3. 支持纯中文 Canonical Article。
4. 词库 mastered / review / unknown 三类。
5. `EnglishSurfaceRatio` 三档。
6. 70–80% 熟悉英语单元 + 20–30% 新目标。
7. word / phrase 两种 span。
8. 最多 1 个 grammar target。
9. Replacement Planner。
10. 点击 span → Bottom Sheet。
11. Bottom Sheet → 对应知识页。
12. Semantic Equivalence Validator。
13. Fluency Critic。
14. completion / span click / reveal / like 埋点。
15. 阅读后 1 个理解题 + 2–3 个英语回忆。

## MVP v1.5

加入：

- sentence span；
- phrase knowledge item；
- mixed TTS；
- “显示原中文”；
- 自动难度建议；
- review urgency；
- 本文英语回顾。

## v2

加入：

- 热点搜索；
- Fact Pack；
- grammar mastery；
- 动态 paragraph difficulty；
- multi-plan caching；
- learning-based recommendation；
- Native-to-English progression。

---

# 40. 首版实现建议

如果现有 App 已经有：

- 用户词库；
- 单词详情页；
- Grammar 页；
- 阅读生成模块；

最短实现路径：

```text
Step 1
先生成高质量纯中文文章

Step 2
后端返回：
canonicalParagraphs
+
learningSpans

Step 3
前端使用 segment renderer

Step 4
点击 span 打 Bottom Sheet

Step 5
通过 knowledgeId 跳现有 Word / Grammar 页面

Step 6
记录 span click / reveal

Step 7
加入“少 / 适中 / 多”三个英语量档位
```

不要在首版：

- 做逐 token 动态替换；
- 做复杂 AST；
- 做句中实时生成；
- 让客户端自己查字典替换；
- 每次用户调档位都重新生成整篇文章。

---

# 41. 推荐 API

## POST /native-reading/generate

```json
{
  "topic": {
    "mode": "custom",
    "query": "为什么日本便利店很少缺货"
  },
  "learning": {
    "englishMode": "balanced",
    "newWordMode": "normal",
    "allowGrammar": true
  }
}
```

返回：

```json
{
  "articleId": "nr_123",
  "title": "...",
  "teaser": "...",
  "readerPayoff": "...",
  "paragraphs": [
    {
      "id": "p1",
      "segments": [
        {
          "type": "zh",
          "text": "日本便利店..."
        },
        {
          "type": "learning",
          "spanId": "s1",
          "text": "predict demand"
        }
      ]
    }
  ],
  "learningSpans": {
    "s1": {
      "kind": "phrase",
      "knowledgeId": "phrase_predict_demand",
      "sourceZh": "预测需求",
      "masteryClass": "review"
    }
  }
}
```

## POST /native-reading/:id/event

```json
{
  "event": "span_reveal",
  "spanId": "s1",
  "position": 312
}
```

---

# 42. Acceptance Criteria

一篇母语阅读文章可发布前至少满足：

## 内容

```text
hook >= 0.75
payoff >= 0.80
informationGain >= 0.75
canonicalNaturalness >= 0.88
```

## 混合语言

```text
semanticEquivalence >= 0.95
codeSwitchNaturalness >= 0.85
```

## 学习

```text
target new vocab within configured range
grammar targets <= configured limit
no unknown cluster violation
all interactive spans have valid knowledge mapping or temporary item
```

## 产品

```text
纯中文模式可以完整阅读
任意 learning span 可以 reveal
任意 persistent knowledge span 可以进入详情
切换英语量不会破坏 articleId / progress
```

---

# 43. 最终产品原则

### 原则 1

**先写一篇值得读的中文文章，再考虑怎么教英语。**

### 原则 2

**英语替换的单位是语义片段，不是字典里的词。**

### 原则 3

**70–80% / 20–30% 描述的是英语学习单元的熟悉度组成，不是整篇文章的语言比例。**

### 原则 4

**英语量和生词量必须是两个独立旋钮。**

### 原则 5

**phrase / chunk / clause 的价值通常高于随机孤立单词。**

### 原则 6

**用户遇到困难时，母语必须随时成为无摩擦的 rescue layer。**

### 原则 7

**SRS 可以寻找自然出现机会，但不能决定文章写什么。**

### 原则 8

**点击英语不是查字典，而是进入一个 Knowledge Item。**

### 原则 9

**热点先有可信 Fact Pack，再生成文章。**

### 原则 10

**最终目标不是永远读中英混合，而是逐渐让用户能够读越来越连续的英语。**

---

# 44. 最核心的产品判断

这个功能真正有潜力的地方，不是：

> “把中文里的 25% 换成英语。”

而是建立一条原本很多英语学习产品没有解决好的连续路径：

```text
我完全能理解的内容
↓
我认识的大量英语局部出现
↓
少量新英语能靠上下文猜出来
↓
英语短语越来越长
↓
完整英语从句越来越多
↓
整句英语
↓
整段英语
↓
全英文阅读
```

如果这条迁移曲线做得好，“母语阅读”就不只是一个阅读模式，而可以成为：

> **从背词阶段进入真实英文阅读阶段的桥接层。**
