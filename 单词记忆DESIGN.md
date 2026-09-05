---
doc: "单词记忆DESIGN.md"
tier: "L4 专项设计"
status: "部分落地"
version: "1.0"
updated: "2026-09-02"
authority: "词汇数据模型的目标形态：Lexeme / 词形 / 词义 / 例句 / 搭配的分层"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
---

# Vocabulary Data Model Design

> **实现口径（2026-09-02）**
>
> 本文是词汇数据模型的目标形态，不是当前实现的描述。落地情况和有意的偏离见 `DECISIONS.md` D-035、D-036、D-037。
>
> - **已落地（身份层）**：§2.2 词性封闭集合、§3 `Lexeme = lemma + POS` 作为身份键、§4 不规则词形入库、§4.1「Word Form != 独立生词」、§5 一个词条挂多个词义、Principle 1/2/3。
> - **已满足但没拆表**：§6 例句绑定词义、§7 搭配独立于例句、Principle 4/5——一行 `vocabulary_details` 就是一个词义，例句和搭配本来就挂在它上面；差的只是词条内部多个词义之间的连线，而那由 (lemma, 词性) 查得出来。
> - **暂不做**：§8 发音表（走系统 TTS，没有音频源）、§10 词频（本地没有语料来源，唯一填法是让模型编一个数出来）、§18/§20/§21 三张 user_* 表（单用户 app，六维能力已在 `spelling_progress`）、§11/§51 全量拆表（要重写所有词汇界面）。
> - **§27～§50 学习材料层**：和 `词汇记忆提示DESIGN.md` 大面积重叠，冲突已按该文 §16 裁决——记忆类型以那边七类为准、记忆材料分词条级/词义级挂载、不另开 spelling_aids 表、不存 AI 自评分、关系表等到有消费方再建。
> - **拼写训练的 S0～S6 规格不在本文**，在 `拼写训练DESIGN.md`（本文件原来的内容，2026-09-02 被本模型文档覆盖后已恢复到那里）。
> - 早先那份 `单词模型DESIGN.md` 是本文 §1～§26 的子集，已删除，以本文为准。

## 1. Overview

本文档定义语言学习 App 的标准词汇数据模型。

目标不是把“一个字符串”当作一个单词，而是将词汇拆分为多个语义层级：

```text
Lexeme
├── Lemma
├── POS
├── Pronunciation
├── Word Forms
├── Senses
│   ├── Definition
│   ├── Translation
│   ├── Examples
│   └── Collocations
└── Metadata
```

同时将：

```text
词典事实数据
```

与：

```text
用户学习状态
```

严格分离。

这套模型应能够支持：

- 单词学习
- 听力识别
- 拼写训练
- 词义辨析
- 多义词学习
- 词形变化训练
- 例句学习
- 搭配学习
- CEFR 分级
- 词频排序
- SRS / 间隔重复
- 主动回忆
- 被动识别
- 后续 AI 自动生成学习材料


---

## 2. Core Concepts

### 2.1 Lemma

`lemma` 表示词典原形。

示例：

```text
running -> run
ran     -> run
cars    -> car
went    -> go
```

因此：

```text
running
```

通常不应该直接作为一个新的独立基础词汇。

它是：

```text
run
```

的某一种词形。


---

### 2.2 POS

POS = Part of Speech，即词性。

推荐使用 Universal POS 风格：

```text
NOUN
VERB
ADJ
ADV
PRON
DET
ADP
NUM
CONJ
PART
INTJ
AUX
PROPN
```

例如：

```text
record + NOUN
record + VERB
```

应当视为两个不同 Lexeme。

因为：

```text
record / NOUN
```

和：

```text
record / VERB
```

虽然拼写相同，但语法行为、发音、含义可能不同。


---

## 3. Lexeme

Lexeme 表示一个词汇单位。

推荐逻辑：

```text
Lexeme = lemma + language + POS
```

例如：

```text
run + en + VERB
run + en + NOUN
```

属于两个不同 Lexeme。

示例：

```json
{
  "id": "lexeme_run_verb",
  "language": "en",
  "lemma": "run",
  "pos": "VERB",
  "ipa": "/rʌn/",
  "cefr": "A2"
}
```


---

## 4. Word Forms

Word Form 表示一个 Lexeme 的具体变形。

例如：

```text
run
runs
ran
running
```

全部属于：

```text
run / VERB
```

示例结构：

```json
[
  {
    "form": "run",
    "features": {
      "verb_form": "base"
    }
  },
  {
    "form": "runs",
    "features": {
      "tense": "present",
      "person": "third",
      "number": "singular"
    }
  },
  {
    "form": "ran",
    "features": {
      "tense": "past"
    }
  },
  {
    "form": "running",
    "features": {
      "verb_form": "participle",
      "aspect": "progressive"
    }
  }
]
```

