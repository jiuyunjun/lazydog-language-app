---
doc: "发音与音标学习DESIGN.md"
tier: "L4 专项设计"
status: "部分落地"
version: "1.0"
updated: "2026-09-07"
authority: "独立音标与发音学习子功能的产品目标、训练闭环、题型、评分、声音画像与 MVP 范围"
index: "DOCS.md"
maintenance: "如加入仓库根目录，须同步 DOCS.md 的文档清单、版本表与变更日志，并运行 python tools/check_docs.py"
---

# 发音与音标学习专项设计

## 1. 文档目的

本设计定义一个**独立的英语音标与发音学习子功能**。

它的目标不是让用户背完一张音标表，而是帮助用户建立：

```text
听到声音
↓
分辨声音
↓
理解声音如何产生
↓
认识对应 IPA 符号
↓
在单词中识别
↓
自己发出
↓
在不同单词和句子中稳定复现
```

核心原则：

> **Sound first, symbol second.**

即：

> **先建立声音类别，再把 IPA 当作这个声音的标签。**

---

## 2. 当前范围

本模块当前是一个**独立子功能**。

### 2.1 本期包含

- IPA 音位学习
- 标准音频播放
- 发音部位与发音方式说明
- 中文母语者常见误读说明
- Minimal Pair 最小对立体听辨
- 音位听辨测试
- 单音 / 单词 / 短语 / 句子跟读
- 发音评估
- 听辨能力与发音能力分开评分
- 用户声音盲区画像
- 模块内部的弱项优先练习
- 模块内部的进步证据

### 2.2 本期明确不做

暂时**不与其他学习模块建立业务关联**：

- 不关联词库
- 不关联单词卡
- 不关联拼写训练
- 不关联听力训练模块
- 不关联阅读模块
- 不关联语法
- 不关联 AI 场景对话
- 不把发音错误写入全局 Error System
- 不进入全局 FSRS / Daily Learning Queue
- 不影响全局 Mastery
- 不修改 CEFR 画像
- 不从其他模块自动跳转到本模块
- 不把本模块的薄弱音自动注入其他训练

未来如需要联动，应单独设计，不在本版顺手加入。

---

## 3. 产品定位

传统音标教学常见路径：

```text
/iː/
/ɪ/
/e/
/æ/
/ʌ/
...

背符号
↓
背例词
↓
测试
```

本模块不采用这种方式作为主流程。

推荐路径：

```text
先听两个声音
↓
发现差异
↓
建立声音类别
↓
理解嘴型 / 舌位 / 发音方式
↓
认识 IPA 符号
↓
放入单词
↓
自己发音
↓
换词、换说话者、换语境再次验证
```

音标是工具，不是最终学习目标。

最终能力是：

> **用户能够听出差异，并稳定地产生目标声音。**

---

## 4. 核心设计原则

### 4.1 Sound First

首次接触新音时优先：

```text
声音
↓
差异
↓
口腔动作
↓
IPA 符号
```

而不是先要求记住符号名称。

### 4.2 Contrast First

孤立听一个声音很难建立清晰类别。

优先使用对比：

```text
/ɪ/ ↔ /iː/
/r/ ↔ /l/
/æ/ ↔ /ɛ/
/s/ ↔ /θ/
/w/ ↔ /v/
```

通过 Minimal Pair 建立边界。

### 4.3 Perception 与 Production 分开

必须分别维护：

```text
👂 听辨能力
🗣 发音能力
```

因为：

> 能听出来，不代表自己能正确发出。

反过来也可能发生：

> 用户偶尔能模仿出正确声音，但仍不能稳定听出差异。

### 4.4 不追求一次判死刑

单次发音评分不能直接定义：

```text
你不会 /θ/
```

系统应通过多次证据形成判断。

### 4.5 从单音迁移到真实语言

训练不能停在：

```text
/θ/
/θ/
/θ/
```

必须逐步进入：

