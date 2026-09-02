# Vocabulary Data Model Design

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

# 2. Core Concepts

## 2.1 Lemma

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

## 2.2 POS

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

# 3. Lexeme

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

# 4. Word Forms

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

## 4.1 Learning Rule

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

# 5. Sense

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

# 6. Examples

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

# 7. Collocations

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

# 8. Pronunciation

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

# 9. CEFR

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

# 10. Frequency

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

# 11. Recommended Database Tables

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

# 12. lexemes

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

# 13. word_forms

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

# 14. senses

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

# 15. examples

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

# 16. collocations

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

# 17. User Progress

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

# 18. user_lexeme_progress

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

# 19. Learning Dimensions

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

# 20. user_sense_progress

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

# 21. user_form_progress

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

# 22. Recommended API Object

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

# 23. MVP Design

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

# 24. Critical Design Principles

## Principle 1

不要使用：

```text
word string = vocabulary identity
```

应该使用：

```text
lexeme_id = vocabulary identity
```


## Principle 2

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


## Principle 3

不要把所有中文释义放进一个 translation 字符串。

应该拆分：

```text
Sense
```


## Principle 4

例句必须尽可能关联具体 Sense。

否则例句无法准确解释对应词义。


## Principle 5

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


## Principle 6

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

# 25. Recommended Entity Relationship

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

# 26. Final Recommended Model

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