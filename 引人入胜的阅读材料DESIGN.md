---
doc: "引人入胜的阅读材料DESIGN.md"
tier: "L4 专项设计"
status: "部分落地"
version: "1.0"
updated: "2026-09-04"
authority: "阅读材料的价值模型、Hook 到 Payoff 的结构、生成管线与质检"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
---

# 英语学习 App 高吸引力阅读材料设计

## 1. 目标

阅读模块的目标不是“生成符合 CEFR 的英语文章”，而是持续产出用户**主动想点开、愿意读完、读完觉得有收获，并且顺带完成英语学习**的内容。

核心原则：

> 先把它做成值得读的内容，再把学习目标嵌进去。

一篇高质量阅读材料同时满足三件事：

1. **想读**：标题、主题、叙事或问题本身有吸引力。
2. **读得下去**：难度适配、节奏好、信息密度合适，不像教材。
3. **读完有收获**：获得一个新知识、新观点、新解释、新技能或可复述的故事，同时完成词汇/语法/阅读能力训练。

如果只能优化一个指标，优先优化“用户是否愿意继续读下一段”，而不是语言知识点覆盖率。

---

## 2. 内容价值模型

不要把所有阅读文章都做成“知识科普”。用户长期阅读需要多种价值来源。

每篇文章至少选择一个 Primary Value，可附加一个 Secondary Value。

### 2.1 Knowledge Value

读完知道一个以前不知道的事实、机制或现象。

例：

- Why airplanes rarely fly in a perfectly straight line
- Why your phone feels faster after restarting
- Why Japanese convenience stores rarely run out of food
- Why some grapes smell like flowers

适合：科技、科学、商业、社会、历史、生活常识。

### 2.2 Insight Value

不是增加一个事实，而是改变用户看问题的方式。

例：

- Why being busy can make you less productive
- Why cheap products sometimes cost more in the long run
- Why people remember unfinished tasks better
- Why “more choices” can make decisions harder

要求文章提供“原本直觉 → 反直觉解释 → 新模型”。

### 2.3 Practical Value

读完以后可以直接做一件事。

例：

- A simple way to remember a new word for months
- How to notice misleading graphs
- How to recover when a conversation in English breaks down
- How to plan a 30-minute city walk that feels longer

必须给出明确可执行结论，而不是泛泛而谈。

### 2.4 Story Value

故事本身值得读，即使没有教学目标也成立。

例：

- The programmer who accidentally deleted a company database
- The passenger who landed a plane with no flying experience
- The tiny mistake that cost a company millions
- The Japanese town that moved an entire train station

关键不是“故事体”，而是具备：人物、目标、阻碍、变化、结果。

### 2.5 Conversation Value

读完以后用户会想把它讲给别人听。

内容应具有至少一种：

- surprising fact
- counterintuitive result
- useful social observation
- memorable number
- unusual story
- “原来如此”的解释

可以将其作为文章质量指标：

> Would a user naturally say “你知道吗……” after reading this?

### 2.6 Emotional Value

适量加入惊讶、好奇、温暖、紧张、幽默、共鸣。

不要为了“有趣”滥用夸张、恐惧、煽情或 clickbait。

---

## 3. 内容结构：Hook → Gap → Discovery → Payoff

高吸引力阅读不应采用传统教材结构：

> Introduction → Explanation → Example → Conclusion

推荐使用：

```text
Hook
↓
Curiosity Gap
↓
Progressive Discovery
↓
Twist / Key Insight
↓
Payoff
↓
Takeaway
```

### 3.1 Hook

前 1–3 句必须制造继续阅读的理由。

可用类型：

#### Unexpected fact

> A modern smartphone can survive thousands of tiny errors every second without you noticing.

#### Mini mystery

> Every night, supermarkets throw away food. Yet convenience stores in Japan somehow manage to keep shelves full without wasting everything. How?

#### Concrete situation

