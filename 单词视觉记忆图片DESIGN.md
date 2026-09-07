---
doc: "单词视觉记忆图片DESIGN.md"
tier: "L4 专项设计"
status: "部分落地"
version: "1.1"
updated: "2026-09-07"
authority: "英语词汇视觉检索、图片筛选、记忆辅助、Brave Image Search 集成与反馈闭环"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
related:
  - "母语阅读DESIGN.md"
  - "引人入胜的阅读材料DESIGN.md"
---

# 单词视觉记忆图片（Vocabulary Visual Memory）专项设计

## 0. 一句话定义

**不是给每个英语单词随便搜一张图，而是根据“当前词义 + 可视觉化场景”生成高信息量的图片检索词，再从搜索结果中选择最能帮助用户理解和记住该词义的图片。**

核心链路：

```text
Word
↓
Sense Disambiguation
↓
Visualizability Analysis
↓
Visual Search Query Generation
↓
Brave Image Search
↓
Candidate Filtering / Ranking
↓
Memory Image
↓
Vocabulary Card / Reading / Review
↓
User Feedback
```

产品目标不是“搜到一张和单词有关的图片”，而是：

> 用户看见图片后，能更快理解这个词、区分这个词义，并在以后更容易把词义回忆出来。

---

# 1. 为什么不能直接搜索 `{word}`

直接搜索：

```text
grip
charge
draft
bank
seal
spring
```

会产生严重歧义。

例如 `charge` 可能是：

- 给设备充电
- 收费
- 指控
- 冲锋
- 掌管
- 电荷

如果只做：

```text
q=charge
```

结果会混合手机充电、信用卡收费、法庭、军队、物理图、品牌页面。

因此系统必须绑定：

```text
VocabularySense
```

而不是只绑定：

```text
VocabularyLemma
```

---

# 2. 核心对象：Vocabulary Sense

```typescript
interface VocabularySense {
  id: string
  lemma: string
  partOfSpeech: string
  meaningZh: string
  definitionEn?: string
  cefr?: string
  frequency?: number
  exampleSentence?: string
  semanticTags?: string[]
  visualizability?: number
}
```

例如：

```json
{
  "id": "charge_v_01",
  "lemma": "charge",
  "partOfSpeech": "verb",
  "meaningZh": "给……充电",
  "definitionEn": "to put electricity into a battery",
  "exampleSentence": "I need to charge my phone.",
  "semanticTags": ["electricity", "battery", "phone"],
  "visualizability": 0.95
}
```

另一个 sense：

```json
{
  "id": "charge_v_02",
  "lemma": "charge",
  "partOfSpeech": "verb",
  "meaningZh": "指控；控告",
  "definitionEn": "to formally accuse someone of a crime",
  "exampleSentence": "He was charged with theft.",
  "semanticTags": ["law", "crime", "court"],
  "visualizability": 0.58
}
```

同一个 lemma 必须拥有独立图片策略和缓存。

---

# 3. 产品目标

## 3.1 Primary Goals

1. 帮助用户快速建立词义与视觉概念连接。
2. 提升 concrete vocabulary 的首次理解速度。
3. 提升后续 recall。
4. 帮助区分多义词、近义词、反义词。
5. 为单词详情、SRS、母语阅读提供低成本视觉辅助。
6. 搜索速度明显快于实时 AI 生图。
7. 不适合图片表达的词主动放弃，而不是强行配图。

## 3.2 Non-goals

不是：

- 每个词都必须有图片；
- 找最漂亮的图片；
- 做通用图库搜索；
- 让图片代替词义解释；
- 认为搜索结果天然可商用；
- 让用户只记住某张图而不是词义。

---

# 4. 核心原则

## 原则 1：Sense First

```text
word ≠ meaning
```

图片绑定 `senseId`。

## 原则 2：Scene First

优先搜索：

> 这个词义在现实里“看起来是什么样”。

而不是：

> 这个词在网页里出现在哪里。

## 原则 3：Concrete Before Abstract

具体名词、动作、状态通常适合图片；功能词、逻辑词、抽象关系并不一定适合。

## 原则 4：Memory Value > Visual Beauty

一张构图普通、但意义明确的图片，通常比商业广告图更适合记忆。

## 原则 5：No Forced Image

无法形成明确视觉概念时：

```text
imageStrategy = none
```

是合法结果。

---

# 5. Visualizability 分类

每个 sense 计算：

```text
VisualizabilityScore: 0..1
```

## 5.1 Very High：0.85–1.00

典型：