### 4.1 Learning Rule

默认情况下：

```text
Word Form != 独立生词
```

用户学习：

```text
run
```

之后，不应该把：

```text
ran
running
runs
```

全部重新作为全新词汇学习。

但是 Word Form 可以拥有独立能力状态，例如：

```text
用户认识 run
但不会拼写 running
```

因此可以单独记录：

```text
user_form_progress
```


---

## 5. Sense

Sense 表示一个 Lexeme 下的一个具体词义。

这是词库设计中非常关键的一层。

例如：

```text
run / VERB
```

可能包含：

```text
1. 跑
2. 运行
3. 经营
4. 延伸
5. 流淌
```

不能把这些含义全部简单塞进：

```text
translation = "跑；运行；经营；流淌"
```

否则无法准确追踪用户到底学会了哪个含义。

推荐结构：

```json
{
  "lexeme_id": "lexeme_run_verb",
  "senses": [
    {
      "id": "run_v_01",
      "definition": "move quickly on foot",
      "translation_zh": "跑；奔跑",
      "cefr": "A1",
      "frequency_rank": 1
    },
    {
      "id": "run_v_02",
      "definition": "operate or function",
      "translation_zh": "运行",
      "cefr": "A2",
      "frequency_rank": 2
    },
    {
      "id": "run_v_03",
      "definition": "manage or operate a business",
      "translation_zh": "经营；管理",
      "cefr": "B1",
      "frequency_rank": 3
    }
  ]
}
```

这样系统可以区分：

```text
用户已经知道：

run = 跑

但不知道：

run a company = 经营公司
```


---

## 6. Examples

例句应该绑定 Sense，而不是直接只绑定 Lexeme。

错误：

```text
run
  -> example1
  -> example2
```

推荐：

```text
run / VERB

Sense 1: 跑
  -> I run every morning.

Sense 2: 运行
  -> The program is running.

Sense 3: 经营
  -> She runs a restaurant.
```

推荐结构：

```json
{
  "id": "example_run_v_03_001",
  "sense_id": "run_v_03",
  "text": "She runs a small restaurant.",
  "translation_zh": "她经营一家小餐馆。",
  "cefr": "A2"
}
```


---

## 7. Collocations

搭配应独立于普通例句保存。

例如：

```text
run a company
run a business
run a restaurant
run a program
run out of time
```

推荐：

```json
{
  "id": "collocation_run_business",
  "sense_id": "run_v_03",
  "text": "run a business",
  "translation_zh": "经营企业"
}
```

Collocation 对主动输出能力尤其重要。


---

## 8. Pronunciation

Pronunciation 不建议只设计成：

```text
ipa
```

因为同一个词可能存在：

- 英式发音
- 美式发音
- 多读音
- 不同词性不同发音

例如：

```text
record / NOUN
record / VERB
```

重音位置不同。

推荐：

```json
{
  "id": "pron_record_noun_us",
  "lexeme_id": "record_noun",
  "dialect": "en-US",
  "ipa": "/ˈrekərd/",
  "audio_url": "..."
}
```

字段：

```text
id
lexeme_id
dialect
ipa
audio_url
source
```


---

## 9. CEFR

建议 CEFR 不只放在 Lexeme 层。

可以存在：

```text
Lexeme CEFR
Sense CEFR
Example CEFR
```

因为同一个单词：

```text
run
```

基础含义：

```text
跑
```

可能属于 A1。

但：

```text
run a company
```

可能属于更高等级。

因此：

```text
Lexeme.cefr
```

只能表示：

```text
推荐首次学习等级
```

而具体难度应以 Sense 为准。


---

## 10. Frequency

建议至少支持：

```text
frequency_rank
frequency_score
frequency_band
```

例如：

```json
{
  "frequency_rank": 381,
  "frequency_score": 0.873,
  "frequency_band": "high"
}
```

如果有条件，最好区分语料来源：

```text
general
spoken
written
business
academic
movie
gaming
```

未来可以支持：

```text
用户学习目标 = 日常口语

优先 spoken_frequency
```

而不是统一使用一个词频。


---

## 11. Recommended Database Tables

建议至少建立以下表：

```text
lexemes
word_forms
senses
pronunciations
examples
collocations
```

学习数据独立：

```text
user_lexeme_progress
user_sense_progress
user_form_progress
```


---

## 12. lexemes

```sql
lexemes
-------

id
language
lemma
pos
cefr
frequency_rank
created_at
updated_at
```

