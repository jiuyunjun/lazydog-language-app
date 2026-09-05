---
doc: "持续学习DESIGN.md"
tier: "L4 专项设计"
status: "部分落地"
version: "1.1"
updated: "2026-09-06"
authority: "持续学习的产品策略：进步证据、动态难度、间隔重复、中断宽容"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
---

# 英语学习持续性专项设计

## 1. 文档目的

本设计专门解决一个问题：

> **如何让用户在英语学习 App 中持续学习，并且持续学习的动力主要来自真实能力增长，而不是单纯依赖签到、积分或焦虑驱动。**

本设计不把“留存”理解为单纯让用户每天打开 App，而是同时优化两类结果：

1. **Behavior Retention**：用户愿意持续回来学习。
2. **Learning Retention**：用户真正记住、会用，并能感知自己正在变强。

核心原则：

> **低门槛开始 → 恰好有点难 → 主动回忆 → 获得反馈 → 感知进步 → 留下下一次学习理由。**

---

## 2. 产品核心目标

### 2.1 North Star

不以以下指标作为唯一核心目标：

- 学习时长
- XP
- 连续签到天数
- 完成课程数量

更推荐的核心指标是：

> **Weekly Proven Progress（每周可证明的能力增长）**

可以通过以下信号综合计算：

- 新掌握知识点数
- 到期知识保留率
- 无提示正确率
- 听力识别提升
- 拼写正确率提升
- 真实语境输出成功率
- 旧知识在新语境中的迁移成功率

---

## 3. 两个核心循环

整个学习系统必须明确区分两个闭环。

### 3.1 Habit Loop

负责让用户愿意回来。

```text
触发
  ↓
打开 App
  ↓
立即看到今天唯一推荐任务
  ↓
2~5 分钟即可完成最低目标
  ↓
获得即时完成反馈
  ↓
看到能力增长证据
  ↓
知道下一次回来会学什么
```

### 3.2 Learning Loop

负责真正学习。

```text
输入
  ↓
理解
  ↓
主动回忆
  ↓
犯错
  ↓
纠正
  ↓
再次提取
  ↓
间隔复习
  ↓
迁移到新语境
  ↓
真实输出
```

产品不能只优化 Habit Loop。

---

## 4. 首页设计

首页的首要任务不是展示功能，而是：

> **消除“今天学什么”的决策成本。**

### 4.1 推荐结构

```text
Good evening

今天的学习
约 7 分钟

复习       2 min
新词       2 min
听力       1 min
拼写       1 min
口语       1 min

[ 继续学习 ]

今天最低目标：2 分钟
```

首页不应该把以下功能全部并列成一级入口：

- 单词
- 语法
- 阅读
- 听力
- 写作
- 口语
- AI
- 复习
- 课程

这些可以存在，但应该作为次级入口。

---

## 5. Daily Learning Queue

系统每天自动生成一个学习队列。

### 5.1 队列组成

建议：

```text
50% 到期复习
20% 最近错误
15% 新知识
10% 当前课程目标
5% 用户兴趣内容
```

比例可以按用户状态动态调整。

### 5.2 队列优先级

```text
P0 即将遗忘且价值高
P1 最近连续答错
P2 当前学习路径关键知识
P3 新知识
P4 兴趣探索内容
```

### 5.3 生成原则

每天学习量不固定，应该根据：

- 到期复习量
- 当前疲劳
- 最近完成率
- 最近正确率
- 用户选择的每日目标
- 最近学习中断情况

动态控制。

---

## 6. Daily Minimum

持续学习系统必须提供极低门槛。

推荐：

```text
最低目标：2 分钟
标准目标：8~12 分钟
深度学习：20+ 分钟
```

### 6.1 最低目标内容

例如：

```text
复习 5 个知识点
完成 1 个听力
完成 1 次主动输出
```

用户完成后立即获得“今日完成”。

然后再提供：

```text
你还有 3 个快要忘记的单词

[ 再学 3 分钟 ]
[ 今天到这里 ]
```

禁止通过 UI 让“今天到这里”产生罪恶感。

---

## 7. Streak 机制

Streak 可以存在，但不能成为唯一动力。