```text
apple
hammer
cliff
otter
helmet
brake caliper
grape
ladder
```

策略：

```text
direct object / scene photo
```

## 5.2 High：0.65–0.85

典型：

```text
grip
kneel
spill
whisper
exhausted
crowded
fragile
```

策略：

```text
action / human situation / visible state
```

## 5.3 Medium：0.40–0.65

典型：

```text
increase
contrast
responsibility
delay
conflict
priority
```

可能适合：

```text
comparison
diagram
visual metaphor
before/after
```

但必须谨慎。

## 5.4 Low：0.15–0.40

典型：

```text
although
likely
despite
perhaps
therefore
rather
```

不建议普通图片搜索。

## 5.5 None：0–0.15

例如：

```text
the
of
that
whether
```

直接：

```text
imageStrategy = none
```

---

# 6. Image Strategy

```typescript
type ImageStrategy =
  | 'object_photo'
  | 'action_scene'
  | 'state_scene'
  | 'spatial_relation'
  | 'comparison'
  | 'diagram'
  | 'labeled_diagram'
  | 'visual_metaphor'
  | 'contrast_pair'
  | 'none'
```

---

# 7. 不同词性的策略

## 7.1 Concrete Noun

`cliff`：

不建议：

```text
cliff
```

推荐：

```text
steep cliff edge landscape
```

或：

```text
person standing near a steep cliff edge
```

输出：

```json
{
  "strategy": "object_photo",
  "query": "steep cliff edge landscape",
  "visualTarget": "clear vertical rock drop"
}
```

## 7.2 Animal / Plant

`otter`：

```text
river otter swimming close up
```

不要优先搜：

```text
otter cute
```

因为会增加表情包、商品和插画噪声。

## 7.3 Tool / Mechanical Object

`brake caliper`：

认识物体：

```text
car brake caliper close up
```

理解结构：

```text
brake caliper labeled diagram
```

## 7.4 Action Verb

`grip = 紧握`：

差：

```text
grip
```

一般：

```text
grip action
```

好：

```text
hand gripping a metal handle close up
```

模板：

```text
subject + visible action + object + optional context
```

## 7.5 Motion Verb

`stumble`：

```text
person stumbling while walking
```

静态图表达力不足时，可在未来扩展到短视频或动图，但 MVP 不做。

## 7.6 Visible Adjective

`exhausted`：

```text
exhausted runner sitting after race
```

比：

```text
exhausted person
```

更有上下文。

## 7.7 Physical Property

`rough`：

```text
rough stone surface close up
```

`fragile`：

```text
cracked fragile glass object
```

不要只找写着 `FRAGILE` 的标签图。

---

# 8. Spatial Vocabulary

这类词更适合统一关系图：

```text
above
beneath
beside
between
inside
outside
across
```

例如 `beneath`：

```text
object beneath another object spatial relationship illustration
```

长期推荐：

```text
spatial vocabulary
→ generated diagram preferred
→ Brave Search fallback
```

理由：语义更精确、风格统一、无第三方图片授权风险。

---

# 9. Opposites / Contrast

以下词适合成对：

```text
shallow / deep
rough / smooth
empty / full
narrow / wide
```

策略：

```text
contrast_pair
```

例如：

```text
shallow vs deep water comparison
```

比各找一张无关图片更适合形成概念对比。

---

# 10. Abstract Vocabulary

例如：

```text
responsibility
priority
consequence
opportunity
reliable
efficient
```

强行搜索：

```text
responsibility concept
```

经常会得到：

- 商业图库；
- 握手；
- 拼图；
- 箭头；
- 大字海报；
- stock photo。

因此先判断：

```text
Can this sense be shown through a concrete situation?
```

例如：

```text
She is responsible for feeding the dog every morning.
```

可搜索：

```text
person feeding dog every morning
```

但必须标记：

```text
imageRole = contextual_example
```

不能声称图片完整代表 `responsibility`。

---

# 11. Grammar / Function Words

例如：

```text
although
even if
unless
as long as
despite
```

默认：

```text
imageStrategy = none
```

或者使用完整句子场景。

例如：

```text
Even though it was raining, he went running.
```

图片：

```text
person running outside in heavy rain
```

重点是完整句义，而不是给 `although` 硬配图。

---

# 12. Visual Search Query

统一结构：

```typescript
interface VisualSearchQuery {
  senseId: string
  query: string
  strategy: ImageStrategy
  visualTarget: string
  mustShow: string[]
  avoid?: string[]
  preferredMedia: 'photo' | 'illustration' | 'diagram' | 'either'
  confidence: number
}
```