推荐唯一约束：

```text
(language, lemma, pos)
```

但需要注意：

少数情况下：

```text
lemma + POS
```

仍然可能存在多个不同词元。

例如真正的同形异义词。

因此生产级设计更推荐：

```text
lexeme_id
```

作为最终身份。

`lemma + POS` 只是主要检索键。


---

## 13. word_forms

```sql
word_forms
----------

id
lexeme_id
form
features_json
is_base_form
```

例如：

```text
lexeme_id = run_VERB
form       = ran
```

`features_json`：

```json
{
  "tense": "past"
}
```


---

## 14. senses

```sql
senses
------

id
lexeme_id
sense_order
definition
translation_zh
cefr
frequency_rank
register
domain
```

register 示例：

```text
formal
informal
slang
technical
literary
archaic
```

domain 示例：

```text
general
business
technology
medicine
gaming
law
finance
```


---

## 15. examples

```sql
examples
--------

id
sense_id
text
translation_zh
cefr
source_type
source
quality_score
```

`source_type`：

```text
dictionary
corpus
ai_generated
movie
game
manual
```


---

## 16. collocations

```sql
collocations
------------

id
sense_id
text
translation_zh
frequency
pattern
```

例如：

```text
run a business
```

pattern：

```text
VERB + DET + NOUN
```


---

## 17. User Progress

词库本身的数据：

```text
run
是动词
过去式 ran
含义包括“跑”“经营”
```

属于事实数据。

用户学习情况：

```text
是否认识
是否会拼
是否听得出来
多久没复习
下次什么时候复习
```

属于用户数据。

两者必须分离。


---

## 18. user_lexeme_progress

```sql
user_lexeme_progress
--------------------

user_id
lexeme_id

familiarity

recognition_score
production_score
listening_score
spelling_score

review_count
correct_count
wrong_count

last_review_at
next_review_at

created_at
updated_at
```

能力最好不要只使用：

```text
mastered = true
```

而是拆成不同维度。


---

## 19. Learning Dimensions

推荐至少追踪：

```text
meaning_recognition
meaning_recall
listening_recognition
pronunciation
spelling
context_understanding
production
```

例如：

```json
{
  "meaning_recognition": 0.95,
  "meaning_recall": 0.78,
  "listening_recognition": 0.52,
  "spelling": 0.35,
  "production": 0.41
}
```

这比：

```text
learned = true
```

更适合真实语言学习。


---

## 20. user_sense_progress

```sql
user_sense_progress
-------------------

user_id
sense_id

recognition_score
recall_score
production_score

review_count
last_review_at
next_review_at
```

这样系统可以知道：

```text
run

Sense 1 跑
掌握度 95%

Sense 2 运行
掌握度 80%

Sense 3 经营
掌握度 30%
```


---

## 21. user_form_progress

词形可以单独追踪。

例如：

```text
run
ran
running
```

用户可能：

```text
知道 ran 是 run 的过去式
```

但是：

```text
无法主动拼写 ran
```

建议：

```sql
user_form_progress
------------------

user_id
word_form_id

recognition_score
spelling_score
production_score

review_count
last_review_at
next_review_at
```


---

## 22. Recommended API Object

客户端获取一个词汇时，可以返回：

```json
{
  "id": "lexeme_run_verb",
  "language": "en",
  "lemma": "run",
  "pos": "VERB",

  "pronunciations": [
    {
      "dialect": "en-US",
      "ipa": "/rʌn/",
      "audio_url": "..."
    }
  ],

  "cefr": "A2",

  "frequency": {
    "general_rank": 381,
    "spoken_rank": 210
  },

  "forms": [
    {
      "form": "runs",
      "features": {
        "person": 3,
        "number": "singular"
      }
    },
    {
      "form": "ran",
      "features": {
        "tense": "past"
      }
    },
    {
      "form": "running",
      "features": {
        "verb_form": "participle"
      }
    }
  ],

  "senses": [
    {
      "id": "run_v_01",
      "translation": "跑；奔跑",
      "definition": "move quickly on foot",
      "cefr": "A1",

      "examples": [
        {
          "text": "I run every morning.",
          "translation": "我每天早上跑步。"
        }
      ]
    },

    {
      "id": "run_v_02",
      "translation": "运行",
      "definition": "operate or function",
      "cefr": "A2",

      "examples": [
        {
          "text": "The program is running.",
          "translation": "程序正在运行。"
        }
      ]
    }
  ]
}
```


---

## 23. MVP Design

第一版不建议一次做得过于复杂。

MVP 可以只保留：