```text
Sound
↓
Minimal Pair
↓
Word
↓
Phrase
↓
Sentence
```

### 4.6 进步证据优先

结束页不强调：

```text
今天学习了 8 个音标
```

更推荐：

```text
/ɪ/ ↔ /iː/

听辨
58% → 76%

你已经连续 8 次分辨正确。
```

---

## 5. 发音标准

### 5.1 不使用“英语固定有 48 个音标”作为技术定义

“48 个音标”是部分英语教学体系中的教学分法，不是所有英语口音共同固定的音位数量。

因此数据模型不得把：

```text
48
```

写成不可变业务常量。

### 5.2 MVP 发音标准

建议 MVP 固定：

```text
General American
locale: en-US
```

理由：

- 避免英美音 IPA 混用
- 避免同一单词出现不同音位分析
- TTS / Pronunciation Assessment 可固定 locale
- 用户先建立一套稳定系统更重要

未来如支持英音，应新增独立 Accent Profile，而不是直接修改既有音位含义。

### 5.3 Accent Profile

概念模型：

```ts
type AccentProfile = {
    id: String
    displayName: String
    locale: String
    phonemeSetVersion: String
}
```

MVP 只有一个 profile：

```text
en-US
```

但数据设计不要假设永远只有一个。

---

## 6. 信息架构

独立入口：

```text
发音与音标
```

进入后首页建议：

```text
发音与音标

今天推荐
你目前最容易混淆：
/ɪ/ ↔ /iː/

[ 练 2 分钟 ]

--------------------------------

我的声音

元音
辅音
声音对比

--------------------------------

我的薄弱项

/ɪ/ ↔ /iː/      62%
/r/ ↔ /l/       71%
/θ/              74%

[ 查看全部 ]
```

页面首要动作：

```text
继续练习
```

而不是展示全部功能入口。

---

## 7. 首次进入：声音摸底

第一次进入不要求用户从第一个音标开始。

先进行约 3～5 分钟快速摸底。

### 7.1 目标

估计：

- 哪些声音用户已经稳定区分
- 哪些声音容易混淆
- 哪些音用户能听出但不会发
- 哪些音用户根本尚未建立类别

### 7.2 听辨题

例如：

```text
🔊 sheep

你听到了哪个？

A ship
B sheep
```

测试：

```text
/ɪ/ ↔ /iː/
```

再例如：

```text
🔊 light

A light
B right
```

测试：

```text
/l/ ↔ /r/
```

### 7.3 发音采样

给出：

```text
three
very
right
ship
sheep
```

用户跟读。

不要求一次覆盖全部音位，只覆盖高价值、高混淆度代表项。

### 7.4 摸底结果

不要输出：

```text
你的发音水平：73 分
```

推荐：

```text
你的声音画像

容易混淆
/ɪ/ ↔ /iː/
/r/ ↔ /l/

发音不稳定
/θ/
/v/

目前较稳定
/m/
/n/
/k/
```

然后：

```text
[ 开始练最弱的一组 ]
```

---

## 8. Sound Card

每一个音位都有独立 Sound Card。

示例：

```text
/ɪ/

短、放松的前元音

🔊 听声音

发音位置
舌位：偏前
舌高：较高
嘴唇：放松
下颌：轻微张开

不要读成
/iː/

常见对比
ship / sheep
sit / seat
live / leave

中文母语者常见问题
容易读得过长、过紧，
接近中文“衣”的感觉。

[ 听辨训练 ]
[ 跟读练习 ]
```

### 8.1 Sound Card 字段

```ts
type PhonemeCard = {
    phonemeId: String
    ipa: String
    category: PhonemeCategory

    shortDescriptionZh: String

    articulation: ArticulationGuide

    commonChineseErrors: List<CommonError>

    exampleWords: List<ExampleWord>

    contrastIds: List<String>
}
```

### 8.2 ArticulationGuide