例：

```json
{
  "senseId": "grip_v_01",
  "query": "hand gripping a metal handle close up",
  "strategy": "action_scene",
  "visualTarget": "a hand visibly holding an object tightly",
  "mustShow": ["hand", "handle", "tight grasp"],
  "avoid": ["brand logo", "sports grip product", "text-only image"],
  "preferredMedia": "photo",
  "confidence": 0.96
}
```

---

# 13. Query Generator

输入：

```json
{
  "word": "grip",
  "sense": "紧握；牢牢抓住",
  "partOfSpeech": "verb",
  "example": "She gripped the handle tightly."
}
```

输出：

```json
{
  "visualizable": true,
  "visualizability": 0.94,
  "strategy": "action_scene",
  "queries": [
    "hand gripping a metal handle close up",
    "person tightly gripping a handle",
    "fingers firmly grasping handle"
  ]
}
```

---

# 14. Query 数量与长度

不要每个词只生成一个 query。

推荐：

```text
1 primary query
+ 2 fallback queries
```

典型策略：

```text
Query A: canonical concrete scene
Query B: alternative object/context
Query C: illustration/diagram fallback
```

推荐长度：

```text
4–10 English words
```

不要：

```text
Please find me an image showing...
```

也不要过短：

```text
grip hand
```

理想：

```text
hand gripping a metal handle close up
```

Query 是搜索引擎 query，不是 AI image prompt。

---

# 15. Query 语言与区域

默认建议：

```text
search_lang=en
country=ALL
```

英文词汇学习使用英文 query 通常结果更丰富。

特定地域对象可以适当改变 country/search_lang，但不要默认把所有请求锁到用户所在国家。

---

# 16. Brave Image Search 集成

Endpoint：

```text
GET https://api.search.brave.com/res/v1/images/search
```

典型请求：

```http
GET /res/v1/images/search
?q=hand%20gripping%20a%20metal%20handle%20close%20up
&count=20
&safesearch=strict
&search_lang=en
&country=ALL
```

Header：

```http
X-Subscription-Token: <API_KEY>
Accept: application/json
```

推荐默认：

```text
count = 30
safesearch = strict
spellcheck = true
```

Brave 当前 Image Search 单次最多可取 200 个结果，但图片搜索不支持 offset 分页。单词视觉检索通常 10–30 个候选就足够，不需要每次拉 100–200 张。

---

# 17. Brave 返回内容

结果通常包括：

```text
image URL
thumbnail
source page URL
image dimensions
title
description
publisher
```

Brave 的 thumbnail 通过其图片代理提供，标准缩略图宽度约 500px 并保持比例。

这适合：

```text
candidate preview
```

但 Brave Image Search 不是 royalty-free image API。

---

# 18. Candidate Pipeline

```text
Brave 20 results
↓
Hard Filters
↓
~10 candidates
↓
Semantic Ranker
↓
~3 candidates
↓
Visual Quality Ranker
↓
Top 1
```

建议保留 Top 8 给用户“换一张”。原稿写的是 Top 3，实现时改大：硬过滤挺狠，抽象一点的词义前几张常常都不贴切，三张换完只剩“重新找”，而重搜要再花一次模型加一次搜索；候选是同一次搜索里白拿的，多留几张只是多存几行 JSON。

---

# 19. Hard Filters

第一层尽量不用昂贵模型。

## Size

若 metadata 可用，可过滤：

```text
width < 200
height < 150
extreme aspect ratio
```

## Domain / Source

维护 `blockedDomains`：

- 成人/不适来源；
- 明显低质量 SEO；
- 无法稳定加载；
- 水印严重；
- 已知风险来源。

## Duplicate

使用：

```text
URL dedupe
source dedupe（同一个站最多两张）
perceptual hash
```

## Text-heavy

图片本身大量写着目标单词时降权。

目的不是精确 OCR，而是估算：

```text
textCoverage
```

---

# 20. Semantic Ranker

输入：

```text
word
sense
visualTarget
candidate thumbnail
candidate title
candidate description
```

判断问题必须是：

> Does this image visually express THIS SENSE?

而不是：

> Is this image related to the word?

---

# 21. Candidate Score

```text
ImageMemoryScore =
0.35 SemanticMatch
+ 0.20 VisualClarity
+ 0.15 SenseSpecificity
+ 0.10 Memorability
+ 0.08 Composition
+ 0.05 ImageQuality
+ 0.04 SourceQuality
+ 0.03 ContextFit
- penalties
```

Penalties：