> You open a map, choose the fastest route, and somehow arrive later than someone who took a longer road.

#### Contradiction

> The fastest way to learn more words may be to spend less time memorizing them.

#### Story opening

> At 2:13 a.m., a junior engineer typed one command and took an entire service offline.

禁止使用：

- In today's world...
- English is very important...
- There are many reasons...
- Have you ever wondered...? 作为高频模板

### 3.2 Curiosity Gap

文章前段提出一个尚未解决的问题，让读者产生信息缺口。

例如：

> The strange part was that nothing was wrong with the engine.

不要马上给答案。

### 3.3 Progressive Discovery

每 80–150 词至少出现一个新的信息点：

- 新事实
- 新线索
- 新例子
- 解释升级
- 小反转

防止文章中间变成平铺直叙。

### 3.4 Payoff

文章必须兑现开头制造的好奇心。

差：

> So there are many reasons for this phenomenon.

好：

> The secret is not better prediction. It is deliberately leaving room for uncertainty.

### 3.5 Takeaway

最后不是 textbook conclusion，而是留下一个用户能带走的东西：

- 一条经验
- 一个 mental model
- 一个可以观察世界的方法
- 一个值得记住的事实
- 一个可执行动作

---

## 4. “收获感”设计

用户读完一篇文章后，系统应能回答：

> 你刚刚到底学到了什么？

每篇文章生成时必须声明一个 `reader_payoff`。

```json
{
  "reader_payoff": {
    "type": "insight",
    "statement": "Fast systems often feel fast because they reduce waiting uncertainty, not just because they reduce actual latency."
  }
}
```

要求：

- 一篇文章只有一个核心 payoff。
- 可以用一句话复述。
- 不是标题的重复。
- 不是泛泛结论。
- 必须在正文中被证据或故事支撑。

读完后 UI 可以显示：

> **One thing worth remembering**  
> People dislike uncertain waiting more than equally long predictable waiting.

这比“本文总结”更有价值。

---

## 5. Topic Engine：不要随机出题

阅读材料的主题应该来自一个 Topic Engine，而不是让模型自由发挥。

输入：

```typescript
interface LearnerInterestProfile {
  strongInterests: string[]
  weakInterests: string[]
  avoidedTopics: string[]
  recentlyReadTopics: string[]
  likedArticles: string[]
  skippedArticles: string[]
}
```

主题候选来源：

1. 用户明确兴趣
2. 当前社会/科技/文化热点
3. Evergreen 高价值知识
4. 与用户已读内容相邻但不重复的主题
5. 可自然承载目标词汇的主题

### 5.1 Topic Mixing

推荐内容分布：

```text
50% 高置信兴趣
20% 相邻兴趣探索
20% 普适高价值内容
10% 惊喜探索
```

不要形成信息茧房。

例如用户喜欢 AI，不意味着每天都是：

- AI coding
- AI agents
- AI chips
- AI jobs

可以扩展到：

- 为什么数据库会出现脏读
- 为什么 GPS 在城市峡谷里会飘
- 一个大型系统事故是如何发生的
- 为什么航空业特别重视 checklist
- 游戏为什么故意制造 friction

---

## 6. 内容类型池

为防止阅读体验单调，至少维护以下内容 archetypes。

### 6.1 Explain Something Weird

结构：奇怪现象 → 猜测 → 真正机制。

例：

> Why cold water sometimes feels warmer after you stay outside

### 6.2 One Question, One Answer

围绕一个具体问题深入解释。

> Why don't electric cars need traditional gearboxes?

### 6.3 Mini Case Study

真实或可靠来源改编的小案例。

> How a typo caused a $300 million trading error

### 6.4 Hidden System

解释日常背后的系统。

> What actually happens after you tap your train card

### 6.5 Counterintuitive Idea

> Why making something slightly harder can improve memory

### 6.6 Short Narrative

以人物或事件推进。