```ts
type ArticulationGuide = {
    tonguePositionZh: String?
    lipPositionZh: String?
    jawPositionZh: String?
    airflowZh: String?
    voicingZh: String?
    keyActionZh: String
}
```

### 8.3 说明原则

不要堆语言学术语。

优先：

```text
舌尖轻触上齿后方
```

而不是只写：

```text
齿龈近音
```

可以在“更多说明”中显示术语。

---

## 9. 中文母语者常见误读

这是核心内容，不是附加内容。

每个高风险音应记录：

```text
目标音
↓
常见替代音
↓
典型后果
↓
修正动作
```

例如：

### /θ/

```text
常见替代：

/θ/ → /s/
think → sink

/θ/ → /t/
think → tink
```

修正：

```text
舌尖轻轻露出上下齿之间，
让气流从舌头与牙齿之间通过。
不要完全堵住气流。
```

### /v/

```text
常见替代：

/v/ → /w/
very → wery
```

修正：

```text
上齿轻触下唇，
保持有声摩擦。
```

### /r/ /l/

重点提示：

- 不要只用中文字近似
- 强调舌位差异
- 先听对比，再练生产

---

## 10. Minimal Pair 核心训练

Minimal Pair 是本模块最核心的题型之一。

### 10.1 基本形式

```text
🔊

A ship
B sheep

你听到了哪个？
```

### 10.2 Level 1：固定词对

```text
ship / sheep
sit / seat
live / leave
```

### 10.3 Level 2：多个词对

同一个声音对换词：

```text
fill / feel
bit / beat
lick / leak
```

目的：

防止用户只记住某一个具体单词。

### 10.4 Level 3：换说话者

使用：

- 不同性别
- 不同声音
- 不同语速
- 不同录音实例

目的：

避免用户只适应一个 TTS 声线。

### 10.5 Level 4：随机位置

例如：

```text
A / B
```

正确答案不能长期固定在某个位置。

### 10.6 Level 5：句中听辨

例如：

```text
I left the ship.
I left the sheep.
```

用户根据音频选择含义或文本。

### 10.7 Level 6：接近自然语速

减少人为停顿与过度清晰发音。

---

## 11. 听辨训练类型

### 11.1 二选一

```text
ship / sheep
```

适合初学。

### 11.2 三选一

```text
ship
sheep
shape
```

适合稳定后提高区分要求。

### 11.3 Same / Different

播放两个音频：

```text
🔊 1
🔊 2

相同
不同
```

用户无需读取单词，降低拼写干扰。

### 11.4 找目标音

```text
目标：/ɪ/

🔊 seat
🔊 sit
🔊 sheep
🔊 live

选出包含 /ɪ/ 的词。
```

### 11.5 IPA → Sound

显示：

```text
/æ/
```

播放多个音频，选择正确声音。

### 11.6 Sound → IPA

播放声音：

```text
🔊
```

选择：

```text
/æ/
/ɛ/
/ʌ/
```

此题型只在用户已经建立声音类别后出现。

---

## 12. 发音训练路径

固定升级路径：

```text
Sound
↓
Word
↓
Phrase
↓
Sentence
```

### 12.1 单音

目标：

感受发音动作。

例如：

```text
/θ/
```

注意：

单音音频与评分如果可靠性不足，可以只提供示范与录音回放，不强制自动评分。

### 12.2 单词

例如：

```text
think
three
nothing
```

### 12.3 Minimal Pair Production

用户连续读：

```text
ship
sheep
```

要求系统分别评估目标音。

### 12.4 Phrase

```text
three things
very well
little red car
```

### 12.5 Sentence

```text
I think there are three.
```

### 12.6 难度原则

不要让用户刚认识音位就读长句。

系统根据该音的发音稳定度逐级开放。

---

## 13. 录音反馈

录音结束后优先显示：

```text
目标
/θ/

本次
78

状态
接近目标，但还不稳定
```

然后显示具体证据：

```text
three       82
think       76
nothing     74
```

### 13.1 禁止

不要只显示：

```text
Pronunciation Score: 73
```