```text
TextHeavy
Watermark
BrandNoise
MultipleMeanings
VisualAmbiguity
UnsafeRisk
Duplicate
StockPhotoGenericness
```

---

# 22. 打分示例：grip

目标：

```text
grip = hold tightly
```

A. 手明显紧握门把手：

```text
SemanticMatch 0.98
```

B. 网球拍握把商品：

```text
0.35
```

C. 健身握力器：

```text
0.30
```

D. “GRIP”文字海报：

```text
0.05
```

---

# 23. SenseSpecificity

例如：

```text
bank = 河岸
```

普通河流：

```text
SemanticMatch: 0.70
SenseSpecificity: 0.45
```

明显展示河岸边缘：

```text
SemanticMatch: 0.95
SenseSpecificity: 0.92
```

---

# 24. 多义词严格隔离

错误缓存 Key：

```text
word
```

正确：

```text
senseId
```

例如：

```text
charge_v_battery
charge_v_accuse
charge_n_fee
charge_n_electricity
```

分别拥有自己的：

```text
query
candidate list
selected image
```

---

# 25. Query Generator Prompt

```text
You generate image-search queries for English vocabulary learning.

Your task is NOT to describe a beautiful image.
Your task is to create a short search query that is likely to retrieve
an image which makes the specified word sense visually obvious.

Always reason from the supplied SENSE, not from the lemma alone.

Prefer:
- concrete objects
- visible actions
- visible states
- clear spatial relationships
- simple scenes

Avoid:
- vague concepts
- stock-photo language
- text posters
- logos
- brand names unless the sense itself is a brand
- metaphor unless the concept cannot be represented literally

If the word sense is not meaningfully visualizable, return visualizable=false.

Queries should normally be 4-10 words.
```

---

# 26. Query Generator Output

```json
{
  "visualizable": true,
  "visualizability": 0.94,
  "reason": "The action has a clear visible hand-object relationship.",
  "strategy": "action_scene",
  "primaryQuery": "hand gripping a metal handle close up",
  "fallbackQueries": [
    "person tightly gripping a handle",
    "fingers firmly grasping metal bar"
  ],
  "visualTarget": "a hand visibly holding an object tightly",
  "mustShow": ["hand", "gripped object"],
  "avoid": ["product advertisement", "logo", "text poster"]
}
```

---

# 27. Candidate Vision Ranker Prompt

```text
You select images for English vocabulary learning.

Target:
word: {word}
sense: {meaning}
visual target: {visualTarget}

Judge whether the image itself makes this exact meaning easier to understand
and remember.

Do not reward:
- a page merely containing the word
- text printed on the image
- brand association
- vague thematic similarity
- visual beauty without semantic clarity

Score:
semanticMatch
senseSpecificity
visualClarity
memorability
textNoise
brandNoise
ambiguity

Return a concise reason and overall score.
```

---

# 28. 是否必须使用 Vision Model

不一定。

## MVP

```text
LLM generates query
↓
Brave ranking
↓
hard filters
↓
Top 8
↓
default Top 1 + user can change
```

## v1.5

加入 multimodal ranker，只评估：

```text
Top 5–10 thumbnails
```

不要对 100 张图全部跑 Vision。

---

# 29. 查询回退

Primary Query 找不到好图：

```text
fallback A
↓
fallback B
```

最多 2–3 次搜索请求。

仍失败：

```text
imageStatus = unavailable
```

禁止无限扩大搜索。

---

# 30. Failure Reasons

```typescript
type ImageFailureReason =
  | 'not_visualizable'
  | 'ambiguous_results'
  | 'low_semantic_match'
  | 'unsafe_results'
  | 'low_quality'
  | 'copyright_policy'
  | 'api_failure'
  | 'no_results'
```

---

# 31. 搜图还是生图

统一抽象：

```text
Visual Asset Router
```

## 优先搜图

适合：

- 动物；
- 食物；
- 工具；
- 车辆；
- 建筑；
- 地理对象；
- 身体动作；
- 真实世界对象；
- 技术部件。

## 优先生图 / 系统图

适合：

- above / below；
- shallow / deep；
- 语法关系；
- 空间关系；
- 统一教学示意图；
- 高版权风险的简单教学图。

## 无图

适合：

```text
whether
therefore
although
seemingly
```

---

# 32. Visual Asset Router

```typescript
interface VisualAssetDecision {
  visualizable: boolean
  visualizability: number
  route:
    | 'image_search'
    | 'generated_image'
    | 'generated_diagram'
    | 'sentence_scene'
    | 'none'
  reason: string
}
```

规则：

