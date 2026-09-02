# Vocabulary Memory Assistance Design

## 1. 目标

本系统用于语言学习 App 中的**词汇辅助记忆（Vocabulary Memory Assistance）**。

目标不是单纯“解释单词”，而是为学习者生成：

- 容易理解的核心含义
- 容易回忆的记忆钩子
- 可靠的词形/构词提示
- 发音与拼写关联
- 具体的视觉或情景联想
- 必要的易混词对比
- 高频自然语境
- 可用于后续检索练习的回忆线索

核心原则：

```text
记忆辅助 ≠ 百科解释
```

系统应优先生成：

```text
短
具体
可视化
可复现
可检索
可验证
```

的记忆线索。

---

## 2. 核心设计原则

### 2.1 不强制统一记忆法

不同单词最适合的记忆方式不同。

例如 `unhappy` 适合通过 `un + happy` 的构词方式记忆；`receive` 更适合强调拼写结构与易错字母顺序；`bizarre` 则可能更适合视觉场景与语义联想。

因此系统应先判断：

```text
这个词最值得记什么？
```

然后只选择最有效的 1–2 种主记忆策略。

---

## 3. 记忆策略分类

> **这七类是记忆类型的唯一权威取值。** `单词记忆DESIGN.md` §35 列了十三类，那是同一批东西的更细切分，
> 映射关系见本文 §16.1。细分不进实现：模型要在十三个标签里挑，界面上却分不出差别。

推荐支持以下记忆类型：

```text
MORPHOLOGY
CONTEXT
ORTHOGRAPHY
PRONUNCIATION
CONTRAST
COLLOCATION
VISUAL_ASSOCIATION
```

### 3.1 MORPHOLOGY / 构词记忆

适用于前缀、后缀、词根、派生词和复合词。

示例：

```text
unhappy = un + happy = 不 + 开心
```

要求：

- 必须基于真实可靠构词关系
- 不得强行编造词根
- 构词解释必须服务于理解和记忆
- **必须标明这是真实构词还是助记拆分**。`unhappy = un + happy` 是真实构词；
  `territory → terr + itory` 只是为了好记而切的块，`itory` 不是后缀。
  两者都能帮上忙，但不能都以"词根"的名义给出去——用户会把编出来的拆分当知识记住
  （`单词记忆DESIGN.md` Principle 10、§42）。拆不出真构词又需要切块时，
  说法是"按这几块记"，不是"这个词由这几部分构成"。

### 3.2 CONTEXT / 情景记忆

适用于日常高频词、动作、状态和事件词。

例如 `purchase`：

```text
站在商店收银台付款，把商品正式买下来。
```

要求场景具体、有动作、能形成画面，并与核心词义直接相关。

### 3.3 ORTHOGRAPHY / 词形记忆

用于拼写复杂词、双写、元音顺序、不规则拼写和高频错拼词。

例如：

```text
receive
```

重点可以是：

```text
rece + ive
```

并提示：

```text
容易错成 recieve
```

可与 Spelling Learning Engine 共享 Weak Segment 数据。

### 3.4 PRONUNCIATION / 发音记忆

适用于拼写与发音不直观、重音容易错、音节结构复杂或弱读明显的词。

输出可包含：

- 音节
- 重音
- 拼写与发音异常点

原则：不要默认使用中文谐音。只有当谐音足够自然、不影响正确发音且对记忆确实有帮助时才使用。

### 3.5 CONTRAST / 对比记忆

适用于近义词、形近词和常见混淆词。

例如：

```text
purchase vs buy
buy 更日常，purchase 更正式
```

最多列 3 个易混词，只保留真正容易混淆的项目。

### 3.6 COLLOCATION / 搭配记忆

有些词单独记忆意义有限，应通过固定搭配记忆。

例如：

```text
make a decision
```

往往比单独记 `decision = 决定` 更有实际价值。

常见搭配类型：

- Verb + Noun
- Adjective + Noun
- Preposition Pattern
- Fixed Expression
- Phrase Pattern

### 3.7 VISUAL_ASSOCIATION / 视觉联想

用于具体名词、动作、状态和难以用构词解释的词。

要求生成 1–2 句话，不写长故事。好的联想应夸张、具体、有动作、能瞬间成像。

避免抽象、复杂、需要额外记忆或与词义联系弱的内容。

---

## 4. 自适应记忆策略选择

生成内容前，模型必须先判断最适合的记忆方式：

