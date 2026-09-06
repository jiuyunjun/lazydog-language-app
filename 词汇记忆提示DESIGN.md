---
doc: "词汇记忆提示DESIGN.md"
tier: "L4 专项设计"
status: "部分落地"
version: "4.0"
updated: "2026-09-07"
authority: "七类记忆策略、记忆提示的生成提示词、输出结构与质量过滤"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
---

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

### 2.2 中文助记链优先（D-069，替代 D-068 的用法兜底）

用户认可的方向是从熟悉的中文关键词回想英文，再通过动作/画面连接当前词义。
可用宽松谐音、夸张画面和字形联想，不要求讲究或文雅，但不能凭空造词源。

```text
谐音联想：ambition → 俺必胜 → 握拳喊「俺必胜」，这股一定要赢的雄心。（助记，非读音）
谐音联想：crab → 快来剥 → 端上一盘螃蟹，招呼大家「快来剥」。（助记，非读音）
字形联想：dessert 中间两个 s 像两份甜点，提醒自己别少写一个 s。
```

- 生成时主动优先尝试中文声音关键词，近似即可，不以逐音节准确音译为门槛。
  只用一个短、熟悉的中文抓手，必须讲清它与词义的联系；不能只贴一个谐音。
- 谐音不是实际读音，标「谐音联想」和短注「助记，非读音」。音标、整词朗读、
  pronunciation 字段仍保持真实发音，不能按中文关键词拆英文音节。
- 谐音不好时可用字形联想、针对真实错拼的口诀或可靠透明构词。辅助英文就地给中文含义。
- 普通场景和双语搭配不再作为助记兜底：`appear：看电影时有人出现在画面里，就是 appear`
  只在重说「出现」，应重写或留空。例句和搭配由词卡原有区域负责。
- 短提示留空仍可保存词卡；独立生成若留空，返回「暂时没找到合适的助记」并可重试。
  旧提示不自动改库，但共用面板隐藏能被本地规则识别的场景/搭配凑数内容，保留换法入口。
- 七类存储标签不增加，声音关键词与画面归 VISUAL_ASSOCIATION；展示文字可以标谐音联想。
  本地不强制谐音链再夹英文，允许以「谐音联想：」标注的中文线索。

自检必须回答：用了哪段声音/字形？中文抓手怎么连到词义？遮住英文，是否仍有有辨识度的链？
字符规则只能拦部分明显反例，不能证明谐音质量和长期记忆有效。此偏好来自用户授权，
不声称任意谐音都获研究验证；D-068 的全屏回想、换法和保存边界继续沿用。

### 2.3 研究依据与适用边界