```text
concrete object → image_search
animal → image_search
action → image_search
spatial relation → generated_diagram
contrast adjective → generated_diagram / image_pair
abstract + concrete example → sentence_scene
function word → none
```

---

# 33. 版权与来源

Brave 提供的是：

```text
search / index / discovery
```

不是：

```text
license grant
```

因此必须区分：

```text
Discovery Asset
```

与：

```text
Licensed / Owned Asset
```

不能默认：

```text
Brave 搜到
→ App 可永久下载
→ 可商用
→ 可重新分发
```

---

# 34. Image Asset 数据结构

```typescript
interface VocabularyImageAsset {
  id: string
  senseId: string

  sourceType:
    | 'brave_search'
    | 'generated'
    | 'licensed_library'
    | 'manual'

  displayUrl: string
  thumbnailUrl?: string
  sourcePageUrl?: string
  originalImageUrl?: string
  publisher?: string
  width?: number
  height?: number
  query?: string

  scores?: {
    semanticMatch: number
    senseSpecificity: number
    visualClarity: number
    memorability: number
    overall: number
  }

  rightsStatus:
    | 'unknown'
    | 'external_display_only'
    | 'licensed'
    | 'owned'
    | 'generated'

  selectedAt: string
}
```

---

# 35. 缓存策略

Brave 官方建议调用方做缓存以减少请求。

但：

```text
API response caching
```

与：

```text
downloading third-party images permanently
```

不是同一件事。

第一版建议缓存：

```text
query metadata
Brave thumbnail URL
source URL
ranking score
```

不要默认把原图永久下载到自己的 CDN。

---

# 36. 推荐缓存 Key

```text
image-query:v1:{senseId}
image-results:v1:{queryHash}
image-selection:v1:{senseId}
```

如果是 generated / licensed asset，可长期持久化。

---

# 37. 图片失效

外部图片可能：

```text
404
domain blocks
URL expires
source removed
```

显示策略：

```text
primary asset
↓ fail
backup asset
↓ fail
hide image area
```

每个 sense 保留 Top 8（见 §18）。

---

# 38. API Secret

Brave API Key：

```text
绝不能放 Android / iOS 客户端
```

必须：

```text
App
↓
Your Backend
↓
Brave API
```

---

# 39. 后端 API

## POST /vocabulary/:senseId/image/search

请求：

```json
{
  "forceRefresh": false
}
```

服务端：

```text
load VocabularySense
↓
Visual Asset Router
↓
Query Generator
↓
Brave Search
↓
Filter
↓
Rank
↓
Cache
```

响应：

```json
{
  "senseId": "grip_v_01",
  "visualizable": true,
  "strategy": "action_scene",
  "query": "hand gripping a metal handle close up",
  "selected": {
    "assetId": "img_123",
    "thumbnailUrl": "...",
    "sourcePageUrl": "...",
    "publisher": "..."
  },
  "alternatives": [
    {
      "assetId": "img_124",
      "thumbnailUrl": "..."
    }
  ]
}
```

---

# 40. 手动换图

UI：

```text
[图片]

grip
紧握

[换一张]
```

用户选择替代图后记录：

```text
image_selected
```

这比单纯模型自评分更有价值。

---

# 41. 用户反馈

轻量反馈：

```text
👍 有帮助
👎 不相关
```

或放入 `⋯`：

- 图片不相关
- 太抽象
- 看不懂
- 图片质量差
- 不想看到这张

---

# 42. Feedback 数据

```typescript
interface VocabularyImageFeedback {
  senseId: string
  assetId: string
  impression: boolean
  changedImage: boolean
  selectedAsReplacement: boolean
  helpful?: boolean
  reason?:
    | 'irrelevant'
    | 'ambiguous'
    | 'low_quality'
    | 'text_heavy'
    | 'unpleasant'
    | 'other'
}
```

---

# 43. 隐式信号

如果用户看到默认图立刻换图：

```text
default selection quality ↓
```

如果连续跳过前三张：

可能是：

```text
query wrong
sense wrong
word not visualizable
```

需要反向降低 query quality，而不是只给第四张加分。

---

# 44. 图片是否真的提高记忆

最终需要 A/B：

```text
No Image
vs
Search Image
vs
Generated Teaching Image
```

测试：

```text
Immediate comprehension
D+1 recall
D+7 recall
response time
```

定义：

```text
Image-Assisted Recall Lift =
Recall_with_image - Recall_without_image
```

---

# 45. 与单词详情页集成

