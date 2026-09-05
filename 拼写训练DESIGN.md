---
doc: "拼写训练DESIGN.md"
tier: "L4 专项设计"
status: "部分落地"
version: "1.0"
updated: "2026-09-04"
authority: "S0～S6 拼写状态机、提示阶梯、错误分类、薄弱片段与延迟回忆"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
---

# Spelling Learning System Design

> 实现状态（2026-09-01）：核心离线闭环已落地，见 `ROADMAP.md` M12。状态机、提示阶梯、八类错误与薄弱片段、多维进度、延迟保持判定、Room 持久化、备份、独立拼写练习入口和拼写能力档案都已实现；§20 的 AI/FSRS/手写/语音扩展仍为后续范围。
>
> 界面以设计稿 `懒狗放洋屁 MVP.dc.html` 59～64 屏为准，本文件负责引擎与数据规格。两处冲突时以设计稿为准（见 `DECISIONS.md` D-028）。

## 1. 目标

本系统用于语言学习 App 中的**渐进式拼写训练（Progressive Spelling Learning）**。

核心目标不是让用户在学习后立即完成一次完整默写，而是通过逐步降低提示强度、逐步延长回忆间隔，让用户建立稳定的长期拼写记忆。

核心原则：

1. 从识别逐步过渡到主动回忆。
2. 提示应逐渐减少，而不是一次性撤掉。
3. 答错后优先提供“最小必要提示”，避免立即暴露完整答案。
4. 学习进度不能只依赖单次正确率。
5. 需要记录用户具体的拼写薄弱点，而不是只记录“这个词会不会”。
6. 真正的掌握必须经过延迟回忆（Delayed Recall）验证。

---

## 2. 学习曲线

整体学习路径：

```text
Seen
  ↓
Recognition
  ↓
Partial Recall
  ↓
Chunk Recall
  ↓
Guided Recall
  ↓
Free Recall
  ↓
Retained
```

对应训练难度：

```text
高提示 + 短间隔
        ↓
低提示 + 中等间隔
        ↓
无提示 + 长间隔
```

两个需要动态控制的核心变量：

```text
Prompt Strength ↓
Recall Interval ↑
```

用户掌握程度越高：

- 提示越少
- 要求主动回忆的内容越多
- 测试间隔越长
- 题目语境越真实

---

## 3. 拼写学习阶段

### S0 — Exposure / 接触

目的：

建立单词声音、字形和结构之间的初步联系。

示例：

```text
environment

en + viron + ment

重点：
en[viron]ment
```

可展示：

- 单词
- 发音
- 音标
- 音节/词块拆分
- 易错部分高亮
- 示例句

此阶段不要求完整拼写。

---

### S1 — Recognition / 拼写识别

目的：

验证用户能否从多个形式中识别正确拼写。

示例：

```text
请选择正确拼写：

A. environment
B. enviroment
C. enviornment
D. environmant
```

该阶段主要训练：

- Orthographic Recognition
- 正确字形识别
- 常见错误形式辨识

注意：

**不能把 S1 的正确率视为“已经掌握拼写”。**

识别属于 Recognition，而不是 Recall。

---

### S2 — Partial Recall / 局部补全

目的：

让用户开始主动回忆单词中的局部字母。

示例：

```text
env_r_nment
```

或：

```text
environm__t
```

系统应优先挖掉用户历史上容易出错的位置。

例如用户经常把：

```text
environment
```

拼成：

```text
enviroment
```

则应该优先训练：

```text
enviro_ment
```

而不是随机删除字母。

---

### S3 — Chunk Recall / 分块拼写

目的：

让用户按照稳定词块重建单词，而不是机械记忆整个字符序列。

示例：

```text
en + _____ + ment
```

正确答案：

```text
viron
```

或：

```text
en | viron | ment
```

适用于：

- 长单词
- 多音节词
- 常见词根/词缀
- 容易发生顺序错误的单词

系统可以根据以下信息生成 Chunk：

- 音节
- morpheme
- prefix
- root
- suffix
- 高频字符组合

---

### S4 — Guided Recall / 提示拼写

目的：

用户基本需要完整拼写，但仍提供少量提示。