```text
Lexeme
├── lemma
├── POS
├── pronunciation
├── CEFR
├── forms[]
└── senses[]
    ├── translation
    ├── definition
    └── examples[]
```

数据库：

```text
lexemes
word_forms
senses
examples
user_lexeme_progress
user_sense_progress
```

第一阶段可以暂时不做：

```text
collocations
domain frequency
register
multiple corpus frequency
complex grammar features
```


---

## 24. Critical Design Principles

### Principle 1

不要使用：

```text
word string = vocabulary identity
```

应该使用：

```text
lexeme_id = vocabulary identity
```


### Principle 2

不要把所有变形都作为独立生词。

```text
running
ran
runs
```

应该关联：

```text
run / VERB
```


### Principle 3

不要把所有中文释义放进一个 translation 字符串。

应该拆分：

```text
Sense
```


### Principle 4

例句必须尽可能关联具体 Sense。

否则例句无法准确解释对应词义。


### Principle 5

学习状态和词典事实必须分开。

禁止：

```sql
words.learned
words.review_count
words.next_review_at
```

推荐：

```text
user_*_progress
```


### Principle 6

“学会一个单词”不是 Boolean。

应该至少区分：

```text
认识
理解
听懂
拼写
主动回忆
主动使用
```


---

## 25. Recommended Entity Relationship

```text
Lexeme
   │
   ├───────────── Pronunciation
   │
   ├───────────── WordForm
   │
   └───────────── Sense
                     │
                     ├──────── Example
                     │
                     └──────── Collocation


User
 │
 ├──────── UserLexemeProgress
 │
 ├──────── UserSenseProgress
 │
 └──────── UserFormProgress
```


---

## 26. Final Recommended Model

完整结构：

```text
Vocabulary System

Lexeme
├── lemma
├── language
├── POS
├── CEFR
├── frequency
│
├── Pronunciations
│
├── Forms
│
└── Senses
    ├── Definition
    ├── Translation
    ├── CEFR
    ├── Frequency
    ├── Register
    ├── Domain
    ├── Examples
    └── Collocations


Learning System

UserLexemeProgress
├── recognition
├── listening
├── spelling
├── production
└── SRS

UserSenseProgress
├── recognition
├── recall
└── production

UserFormProgress
├── recognition
├── spelling
└── production
```

这套模型可以作为后续：

```text
单词卡
听力训练
拼写训练
例句生成
语法训练
AI 辅助记忆
SRS
词汇等级测试
```

的统一词汇底层数据模型。


---

## 27. Learning Material Layer

除了词典事实数据与用户学习状态之外，系统应增加独立的：

```text
Learning Material Layer
```

用于保存围绕词汇生成或整理的学习材料。

推荐整体分层：

```text
Dictionary Layer
├── Lexeme
├── Sense
├── WordForm
├── Pronunciation
└── Frequency / CEFR / Register / Domain

Learning Material Layer
├── Example
├── Collocation
├── Phrase
├── Idiom
├── PhrasalVerb
├── WordFamily
├── Contrast
├── MemoryAid
└── LearningPackage

User Learning Layer
├── UserLexemeProgress
├── UserSenseProgress
├── UserFormProgress
├── UserPhraseProgress
└── ReviewHistory
```

设计原则：

```text
词典数据描述“这个词是什么”

学习资料描述“应该如何理解和记忆这个词”

用户数据描述“用户目前掌握到了什么程度”
```


---

## 28. Phrase

> 裁决：Principle 8（认识词条 ≠ 掌握固定表达）已经满足——表达在库里就是独立条目、独立复习进度。
> 但 **`lexeme_id` 改为可选**：情景演练里存下来的"回头我发你"这类整句没有母词，
> 强行归到某个词头上只会制造错误归属。由生成新词顺带产出的短语才挂词条。

Phrase 表示具有独立学习价值的常用短句、固定表达或公式化表达。

它与 Example 不同。

Example 的主要作用是：

```text
展示某个 Sense 在完整语境中的使用方式
```

Phrase 的主要作用是：

```text
让用户直接学习可复用的表达单位
```

例如学习：

```text
mind
```

可以包含：

```text
Never mind.
Do you mind?
Keep that in mind.
I don't mind.
Would you mind if...?
```

这些表达不应该仅仅作为普通例句保存。

推荐结构：

```sql
phrases
-------

id
lexeme_id
sense_id

text
translation

type
cefr
frequency_rank
scenario

register
source_type
quality_score

created_at
updated_at
```

其中：

```text
lexeme_id
```

必填。

```text
sense_id
```

在可以明确绑定具体词义时填写。

`type` 推荐：

```text
conversation_phrase
fixed_expression
formulaic_expression
phrasal_expression
response_phrase
sentence_pattern
```