```text
┌──────────────────────┐
│       [ IMAGE ]      │
│                      │
│ grip /ɡrɪp/          │
│ v. 紧握；牢牢抓住     │
│                      │
│ She gripped the      │
│ handle tightly.      │
│                      │
│ [▶] [换图] [加入复习] │
└──────────────────────┘
```

图片定位：

```text
meaning aid
```

不是页面主体。

---

# 46. 与 SRS 集成

首次学习：

```text
word + image + context
```

复习时逐渐撤掉图片。

### Stage 1

```text
front: image + word
```

### Stage 2

```text
front: word only
answer: meaning + image
```

### Stage 3

```text
no image
```

避免形成对某张图片的依赖。

---

# 47. Image Scaffold Fade

路径：

```text
strong image support
↓
answer-side image
↓
optional image
↓
no image
```

最终要记住的是英语，而不是图片。

---

# 48. 与“母语阅读”集成

用户点击英语 span：

```text
gripped
```

Bottom Sheet：

```text
grip
紧握

[visual memory image]

She gripped the handle tightly.

[查看详情]
```

如果图片已缓存应 instant display。

不要点击后才第一次调用 Brave。

---

# 49. 预取

文章生成结束：

```text
target vocabulary
↓
check image cache
↓
missing image
↓
pre-fetch image search
```

只处理：

```text
new targets
important review items
```

不要为文章里的所有熟词预取。

---

# 50. Batch 与全局复用

如果一次加入 20 个词：

```text
20 words
↓
Visualizability Filter
↓
12 visualizable
↓
12 searches
```

进一步使用：

```text
Global SenseVisualRegistry
```

同一 sense 的高质量默认图可以跨用户复用。

因此 Brave Search 更适合作为：

```text
cold-start asset discovery
```

而不是每次打开单词都实时搜索。

---

# 51. SenseVisualRegistry

```typescript
interface SenseVisualRegistry {
  senseId: string
  defaultAssetId?: string
  alternativeAssetIds: string[]
  queryVersion: string
  rankerVersion: string
  confidence: number
  humanReviewed: boolean
  updatedAt: string
}
```

---

# 52. Safe Search

公开学习产品默认：

```text
safesearch=strict
```

即使使用 strict，也建议保留 application-level moderation。

特别关注：

- 身体部位；
- 暴力动词；
- 医疗词；
- 性相关词；
- 未成年账户。

---

# 53. 敏感词路由

例如：

```text
wound
bleed
corpse
weapon
naked
```

定义：

```typescript
type Sensitivity =
  | 'normal'
  | 'mild'
  | 'graphic'
  | 'adult'
```

对 `graphic` 可默认：

```text
generated neutral illustration
```

或：

```text
none
```

避免直接搜索真实冲击性照片。

---

# 54. Example Library

## object

```text
cliff
→ steep cliff edge landscape
```

```text
ladder
→ aluminum ladder leaning against wall
```

```text
otter
→ river otter swimming close up
```

## action

```text
grip
→ hand gripping metal handle close up
```

```text
kneel
→ person kneeling on one knee
```

```text
spill
→ person spilling glass of water
```

```text
pour
→ hand pouring water into glass
```

## state

```text
exhausted
→ exhausted runner sitting after race
```

```text
crowded
→ crowded subway train passengers
```

```text
empty
→ completely empty room
```

## physical adjective

```text
rough
→ rough stone surface close up
```

```text
smooth
→ smooth polished stone surface close up
```

```text
fragile
→ cracked fragile glass object
```

## relation

```text
beneath
→ object beneath another object diagram
```

```text
between
→ object between two objects diagram
```

## comparison

```text
shallow
→ shallow versus deep water comparison
```

```text
narrow
→ narrow versus wide road comparison
```

## technical

```text
brake caliper
→ car brake caliper close up
```

```text
piston
→ engine piston labeled diagram
```

```text
cache
→ generated technical diagram preferred
```

---

# 55. 错误示例

## grip

差：

```text
grip
```

好：

```text
hand gripping metal handle close up
```

## bank = 河岸

差：

```text
bank
```

好：

```text
river bank shoreline close up
```

## draft = 草稿

差：

```text
draft
```

可能出现啤酒、NBA draft、征兵、气流。

好：

```text
rough first draft handwritten document
```

## seal = 海豹

差：

```text
seal
```

好：

```text
harbor seal animal swimming
```

---

# 56. Query Quality Validator

在调用 Brave 前检查：

```text
Does query:
- uniquely identify sense?
- contain visible subject/object?
- contain brand ambiguity?
- rely on abstract wording?
- likely return text posters?
```

输出：