示例：

```text
环境

e__________
11 letters
```

或：

```text
🔊 /ɪnˈvaɪrənmənt/

First letter: e
```

可使用的提示：

- 首字母
- 单词长度
- 音节数量
- Word Chunk 数量
- 部分字母
- 示例句
- 中文/目标语言释义
- 发音

提示应动态减少。

---

### S5 — Free Recall / 完整默写

目的：

真正验证用户能否在没有字符级提示的情况下重建单词。

示例：

```text
🔊 [播放 environment]

请输入完整单词：
_____________
```

或者：

```text
环境

请输入英文：
_____________
```

也可以放在语境中：

```text
We need to protect the ________.
```

要求用户完整输入：

```text
environment
```

S5 应成为判断拼写掌握程度的主要依据之一。

---

### S6 — Retained / 长期保持

目的：

验证用户经过较长时间后仍然能够正确拼写。

例如：

```text
Day 0
env_r_nment

Day 1
e__________

Day 3
🔊 environment

Day 7
环境 → 完整拼写

Day 21
We need to protect the ________.
```

如果用户在多个时间点仍能无提示拼写正确，则进入 Retained。

---

## 4. 状态机

推荐状态：

```text
NEW
SEEN
ASSISTED
PARTIAL_RECALL
GUIDED_RECALL
FREE_RECALL
RETAINED
```

状态流转：

```text
NEW
 ↓
SEEN
 ↓
ASSISTED
 ↓
PARTIAL_RECALL
 ↓
GUIDED_RECALL
 ↓
FREE_RECALL
 ↓
RETAINED
```

允许降级：

```text
RETAINED
   ↓
FREE_RECALL
   ↓
GUIDED_RECALL
   ↓
PARTIAL_RECALL
```

如果用户长期遗忘，不应该永久维持 Mastered 状态。

---

## 5. 升级规则

不建议：

```text
答对一次 → 升级
```

建议基于：

- 正确次数
- 连续正确
- 是否使用提示
- 回答耗时
- 测试间隔
- 历史错误
- 是否跨日期成功回忆

示例规则：

### S1 → S2

条件：

```text
Recognition 连续正确 >= 2
```

---

### S2 → S3

条件：

```text
不同缺失位置正确 >= 2
且
最近错误率 < 30%
```

---

### S3 → S4

条件：

```text
Chunk Recall 正确 >= 2
且
无直接字符提示情况下至少成功 1 次
```

---

### S4 → S5

条件：

```text
Guided Recall 成功 >= 2
且
提示强度 <= LOW
```

---

### S5 → S6

条件建议：

```text
Free Recall 至少成功 3 次
且
成功发生在 >= 2 个不同日期
且
至少有一次 recall interval >= 7 days
```

---

## 6. 降级规则

如果用户出现遗忘，应适度恢复提示。

示例：

```text
FREE_RECALL 连续错误 2 次
→ GUIDED_RECALL
```

```text
GUIDED_RECALL 连续错误 2 次
→ PARTIAL_RECALL
```

但不要因为一次 typo 就立即大幅降级。

需要区分：

```text
Memory Failure
vs
Typing Mistake
```

---

## 7. 提示梯度 Hint Ladder

用户答错后，不应该立即显示完整答案，而应该按“最小必要提示”逐步增加帮助。

> **2026-09-04 改版。** 原来这一节是「答错后弹出一句描述」（"中间部分是 ...ron..."）。
> 实机用下来有三个问题：
>
> 1. 提示是**第三人称的旁白**，用户还得自己在脑子里把它拼回单词里；
> 2. 每要一次提示**弹一次窗**，要三次提示就弹三次，还得点「知道了」关掉；
> 3. **还没交答案时无话可说**，只能讲"一共 11 个字母"——而动态难度（`持续学习DESIGN.md` §11）
>    在用户吃力时正好会让提示从第 1 级起步，于是他一进来看到的第一句就是这句废话。
>
> 改成**题面上的骨架逐级显形**：他看到的东西和他要写的东西长得一模一样。

### 7.1 提示作用在题面本身

