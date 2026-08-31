# 英语听力训练模块 Design Spec

## 1. 产品目标

设计一个“先听语音，再理解意思”的英语听力训练模块。

核心目标不是测试用户是否认识单词，而是训练：

**英语声音 → 语义理解**

尽量减少：

**英语声音 → 英文拼写 → 中文翻译**

这种中间转换。

模块需要强调真实听力、连续语流、口语表达和场景理解。

---

# 2. 核心训练流程

每一道题按照以下流程执行：

## Step 1：只播放语音

进入题目时：

* 不显示英文原文
* 不显示中文意思
* 不自动显示字幕
* 页面主要元素是一个播放按钮

示例：

播放：

“I barely made it to the meeting on time.”

页面显示：

Listening 3 / 10

[播放语音]

已播放 0 次

问题：

“这句话是什么意思？”

---

# 3. 答题模式

第一版 MVP 默认使用：

## 中文选择题

提供 3～4 个意思接近的选项。

例如：

A. 我勉强准时赶到了会议
B. 我提前参加了会议
C. 我几乎没有参加会议
D. 会议准时结束了

错误选项不能明显荒谬。

错误选项应该尽可能来自：

* 关键词误解
* 否定词误听
* 时态误解
* 高频错误理解
* 相似场景
* 相似动作
* 连读导致的误听

后续可以增加：

### 自由输入模式

用户用中文输入理解到的意思。

AI 判断：

* 完全理解
* 大意正确
* 部分理解
* 理解错误

### 自我判断模式

用户选择：

* 听懂了
* 大概听懂
* 没听懂

适合快速训练模式。

---

# 4. 播放规则

每次播放都需要计数。

显示：

已播放 1 次
已播放 2 次
已播放 3 次

用户可以重复播放，但次数影响 Listening Score。

原因：

用户第一次听懂和听五遍以后听懂，能力意义不同。

建议评分：

首次听懂：
100

第二次听懂：
85

第三次听懂：
70

更多播放：
60 或以下

如果使用提示，再继续扣分。

---

# 5. 提示系统

不要在用户第一次没听懂时直接显示答案。

设计渐进式提示。

## Hint Level 0

完全裸听。

## Hint Level 1：语义场景提示

例如：

“这句话与迟到 / 赶时间有关。”

## Hint Level 2：关键词提示

例如：

“注意听 barely 这个词。”

## Hint Level 3：部分英文

例如：

I _____ made it to the meeting on time.

## Hint Level 4：完整英文

显示：

I barely made it to the meeting on time.

提示使用次数必须记录。

---

# 6. 答案揭晓页面

用户答题以后，不立即切下一题。

进入 Reveal 阶段。

显示：

原句：

I barely made it to the meeting on time.

中文：

我勉强准时赶到了会议。

重点表达：

barely made it

意思：

差一点没赶上 / 勉强赶上

并显示：

Listening Score：85

原因：

第二次播放后答对。

---

# 7. “再听一次”机制

Reveal 页面必须有：

[再听一次]

这是整个学习机制中非常重要的一步。

第一次用户只听声音。

知道意思以后，再听相同语音。

用户往往会突然发现：

“原来他说的是这个。”

目标是建立：

声音 → 含义

的神经关联。

Reveal 页面可以提供：

* 再听一次
* 慢速播放
* 正常速度
* Shadowing 模式
* 下一句

MVP 可以只保留：

* 再听一次
* 下一句

---

# 8. 每个句子的内容模型

一句训练内容不能只有：

category

建议至少包含以下 6 个主要维度。

---

## Dimension 1：Scene 场景

描述这句话发生在哪里 / 什么关系中。

一级场景建议：

1. 日常高频
2. 商务职场
3. 恋爱关系
4. 朋友社交
5. 旅行
6. 餐厅 / 咖啡店
7. 购物
8. 校园
9. 科技 / IT
10. 游戏
11. 影视场景
12. 新闻
13. 网络社交
14. 紧急求助
15. 兴趣爱好
16. 家庭
17. 医疗生活
18. 电话沟通
19. 服务行业
20. 自由场景

Scene 应支持二级分类。

例如：

商务职场：

* 项目进度
* 会议
* 汇报
* 提需求
* 拒绝需求
* 请求帮助
* Bug说明
* Deadline
* 客户沟通
* 面试
* Small Talk
* 道歉
* 风险说明

恋爱关系：

* 约会
* 表白
* 暧昧
* 关心
* 撒娇
* 吵架
* 道歉
* 冷战
* 和好
* 表达不满
* 提出边界
* 分手
* 日常聊天

游戏：

* FPS
* RPG
* MOBA
* 生存游戏
* MMO
* 剧情游戏