```text
A. 构词记忆
B. 场景记忆
C. 词形记忆
D. 发音记忆
E. 对比记忆
F. 固定搭配记忆
G. 视觉联想
```

选择：

```text
Primary Strategy: 1
Secondary Strategy: 0–1
```

不要让每个词都生成全部类型，否则会产生大量低价值信息。

**打平时的兜底顺序**（`单词记忆DESIGN.md` §35，只在模型选不出、或两种策略同样适用时用）：

```text
场景 > 搭配 > 构词 > 对比 > 联想 > 词形 > 发音
```

这只是打破平局，不是默认排序——"这个词最值得记什么"永远优先于任何固定次序（§2.1）。
唯一的硬规则是**中文谐音永远垫底**：目标是建立"英语声音/拼写 → 概念"，
谐音建的是"英语 → 中文音 → 中文 → 含义"，多绕两道且容易带坏发音。

---

## 5. 推荐 Prompt

```text
你是一个语言学习记忆辅助生成器。

你的任务不是解释单词本身，而是为学习者生成“容易记住、容易回忆、容易区分”的记忆线索。

目标单词：
{{word}}

语言：
{{target_language}}

学习者母语：
{{native_language}}

学习者水平：
{{cefr_level}}

首先判断这个词最适合哪种记忆方式：

A. 构词记忆
B. 场景记忆
C. 词形记忆
D. 发音记忆
E. 对比记忆
F. 固定搭配记忆
G. 视觉联想

只选择最有效的 1–2 种作为主要策略。

请按以下结构生成：

1. 核心含义
- 用最短的话说明最核心、最常用的意思。
- 不要罗列大量次要释义。

2. 最佳记忆策略
- 输出 primary_memory_type。
- 如果需要，可输出 secondary_memory_type。

3. 记忆钩子
- 生成一句不超过 20 字的记忆提示。
- 必须能帮助用户在几秒内重新想起目标词。

4. 构词/词形提示
- 如果存在可靠词根、前缀、后缀或构词关系，则拆解。
- 如果没有，不要强行解释。
- 标出容易拼错的片段。

5. 发音提示
- 给出音节和重音。
- 只指出真正值得注意的发音点。
- 不要强行生成中文谐音。

6. 视觉/场景联想
- 只有当它确实有帮助时生成。
- 必须具体、短、可视化。
- 控制在 1–2 句话。

7. 易混词
- 最多 3 个。
- 每个只说明一个关键区别。
- 不要为了凑数量添加无关词。

8. 高频语境
- 给出 1 个自然、高频、简单的例句。
- 必须体现最典型用法。

9. 回忆测试
- 生成一个不直接暴露答案的问题。
- 用于后续 retrieval practice。

输出原则：
- 简洁优先
- 每条内容必须服务于记忆
- 禁止百科式解释
- 禁止编造词源
- 禁止强行谐音
- 禁止强行联想
- 优先真实构词与语义关联
- 其次考虑情景和视觉联想
- 记忆线索必须能在 3–5 秒内重新唤起目标词
```

---

## 6. 推荐输出结构

建议 App 使用结构化 JSON：

```json
{
  "word": "purchase",
  "core_meaning": "购买",
  "primary_memory_type": "CONTEXT",
  "secondary_memory_type": "CONTRAST",
  "memory_hook": "正式场合中的‘购买’",
  "morphology": null,
  "spelling": {
    "weak_segment": "pur-chase",
    "common_errors": []
  },
  "pronunciation": {
    "syllables": ["pur", "chase"],
    "stress": 1,
    "note": null
  },
  "visual_association": "在商店柜台付款，把商品正式买下来。",
  "confusions": [
    {
      "word": "buy",
      "difference": "buy 更日常，purchase 更正式"
    }
  ],
  "collocations": [
    "purchase equipment",
    "purchase a ticket"
  ],
  "example": "We need to purchase new equipment.",
  "recall_question": "正式表达‘购买设备’时可以用哪个词？"
}
```

---

## 7. UI 展示建议

不要把所有生成字段一次性展示。

推荐首屏只显示：

```text
单词
核心意思
记忆钩子
最佳记忆方式
```

例如：

```text
purchase
购买

💡 记忆：
正式场合里的 buy
```

用户点击“更多记忆提示”后，再展开：

- 构词
- 拼写
- 发音
- 场景
- 易混词
- 搭配

避免信息过载。

---

## 8. Progressive Hint

记忆辅助也可以使用逐步提示。