- [Karpicke & Roediger, 2008, Science](https://doi.org/10.1126/science.1152408)：
  外语词汇实验中，成功回忆后继续检索有利于延迟回忆，继续重读未呈现同样收益。
  本产品据此增加遮挡后主动回想；这不是本 App 已验证的学习效果，也不替代间隔复习。
- [WordCraft, CHI 2026 会议论文](https://doi.org/10.1145/3772318.3790668)（此前阅读的是作者 arXiv 版本）：
  针对中文母语英语学习者，指出合适的声音关键词、连贯联想和画面构建本身有难度；
  自动给出成品会减少学习者参与。产品推论是中文解释降低理解成本，且保留主动回想。
  该研究使用多模态创作流程，不能把其效果直接套到本 App 的纯文字提示。

本次没有实现画布、AI 配图或用户自创提示编辑器；它们需要单独评估启动成本。
上述证据支持方向，不能证明每个 AI 提示有效。验收要逐词审阅，并另做真实延迟回忆评估。

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
仅描述购买场景不足以助记；需额外连接声音或字形，否则留空。
```

场景不能只重述词义。必须包含声音或字形抓手，并与核心词义直接连接（D-069）。

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

真实发音提示与谐音助记分开；谐音按 §2.2 主动尝试，放入 memory_hook，不放进 pronunciation。

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

**策略选择（D-069）**：优先尝试用户偏好的中文谐音/字形联想；有实际错拼时针对错误，
有透明构词时可拆。普通场景/搭配不兜底，没找到好助记允许留空。
旧的「场景 > 搭配 > …」顺序已被本条替代，不能继续指导生成。

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
- 用中文简述请求指定的当前词义；未指定时才取最常用义。
- 不要罗列大量次要释义。

2. 最佳记忆策略
- 输出 primary_memory_type。
- 如果需要，可输出 secondary_memory_type。

3. 记忆钩子
- 用 20～90 字、最多两句讲通一个中文锚点与目标英文的联系（D-068），推荐包含目标词。
- 优先中文谐音/字形联想，辅助英文就地译成中文；无可靠助记留空，禁止用普通场景兜底，详见 §2.2。

4. 构词/词形提示
- 如果存在可靠词根、前缀、后缀或构词关系，则拆解。
- 如果没有，不要强行解释。
- 标出容易拼错的片段。

5. 发音提示
- 给出音节和重音。
- 只指出真正值得注意的发音点。
- 真实读音字段不放谐音；谐音助记主动尝试，写入 memory_hook。

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
- 60 字内，以中文情境要求回想刚学的完整英文单词；不只考字母、词缀或中文释义。
- 不出现目标词或拆开的完整答案；离开原卡也能理解，不依赖「上面那个词」。

输出原则：
- 简洁优先
- 每条内容必须服务于记忆
- 禁止百科式解释
- 禁止编造词源
- 禁止无声音联系的硬凑谐音
- 禁止强行联想
- 优先中文声音关键词或字形联想，也可用可靠构词
- 禁止以普通场景或双语搭配充数
- 自检：不查词能看懂吗？是否只多记一个联系？能用哪个中文线索回想刚学的英文？
```

---

## 6. 推荐输出结构

建议 App 使用结构化 JSON：

```json
{
  "schemaVersion": 1,
  "word": "ambition",
  "core_meaning": "雄心",
  "primary_memory_type": "VISUAL_ASSOCIATION",
  "secondary_memory_type": null,
  "memory_hook": "谐音联想：ambition → 俺必胜 → 握拳喊俺必胜，这股一定要赢的雄心。（助记，非读音）",
  "morphology": null,
  "spelling": null,
  "pronunciation": null,
  "visual_association": null,
  "confusions": [],
  "collocations": [],
  "example": "His ambition is to win.",
  "recall_question": "握拳喊着一定要赢，这股雄心用刚学的英文怎么说？"
}
```

---

## 7. UI 展示建议

不要把所有生成字段一次性展示。

首屏「怎么记」只展示完整中文线索和操作，不再重复词卡已有释义，策略标签移到展开区。

示例：

```text
💡 怎么记
dessert 甜点中间有两个 s；把它们想成饭后还想吃的两份甜点，
提醒自己别漏一个 s。（人为联想）

[遮住，回想一下]  [更多记忆提示]  [换个记法]
```

点击回想后打开不透明全屏窗口，覆盖词卡标题、例句及全部提示，只显示中文问题：
「饭后还想来两份，刚学的『甜点』用哪个英文词？」。先尝试说出英文，再点「看答案」，
显示目标词、当前释义和原线索，之后「回到词卡」。返回键或「先回词卡」随时退出。
窗口支持滚动、系统栏安全区和大字体；每次重新打开都先隐藏答案。
生成中不进入回想。旧提示继续可读；旧问题缺失、过长、无中文或直接泄题时不展示回想入口。
只有短提示时继续提供独立生成入口，不额外自动请求 AI。不更新 FSRS、自评、拼写或学习记录。

用户点击“更多记忆提示”后，再展开：

- 构词
- 拼写
- 发音
- 场景
- 易混词
- 搭配

避免信息过载。

首次学词揭晓后和详情页使用同一面板：有提示可「换个记法」，没有则「生成记忆提示」。
批量旧提示同样进入避开内容，失败保留旧提示。新词尚未入库时只保留草稿，用户自评入库时
与词卡一起保存，不为了拿到 itemId 提前记录学习（D-062）。

---

## 8. Progressive Hint

本节为后续与 Mastery 联动的目标设计；D-068 的即时回想不实现提示计分，不更新掌握度。

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

人工审阅以下样本：unhappy（透明构词）、dessert（双写）、borrow（方向）、need（短词）、
ambition / crab（中文谐音）、appear（禁止场景凑数）、bizarre（不得硬造故事）。检查不查辅助词能理解、无假词源、
当前词义正确、只讲一个联系，回想问题能独立提示完整目标词。没有达到标准则换法。
本地字符校验只能拦确定问题，不能识别所有生硬故事、错误词源或未翻译的辅助词。

一个好的 Memory Hook 必须满足：

### 简短

推荐 20～90 字、最多两句。短到只剩释义或口号不算简洁；应完整讲通一个中文锚点与英文的联系。
本地校验仍允许 4～19 字的有用短提示，字符数包含英文；不强迫凑长度（D-062）。

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
if memory_hook missing or too long or generic advice or no Chinese explanation:
    reject

if memory_hook is labelled plain context/collocation or is detectable scene filler:
    reject

if memory_hook has neither an English form cue nor an explicitly labelled Chinese sound association:
    reject

if memory_hook repeats meaning or normalized previous hook:
    reject

if recall_question missing or longer than 60 characters or no Chinese guidance or reveals target word:
    reject

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

D-062 补充：批量短提示为空或不合格时只清掉提示，不丢掉其余合格词卡；独立生成则明确失败，
留给用户重试，不覆盖旧提示。英文抓手与重复校验仅拦可检测问题，不代表已经验证真实记忆效果。

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
首屏      §7  词 / 核心意思 / 完整记忆线索 / 策略 / 回想问题
展开      构词、拼写、发音、场景、易混词、搭配
```

两条一起才完整：先按 §44 别装太多，再按 §7 别一次全铺开。