进一步细分：

* 报点
* 掩护
* 撤退
* 组队
* 战术
* 装备
* Boss
* 任务
* 剧情对白
* 玩家聊天

---

# 9. Dimension 2：Intent 沟通意图

这是非常重要的维度。

用户最终应该学习：

“我想表达某种意图时，英语怎么说。”

建议 Intent：

* 请求
* 拒绝
* 同意
* 不同意
* 建议
* 道歉
* 感谢
* 解释
* 澄清
* 质疑
* 抱怨
* 安慰
* 鼓励
* 表达喜欢
* 表达不满
* 表达惊讶
* 表达怀疑
* 调侃
* 开玩笑
* 警告
* 命令
* 提醒
* 邀请
* 接受邀请
* 拒绝邀请
* 求助
* 表达意见
* 表达态度
* 确认信息
* 结束谈话

例如：

Scene：

恋爱关系

Intent：

表达不满

可以生成：

“I just wish you'd told me earlier.”

Scene：

商务

Intent：

表达不满

可以生成：

“I wish we'd been informed a little earlier.”

两句话表达的是类似意图，但 Register 和 Tone 不同。

---

# 10. Dimension 3：Level 难度

不要只用 CEFR。

可以同时记录：

CEFR：

A1 / A2 / B1 / B2 / C1

同时增加：

Vocabulary Difficulty

1～5

Grammar Difficulty

1～5

Sentence Length

Short / Medium / Long

Concept Complexity

1～5

最终难度可以由多个指标计算。

例如：

CEFR：B1

Vocabulary：2

Grammar：3

Sentence Length：Medium

Listening Difficulty：4

---

# 11. Dimension 4：Audio Listening Difficulty

这是整个模块区别于普通英语 App 的重要部分。

每句话标记听觉难点。

例如：

* linking 连读
* reduction 弱读
* contraction 缩写
* elision 吞音
* assimilation 音变
* flap T
* gonna / wanna / gotta
* 数字
* 日期
* 时间
* 人名
* 地名
* 专有名词
* 重音
* 情绪语音
* 快速语速
* 背景噪声
* 口音

例如：

“What are you gonna do about it?”

Audio Tags：

* reduction
* gonna
* linking
* fast speech

用户不会以后，可以记录为：

Listening Weakness：

Reduction：Weak

---

# 12. Dimension 5：Register 语体

同一个意思在不同环境下表达不同。

Register：

1. Formal
2. Professional
3. Neutral
4. Casual
5. Very Casual
6. Slang

例如：

正式：

“Would you mind taking a look at this?”

普通：

“Can you take a look at this?”

非常口语：

“Can you check this out?”

系统需要避免让用户误以为所有表达都可以在所有场景使用。

---

# 13. Dimension 6：Tone 情绪

建议：

* Neutral
* Happy
* Excited
* Friendly
* Caring
* Romantic
* Flirty
* Angry
* Annoyed
* Disappointed
* Sad
* Nervous
* Sarcastic
* Surprised
* Serious
* Urgent

Tone 同时可以影响 TTS。

例如：

恋爱：

“You could've just told me.”

Neutral 和 Angry 的听感完全不同。

---

# 14. 内容分类：首页设计

用户首页进入 Listening 模块。

显示：

“你今天想听什么？”

主要卡片：

日常高频
每天都会遇到

商务职场
会议 · 汇报 · 客户

恋爱关系
约会 · 关心 · 吵架

影视场景
经典场景表达

游戏世界
组队 · 指令 · 剧情

旅行
机场 · 酒店 · 餐厅

科技 IT
Bug · 开发 · 项目

朋友社交
聊天 · 玩笑 · 聚会

生存英语
紧急情况 · 求助

下面：

智能混合模式

默认：

70% 当前选择场景

20% 用户弱项复习

10% 探索新场景

按钮：

开始 10 句训练

---

# 15. 影视 / 游戏内容设计

影视和游戏不要只做“经典台词数据库”。

建议分成两种内容。

## A. 真实经典台词

来自：

电影 / 电视剧 / 游戏。

Metadata：

sourceType：quote

source：

作品名称

character：

角色

scene：

场景

版权方面必须注意使用方式。

如果无法确认授权，不要大量复制完整台词数据库。

---

## B. Inspired Scene 原创场景表达

更推荐大规模使用。

例如：

Style：

Superhero Movie

Scene：

大战前队友争吵

Intent：

Warning

Tone：

Serious

Level：

B1

AI 原创生成：

“If we go in there now, we're not coming back.”

不是某一部电影的原句。

但用户可以学习影视风格的真实表达。

---

# 16. 游戏内容

游戏分类建议独立设计。

例如：

FPS：