例如用户回忆 `purchase` 失败后：

### Hint 1

```text
表示“购买”
```

### Hint 2

```text
比 buy 更正式
```

### Hint 3

```text
pur_____
```

### Hint 4

```text
pur + chase
```

### Hint 5

```text
purchase
```

原则：

```text
Hint 越多，本次 Recall Credit 越低
```

这样可以直接与 Mastery Engine 集成。

---

## 9. Memory Hook 质量要求

一个好的 Memory Hook 必须满足：

### 简短

最好控制在 5–20 字。

### 单一

只表达一个主要记忆关系，不要把词根、发音、故事和例句全部塞进一句。

### 直接

看到提示以后应迅速指向目标词。

### 稳定

不能依赖过多额外知识。

### 不误导

不能为了好记牺牲：

- 正确词义
- 正确发音
- 正确词源
- 正确使用场景

---

## 10. 生成质量过滤

生成后应进行自检。

推荐规则：

```text
if memory_hook too long:
    regenerate

if morphology is speculative:
    remove

if visual association weakly related:
    remove

if confusion words are irrelevant:
    remove

if example is unnatural:
    regenerate

if pronunciation mnemonic may cause wrong pronunciation:
    remove
```

核心原则：

```text
宁缺毋滥
```

没有好的联想时，不生成，优于生成牵强内容。

---

## 11. 与拼写系统联动

Vocabulary Memory Assistance 应和 Spelling Learning Engine 共用部分数据，例如：

```text
Weak Segment
Common Error
Spelling Pattern
```

如果用户：

```text
receive → recieve
```

Memory Assistance 可以强调：

```text
词形提示：rece + ive
```

而 Spelling Engine 后续生成：

```text
rec__ve
```

形成闭环：

```text
记忆提示
↓
主动拼写
↓
错误分析
↓
重新生成针对性提示
```

---

## 12. 与 Mastery Model 联动

每个词可以维护：

```text
VocabularyMastery {
    meaningRecognition
    recall
    spelling
    listening
    context
    production
    retention
}
```

Memory Assistance 不应该永久展示相同内容，而应根据薄弱维度调整。

例如：

```text
meaningRecognition = HIGH
spelling = LOW
```

则重点显示拼写结构和 Weak Segment，而不是重复中文释义。

---

## 13. 用户个性化

系统可以根据历史错误逐渐生成个性化辅助记忆。

例如用户经常发生 `vowel order error`，则以后优先突出元音顺序。

如果用户更容易通过场景记忆，则提高 `CONTEXT` 和 `VISUAL_ASSOCIATION` 权重。

示例：

```json
{
  "preferred_memory_types": {
    "CONTEXT": 0.82,
    "MORPHOLOGY": 0.75,
    "VISUAL_ASSOCIATION": 0.63,
    "PRONUNCIATION": 0.42
  }
}
```

---

## 14. 质量评估指标

可记录用户使用某条 Memory Hint 后的：

- Recall Success Rate
- Recall Speed
- Hint Usage
- Delayed Recall Success
- Spelling Improvement
- User Feedback

例如：

```text
Memory Hook A
7-day recall = 82%

Memory Hook B
7-day recall = 54%
```

系统长期可以学习：

```text
什么类型的提示对这个用户最有效
```

---

## 15. 最终产品逻辑

推荐完整流程：

```text
输入单词
↓
分析词义、词形、发音、使用特征
↓
选择最佳记忆策略
↓
生成少量高价值记忆线索
↓
用户学习
↓
延迟 Recall
↓
记录错误
↓
更新 Mastery / Weakness
↓
重新选择更适合的记忆辅助
```

核心思想：

```text
不是让 AI 尽可能多地产生联想，
而是让 AI 找出“这个词最值得记住的那个点”。
```

最终系统定位应是：

```text
Adaptive Vocabulary Memory Engine
```

而不是：

```text
Vocabulary Explanation Generator
```

---

## 16. 与词汇数据模型的分工

`单词记忆DESIGN.md` §27～§50（学习材料层）和本文覆盖的是同一片地方：那边从"库里该有哪些表"往下看，
这边从"给用户看什么才记得住"往上看。重叠的部分按下面的口径裁决，两份文档不再各说各的。

### 16.1 记忆类型：以本文七类为准

`单词记忆DESIGN.md` §35 的十三类映射进来：