### 6.7 Decision / Trade-off

提供两个方案，让读者理解权衡。

> Is a bigger battery always better?

### 6.8 Myth vs Reality

但避免制造 strawman。

### 6.9 Before / After

解释某项技术、制度、产品如何变化。

### 6.10 “You Can Notice This Today”

读完即可在现实生活观察验证。

此类型对建立“学到东西”的感觉特别有效。

---

## 7. 语言学习目标应该隐形

文章正文不应该频繁提醒用户：

> Today's vocabulary is...

语言目标放在生成层，而不是阅读体验层。

正文优先级：

```text
自然性
> 可读性
> 信息价值
> 学习目标覆盖
```

如果目标词会破坏文章自然度，宁可换词或减少出现次数。

### 7.1 Target Vocabulary

300–500 词建议：

- 新目标词：4–7
- 复习词：6–12
- 自然重复：1–3 次
- 不强制每个词重复

要求目标词：

- 出现在可推断语境中
- 尽可能承担文章真实语义
- 不为了覆盖率造句

### 7.2 Grammar

每篇最多 1–2 个主要 grammar targets。

目标语法自然复现，而不是刻意堆砌。

### 7.3 Difficulty

基于用户实际词汇模型控制，而不是只看 CEFR。

建议目标：

```text
known word coverage: 95–98%
unknown but inferable: 1–3%
hard blockers: <1%
```

---

## 8. 阅读节奏设计

移动端阅读尤其需要控制视觉与认知负荷。

建议：

- 单篇 250–700 词
- 单段 30–80 词
- 通常 5–9 段
- 每段承担一个明确功能
- 每 2–3 段有一个 mini payoff

不要生成 500 词、4 个超长段落。

### 8.1 Micro Hooks

段落结尾偶尔使用轻度的 forward pull：

> But that only solves half the problem.

> The bigger surprise comes from what happens next.

> This is where the usual explanation breaks down.

不能每段都使用，否则变成廉价 clickbait。

---

## 9. 标题设计

标题决定点击率，但不能牺牲可信度。

推荐标题模型：

### Question

> Why Does Your Phone Get Hot While Charging?

### Hidden Explanation

> The Small Trick That Makes Elevators Feel Faster

### Specific Surprise

> A 2-Second Delay Can Feel Longer Than a 10-Second Wait

### Story

> The Engineer Who Broke the Internet for 30 Minutes

### Practical

> A Better Way to Remember Words You Keep Forgetting

标题评分：

```text
Curiosity
Specificity
Clarity
Credibility
Relevance
```

拒绝：

- You Won't Believe...
- This Changes Everything
- The Secret They Don't Want You to Know

---

## 10. 生成 Pipeline

推荐至少使用多阶段生成。

```text
Learner State
↓
Topic Candidate Generator
↓
Topic Ranker
↓
Content Research / Fact Pack
↓
Article Planner
↓
Writer
↓
Interest Critic
↓
Learning Critic
↓
Fact / Difficulty Validator
↓
Rewrite if needed
↓
Exercise Generator
↓
Publish
```

### 10.1 Topic Candidate Generator

一次生成 10–30 个候选，不直接写文章。

每个候选包含：

```json
{
  "title": "Why Elevators Often Have Mirrors",
  "hook": "Mirrors were not added only so passengers could check their appearance.",
  "valueType": "insight",
  "readerPayoff": "Perceived waiting time can matter as much as actual waiting time.",
  "interestTags": ["design", "psychology", "technology"],
  "estimatedLevel": "B1",
  "novelty": 0.86
}
```

### 10.2 Topic Ranking

```text
TopicScore =
0.30 × InterestFit
+ 0.20 × Curiosity
+ 0.20 × ReaderPayoff
+ 0.10 × Novelty
+ 0.10 × LanguageFit
+ 0.10 × Diversity
```

避免单纯根据用户兴趣打分，否则主题会越来越窄。