“Cover me, I'm reloading.”

“Two enemies on your left.”

“Fall back. We're too exposed here.”

MMORPG：

“Let's wait for the healer.”

“Don't pull the boss yet.”

“I'm almost out of mana.”

剧情 RPG：

“I don't think we can trust him.”

“There has to be another way.”

这种内容对年轻用户非常有吸引力。

---

# 17. 句子生成 Schema

推荐后端句子对象：

{
"id": "",
"text": "",
"meaning": "",

"scene": "",
"subScene": "",

"intent": "",
"tone": "",
"register": "",

"cefr": "B1",

"vocabularyDifficulty": 2,
"grammarDifficulty": 2,
"listeningDifficulty": 3,

"sentenceLength": "medium",

"audioFeatures": [
"linking",
"reduction"
],

"keyExpressions": [
{
"text": "",
"meaning": ""
}
],

"wrongAnswers": [
"",
"",
""
],

"sourceType": "generated",

"source": null,

"tags": []
}

---

# 18. AI 生成 Prompt 输入

不要直接：

“生成一个 B1 英语句子。”

应该输入结构化条件：

Scene：
恋爱关系

SubScene：
吵架

Intent：
表达不满

Tone：
Disappointed

Register：
Casual

CEFR：
B1

Listening Feature：
Reduction + Linking

Sentence Length：
8–14 words

Vocabulary：
High-frequency spoken English

然后要求 AI 返回：

* English sentence
* Chinese natural meaning
* key expression
* 3 wrong options
* listening feature
* explanation

---

# 19. AI 句子生成原则

必须满足：

1. 句子自然
2. 母语者实际可能说
3. 不为了教学故意写书面英语
4. 场景明确
5. Intent 明确
6. 不使用超出用户等级太多的单词
7. 可以有少量略高于等级的表达
8. 避免纯教科书英语
9. 优先高频表达
10. 每句话最好只有 1～2 个主要学习点

---

# 20. 用户能力模型

系统不应该只有：

English Level：B1

需要记录：

Vocabulary

Grammar

Listening

并将 Listening 再细分：

* clean speech
* fast speech
* linking
* reduction
* numbers
* accents
* slang
* sentence comprehension

同时记录 Scene 熟练度：

Daily：82

Business：73

Dating：58

Gaming：91

Travel：76

这样用户能看到：

“我的游戏英语很好，但恋爱和商务听力比较弱。”

---

# 21. Listening Score

单句评分考虑：

是否正确

播放次数

提示次数

反应时间

建议基础公式：

首次裸听答对：

100

第二遍：

85

第三遍：

70

使用 Hint 1：

-15

使用 Hint 2：

-30

看部分字幕：

最多 50

完整字幕以后才理解：

20

评分主要用于用户个人成长，不建议过度强调精确科学性。

---

# 22. 每日训练

默认：

10 句

约 5～10 分钟。

组成：

第 1～6 句：

用户选择 Scene

第 7～8 句：

最近错误

第 9 句：

旧知识复习

第 10 句：

Explore / Surprise

最后生成：

今日 Listening Score

例如：

78

首次听懂：

6 / 10

第二遍听懂：

2

提示后：

1

未听懂：

1

---

# 23. 每日结果页面

显示：

今日真实英语听力

78%

首次听懂：

60%

平均播放：

1.6 次

最强：

日常高频

最弱：

Weak Reduction

今日最容易误听：

“would've”

建议：

明天增加 3 句弱读训练。

---

# 24. 长期留存设计

用户每周看到：

上周 Listening：

68

本周：

74

+6

同时展示：

Reduction：

51 → 63

Business：

65 → 70

Daily：

74 → 81

让用户看到真正的能力变化，而不是只有：

连续签到 7 天。

---

# 25. MVP 范围

第一版不要做太复杂。

只实现：

1. Scene 选择
2. 播放 TTS
3. 中文三选一
4. 播放次数记录
5. 提示
6. 英文 Reveal
7. 再听一次
8. Listening Score
9. 下一句
10. 10 句训练总结

暂时不要做：

* 语音识别
* Shadowing 自动评分
* 多口音
* AI 自由输入评价
* 社交排行榜
* 实时对话

先验证：

用户是否喜欢：

“听 → 猜意思 → 揭晓 → 再听”

这个核心 Loop。

---

# 26. 核心产品原则

这个功能不应该成为：

“听英语单词然后选中文。”

而应该成为：

“听真实英语表达，然后判断说话人在表达什么。”

核心训练单位优先使用：

句子

短对话

真实场景

而不是孤立单词。

最终产品价值：

用户不是记住更多英文，而是逐渐能够在现实世界中：

“第一次听到一句英语，就直接理解它。”