用户不知道怎么改。

### 13.2 推荐反馈

```text
这次主要问题：

/θ/ 的摩擦不够明显，
听起来有时接近 /t/。

试试：
不要用舌头完全堵住气流。
```

反馈应尽量对应：

```text
声音差异
+
发音动作
```

而不是抽象鼓励。

---

## 14. 听辨与发音双轨评分

每个音位或声音对分别维护：

```text
Perception Score
Production Score
```

例如：

```text
/ɪ/ ↔ /iː/

👂 听辨     92%
🗣 发音     63%
```

### 14.1 Perception Score

建议使用最近 N 次、带难度权重的表现。

参考信号：

- 正确率
- 连续正确
- 反应时间
- speaker 数量
- word context 数量
- 是否需要重复播放
- 是否使用提示

### 14.2 Production Score

参考：

- 音素准确度
- 多次发音稳定性
- 不同单词中的迁移
- 短语 / 句子中的保持能力
- 最近错误频率

### 14.3 不直接平均成一个总分

可以提供整体摘要，但数据模型必须保留两个方向。

---

## 15. Confidence

所有弱项判断都应带置信度。

例如：

```text
/r/ ↔ /l/

听辨 62%
样本：4

证据不足
```

而不是立即标红。

样本增加后：

```text
/r/ ↔ /l/

听辨 61%
样本：28

高置信弱项
```

概念：

```ts
type SkillEstimate = {
    score: Float
    sampleCount: Int
    confidence: Float
}
```

---

## 16. 声音盲区画像

模块维护：

```text
我的声音
```

推荐分为：

```text
需要优先练
容易混淆
发音不稳定
正在变稳
已经稳定
```

示例：

```text
需要优先练

/ɪ/ ↔ /iː/
听辨 61%
发音 72%

/r/ ↔ /l/
听辨 68%
发音 81%

/θ/
发音 70%
```

### 16.1 排序

优先级可综合：

```text
弱项程度
×
证据置信度
×
基础价值
×
最近训练时间
```

MVP 不需要复杂机器学习。

---

## 17. 模块内部自适应难度

目标不是长期 100% 正确。

建议训练保持：

```text
约 75% ~ 85% 成功率
```

### 17.1 太容易

如果：

```text
正确率 > 90%
```

可以：

- 换词
- 换 speaker
- 缩短反应时间
- 增加三选一
- 进入句子
- 减少重复播放
- 加入更接近的干扰项

### 17.2 太难

如果：

```text
正确率 < 65%
```

可以：

- 回到二选一
- 固定词对
- 放慢音频
- 允许重复播放
- 先播放两个声音对比
- 显示口型说明
- 显示 IPA
- 回到 Sound Card

---

## 18. Hint Ladder

提示不要只有：

```text
显示答案
```

### Level 0

只有音频：

```text
🔊
```

### Level 1

再次播放。

### Level 2

播放两个目标声音对比。

### Level 3

显示 IPA。

### Level 4

显示发音位置提示。

### Level 5

显示答案并解释区别。

系统记录用户用到哪一层提示。

---

## 19. 模块内部学习阶段

每个音位：

```ts
enum class PronunciationStage {
    Unseen,
    Introduced,
    Discriminating,
    Producing,
    Stable
}
```

解释：

### Unseen

尚未学习。

### Introduced

已经看过 Sound Card，知道声音大致特征。

### Discriminating

能够在 Minimal Pair 中稳定听辨。

### Producing

能够在多个单词中发出目标音。

### Stable

能够：

- 多个单词稳定听辨
- 多 speaker 听辨
- 多个词中稳定发音
- 短语 / 句子中保持

### 注意

`Stable` 只是本音标模块内部状态。

当前版本不映射到全局 Mastery。

---

## 20. 模块内部推荐队列

虽然不接全局 Daily Learning Queue，本模块内部仍需要：

```text
今天最值得练什么
```

建议组成：