`scenario` 推荐：

```text
daily
work
business
travel
dating
school
gaming
technology
movie
social
restaurant
shopping
emergency
```

示例：

```json
{
  "id": "phrase_mind_keep_in_mind",
  "lexeme_id": "mind_noun",
  "sense_id": "mind_n_01",
  "text": "Keep that in mind.",
  "translation": "记住这一点。",
  "type": "fixed_expression",
  "cefr": "B1",
  "frequency_rank": 1,
  "scenario": "daily"
}
```


---

## 29. Phrase vs Example vs Collocation

三者必须区分。

### Example

完整句子，用来解释词义和语法。

```text
She runs a small restaurant.
```

主要绑定：

```text
Sense
```


### Collocation

词与词之间的高频搭配。

```text
run a business
make a decision
heavy rain
strong evidence
```

通常是不完整句。


### Phrase

具有独立交流价值、可以直接记忆和输出的表达。

```text
Never mind.
Keep that in mind.
That makes sense.
It depends.
```

推荐关系：

```text
Sense
├── Examples
├── Collocations
└── Phrases
```


---

## 30. Idiom

习语需要与普通 Phrase 区分。

例如：

```text
break the ice
hit the nail on the head
piece of cake
```

其含义通常不能简单通过组成词推导。

推荐：

```sql
idioms
------

id
text
translation
definition

cefr
frequency_rank
register
scenario

literal_meaning
usage_note

source_type
quality_score
```

如果某个习语与某个 Lexeme 强关联，可以增加：

```text
primary_lexeme_id
```

但不建议强制所有习语绑定单一 Lexeme。


---

## 31. Phrasal Verb

英语学习中建议将短语动词作为独立学习实体。

例如：

```text
give up
look after
run out
figure out
pick up
```

不能仅作为普通 collocation 处理，因为：

```text
give
```

和：

```text
give up
```

在语义和语法行为上可能差别很大。

推荐：

```sql
phrasal_verbs
-------------

id
base_lexeme_id

text
translation
definition

separable
transitive

cefr
frequency_rank
register

created_at
updated_at
```

例如：

```json
{
  "text": "give up",
  "base_lexeme_id": "give_verb",
  "translation": "放弃",
  "separable": false,
  "transitive": true,
  "cefr": "A2"
}
```

如果需要支持多个词义，应进一步：

```text
PhrasalVerb
└── PhrasalVerbSense[]
```


---

## 32. Word Family

Word Family 用于建立词族关联。

例如：

```text
act
action
active
actively
activity
activate
activation
```

或：

```text
territory
territorial
territorially
```

推荐不要简单存成：

```text
word_family = "xxx, xxx, xxx"
```

而应建立关系表：

```sql
lexeme_relations
----------------

id
source_lexeme_id
target_lexeme_id
relation_type
weight
note
```

`relation_type` 可以包括：

```text
derivation
same_root
synonym
antonym
related
confusable
formal_variant
informal_variant
```

例如：

```text
territory
  └── territorial

relation_type = derivation
```

对于仅用于辅助记忆、但不是严格派生关系的词，可以使用：

```text
same_root
related
```


---

## 33. Contrast / Confusable Words

> 裁决：暂不建 `contrasts` / `lexeme_relations` 关系表（`词汇记忆提示DESIGN.md` §16.5）。
> 易混词目前依附在记忆提示里。关系表的价值在于双向可查和易混词专项训练，
> 在有这个消费方之前，一张只写不读的表只会在词条删除时留下悬空引用。

易混词应该作为独立学习资料保存。

例如：

```text
territory
area
region
district
zone
```

用户真正需要的不是：

```text
这些词都有“区域”的意思
```

而是理解：

```text
它们什么时候不能互换
```

推荐：

```sql
contrasts
---------

id

lexeme_id
target_lexeme_id

summary
difference
usage_rule

example_a
example_b

cefr
quality_score
```

例如：

```json
{
  "lexeme_id": "territory_noun",
  "target_lexeme_id": "area_noun",
  "summary": "territory 强调归属、控制或势力范围；area 泛指区域",
  "usage_rule": "涉及国家、动物地盘、控制范围时更常使用 territory。"
}
```

该模型可以扩展到：

```text
affect vs effect
borrow vs lend
say vs tell
economic vs economical
```


---

## 34. Memory Aid

> 裁决：记忆材料的类型集合、生成规则和展示口径以 `词汇记忆提示DESIGN.md` 为准（该文 §16.1）。
> 本节的 `lexeme_id` / `sense_id` 分层被采纳，落法见该文 §16.2：构词、词形、发音挂词条，
> 场景、对比、搭配、联想挂词义。