### 7.1 推荐设计

同时展示：

```text
学习旅程：128 天
最近 30 天活跃：27 / 30
当前连续学习：12 天
```

相比只展示：

```text
🔥 128 天
```

更抗挫败。

### 7.2 Grace Mechanism

必须支持：

- Streak Freeze
- 补签券
- 每周允许一次休息
- 旅行模式
- 生病模式
- 周完成率

核心原则：

> 一次中断不应摧毁长期积累。

---

## 8. 知识学习阶段

每一个知识点都应尽量经历：

```text
Recognize
   ↓
Recall
   ↓
Produce
   ↓
Apply
```

### 8.1 单词示例

#### L1 识别

```text
apple

A 苹果
B 香蕉
C 梨
```

#### L2 轻回忆

```text
🍎

a _ p _ e
```

#### L3 完整拼写

```text
🍎

________
```

#### L4 句子填空

```text
I ate an ______ after lunch.
```

#### L5 主动表达

```text
请用 apple 描述你的早餐。
```

#### L6 真实语境

```text
AI:
What did you have for breakfast?

🎤
```

---

## 9. Retrieval Practice

学习过程中必须优先使用主动提取，而不是重复阅读。

### 9.1 错误模式

```text
apple = 苹果

下一页
apple = 苹果

下一页
apple = 苹果
```

### 9.2 推荐模式

```text
看到含义
↓
隐藏答案
↓
主动回忆
↓
检查
↓
纠错
```

支持多种 retrieval：

- 选择
- 首字母提示
- 缺字拼写
- 完整拼写
- 听音拼写
- 中译英
- 英译义
- 句中生成
- 口语生成

---

## 10. Spaced Repetition

推荐使用 FSRS 思路管理长期记忆，而不是固定：

```text
1 天 → 3 天 → 7 天 → 14 天
```

### 10.1 Memory State

```ts
type MemoryState = {
  difficulty: number
  stability: number
  retrievability: number

  lastReviewedAt: Date
  nextReviewAt: Date

  reviewCount: number
  lapseCount: number

  lastGrade: ReviewGrade
}
```

### 10.2 Review Grade

```ts
enum ReviewGrade {
  Again,
  Hard,
  Good,
  Easy
}
```

### 10.3 可纳入复习调度的知识类型

不仅限于 vocabulary：

- Word
- Phrase
- Grammar
- Sentence Pattern
- Pronunciation Pattern
- Listening Chunk
- Collocation

统一抽象：

```ts
type LearnableItem = {
  id: string
  type: LearnableType
  contentRef: string
  memoryState: MemoryState
}
```

---

## 11. Adaptive Difficulty

目标不是 100% 正确率。

建议系统长期保持：

```text
目标成功率：75% ~ 85%
```

### 11.1 太容易

如果最近：

```text
correctRate > 90%
```

系统可：

- 减少提示
- 从选择题切换成生成题
- 增加拼写
- 增加听写
- 增加口语
- 增加新语境
- 增加相近词干扰

### 11.2 太难

如果：

```text
correctRate < 65%
```

系统可：

- 增加图片
- 提供首字母
- 提供词根
- 提供母语解释
- 分解任务
- 降低句子复杂度
- 增加示范
- 延长复习间隔增长速度

---

## 12. Hint Ladder

提示不能只有：

```text
显示答案
```

应设计成逐级辅助。

### 12.1 拼写提示

```text
Level 0
________

Level 1
a____

Level 2
a_p__

Level 3
/aepəl/

Level 4
🍎 + /aepəl/

Level 5
apple
```

### 12.2 语法提示

```text
Level 0
I ___ to Tokyo yesterday.

Level 1
动词：go

Level 2
过去时

Level 3
went

Level 4
完整答案
I went to Tokyo yesterday.
```

系统记录用户使用到第几层提示。

---

## 13. Mastery 模型

不要只有：

```text
已学 / 未学
```

建议：

```ts
enum MasteryLevel {
  Seen,
  Recognized,
  Recalled,
  Produced,
  Applied,
  Stable
}
```

### 13.1 示例

用户能选择正确答案：

```text
Recognized
```