---

## 11. Fact Pack

对于知识型阅读，不推荐让 Writer 一边“想事实”一边写。

先构造 Fact Pack：

```json
{
  "facts": [
    {
      "claim": "...",
      "confidence": "high",
      "source": "..."
    }
  ],
  "uncertainClaims": [],
  "doNotClaim": []
}
```

Writer 只能基于 Fact Pack 写事实性内容。

好处：

- 降低 hallucination
- 可以更新时效内容
- 可以保留来源
- Critic 更容易核验

对于故事、虚构场景，应明确标记：

```json
"content_mode": "fictional_scenario"
```

不能把模型编的故事包装成真实案例。

---

## 12. Article Planner

Writer 前先生成结构。

示例：

```json
{
  "title": "Why Elevators Often Have Mirrors",
  "hook": "Mirrors solve a problem that has little to do with appearance.",
  "central_question": "Why were mirrors installed in elevators?",
  "reader_payoff": "Changing perceived waiting can be cheaper than changing actual speed.",
  "beats": [
    {
      "role": "setup",
      "content": "Old elevators felt painfully slow."
    },
    {
      "role": "failed_solution",
      "content": "Making them mechanically faster was expensive."
    },
    {
      "role": "discovery",
      "content": "Designers changed passengers' experience instead."
    },
    {
      "role": "explanation",
      "content": "Mirrors occupied attention and reduced perceived waiting."
    },
    {
      "role": "transfer",
      "content": "The same principle appears in loading indicators and queue design."
    }
  ],
  "target_vocab": ["notice", "reduce", "improve", "instead", "experience"]
}
```

---

## 13. Writer Prompt 设计

System 级约束示例：

```text
You write compelling English reading material for language learners.

Your first responsibility is to create something genuinely worth reading.
The article should work even if all language-learning features were removed.

Optimize for:
1. curiosity
2. clarity
3. useful or memorable payoff
4. natural English
5. learner-appropriate difficulty

Do not write like a textbook.
Do not explain that the text is for English learners.
Do not mechanically insert vocabulary.
Do not use generic introductions or generic conclusions.

The first 2-3 sentences must create a concrete reason to continue reading.
Every paragraph must add new information, tension, explanation, or payoff.
The ending must deliver the promised insight rather than simply summarize the article.
```

User payload：

```json
{
  "learner": {
    "level": "B1",
    "knownVocabulary": [],
    "targetVocabulary": [],
    "reviewVocabulary": []
  },
  "contentPlan": {},
  "factPack": {},
  "constraints": {
    "length": "350-450 words",
    "knownWordCoverage": ">=96%"
  }
}
```

---

## 14. Interest Critic

这是整个系统非常重要的一层。

不要问 Critic：

> Is this article good?

应该从“真实读者是否愿意继续读”检查。

输出：

```json
{
  "scores": {
    "hook": 0.82,
    "curiosity": 0.76,
    "informationGain": 0.91,
    "pacing": 0.68,
    "payoff": 0.88,
    "naturalness": 0.90
  },
  "boringSections": [
    {
      "paragraph": 3,
      "reason": "Repeats the mechanism without adding new information"
    }
  ],
  "rewriteInstructions": [
    "Compress paragraph 3",
    "Move the surprising example earlier"
  ]
}
```

建议阈值：

```text
hook >= 0.75
payoff >= 0.80
naturalness >= 0.85
informationGain >= 0.75
overall >= 0.82
```

低于阈值自动 rewrite。

---

## 15. Boringness Detector

专门检测“正确但无聊”。

常见症状：

- 开头过于泛化
- 同一观点换句话重复
- 大量定义
- 没有具体例子
- 全文没有 surprise
- 没有因果链
- 没有人、场景或具体对象
- payoff 在开头已经完全说完
- 每段句式相似
- 结尾只是 summary

定义：