Memory Aid 表示帮助用户形成长期记忆的辅助材料。

不要仅设计：

```text
mnemonic: string
```

因为不同单词适合完全不同的记忆方式。

推荐：

```sql
memory_aids
-----------

id

lexeme_id
sense_id

type
title
content

priority
quality_score
confidence

source_type

created_at
updated_at
```

其中：

```text
sense_id
```

可选。

当记忆材料针对整个词时：

```text
sense_id = null
```

针对特定词义时绑定具体 Sense。


---

## 35. Memory Aid Types

推荐支持：

```text
semantic_scene
context
collocation

root
prefix_suffix
etymology
word_family

contrast

visual
association
story
sound_mnemonic
spelling_pattern
```

优先级建议如下：

```text
1. semantic_scene
2. context
3. collocation
4. root / prefix_suffix
5. word_family
6. contrast
7. visual
8. association
9. story
10. sound_mnemonic
```

原因：

语言学习的目标应尽量建立：

```text
英语声音 / 拼写
        ↓
   概念 / 场景
```

而不是长期依赖：

```text
英语
 ↓
中文谐音
 ↓
中文
 ↓
含义
```

因此：

```text
sound_mnemonic
```

只应作为辅助记忆手段，而不是默认策略。


---

## 36. Semantic Scene

Semantic Scene 是推荐优先使用的记忆材料。

它使用一个清晰、具体的概念场景把词与含义绑定。

例如：

```text
territory
```

可以对应：

```text
一只狼站在森林边界附近巡视，
阻止其他狼进入自己的地盘。
```

该场景直接表达：

```text
属于某个人、动物或国家并被控制的区域
```

而不是通过中文谐音间接记忆。

推荐：

```json
{
  "type": "semantic_scene",
  "lexeme_id": "territory_noun",
  "content": "一只狼在森林边界巡视并驱赶闯入者，这片森林是它的 territory。"
}
```


---

## 37. Root / Prefix / Suffix Memory

对于词根关系明确的词，可以生成结构化词根记忆。

例如：

```text
territory

terr / terra = earth, land
```

推荐保存结构化数据，而不是只保存说明文字：

```json
{
  "type": "root",
  "lexeme_id": "territory_noun",
  "components": [
    {
      "form": "terr",
      "meaning": "earth / land"
    }
  ],
  "explanation": "与土地、地面相关，因此 territory 表示一定范围内的土地或控制区域。"
}
```

注意：

不得为了生成记忆材料而强行拆词。

如果词源或词根关系不能确认：

```text
不要生成伪词根分析。
```


---

## 38. Etymology

词源与“为了方便记忆而拆词”必须分开。

推荐：

```sql
etymologies
-----------

id
lexeme_id

origin_language
origin_form
origin_meaning
development
source
confidence
```

例如词源材料只有在：

```text
来源可靠
关系明确
对学习有帮助
```

时才推荐展示。

AI 生成内容时：

```text
不能把未经证实的字母拆分描述成真实词源。
```


---

## 39. Story Memory

Story 适合：

```text
抽象词
难拼词
易忘词
缺少明显词根线索的词
```

推荐故事必须：

```text
短
强画面感
只突出一个核心含义
尽量包含目标单词
```

示例：

```text
territory

两只狼每天巡视森林边界。
陌生狼刚跨过去，它们立刻冲过去驱赶。
因为这里是它们的 territory —— 地盘。
```

避免：

```text
为了塞入每个字母而制造非常复杂的故事。
```


---

## 40. Visual Memory

Visual Memory 可以保存：

```text
图片生成提示
图片 URL
核心视觉概念
```

推荐：

```sql
visual_memories
---------------

id
lexeme_id
sense_id

visual_concept
image_prompt
image_url

quality_score
```

例如：

```json
{
  "lexeme_id": "territory_noun",
  "visual_concept": "一只狼守护有明显边界的森林区域",
  "image_prompt": "A wolf guarding the boundary of its forest territory..."
}
```

视觉应直接表达词义，而不是依赖图片中的文字。


---

## 41. Sound Mnemonic

Sound Mnemonic 为谐音辅助。

示例：

```text
territory
→ “特瑞垂”
```

只能用于：

```text
初次快速建立记忆钩子
```

不应作为最终记忆目标。

推荐：

```json
{
  "type": "sound_mnemonic",
  "lexeme_id": "territory_noun",
  "content": "使用发音近似建立临时联想。",
  "priority": 10
}
```

系统可以根据用户习惯决定是否展示。


---

## 42. Spelling Memory