能无提示拼写：

```text
Recalled
```

能自己造句：

```text
Produced
```

能在新场景自然使用：

```text
Applied
```

长期复习后保持：

```text
Stable
```

---

## 14. Progress Evidence

持续学习最关键的设计之一。

系统必须定期告诉用户：

> **你以前不会，现在已经会了。**

### 14.1 日反馈

```text
今天掌握：

+3 新词
+2 短语

你重新记住了：
supposed to

拼写准确率：
76% → 82%
```

### 14.2 周反馈

```text
本周进步

听力
62% → 69%

拼写
74% → 81%

主动词汇
412 → 438

最明显进步：
Connected Speech
```

### 14.3 长期证明

例如：

```text
30 天前：

I ___ to Japan last year.
❌ go

今天：

I ___ to Japan last year.
✅ went
```

文案：

> 30 天前你还会在这里出错。

---

## 15. Proof of Progress Challenge

建议至少每周触发一次。

目的：

> 让用户体验“以前听不懂，现在听懂了”。

### 示例

系统挑选过去 2~4 周学习的：

```text
actually
supposed to
end up
probably
```

组合成：

```text
I was actually supposed to go yesterday,
but I ended up staying home.
```

用户完成：

- 听力理解
- 关键词识别
- 复述

然后展示：

```text
你听懂了 4 周前学习的 4 个表达。
```

---

## 16. Ability Map

不要只显示课程进度。

建议显示能力地图：

```text
Vocabulary       B1
Listening        A2+
Speaking         A2
Grammar          B1
Writing          A2+
Reading          B1+
```

进一步：

```text
Listening

Daily Conversation     82%
Numbers & Dates        95%
Connected Speech       43%
Fast Speech            31%
Accent Robustness      58%
```

核心目的：

> 用户能够看到“哪里变强了、哪里仍然弱”。

---

## 17. AI Tutor 的职责

AI Tutor 不能只是：

```text
What would you like to talk about?
```

这会把任务规划压力重新交给用户。

AI 应该负责：

1. 使用最近学过的知识。
2. 创建真实任务。
3. 控制难度。
4. 引导主动输出。
5. 捕获错误。
6. 把错误加入复习系统。

---

## 18. AI Scenario

### 18.1 输入

系统传入：

```json
{
  "targetWords": [
    "reservation",
    "available",
    "for two"
  ],
  "grammar": [
    "Is there ...?"
  ],
  "level": "A2",
  "scenario": "restaurant"
}
```

### 18.2 对话

```text
AI:
Good evening, Sakura Restaurant.
How can I help you?

User:
I'd like a reservation.

AI:
Sure. For how many people?
```

### 18.3 结束反馈

```text
成功使用：

✓ reservation
✓ for two

需要改进：

Is there available table?
↓
Is there a table available?
```

并自动生成：

```text
ErrorReviewItem
```

加入后续复习。

---

## 19. Personal Relevance

系统应尽可能利用用户兴趣生成学习素材。

### 19.1 Onboarding

询问：

```text
为什么学习英语？

□ 工作
□ 技术
□ 旅行
□ 游戏
□ 留学
□ 考试
□ 影视
□ 日常交流
```

再问：

```text
兴趣

□ AI
□ Programming
□ Cybersecurity
□ Gaming
□ Travel
□ Motorcycles
□ Anime
□ Business
```

### 19.2 例句生成

避免长期使用：

```text
Tom bought an apple.
```

针对技术用户：

```text
The server went down during deployment.
```

针对摩托用户：

```text
The bike ran out of fuel.
```

针对旅行：

```text
We ended up taking a different train.
```

---

## 20. Recommendation vs Freedom

完全自由会产生决策负担。

完全强制会降低 autonomy。

推荐：

```text
80% 系统推荐
20% 用户自由
```

首页：

```text
推荐
[ 继续今日学习 ]

自由练习
🎧 听力
🗣 口语
📖 阅读
🧠 复习
```

---

## 21. Session Flow

一次标准学习 Session：

```text
Warm-up
↓
Due Review
↓
Recent Mistakes
↓
New Knowledge
↓
Active Recall
↓
Application
↓
Progress Evidence
↓
Optional Extension
```