```json
{
  "senseSpecificity": 0.94,
  "visualConcreteness": 0.91,
  "searchability": 0.89,
  "risk": [],
  "approved": true
}
```

低于阈值重新生成 query。

---

# 57. 推荐阈值

Query：

```text
senseSpecificity >= 0.85
visualConcreteness >= 0.75
```

Candidate：

```text
semanticMatch >= 0.85
visualClarity >= 0.70
overall >= 0.80
```

如果 Top 1：

```text
overall < 0.70
```

不要硬展示。

---

# 58. MVP v1

第一版只实现：

1. `senseId` 级图片绑定。
2. Visualizability 分类。
3. LLM 生成 `visual_search_query`。
4. 1 primary + 2 fallback query。
5. Brave Image Search。
6. `count=30`。
7. `safesearch=strict`。
8. 基础 size / URL / duplicate filter。
9. 保存 Top 8。
10. 默认展示 Top 1。
11. 用户可“换一张”。
12. 记录换图行为。
13. 搜不到允许无图。
14. concrete words 优先。
15. API Key 只放后端。

MVP 暂时不需要：

- 全候选 Vision Ranking；
- AI 生图 fallback；
- OCR；
- 自动版权授权判断；
- 复杂 A/B；
- 关系图生成器。

---

# 59. MVP v1.5

加入：

- Vision reranker；
- text-heavy detector；
- visual asset router；
- generated diagrams；
- sensitive-word routing；
- Global SenseVisualRegistry；
- 图片帮助/不帮助反馈。

---

# 60. v2

加入：

- 搜图 / 生图动态路由；
- Recall Lift 数据驱动；
- 词类专属策略；
- contrast pair；
- phrase / collocation image；
- sentence-scene；
- image scaffold fade；
- personalized visual preference；
- licensed image providers；
- stale asset replacement。

---

# 61. 推荐 Pipeline

```text
Vocabulary Sense
↓
Visualizability Classifier
↓
Visual Asset Router
│
├── none
│
├── generated diagram
│
├── generated image
│
└── image search
      ↓
      Search Query Generator
      ↓
      Query Validator
      ↓
      Brave Image Search
      ↓
      Hard Filter
      ↓
      Candidate Ranker
      ↓
      Best Asset
↓
SenseVisualRegistry
↓
Vocabulary UI
↓
Feedback / Recall
```

---

# 62. 数据库建议

```text
vocabulary_sense
vocabulary_visual_query
vocabulary_image_asset
sense_visual_registry
vocabulary_image_feedback
```

`vocabulary_visual_query`：

```sql
id
sense_id
strategy
query
visual_target
must_show_json
avoid_json
generator_version
quality_score
created_at
```

`vocabulary_image_asset`：

```sql
id
sense_id
source_type
thumbnail_url
original_url
source_page_url
publisher
width
height
semantic_score
visual_clarity_score
overall_score
rights_status
status
created_at
last_validated_at
```

status：

```text
candidate
selected
rejected
stale
blocked
```

---

# 63. 前端状态

```text
loading
ready
unavailable
failed
```

图片失败不能阻塞单词页。

如果无图，直接收起图片区域，不需要留大块 placeholder。

---

# 64. 性能

单词详情打开时不要实时执行：

```text
LLM → Brave → Vision
```

理想：

```text
precomputed / cached
```

只有 cache miss 才执行发现流程。

页面主体不等待图片。

---

# 65. 成本控制

最大成本来源：

```text
LLM query generation
Search API
Vision ranking
```

优化顺序：

1. Global sense cache。
2. 只对 visualizable words 搜图。
3. Query generation batch。
4. Vision 只看 Top 5。
5. 高频词人工确认后不再重搜。

---

# 66. 高价值人工校验集

前期可先做：

```text
Top 1,000 / 3,000 高频 concrete senses
```

人工快速确认默认图。

长尾：

```text
auto discovery
```

核心体验会稳定很多。

---

# 67. Phrase 图片

不是所有 phrase 都适合。

```text
take a seat
```

适合：

```text
person sitting down on chair
```

而：

```text
take into account
```

不适合普通真实图片。

Visualizability 仍按 phrase sense 判断。

---

# 68. 是否把词直接画在图片上

默认不要。

正确 UI：

```text
image
+
word outside image
```

这样可以分别控制：

```text
image cue
word cue
meaning cue
```

---

# 69. Recall Test

## Recognition

```text
[image]

Which word fits this image?

A grip
B float
C release
D lean
```

## Recall

```text
[image]

________ the handle tightly.
```

用户输入：