> 裁决：不另开 `spelling_aids` 表（`词汇记忆提示DESIGN.md` §16.3）——这份数据已经有两个准确的家：
> 词固有的拼写事实在 `vocabulary_details`，这个人的错误历史在 `spelling_progress` / `spelling_attempts`。
> 本节"记忆拆分 ≠ 真实形态学"那条警告已采纳，见该文 §3.1 和 `拼写训练DESIGN.md`。

拼写记忆应与语义记忆分开。

例如：

```text
necessary
```

用户可能：

```text
知道是什么意思
```

但不会拼写。

推荐保存：

```sql
spelling_aids
-------------

id
lexeme_id

type
content
difficulty
```

类型：

```text
chunking
morphology
pattern
contrast
missing_letters
```

例如：

```text
territory
→ terr + itory
```

只有在该拆分用于拼写辅助时，可以标记：

```text
type = chunking
```

但不能因此声称：

```text
itory
```

是真实词根或后缀。

需要严格区分：

```text
记忆拆分
```

与：

```text
真实形态学结构
```


---

## 43. Learning Package

前端学习一个词时，不应该临时从多个表随机拼数据。

建议后端构建统一的：

```text
Learning Package
```

它是面向学习场景的聚合对象。

例如：

```json
{
  "lexeme": {
    "lemma": "territory",
    "pos": "NOUN",
    "ipa": "/ˈterətɔːri/"
  },

  "core_sense": {
    "translation": "领土；领地；地盘",
    "definition": "an area controlled by a person, animal, organization, or country"
  },

  "semantic_scene": {
    "text": "一只狼守护自己森林的边界。"
  },

  "examples": [
    {
      "text": "Wolves defend their territory.",
      "translation": "狼会保护自己的领地。"
    }
  ],

  "collocations": [
    "enemy territory",
    "disputed territory",
    "defend one's territory"
  ],

  "phrases": [
    {
      "text": "You're in enemy territory.",
      "translation": "你进入敌方地盘了。"
    }
  ],

  "word_family": [
    "territorial"
  ],

  "contrasts": [
    {
      "word": "area",
      "difference": "area 泛指区域；territory 通常带有归属或控制含义。"
    }
  ],

  "memory_aids": [
    {
      "type": "root",
      "content": "terr / terra 与土地有关"
    },
    {
      "type": "story",
      "content": "狼守护森林边界，因为这里是它的 territory。"
    }
  ]
}
```


---

## 44. Learning Package Selection Rules

Learning Package 不应该无限堆内容。

推荐首次学习默认展示：

```text
1 个核心 Sense
1 个 Semantic Scene
1 个高质量核心例句
2～4 个 Collocations
1～3 个高频 Phrases
0～2 个 Memory Aids
```

其他内容按需展开：

```text
更多词义
词族
易混词
词源
更多例句
更多短语
```

目标是：

```text
第一次学习时降低认知负荷
```

而不是：

```text
一次把词典所有信息全部展示给用户
```


---

## 45. Phrase Learning Progress

短语和固定表达需要独立学习进度。

例如用户认识：

```text
mind
```

不代表用户一定掌握：

```text
Never mind.
Keep in mind.
Would you mind if...?
```

因此推荐：

```sql
user_phrase_progress
--------------------

user_id
phrase_id

recognition_score
listening_score
production_score

review_count
correct_count
wrong_count

last_review_at
next_review_at
```


---

## 46. Material Quality Metadata

> 裁决：`source_type` / `generator` / `generator_version` 采纳（换模型或改提示词后要能批量重刷）；
> `quality_score` / `confidence` 不采纳——那两个数只能由生成它的模型自己打，等于把自评当事实存库。
> 真实的质量信号是 §14 那种用户行为数据。详见 `词汇记忆提示DESIGN.md` §16.4。

AI 生成学习材料后，必须支持质量管理。

推荐所有生成型内容包含：

```text
source_type
generator
generator_version
quality_score
confidence
review_status
```

例如：

```json
{
  "source_type": "ai_generated",
  "generator": "llm",
  "generator_version": "v3",
  "quality_score": 0.91,
  "confidence": 0.88,
  "review_status": "auto_approved"
}
```

`review_status`：

```text
pending
auto_approved
human_approved
rejected
```

这样未来可以：

```text
重新生成低质量材料
版本升级后批量刷新
人工审核重点词汇
```


---

## 47. Material Personalization

学习资料可以根据用户目标动态选择。

例如用户偏好：

```text
日常英语
```

优先：

```text
spoken examples
daily phrases
conversation collocations
```

用户偏好：

```text
技术英语
```

优先：

```text
technology domain
documentation examples
technical collocations
```

用户偏好：