```text
40% 当前最弱声音对
25% 最近发音不稳定音位
20% 已学音位的保持测试
15% 新音位
```

比例可动态变化。

推荐入口只给一个：

```text
[ 开始 2 分钟训练 ]
```

用户也可以自由进入音标表。

---

## 21. Session Flow

### 21.1 2 分钟 Quick Practice

```text
弱项听辨 × 4
↓
弱项跟读 × 2
↓
结果
```

### 21.2 5 分钟标准训练

```text
Warm-up
↓
Minimal Pair
↓
新声音 / 弱声音
↓
Word Production
↓
Sentence Production
↓
Progress Evidence
```

### 21.3 10 分钟深度训练

```text
听辨
↓
发音位置
↓
多词听辨
↓
多 speaker
↓
单词跟读
↓
短语
↓
句子
↓
延迟再测
```

---

## 22. End-of-Session

结束页必须告诉用户发生了什么能力变化。

例如：

```text
今天的声音训练

/ɪ/ ↔ /iː/

听辨
68% → 79%

发音
71% → 75%

今天第一次稳定分清：
ship / sheep

还需要练：
live / leave

[ 再练 2 分钟 ]
[ 完成 ]
```

禁止只展示：

```text
+50 XP
```

---

## 23. Progress Evidence

### 23.1 日反馈

```text
今天：

/ɪ/ ↔ /iː/
连续听对 9 次

/θ/
3 个单词发音稳定
```

### 23.2 周反馈

```text
本周声音进步

/ɪ/ ↔ /iː/
58% → 81%

/r/ ↔ /l/
66% → 73%

最明显进步：
/ɪ/ ↔ /iː/
```

### 23.3 Before / After

保存可比较的历史录音时，可以提供：

```text
14 天前
three
Score 61

今天
three
Score 83
```

如果隐私或存储成本不允许保留音频，则只保留结构化评分历史。

---

## 24. 音频设计

### 24.1 不长期使用单一 speaker

Minimal Pair 训练必须逐步提供声音变化。

建议至少有：

- 2 个以上声线
- 男 / 女
- 不同语速

### 24.2 同题不能只换音量

真正需要变化：

- speaker
- word
- sentence context
- speech rate

### 24.3 Replay

初级允许多次播放。

进阶逐渐减少重复。

记录：

```text
replayCount
```

作为听辨难度证据之一。

---

## 25. 发音评估服务

MVP 可使用现有 Azure Speech 能力完成：

- TTS
- STT
- Pronunciation Assessment
- phoneme-level evidence

### 25.1 架构原则

“独立子功能”指产品业务独立，**不代表重新造一套语音基础设施**。

实现时仍应复用既有：

```text
SpeechController
↓
SpeechProvider
↓
AzureSpeechProvider
```

禁止为本功能新建平行的：

```text
PronunciationSpeechService
AudioEngine
VoiceManager
```

除非未来经过正式架构变更。

### 25.2 评估结果

服务返回的分数只能作为证据。

业务层应形成：

```text
原始服务结果
↓
本地归一化
↓
多次历史
↓
稳定性判断
↓
用户可读反馈
```

UI 不直接根据一次 SDK 分数判定“会 / 不会”。

---

## 26. 自动评分可靠性

发音自动评分天然存在噪声。

可能受到：

- 麦克风
- 环境噪声
- 说话音量
- 录音截断
- TTS / 参考发音差异
- 网络
- SDK 模型误差

影响。

### 26.1 单次异常

例如：

```text
最近：
82 / 79 / 81 / 61 / 80
```

不应因为一次 `61` 立即降级。

### 26.2 连续证据

例如：

```text
67 / 64 / 62 / 66 / 63
```

才更适合判定为稳定问题。

### 26.3 低质量录音

如果检测到：

- 音量过低
- 无有效语音
- 录音太短
- 明显截断

应显示：

```text
这次录音不够清楚，再试一次。
```

而不是给低分。

---

## 27. 用户录音