```text
BoringnessScore =
Genericness
+ Repetition
+ Predictability
+ Abstraction
- Curiosity
- Specificity
- NarrativeMovement
- InformationGain
```

建议把它做成独立 Validator，而不是完全依赖 Writer 自评。

---

## 16. Exercise 设计：不要毁掉好文章

阅读结束后不要立刻弹 10 道传统选择题。

推荐流程：

```text
Read
↓
1 个 comprehension check
↓
显示 payoff
↓
2–4 个语言学习任务
↓
optional deep dive
```

### 16.1 First Question

优先测试理解，而不是记细节。

> What was the main reason mirrors helped solve the elevator problem?

### 16.2 Retrieval

让用户回忆最重要的信息。

> Without looking back, explain the main idea in one sentence.

### 16.3 Vocabulary in Context

> In this paragraph, what does “reduce” most likely mean?

### 16.4 Transfer

这是“读完有收获”的关键强化。

> Where else could this idea be useful?

或：

> Can you think of an app that changes perceived waiting time?

使文章知识从“读过”变成“理解”。

---

## 17. Reading UI

### 17.1 Feed Card

```text
[Technology · 4 min]

Why Elevators Often Have Mirrors

Mirrors solve a problem that has surprisingly little to do with appearance.

B1 · 5 new words
```

不要在卡片上堆太多“课程信息”。

优先展示：

- 标题
- teaser
- 阅读时间
- category

CEFR、词数等作为 secondary metadata。

### 17.2 正文

默认是纯阅读体验。

单词辅助应按需出现：

- 点击词 → 简短英文释义
- 再点击 → 中文
- 发音
- 当前句中的意义

不要默认高亮几十个目标词，破坏阅读流。

可以只对目标词提供非常轻的 underline/dot，允许关闭。

### 17.3 Paragraph Checkpoint

长文章可以加入一个无打断式 checkpoint：

> So far, what is the main problem?

但不要过多。

---

## 18. 用户反馈闭环

真正决定“什么有趣”的不是模型，而是用户行为。

记录：

```typescript
interface ReadingBehavior {
  impression: boolean
  opened: boolean
  openDelayMs: number
  readingTimeMs: number
  scrollDepth: number
  completed: boolean
  vocabularyClicks: number
  abandonedParagraph?: number
  saved: boolean
  shared: boolean
  liked: boolean
  disliked: boolean
  nextArticleOpened: boolean
}
```

高价值行为优先级：

```text
share/save
> completed
> next article opened
> long reading dwell
> click
```

不要把 CTR 当唯一目标，否则系统会向 clickbait 演化。

### 18.1 Satisfaction Score

```text
SatisfactionScore =
0.25 × Completion
+ 0.20 × NormalizedReadingTime
+ 0.15 × SaveOrShare
+ 0.15 × ExplicitFeedback
+ 0.15 × NextArticleIntent
+ 0.10 × LearningOutcome
```

---

## 19. 推荐系统中的探索

长期推荐需要 explore/exploit。

用户可能不知道自己喜欢某类内容。

例如从：

```text
AI → software failures → aviation safety → human factors → cognitive bias
```

可以逐步发现新的兴趣簇。

每篇文章建立 embedding / tags：

```json
{
  "topic": ["technology", "design"],
  "concept": ["perception", "waiting"],
  "format": "hidden-system",
  "tone": "curious",
  "value": "insight"
}
```

推荐时不要只按 topic 相似度，还要考虑：

- format diversity
- value diversity
- novelty
- recent fatigue

---

## 20. Anti-Repetition

LLM 阅读产品很容易产生“看了 10 篇感觉都是一篇”。

需要显式记录最近内容：

```text
last 30:
- topics
- hooks
- article structures
- title patterns
- takeaway types
- target vocab
```

生成前加入：

```text
Avoid repeating these recently used patterns:
- "Why X is actually Y"
- opening with "Imagine..."
- ending with "The next time you..."
```