### 21.1 8 分钟示例

```text
0:00~1:00
旧词复习

1:00~3:00
新词 × 3

3:00~4:30
拼写

4:30~6:00
听力

6:00~7:00
AI 场景

7:00~8:00
进步总结
```

---

## 22. End-of-Session

结束页非常重要。

不要只有：

```text
+80 XP
```

推荐：

```text
今日完成

你今天真正掌握了：

✓ 3 个新词
✓ 1 个句型
✓ 纠正 2 个错误

你已经连续 5 次正确使用：

be supposed to

Listening
61% → 63%

明天：
继续练习 connected speech
```

再提供：

```text
[ 再学 3 分钟 ]
[ 完成 ]
```

---

## 23. Error System

错误是最重要的学习数据之一。

### 23.1 ErrorRecord

```ts
type ErrorRecord = {
  id: string

  itemId?: string

  input: string
  expected: string

  errorType: ErrorType

  occurredAt: Date

  context: {
    exerciseType: string
    sentence?: string
    audioId?: string
  }

  resolved: boolean
}
```

### 23.2 ErrorType

```ts
enum ErrorType {
  Spelling,
  Grammar,
  Vocabulary,
  WordOrder,
  Listening,
  Pronunciation,
  Meaning,
  Collocation
}
```

### 23.3 错误必须转化成学习任务

```text
错误
↓
归类
↓
生成最小知识点
↓
进入复习队列
↓
再次测试
↓
跨语境测试
↓
标记已解决
```

---

## 24. 用户状态模型

推荐维护：

```ts
type LearnerState = {
  estimatedCEFR: CEFRLevel

  vocabularyLevel: number
  grammarLevel: number
  listeningLevel: number
  speakingLevel: number
  readingLevel: number
  writingLevel: number

  dailyTargetMinutes: number

  recentAccuracy: number
  recentCompletionRate: number

  fatigueScore: number

  activeStreak: number
  activeDays30: number

  interestTags: string[]
}
```

---

## 25. Fatigue Detection

长期留存不能忽略疲劳。

系统可以通过：

- Session 中退出次数
- 连续答错
- 反应时间突然增加
- Skip 增多
- Hint 使用率增加
- 最近 7 天学习时间下降

估计疲劳。

### 25.1 疲劳高

减少：

- 新知识
- 长句
- 写作

增加：

- 简短复习
- 图片题
- 熟悉内容
- 成就回顾

例如：

```text
今天状态可能有点累。

我们只复习 3 分钟。
```

---

## 26. Recovery Flow

用户连续几天没学习时，不要：

```text
你已经落后 74 个复习。
```

这是灾难。

推荐：

```text
欢迎回来。

不用补完以前所有任务。

今天先重新热身 3 分钟。
```

后台重新计算 FSRS 调度。

### 26.1 Recovery Session

```text
3 个高价值旧知识
2 个熟悉知识
1 个轻量听力
```

完成后：

```text
你已经重新进入学习节奏。
```

---

## 27. 游戏化

游戏化只能辅助学习。

推荐：

- Streak
- Achievement
- Weekly Goal
- Challenge
- Level
- Mastery Badge

不推荐把核心循环变成：

```text
广告
↓
金币
↓
抽箱
↓
皮肤
```

### 27.1 Reward 原则

奖励优先级：

```text
能力反馈
>
解锁新学习内容
>
挑战
>
徽章
>
虚拟货币
```

---

## 28. Achievement

Achievement 应对应真实能力。

### 好

```text
First 100 Words Mastered
```

```text
10 Listening Challenges Passed
```

```text
7 Days Without Spelling Hint
```

### 差

```text
Click 100 Buttons
```

---

## 29. Notification Strategy

通知的目的：

> 帮用户开始学习。

而不是制造焦虑。

### 推荐

```text
你今天有 5 个单词即将进入遗忘区，
复习大约需要 2 分钟。
```

或者：

```text
昨天的 “supposed to”
今天已经到最佳复习时间。
```

### 不推荐

```text
🔥 你要断签了！！！
```

---

## 30. 指标体系