### 27.1 MVP

录音主要用于：

- 当次播放给用户自己听
- 当次发音评估

### 27.2 默认隐私原则

推荐：

```text
默认不永久保存原始录音
```

只保存：

- 目标
- 时间
- 结构化评分
- 错误证据
- session 信息

如未来提供“听听以前的自己”，应明确让用户选择是否保存录音。

---

## 28. 数据模型

以下为产品层概念模型，具体 Room 结构另行设计。

### 28.1 Phoneme

```ts
type Phoneme = {
    id: String
    accentProfileId: String

    ipa: String
    category: PhonemeCategory

    displayOrder: Int

    articulation: ArticulationGuide

    shortDescriptionZh: String
}
```

### 28.2 PhonemeContrast

```ts
type PhonemeContrast = {
    id: String

    leftPhonemeId: String
    rightPhonemeId: String

    difficulty: Float

    minimalPairs: List<MinimalPair>
}
```

### 28.3 MinimalPair

```ts
type MinimalPair = {
    leftWord: String
    rightWord: String

    leftIpa: String
    rightIpa: String

    noteZh: String?
}
```

### 28.4 PerceptionAttempt

```ts
type PerceptionAttempt = {
    id: String

    targetId: String
    exerciseType: String

    audioVariantId: String?

    answer: String
    expected: String
    correct: Boolean

    replayCount: Int
    hintLevel: Int

    responseTimeMs: Long

    createdAt: Instant
}
```

### 28.5 ProductionAttempt

```ts
type ProductionAttempt = {
    id: String

    phonemeId: String
    text: String
    exerciseLevel: ProductionLevel

    providerScore: Float?
    normalizedScore: Float?

    phonemeEvidenceJson: String?

    recordingQuality: RecordingQuality

    createdAt: Instant
}
```

### 28.6 PronunciationProgress

```ts
type PronunciationProgress = {
    targetId: String

    perception: SkillEstimate?
    production: SkillEstimate?

    stage: PronunciationStage

    lastPracticedAt: Instant?
}
```

---

## 29. 音标总览页

不建议做成学校式密集表格作为默认首页。

可以保留：

```text
全部声音
```

进入后分组：

```text
元音
辅音
声音对比
```

每个音位显示：

```text
/ɪ/
听辨 82%
发音 71%
```

未学：

```text
/ʒ/
未学习
```

### 29.1 状态视觉

使用 Material 3 常规状态表达。

不要用大量红绿颜色作为唯一信息。

同时用：

- 文本
- 图标
- 进度
- 状态标签

保证无障碍。

---

## 30. Sound Detail 页面

结构建议：

```text
/ɪ/

[ 🔊 播放 ]

短、放松的前元音

发音方法
...

常见错误
...

对比
/ɪ/ ↔ /iː/

例词
ship
sit
live

--------------------------------

你的表现

听辨 82%
发音 71%

[ 开始练习 ]
```

页面只有一个主要动作：

```text
开始练习
```

---

## 31. 训练 UI 原则

### 31.1 单屏只问一件事

不要同时出现：

- 音标表
- 长解释
- 评分
- 多个按钮
- 下一个课程推荐

### 31.2 答题时减少干扰

听辨页：

```text
🔊

ship

sheep
```

足够。

### 31.3 反馈后再教学

顺序：

```text
先回答
↓
再显示正确答案
↓
再显示必要解释
```

不要在答题前泄露答案。

---

## 32. MVP

MVP 只验证核心闭环：

```text
听
↓
分辨
↓
学动作
↓
读
↓
评分
↓
看到弱项
↓
再次练
```

### P0

- 固定 en-US Accent Profile
- 音位数据
- Sound Card
- 标准 TTS
- Minimal Pair 二选一
- Sound → IPA
- 单词跟读
- 句子跟读
- Azure Pronunciation Assessment
- Perception / Production 双评分
- 声音盲区列表
- 模块内部推荐练习
- Session 结束进步反馈