```text
影视 / 游戏
```

优先：

```text
dialogue style examples
gaming scenario
movie scenario
```

因此学习材料表最好统一支持：

```text
scenario
domain
register
difficulty
frequency
```


---

## 48. Recommended Learning Sequence

推荐一个新词的首次学习顺序：

```text
Step 1
声音 + 拼写 + 核心词义

Step 2
Semantic Scene

Step 3
核心例句

Step 4
高频 Collocations

Step 5
常用 Phrase

Step 6
主动回忆

Step 7
听力识别

Step 8
拼写回忆

Step 9
根据错误情况补充 Memory Aid
```

注意：

Memory Aid 不一定需要第一次全部展示。

更好的策略是：

```text
用户记得住
→ 不增加辅助材料

用户连续记错
→ 加入 Visual / Root / Contrast

仍然记错
→ 加入 Story / Association

拼写持续错误
→ 使用 Spelling Aid
```


---

## 49. Adaptive Memory Strategy

记忆策略应根据错误类型选择。

例如：

```text
错误：不知道什么意思
→ Semantic Scene / Context

错误：几个近义词混淆
→ Contrast

错误：词形记不住
→ Morphology / Word Family

错误：听到认不出来
→ Pronunciation + Listening Example

错误：会认不会拼
→ Spelling Aid

错误：知道意思但不会使用
→ Collocation + Phrase + Production Exercise
```

不要对所有错误统一使用：

```text
再看一遍释义
```


---

## 50. Final Extended Architecture

完整推荐架构：

```text
Vocabulary System

Dictionary Layer
│
├── Lexeme
│   ├── Lemma
│   ├── POS
│   ├── CEFR
│   ├── Frequency
│   └── Metadata
│
├── Sense
├── WordForm
├── Pronunciation
└── LexemeRelation


Learning Material Layer
│
├── Example
├── Collocation
├── Phrase
├── Idiom
├── PhrasalVerb
├── Contrast
│
├── MemoryAid
│   ├── SemanticScene
│   ├── Context
│   ├── Root
│   ├── PrefixSuffix
│   ├── Etymology
│   ├── WordFamily
│   ├── Visual
│   ├── Association
│   ├── Story
│   ├── SoundMnemonic
│   └── SpellingAid
│
└── LearningPackage


User Learning Layer
│
├── UserLexemeProgress
├── UserSenseProgress
├── UserFormProgress
├── UserPhraseProgress
└── ReviewHistory
```


---

## 51. Extended MVP Recommendation

对于 MVP，不建议一次实现所有表。

建议第一阶段增加：

```text
phrases
lexeme_relations
memory_aids
user_phrase_progress
```

形成：

```text
lexemes
word_forms
senses
pronunciations
examples
collocations
phrases
lexeme_relations
memory_aids

user_lexeme_progress
user_sense_progress
user_form_progress
user_phrase_progress
```

其中 Memory Aid 初期只支持：

```text
semantic_scene
root
contrast
story
spelling_pattern
```

已经足够支撑完整的词汇学习体验。


---

## 52. Extended Critical Principles

### Principle 7

Example、Collocation、Phrase 是三个不同概念。

不得全部塞进：

```text
examples[]
```


### Principle 8

用户认识一个 Lexeme，不代表掌握它的固定表达。

Phrase 应有独立学习进度。


### Principle 9

记忆材料必须根据词的特点生成。

不得对所有词强制：

```text
词根拆解
谐音
故事
```


### Principle 10

未经确认的词源关系不得作为事实展示。

必须区分：

```text
真实词源 / 形态学
```

与：

```text
人为记忆拆分
```


### Principle 11

首次学习内容必须限制数量。

Learning Package 的目标是：

```text
选择最值得学的信息
```

而不是：

```text
展示数据库中的所有信息
```


### Principle 12

记忆辅助应该是自适应的。

系统应根据用户具体错误类型决定：

```text
下一次提供什么帮助
```


---

## 53. Recommended Final Mental Model

最终不要把整个系统理解为：

```text
单词表
```

而应该理解为：

```text
Vocabulary Knowledge Graph
        +
Learning Material System
        +
Adaptive Memory System
        +
User Mastery Model
```

一个词汇节点可以连接：

```text
词义
词形
发音
例句
搭配
短句
习语
短语动词
词族
近义词
反义词
易混词
场景
图片
故事
词根
拼写提示
```

然后根据用户当前掌握状态，动态决定：

```text
现在最应该展示什么
下一题应该测试什么
下一次复习应该强化什么
```

这比传统：

```text
word + translation + example
```

的数据模型更适合作为现代语言学习 App 的底层架构。