### 30.1 留存

```text
D1
D7
D30
D90
```

但不能单独看。

### 30.2 学习质量

重点：

```text
Retrieval Success Rate
Hint-Free Accuracy
Retention Rate
Transfer Success Rate
Mistake Recurrence Rate
```

### 30.3 推荐核心指标

#### Weekly Proven Progress

```text
WPP =
mastery gains
+ retention gains
+ transfer gains
```

#### Learning Survival Rate

```text
30 天前掌握的知识
现在仍然能正确提取的比例
```

#### Recovery Rate

```text
中断 3 天后的用户
7 天内恢复学习的比例
```

---

## 31. 不应该优化的指标

警惕：

```text
Session Length
```

学习越长不一定越好。

警惕：

```text
XP / Day
```

可能出现刷题。

警惕：

```text
Lessons Completed
```

完成 ≠ 掌握。

警惕：

```text
Daily Streak
```

可能只完成最低动作保 streak。

---

## 32. 推荐事件埋点

```text
session_started
session_completed

daily_goal_completed

exercise_answered
exercise_wrong
hint_used

item_seen
item_recalled
item_produced
item_applied

review_due
review_completed

mastery_upgraded

proof_of_progress_started
proof_of_progress_completed

ai_scenario_started
ai_scenario_completed

streak_saved
learning_recovered
```

---

## 33. 实验优先级

### Experiment 1

首页：

```text
功能菜单
```

vs

```text
单一 Continue 按钮
```

测：

- start rate
- session completion

---

### Experiment 2

结束页：

```text
+ XP
```

vs

```text
能力增长证据
```

测：

- next-day return
- 7-day retention

---

### Experiment 3

固定难度

vs

Adaptive Difficulty

测：

- completion
- frustration
- retention
- delayed test

---

### Experiment 4

普通 streak

vs

streak + grace

测：

- 30-day survival
- post-break recovery

---

## 34. MVP 优先级

### P0

必须先做：

1. Daily Learning Queue
2. Retrieval Practice
3. FSRS
4. Adaptive Difficulty
5. Error Tracking
6. Progress Evidence
7. Daily Minimum
8. Session Completion

### P1

之后：

1. AI Scenario
2. Interest Personalization
3. Ability Map
4. Proof of Progress
5. Streak + Grace
6. Recovery Flow

### P2

后续：

1. Achievement
2. Weekly Challenge
3. Friends
4. League
5. Avatar
6. Store

---

## 35. 推荐整体架构

```text
                 Learner Profile
                        │
                        ↓
              ┌─────────────────┐
              │ Recommendation  │
              │     Engine      │
              └────────┬────────┘
                       │
          ┌────────────┼────────────┐
          ↓            ↓            ↓
      FSRS Due      Errors       Course
          │            │            │
          └────────────┼────────────┘
                       ↓
                Daily Queue
                       ↓
                 Session Engine
                       ↓
             Exercise Generator
                       ↓
          Adaptive Difficulty
                       ↓
                 User Answer
                       ↓
              ┌────────┴────────┐
              ↓                 ↓
          Memory Update      Error Model
              ↓                 ↓
              └────────┬────────┘
                       ↓
                Ability Model
                       ↓
               Progress Evidence
```

---

## 36. 核心产品原则

开发过程中所有功能都应该问以下问题：

### Q1

这个功能有没有降低开始学习的摩擦？

### Q2

这个功能有没有增加主动回忆？

### Q3

这个功能有没有帮助系统更准确知道用户会不会？

### Q4

这个功能有没有让用户感知到真实进步？

### Q5

这个功能有没有帮助用户把知识迁移到现实场景？

如果五个问题都是否：

> 不应该成为当前优先功能。

---

## 37. 最终产品理念

产品不应该试图让用户：

> 对 XP 上瘾。

也不应该主要让用户：

> 害怕失去 streak。

真正应该建立的循环是：

```text
我开始学习
↓
我遇到一点挑战
↓
我成功回忆出来
↓
我发现自己会了
↓
系统证明我比以前更强
↓
我想继续看看还能进步多少
```

最终目标：

> **让进步本身成为持续学习的奖励。**