题面上本来就有一排字母格（设计稿 60、63 屏）。**提示不另画一块区域，它就是让那排格子多亮几个。**

一屏上出现两种挖法（题型自己的挖空 + 提示的骨架）比没有提示更让人糊涂——
用户会分不清该按哪一份去填。所以提示给出的是"显形位置"，和题型自己的挖空**取并集**：
局部补全本来就露着大半个词，提示只在它基础上再点亮几处，永远不会盖掉题型已经给的东西。

字母数本身就是信息，格子一直都在，**不单收一级的费**。
完整默写是例外：没要提示时不显形，一旦要了第一级，格子才出现。

### 7.2 五级显形

以 `environment`（词块 `en · viron · ment`）为例：

| 级 | 名字 | 格子（以空白题面为例） | 另给 |
|---|---|---|---|
| 0 | 无提示 | `_ _ _ _ _ _ _ _ _ _ _` | — |
| 1 | 结构 | `_ _  _ _ _ _ _  _ _ _ _`（块之间拉开距离） | 「先按这几块想」 |
| 2 | 首字母 | `e _  v _ _ _ _  m _ _ _` | — |
| 3 | 读音 | 同上 | 音标 **+ 朗读按钮** |
| 4 | 薄弱段 | `e _  v i r o n  m _ _ _` | 「这一段是你最常写错的地方」 |
| 5 | 答案 | `e n  v i r o n  m e n t` | — |

第 3 级给音标的同时必须给一个朗读按钮：这一级买的是"这个词怎么念"，
只摆一串 IPA 符号，对读不出音标的人等于没给。

规则：

- **每一级给的都不比上一级少**，否则花掉的分换不到东西。
- **只有第 5 级出现完整拼写**。逐级要提示不能变成"点四下看答案"。
- 第 3 级给的是**另一个维度**的线索（音形对应），不是更多字母；没有音标的词直接跳过它的效果，
  骨架不变。
- 第 4 级的"薄弱段"优先按**这次错在哪**定位，没有新错答案才退回历史累计最多的那一段。
- 四选一（S1）和接触卡（S0）没有提示阶梯：答案就在选项里，再给提示等于直接指出来。
- 「再要一点提示」按钮上写明**下一级会给什么**（「提示 · 首字母」），并且单行不换行：
  代价是掌握度，总得让人在花掉之前知道买的是什么；而两个按钮并排本来就窄，
  换行会把字挤得看不全。

### 7.3 「错在哪一类」不占提示等级

答错时另给一句判定反馈：

```text
双写不对：该双写的地方没双，或者不该双的双了。
```

它**不是提示**，是判定结果，所以不花分。知道"该双写"往往就够自己找出来了；
为这句话收一级提示的费，等于逼人用掌握度买一个本来就该告诉他的东西。

手滑（编辑距离 1 且用时 < 2 秒）走的是另一句话，也不扣分、不降级，见 §5。

### 7.4 Mastery Credit

```text
用户每获得一级 Hint，本次答案的 Mastery Credit 应降低。
```

| Hint Level | Mastery Credit |
|---|---:|
| 0 | 1.0 |
| 1 | 0.8 |
| 2 | 0.6 |
| 3 | 0.4 |
| 4 | 0.2 |
| 5 | 0 |

**等距不变**（改版时重新审过一次）。有人会觉得"看几个字母"和"看整块"不该扣一样多，
但 Credit 衡量的是**这次提取有多独立**，不是泄露了多少字母：多要一级就是少独立一档，
这个口径简单、可解释、也和界面上"一级一格"的观感一致。
要改成非等距，得先有真实数据说明某一级明显被高估或低估了，凭手感调只会让分数更难解释。

---

## 8. 拼写错误分类

系统不应该只记录：

```text
correct = false
```

应该识别具体错误类型。

---

### 8.1 Omission

漏字。

```text
environment
→ enviroment
```

记录：

```text
type = OMISSION
missing = "n"
position = 7
```

---

### 8.2 Insertion

多字。

```text
necessary
→ neccessary
```

---

### 8.3 Substitution

字母替换。

```text
environment
→ environmant
```

---

### 8.4 Transposition

顺序交换。