### P1

- 多 speaker
- 三选一听辨
- Same / Different
- Phrase Production
- 句中 Minimal Pair
- Hint Ladder
- Confidence
- Before / After 结构化比较
- 更完整的中文母语者错误库

### P2

- 多 Accent Profile
- 英音
- 口型动画
- 舌位动画
- 相机辅助口型
- 保存历史录音
- 高级 rhythm / stress 训练
- Connected Speech 专项

---

## 33. MVP 不做

明确排除：

- 3D 口腔模型
- AI 虚拟教师
- 实时视频嘴型识别
- 社交排行榜
- 用户互评
- 复杂等级树
- 课程剧情
- 大量游戏化
- 多口音同时学习
- 全局学习系统联动
- 为发音功能新增独立语音基础设施

这些都可能有价值，但不会帮助验证最关键问题：

> **用户是否能通过短训练真正改善声音辨认和产生能力。**

---

## 34. 验收标准

MVP 至少满足：

### 34.1 Sound Learning

用户可以：

- 浏览一个音位
- 听标准发音
- 阅读简洁中文发音说明
- 查看常见误读
- 查看代表词

### 34.2 Perception

用户可以：

- 完成 Minimal Pair
- 得到即时正确反馈
- 重复播放
- 查看必要解释
- 形成独立的听辨分数

### 34.3 Production

用户可以：

- 跟读单词
- 跟读句子
- 获得可理解的发音反馈
- 查看目标音的历史表现
- 形成独立的发音分数

### 34.4 Weakness Profile

系统能够：

- 根据多次表现识别弱项
- 区分听辨弱与发音弱
- 按弱项优先推荐练习
- 不因一次异常直接判定

### 34.5 Progress Evidence

用户完成训练后能够看到：

```text
哪一个声音进步了
哪一个仍然弱
下一次最值得练什么
```

---

## 35. 成功指标

不以：

```text
看完多少音标
课程完成率
学习时长
```

作为唯一成功标准。

更重要的是：

### Perception

- 未见词 Minimal Pair 正确率提高
- 多 speaker 条件下保持
- 重复播放次数降低
- 反应时间下降

### Production

- 同一音位跨多个单词评分提高
- 评分波动减小
- 从 Word 迁移到 Phrase / Sentence
- 稳定错误减少

### Product

- 用户愿意主动进入“练最弱声音”
- 2 分钟 Quick Practice 完成率
- 训练后再次练习率

---

## 36. 推荐的核心循环

整个模块最终应收敛到：

```text
听两个声音
↓
发现自己分不清
↓
建立声音差异
↓
学习发音位置
↓
认识 IPA
↓
再次听辨
↓
自己跟读
↓
得到多次证据
↓
系统识别具体弱音
↓
换词 / 换 speaker 再测
↓
用户看到“现在真的分清了”
```

这个闭环比：

```text
背完 48 个符号
```

更接近真正的发音能力增长。

---

## 37. 与项目现有系统的边界

本模块在产品上独立。

当前只共享已有基础设施，不共享学习状态。

### 可以共享

```text
SpeechProvider
Azure Speech
通用 UI 组件
通用数据库基础设施
AppCopy / Theme
```

### 暂不共享

```text
Vocabulary Progress
Listening Progress
Spelling Progress
Global ErrorRecord
FSRS
Daily Learning Queue
CEFR
Global Mastery
```

即：

> **共享基础设施，不共享学习业务状态。**

这样既保持本功能独立，也避免制造第二套语音架构。

---

## 38. 后续扩展方向

只有在独立模块验证有效后，再考虑：

1. 单词页点击 IPA 进入声音详情
2. 拼写错误关联音位
3. 听力错误关联声音对
4. 发音错误进入全局复习
5. Reading / Speaking 中注入个人弱音
6. AI Scenario 针对弱音生成句子
7. Global Ability Map 增加 Pronunciation

这些全部属于未来版本。

**当前版本不实现。**