```text
grip
```

## Sense Disambiguation

`charge`：

```text
[phone charging]
[person in court]
```

选择哪张图对应：

```text
charge = 给……充电
```

---

# 70. 不要过度依赖图片

图片不能替代：

- pronunciation；
- definition；
- usage；
- collocation；
- grammar；
- context。

最终单词模型仍然是：

```text
Form
Meaning
Pronunciation
Usage
Context
Memory Cue
```

图片只是 `Memory Cue`。

---

# 71. Acceptance Criteria

## Query

```text
sense-specific
visual
4–10 words preferred
not lemma-only
```

## Candidate

```text
semanticMatch >= 0.85
not text-heavy
not obvious brand/product noise
safe
```

## UX

```text
image failure does not block word page
user can replace image
source metadata retained
```

## Data

```text
image bound to senseId
query/version recorded
source URL retained
feedback recorded
```

## Security

```text
Brave API key server-side only
```

---

# 72. KPI

## Retrieval Quality

```text
Top1 Semantic Accuracy
Top3 Useful Image Rate
Image Replace Rate
No-good-result Rate
```

## Product

```text
Image View Rate
Image Replace Rate
Helpful Feedback Rate
Word Page Dwell
```

## Learning

```text
Immediate Recall
D+1 Recall
D+7 Recall
Recall Latency
```

核心：

```text
Image-Assisted Recall Lift
```

---

# 73. North Star

这个模块不以：

```text
Images Generated
Images Searched
```

为目标。

真正目标是：

> 有多少视觉辅助实际提高了用户对目标词义的理解和后续回忆。

---

# 74. 最终原则

1. **图片绑定词义，不绑定单词字符串。**
2. **搜具体场景，不搜抽象 keyword。**
3. **`visual_search_query` 是独立生成产物。**
4. **不是所有词都应该有图片。**
5. **搜索相关不等于教学相关，必须判断 exact sense。**
6. **最清楚的图比最漂亮的图更有价值。**
7. **Brave 负责发现，不等于获得第三方图片版权。**
8. **冷启动用搜索，长期靠 SenseVisualRegistry 复用。**
9. **具体词优先 Search，关系/抽象教学图可以 Generate。**
10. **最终用 Recall Lift 判断图片有没有价值，而不是图片点击率。**

---

# 75. 推荐第一版最终方案

```text
VocabularySense
↓
判断 visualizable
↓
LLM：生成 1 primary + 2 fallback visual_search_query
↓
Brave Image Search：primary query，count=30，strict
↓
基础过滤
↓
取 Top 8
↓
默认 Top 1
↓
用户可以换图
↓
把选择写入 SenseVisualRegistry
```

先不要加 Vision Model。

上线后观察：

```text
默认图换图率
Top 8 命中率
不同词性的失败率
```

如果默认图质量明显不够，再加入：

```text
Top 5 thumbnail → multimodal reranker
```

这样能用很低的工程复杂度验证这个功能是否真的有学习价值。

---

# 76. 最核心的产品判断

最容易做错成：

```text
word
↓
Brave 一搜
↓
第一张
```

真正值得做的是：

```text
word
↓
当前 sense
↓
这个 sense 能不能视觉化？
↓
什么现实场景最能表达它？
↓
把场景转成搜索引擎能理解的 query
↓
从候选里判断谁真正表达了这个 sense
↓
只在图片确实有学习价值时展示
```

因此模块更准确的名字不是：

> Vocabulary Image Search

而是：

> **Vocabulary Visual Grounding Engine**

也就是：

> 把语言知识尽可能可靠地锚定到一个可以被人快速感知和记住的视觉概念上。

---

# 77. Brave API 实现备注（2026-09-07 核对）

当前 Brave Image Search 文档：

```text
GET /res/v1/images/search
```

主要参数：

```text
q: required, max 400 chars / 50 words
count: default 50, max 200, no pagination / no offset
safesearch: strict(default) / off
spellcheck: true(default)
search_lang: default en
country: default US, supports ALL
```

结果通常含：

```text
thumbnail
original image URL
source page URL
width / height when available
title
description
publisher
```

Brave thumbnail 使用其 image proxy，标准缩略图宽度约 500px，并保持图片比例。

Brave Search API 不授予第三方内容版权，App 仍需自行处理第三方图片的使用权、缓存和再分发问题。

参考：

```text
https://api-dashboard.search.brave.com/documentation/services/image-search
https://api-dashboard.search.brave.com/api-reference/images/image_search
https://api-dashboard.search.brave.com/documentation/resources/help-feedback
```