```text
receive
→ recieve
```

---

### 8.5 Doubling Error

双写错误。

```text
necessary
→ neccessary
```

或：

```text
accommodation
→ accomodation
```

---

### 8.6 Vowel Order Error

元音顺序错误。

```text
receive
→ recieve
```

---

### 8.7 Phonetic Spelling

根据发音错误推测拼写。

例如：

```text
definitely
→ definately
```

---

### 8.8 Morphology Error

词根、词缀、词形变化错误。

例如：

```text
happiness
→ happyness
```

---

## 9. Weak Segment / 薄弱字符片段

建议系统不仅追踪 Word Level Mastery，还追踪：

```text
Weak Segment
```

例如：

```text
environment
```

用户多次在：

```text
viron
```

区域犯错。

记录：

```json
{
  "word": "environment",
  "weak_segments": [
    {
      "segment": "viron",
      "start": 2,
      "end": 7,
      "error_count": 4
    }
  ]
}
```

之后生成题目时优先针对该片段。

例如：

```text
en_____ment
```

而不是：

```text
environm___
```

---

## 10. 用户拼写画像

除了单词 Mastery，建议维护用户级别的 Spelling Profile。

例如：

```json
{
  "vowel_order_error_rate": 0.18,
  "doubling_error_rate": 0.25,
  "omission_error_rate": 0.12,
  "suffix_error_rate": 0.21,
  "phonetic_spelling_error_rate": 0.08
}
```

如果用户经常发生：

```text
receive → recieve
believe → beleive
piece → peice
```

系统应该识别：

```text
VOWEL_ORDER
```

是用户的高频弱点。

随后可以安排 Pattern Training，而不仅仅复习单词本身。

---

## 11. Spelling Mastery Vector

不要只存：

```text
mastery = 0.8
```

推荐维护多个维度：

```text
SpellingMastery {
    recognition
    partialRecall
    chunkRecall
    phonemeGraphemeMapping
    freeRecall
    retention
}
```

示例：

```json
{
  "word": "environment",
  "recognition": 0.98,
  "partial_recall": 0.91,
  "chunk_recall": 0.82,
  "phoneme_grapheme": 0.72,
  "free_recall": 0.56,
  "retention": 0.41
}
```

系统可以据此自动决定下一题。

例如：

```text
recognition 高
freeRecall 低

→ 不再出选择题
→ 增加完整拼写
```

---

## 12. 题型选择算法

伪代码：

```text
if recognition < 0.6:
    use Recognition

else if partialRecall < 0.7:
    use Partial Completion

else if chunkRecall < 0.7:
    use Chunk Recall

else if freeRecall < 0.75:
    use Guided Recall

else:
    use Free Recall
```

如果：

```text
retention < threshold
```

则优先进行：

```text
Delayed Free Recall
```

---

## 13. Delayed Recall

刚学习后立即答对不代表长期记忆。

必须使用延迟回忆。

建议基础间隔：

```text
10 min
1 day
3 days
7 days
14 days
30 days
60 days
```

实际间隔应根据用户表现动态调整。

### 答对

增加下一次复习间隔。

例如：

```text
1d → 3d → 7d → 14d → 30d
```

### 答错

缩短间隔：

```text
14d → 3d
```

并适当恢复提示：

```text
Free Recall
→ Guided Recall
```

---

## 14. 复习优先级

每个单词计算：

```text
Review Priority
```

参考因素：

```text
Priority =
    Forgetting Risk
  + Weak Segment Score
  + Error Frequency
  + Importance
  + User Usage Frequency
```

优先复习：

1. 即将遗忘的词
2. 高频错误词
3. 高频实用词
4. 用户近期学习内容
5. 用户特定错误模式涉及的词

---

## 15. 拼写成绩计算

一次答题不能简单：

```text
正确 = 1
错误 = 0
```

建议考虑：

```text
score =
    correctness
  × hintPenalty
  × responseTimeFactor
  × recallDifficulty
  × intervalFactor
```

例如：

用户在 14 天后：

- 无提示
- 3 秒内
- 完整拼写正确

应获得很高 Mastery Credit。

而：