```text
semantic_scene / context        → CONTEXT
collocation                     → COLLOCATION
root / prefix_suffix / etymology→ MORPHOLOGY
spelling_pattern                → ORTHOGRAPHY
contrast                        → CONTRAST
visual / association / story    → VISUAL_ASSOCIATION
sound_mnemonic                  → 不单列，只在自然时写进 memory_hook（§3.4）
word_family                     → 不是记忆类型，是词条之间的关系，见 16.5
```

细分不进实现的理由是同一条：模型要在十三个标签里挑，界面上却分不出差别，
用户看到的仍然是"这个词最值得记的那一点"。多出来的九个标签只增加选择噪声。

`story` 并到 VISUAL_ASSOCIATION 而不是单列，还因为 §3.7 已经写死"1～2 句、不写长故事"——
真让模型写故事，它会写长，而长故事在 3～5 秒内唤不起目标词。

### 16.2 挂载层级：三类挂词条，四类挂词义

这是这次对齐里唯一改变现有行为的一条。`单词记忆DESIGN.md` §34 的 memory_aids 同时有
`lexeme_id` 和可选 `sense_id`，这个区分是对的：

```text
词条级（一个词只生成一次，所有词义共用）
  MORPHOLOGY      构词就是这个词形的构词
  ORTHOGRAPHY     易错段属于词形，和意思无关
  PRONUNCIATION   音节重音属于词形

词义级（每个词义各生成一份）
  CONTEXT         "跑"和"经营"的场景是两回事
  CONTRAST        跟谁容易混，取决于是哪个意思
  COLLOCATION     run a company 只属于"经营"那个词义
  VISUAL_ASSOCIATION
```

多义词各自成条之后（`单词记忆DESIGN.md` §5），不分层的话 `run` 的五个词义会各自生成一遍
一模一样的构词和拼写提示——重复、费 token，而且五份还可能互相矛盾。

### 16.3 拼写记忆不再单开一张表

§42 提议的 `spelling_aids` 不采纳。同一份数据已经有两个准确的家：

```text
词固有的拼写事实   vocabulary_details.chunksJson / trickyPart / misspellingsJson
这个人的错误历史   spelling_progress.weakSegments、spelling_attempts
```

第三张表只会让"谁是权威"变模糊。§42 真正有价值的是那条警告——
`territory → terr + itory` 是助记切块，不是形态学——已经吸收进 §3.1，
也和拼写引擎"本地猜出来的词块不能拿去讲'这里最容易错'"是同一条原则。

### 16.4 质量元数据：记来源，不记 AI 自评分

§46 要求生成型内容带 `source_type / generator / generator_version / quality_score / confidence / review_status`。

采纳前三个：模型名和提示词版本本来就在生成结果里，留下来才能在换模型或改提示词之后
批量重刷旧内容。

**不采纳 `quality_score` / `confidence`**：这两个数只能由生成它的模型自己打，
那是让模型给自己的作业打分，然后把 0.91 当成事实存进库。真实的质量信号在 §14——
用这条提示之后的 7 天回忆成功率，那是用户行为，不是模型自称。

`review_status` 简化成"用户有没有把它重新生成过"，这是我们真能观测到的唯一一种"审核"。

### 16.5 易混词、词族：暂不建关系表

§32/§33 要 `lexeme_relations` 和 `contrasts` 两张关系表。目前易混词依附在记忆提示里
（`confusions: [{word, difference}]`），是一次性文本而不是可查询的关系。

关系表真正的价值是双向可查——学 A 时提醒 B，学 B 时也提醒 A，以及"易混词专项训练"。
在有这个消费方之前不建表：一张只写不读的关系表，只会在词条被删除时留下悬空引用。

**但补一条约束**：`confusions` 里的词必须是真值得学的词条（库里已有，或够格加进库），
不能为了凑三个而列一个用户永远不会遇到的词。这是 §10「宁缺毋滥」在这一项上的具体化。

### 16.6 首屏与学习包：两个层次，不冲突

§44 的配额（1 个核心词义 / 1 个场景 / 1 条例句 / 2～4 个搭配 / 1～3 条短语 / 0～2 条记忆提示）
说的是"一个词的学习包里装多少"；本文 §7 说的是"这些东西里首屏先露哪几个"。

```text
学习包    §44 决定装什么进来
首屏      §7  决定先给用户看四行：词 / 核心意思 / 记忆钩子 / 最佳记忆方式
展开      构词、拼写、发音、场景、易混词、搭配
```

两条一起才完整：先按 §44 别装太多，再按 §7 别一次全铺开。