不仅 topic 去重，还要结构和语言模板去重。

---

## 21. 内容新鲜度

阅读 Feed 推荐混合：

```text
40% evergreen
30% personalized interest
20% current / recent
10% experimental
```

时效内容必须有事实检索和日期控制。

Evergreen 内容更适合长期 SRS，因为未来重新出现不会过期。

---

## 22. 与 SRS 的融合

不要让阅读材料服务于 SRS 到破坏内容。

正确方向：

```text
Content Topic
↓
找到可自然使用的复习词
↓
轻量注入
```

而不是：

```text
20 个今天必须复习的词
↓
强行编成文章
```

可定义词汇注入优先级：

```text
semantic fit > review urgency
```

如果一个复习词与文章高度不匹配，延后到其他内容。

---

## 23. 数据结构

```typescript
interface ReadingArticle {
  id: string

  title: string
  teaser: string
  content: Paragraph[]

  category: string
  tags: string[]
  archetype: ArticleArchetype
  valueType: ValueType

  centralQuestion: string
  readerPayoff: string

  targetLevel: CEFR
  estimatedReadingMinutes: number

  vocabulary: VocabularyOccurrence[]
  grammarTargets: GrammarOccurrence[]

  factPackId?: string
  contentMode: 'factual' | 'fictional' | 'adapted_story'

  scores: {
    hook: number
    curiosity: number
    payoff: number
    naturalness: number
    informationGain: number
    difficultyFit: number
    overall: number
  }

  generationMetadata: {
    generatorVersion: string
    promptVersion: string
    generatedAt: string
  }
}
```

---

## 24. MVP

第一阶段不要一开始构建复杂推荐 AI。

### MVP v1

实现：

1. 兴趣标签
2. 10 种 article archetype
3. Topic Candidate → Planner → Writer → Critic
4. reader payoff
5. 目标词轻量注入
6. 点击查词
7. 3 类阅读题
8. completion / like / save 反馈

### v2

加入：

- 用户词汇 mastery
- 个性化难度
- SRS integration
- topic recommender
- anti-repetition

### v3

加入：

- 实时热点内容
- Fact Pack + source verification
- multi-armed bandit 推荐
- 个性化 article archetype
- 自动兴趣图谱

---

## 25. 核心 KPI

不要以“每天生成多少文章”为 KPI。

### Content KPI

```text
Article Open Rate
Completion Rate
Median Reading Time
Save Rate
Share Rate
Next Article Rate
```

### Learning KPI

```text
Target Word Recall D+1
Target Word Recall D+7
Comprehension Accuracy
Inference Question Accuracy
Active Recall Success
```

### North Star

推荐：

> Weekly Meaningful Reads

定义为：

```text
用户完成阅读
AND
通过核心 comprehension
AND
产生至少一个有效学习行为
```

避免单纯用 DAU / 阅读次数驱动低质量内容。

---

## 26. 最终设计原则

### 原则 1

**内容本身必须值得读。**

删除所有英语学习功能以后，如果文章不值得读，就不应该发布。

### 原则 2

**每篇只承诺一个主要收获。**

信息多不等于信息价值高。

### 原则 3

**制造好奇心，但必须兑现。**

Hook 不是骗点击，而是建立一个真正值得回答的问题。

### 原则 4

**语言学习目标应该隐藏在自然内容里。**

用户体验是“我刚读了个很有意思的东西”，而不是“我完成了第 12 篇 B1 教材”。

### 原则 5

**兴趣由行为学习，而不是由模型猜。**

推荐系统最终应根据打开、读完、保存、分享和继续阅读行为学习用户真正喜欢什么。

### 原则 6

**优化长期阅读欲望，而不是单篇 CTR。**

真正优秀的系统应该让用户形成：

> “我看看今天有什么有意思的东西。”

而不是：

> “今天还得完成英语任务。”

这应该成为整个阅读模块的产品方向。