- 刚刚看过答案
- 给了大部分字符
- 最终填写正确

Mastery Credit 应较低。

---

## 16. 推荐的数据结构

### WordSpellingProgress

```json
{
  "word_id": "word_001",
  "stage": "GUIDED_RECALL",

  "recognition_score": 0.95,
  "partial_recall_score": 0.82,
  "chunk_recall_score": 0.75,
  "phoneme_grapheme_score": 0.68,
  "free_recall_score": 0.53,
  "retention_score": 0.40,

  "last_review_at": "2026-09-01T10:00:00Z",
  "next_review_at": "2026-09-04T10:00:00Z",

  "success_streak": 2,
  "failure_streak": 0,

  "review_count": 8,
  "free_recall_success_count": 2,

  "current_interval_days": 3
}
```

---

### SpellingAttempt

```json
{
  "attempt_id": "attempt_123",
  "word_id": "word_001",

  "question_type": "FREE_RECALL",

  "expected": "environment",
  "answer": "enviroment",

  "correct": false,

  "hint_level": 0,

  "response_time_ms": 4800,

  "error_types": [
    "OMISSION"
  ],

  "weak_segment": "viron",

  "created_at": "2026-09-01T10:00:00Z"
}
```

---

### UserSpellingProfile

```json
{
  "user_id": "user_001",

  "omission_rate": 0.12,
  "insertion_rate": 0.05,
  "substitution_rate": 0.09,
  "transposition_rate": 0.08,

  "doubling_error_rate": 0.25,
  "vowel_order_error_rate": 0.18,
  "suffix_error_rate": 0.21,

  "avg_free_recall_time_ms": 5200
}
```

---

## 17. 推荐 UX

完整流程示例：

### 第一次遇到 environment

```text
🔊 environment

environment

en + viron + ment

环境
```

---

### 第一次复习

```text
哪个拼写正确？

environment
enviroment
enviornment
environmant
```

---

### 第二次

```text
环境

env_r_nment
```

---

### 第三次

```text
🔊 environment

en + _____ + ment
```

---

### 第四次

```text
环境

e__________
```

---

### 第五次

```text
🔊 environment

请输入：
____________
```

---

### 第六次

```text
We need to protect the ________.

🔊 [整句音频]
```

整个学习过程实现：

```text
认识
→ 局部提取
→ 结构提取
→ 完整提取
→ 语境应用
→ 延迟保持
```

---

## 18. Mastered 的定义

不要把：

```text
答对一次
```

定义为 Mastered。

推荐：

```text
Mastered =
    Free Recall 成功 >= 3 次
    AND 成功跨 >= 2 个不同日期
    AND 至少一次 interval >= 7 days
    AND 最近 Free Recall 成功
```

更严格模式可以要求：

```text
interval >= 14 days
```

---

## 19. 核心产品原则

最终产品应该避免：

```text
不断让用户做已经会的选择题
```

而应该：

```text
用户越会
→ 提示越少
→ 主动回忆越多
→ 间隔越长
→ 场景越真实
```

因此拼写系统本质上不是一个：

```text
Spell Checker
```

而应该是：

```text
Adaptive Spelling Learning Engine
```

其核心能力是：

```text
检测用户当前能做到什么
        ↓
判断哪里最薄弱
        ↓
只提供必要提示
        ↓
安排下一次适当难度的回忆
        ↓
通过延迟测试验证长期记忆
```

---

## 20. 后续可扩展方向

后续可以进一步增加：

- AI 自动生成易混淆错误选项
- 基于用户历史错误生成 Distractors
- 音素到字母映射训练
- Dictation Mode
- Sentence Dictation
- Phrase Dictation
- 词根/词缀专项训练
- 相似拼写词对比
- Keyboard Typo Detection
- 手写拼写
- Speech-to-Spelling
- CEFR 分级
- SRS / FSRS 调度
- 用户个人 Forgetting Curve
- AI 自动判断“不会拼”还是“手滑”
- 拼写能力 Dashboard

最终可将：

```text
Vocabulary Learning
Spelling Learning
Listening
Context Understanding
Production
```

统一到同一个多维 Mastery Model 中。
