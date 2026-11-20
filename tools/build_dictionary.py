# -*- coding: utf-8 -*-
"""生成 app/src/main/assets/dictionary_en_zh.db —— 内置离线英汉词典的基础包（DECISIONS.md D-095）。

两份数据：

1. **ECDICT**（skywind3000/ECDICT，MIT）：词形、中文释义、考试标签、牛津 3000、柯林斯星级、
   词频排名、变形表。释义按词性分行，一行里用「,」「;」「；」分隔义项。
2. **CMUdict**（cmusphinx/cmudict，BSD 2-Clause）：美式发音，ARPAbet 转成 App 用的 IPA 写法
   （`/ˈrekərd/`，元音带长音符 iː / uː / ɑː / ɔː，r 写作 r）。查不到的词退回 ECDICT 原音标，
   并在 `ipa_source` 标 `ecdict`。

**义项颗粒度和词典对齐**（D-095 第 2 条）：一行里按顶层分隔符切开，一项就是一个义项，
括号里的逗号不切（「使(马,鹰等)戴头罩」是一项）。vt. / vi. / v. 都并进 VERB，
同一 (原型, 词性) 下文字完全相同的义项只留第一次出现的那个——「记录」在 vt. 和 vi. 下各出现一次，
那是同一个义项写了两遍，不是两个。领域行（[计] [医] [网络]…）和「go的过去式」这类说明行不收：
前者不是给学习者的义项，后者由 `forms` 表表达。

产物是只读 SQLite，**由本脚本生成，不要手改**。表结构：

    meta(key, value)                         来源、许可、包 id、版本、词条数
    words(lemma, ipa, ipa_source, frq_rank, tags, oxford, collins)
    senses(lemma, pos, sense_order, meaning_zh)
    forms(form, lemma, kind, irregular)      kind 取 ECDICT exchange 的类别字母

一个包一个 db 文件（D-095 第 5 条），本脚本只出基础包 `base`。词典口径变了就把 PACK_VERSION 加一，
App 按版本号决定是否覆盖本地副本、是否重跑老卡回填。

用法：
    python tools/build_dictionary.py                                   # 联网下载两份数据
    python tools/build_dictionary.py --ecdict ecdict.csv --cmudict cmudict.dict
"""
from __future__ import print_function

import argparse
import csv
import io
import os
import re
import sqlite3
import sys
import tempfile
import urllib.request

ECDICT_URL = "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv"
CMUDICT_URL = "https://raw.githubusercontent.com/cmusphinx/cmudict/master/cmudict.dict"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "dictionary_en_zh.db")

PACK_ID = "base"
PACK_VERSION = 1

# 没有考试标签、牛津、柯林斯的词，词频排名在这以内才收。30000 大约留下 3.8 万个词条，
# 再往后基本是专业词和变体拼写，查词时退回 AI 讲解就行。
DEFAULT_RANK_LIMIT = 30000

LEMMA_RE = re.compile(r"^[A-Za-z][A-Za-z'\- ]*[A-Za-z]$|^[AaI]$")

# ---------------------------------------------------------------------------
# 词性

# 与 domain/vocabulary/PartOfSpeech.parse 的别名表保持一致；认不出来的行整行不收。
POS_ALIASES = {
    "n": "NOUN", "noun": "NOUN",
    "v": "VERB", "vi": "VERB", "vt": "VERB", "verb": "VERB",
    "a": "ADJ", "adj": "ADJ",
    "ad": "ADV", "adv": "ADV",
    "pron": "PRON",
    "art": "DET", "det": "DET",
    "prep": "ADP",
    "num": "NUM",
    "conj": "CONJ",
    "int": "INTJ", "interj": "INTJ",
    "aux": "AUX",
}

POS_PREFIX_RE = re.compile(r"^((?:[a-z]+\.\s*&?\s*)+)(.*)$")


def parse_line(line):
    """一行释义 → (词性 wire, 正文)。认不出词性返回 (None, 原文)。"""
    line = line.strip()
    m = POS_PREFIX_RE.match(line)
    if not m:
        return None, line
    tokens = [t for t in re.split(r"[.&\s]+", m.group(1)) if t]
    if not tokens or any(t not in POS_ALIASES for t in tokens):
        return None, line
    # 「vt.& vi.」这类都落在同一个词性上；「v.& n.」这种跨词性的少见，取第一个。
    return POS_ALIASES[tokens[0]], m.group(2).strip()


# ---------------------------------------------------------------------------
# 义项切分

OPEN = "([（【<《"
CLOSE = ")]）】>》"
SEPARATORS = ",;，；"


def split_senses(text):
    """按顶层分隔符切义项，括号里的分隔符不切。"""
    out, buf, depth = [], [], 0
    for ch in text:
        if ch in OPEN:
            depth += 1
        elif ch in CLOSE:
            depth = max(0, depth - 1)
        if ch in SEPARATORS and depth == 0:
            out.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    out.append("".join(buf))
    return [s.strip() for s in out if s.strip()]


# 「亚伦（男子名）」「艾迪生（姓氏）」：小写拼写的人名靠词频混进来，学习者不需要它们当义项。
# 地名不在这里排：China / Paris 这类是要教的，而且大写词条本来只收带标签的。
NAME_SENSE_RE = re.compile(r"[（(][^）)]*(人名|姓氏|男子名|女子名|男名|女名)")
CJK_RE = re.compile(r"[一-鿿]")


def usable_sense(sense):
    """人名义项和没有一个汉字的英文释义都不收。"""
    return bool(CJK_RE.search(sense)) and not NAME_SENSE_RE.search(sense)


def senses_of(translation, multiword):
    """ECDICT translation 字段 → [(词性, [义项...])]，词性按首次出现排序。"""
    grouped = {}
    order = []
    for raw in translation.replace("\\n", "\n").split("\n"):
        raw = raw.strip()
        if not raw or raw.startswith("["):
            continue
        pos, body = parse_line(raw)
        if pos is None:
            # 短语的释义行通常不带词性前缀；单词不带前缀的多是「go的过去式」这类说明行。
            if not multiword:
                continue
            pos = "PHRASE"
        if pos not in grouped:
            grouped[pos] = []
            order.append(pos)
        for sense in split_senses(body):
            if usable_sense(sense) and sense not in grouped[pos]:
                grouped[pos].append(sense)
    return [(p, grouped[p]) for p in order if grouped[p]]


# ---------------------------------------------------------------------------
# 音标

ARPABET_VOWELS = {
    "AA": "ɑː", "AE": "æ", "AW": "aʊ", "AY": "aɪ", "EH": "e", "EY": "eɪ",
    "IH": "ɪ", "IY": "iː", "OW": "oʊ", "OY": "ɔɪ", "UH": "ʊ", "UW": "uː",
    "AO": "ɔː",
    # AH / ER 的读法跟重音走：重读 ʌ / ɜːr，弱读 ə / ər。
}
ARPABET_CONSONANTS = {
    "B": "b", "CH": "tʃ", "D": "d", "DH": "ð", "F": "f", "G": "ɡ", "HH": "h",
    "JH": "dʒ", "K": "k", "L": "l", "M": "m", "N": "n", "NG": "ŋ", "P": "p",
    "R": "r", "S": "s", "SH": "ʃ", "T": "t", "TH": "θ", "V": "v", "W": "w",
    "Y": "j", "Z": "z", "ZH": "ʒ",
}

# 合法的英语词首辅音丛，用来决定重音符放在哪个辅音前面（最大首音原则的简化版）。
LEGAL_ONSETS = {
    ("P", "L"), ("P", "R"), ("P", "Y"), ("B", "L"), ("B", "R"), ("B", "Y"),
    ("T", "R"), ("T", "W"), ("T", "Y"), ("D", "R"), ("D", "W"), ("D", "Y"),
    ("K", "L"), ("K", "R"), ("K", "W"), ("K", "Y"), ("G", "L"), ("G", "R"),
    ("G", "W"), ("G", "Y"), ("F", "L"), ("F", "R"), ("F", "Y"), ("TH", "R"),
    ("TH", "W"), ("SH", "R"), ("S", "L"), ("S", "W"), ("S", "P"), ("S", "T"),
    ("S", "K"), ("S", "M"), ("S", "N"), ("S", "F"), ("V", "Y"), ("M", "Y"),
    ("N", "Y"), ("HH", "Y"), ("L", "Y"),
    ("S", "P", "L"), ("S", "P", "R"), ("S", "T", "R"), ("S", "K", "R"),
    ("S", "K", "W"), ("S", "K", "L"), ("S", "P", "Y"), ("S", "K", "Y"),
}


def _onset_length(consonants):
    """辅音串里，最后多少个能做下一个音节的首音。"""
    for n in range(min(3, len(consonants)), 1, -1):
        if tuple(consonants[-n:]) in LEGAL_ONSETS:
            return n
    return 1 if consonants else 0


def arpabet_to_ipa(phones):
    """['R', 'EH1', 'K', 'ER0', 'D'] → '/ˈrekərd/'。单音节词不标重音。"""
    vowels = [p for p in phones if p[-1].isdigit()]
    mark_stress = len(vowels) > 1
    out = []
    pending = []  # 还没写出去的辅音
    for p in phones:
        if not p[-1].isdigit():
            pending.append(p)
            continue
        base, stress = p[:-1], p[-1]
        if base == "AH":
            vowel = "ʌ" if stress == "1" else "ə"
        elif base == "ER":
            vowel = "ɜːr" if stress == "1" else "ər"
        else:
            vowel = ARPABET_VOWELS[base]
        mark = {"1": "ˈ", "2": "ˌ"}.get(stress, "") if mark_stress else ""
        if mark and out:
            k = _onset_length(pending)
            coda, onset = pending[:len(pending) - k], pending[len(pending) - k:]
        else:
            coda, onset = [], pending
        out.extend(ARPABET_CONSONANTS[c] for c in coda)
        out.append(mark)
        out.extend(ARPABET_CONSONANTS[c] for c in onset)
        out.append(vowel)
        pending = []
    out.extend(ARPABET_CONSONANTS[c] for c in pending)
    return "/" + "".join(out) + "/"


def load_cmudict(path):
    """词（小写）→ 第一个读音的 IPA。同一个词的多个读音只取第一个：

    record 的名词和动词读音不同，但 CMUdict 不标词性，没法按词性选，
    这是已知限制（D-095 实现记录）。"""
    out = {}
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            head, *phones = line.split()
            if "(" in head:
                continue
            word = head.lower()
            if word in out:
                continue
            try:
                out[word] = arpabet_to_ipa(phones)
            except KeyError:
                continue
    return out


def ecdict_ipa(phonetic):
    """ECDICT 的音标偏英式，写法也不统一（ә 用的是西里尔字母、重音用撇号）。只做最小规整。"""
    text = phonetic.strip().replace("ә", "ə").replace("'", "ˈ").replace(",", "ˌ").replace(":", "ː")
    return "/" + text + "/" if text else ""


# ---------------------------------------------------------------------------
# 变形

EXCHANGE_KINDS = set("pdi3rst")


def regular_forms(lemma, kind):
    """按规则能推出来的变形。推得出来的就不是"不规则变形"，不进卡片的 forms 字段。"""
    w = lemma.lower()
    c = w[-1] if w else ""
    double = w + c if len(w) >= 3 and c not in "aeiouwxy" and w[-2] in "aeiou" and w[-3] not in "aeiou" else w
    if kind == "s" or kind == "3":
        if re.search(r"(s|x|z|ch|sh|o)$", w):
            return {w + "es", w + "s"}
        if re.search(r"[^aeiou]y$", w):
            return {w[:-1] + "ies"}
        return {w + "s"}
    if kind in ("p", "d"):
        if w.endswith("e"):
            return {w + "d"}
        if re.search(r"[^aeiou]y$", w):
            return {w[:-1] + "ied"}
        return {w + "ed", double + "ed", w + "ked"}
    if kind == "i":
        if w.endswith("ie"):
            return {w[:-2] + "ying"}
        if w.endswith("e") and not w.endswith("ee"):
            return {w[:-1] + "ing", w + "ing"}
        return {w + "ing", double + "ing", w + "king"}
    if kind in ("r", "t"):
        suffix = "er" if kind == "r" else "est"
        if w.endswith("e"):
            return {w + suffix[1:]}
        if re.search(r"[^aeiou]y$", w):
            return {w[:-1] + "i" + suffix}
        return {w + suffix, double + suffix}
    return set()


def parse_exchange(lemma, exchange):
    """ECDICT exchange → [(变形, 类别, 是否不规则)]，只收这个原型自己的变形。"""
    out = []
    for item in exchange.split("/"):
        if ":" not in item:
            continue
        kind, form = item.split(":", 1)
        form = form.strip()
        if kind not in EXCHANGE_KINDS or not form or form.lower() == lemma.lower():
            continue
        irregular = form.lower() not in regular_forms(lemma, kind)
        out.append((form, kind, irregular))
    return out


# ---------------------------------------------------------------------------
# 选词


def _int(value):
    try:
        return int(value)
    except ValueError:
        return 0


def keep_entry(row, rank_limit):
    word = row["word"]
    if not LEMMA_RE.match(word):
        return False
    marked = bool(row["tag"].strip()) or row["oxford"] == "1"
    if " " in word or word[0].isupper() and word not in ("I",):
        return marked
    if marked or _int(row["collins"]) > 0:
        return True
    rank = rank_of(row)
    return 0 < rank <= rank_limit


def rank_of(row):
    frq, bnc = _int(row["frq"]), _int(row["bnc"])
    ranks = [r for r in (frq, bnc) if r > 0]
    return min(ranks) if ranks else 0


# ---------------------------------------------------------------------------


def download(url, dest):
    print("下载 " + url, file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "dictionary-builder"})
    with urllib.request.urlopen(req) as resp, open(dest, "wb") as f:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)


SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE words(
    lemma TEXT PRIMARY KEY,
    ipa TEXT NOT NULL,
    ipa_source TEXT NOT NULL,
    frq_rank INTEGER NOT NULL,
    tags TEXT NOT NULL,
    oxford INTEGER NOT NULL,
    collins INTEGER NOT NULL
);
CREATE TABLE senses(
    lemma TEXT NOT NULL,
    pos TEXT NOT NULL,
    sense_order INTEGER NOT NULL,
    meaning_zh TEXT NOT NULL,
    PRIMARY KEY(lemma, pos, sense_order)
);
CREATE TABLE forms(
    form TEXT NOT NULL,
    lemma TEXT NOT NULL,
    kind TEXT NOT NULL,
    irregular INTEGER NOT NULL,
    PRIMARY KEY(form, lemma, kind)
);
CREATE INDEX words_lemma_nocase ON words(lemma COLLATE NOCASE);
CREATE INDEX senses_lemma_nocase ON senses(lemma COLLATE NOCASE, pos);
CREATE INDEX forms_form_nocase ON forms(form COLLATE NOCASE);
"""


def build(ecdict_path, cmudict_path, out_path, rank_limit):
    cmu = load_cmudict(cmudict_path)
    words, senses, forms = [], [], []
    from_cmu = 0
    with io.open(ecdict_path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if not keep_entry(row, rank_limit):
                continue
            lemma = row["word"].strip()
            groups = senses_of(row["translation"], " " in lemma)
            if not groups:
                continue
            ipa = cmu.get(lemma.lower(), "") if " " not in lemma else ""
            source = "cmudict"
            if ipa:
                from_cmu += 1
            else:
                ipa, source = ecdict_ipa(row["phonetic"]), "ecdict"
            words.append((lemma, ipa, source if ipa else "", rank_of(row),
                          row["tag"].strip(), 1 if row["oxford"] == "1" else 0, _int(row["collins"])))
            for pos, items in groups:
                for order, meaning in enumerate(items):
                    senses.append((lemma, pos, order, meaning))
            for form, kind, irregular in parse_exchange(lemma, row["exchange"]):
                forms.append((form, lemma, kind, 1 if irregular else 0))

    if os.path.exists(out_path):
        os.remove(out_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    db = sqlite3.connect(out_path)
    db.executescript(SCHEMA)
    meta = {
        "pack_id": PACK_ID,
        "pack_version": str(PACK_VERSION),
        "source": "ECDICT (skywind3000/ECDICT, MIT); CMUdict (cmusphinx/cmudict, BSD-2-Clause)",
        "generator": "tools/build_dictionary.py — 由脚本生成，不要手改",
        "rank_limit": str(rank_limit),
        "word_count": str(len(words)),
        "sense_count": str(len(senses)),
    }
    db.executemany("INSERT INTO meta VALUES (?, ?)", sorted(meta.items()))
    db.executemany("INSERT INTO words VALUES (?, ?, ?, ?, ?, ?, ?)", words)
    db.executemany("INSERT INTO senses VALUES (?, ?, ?, ?)", senses)
    db.executemany("INSERT OR IGNORE INTO forms VALUES (?, ?, ?, ?)", forms)
    db.execute("PRAGMA user_version = %d" % PACK_VERSION)
    db.commit()
    db.execute("VACUUM")
    db.close()
    size = os.path.getsize(out_path)
    print("词条 %d，义项 %d，变形 %d，CMUdict 美式音标覆盖 %d（%.1f%%），文件 %.1f MB → %s" % (
        len(words), len(senses), len(forms), from_cmu, 100.0 * from_cmu / max(1, len(words)),
        size / 1048576.0, os.path.relpath(out_path, ROOT)))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--ecdict", help="本地 ecdict.csv；不给就联网下载")
    parser.add_argument("--cmudict", help="本地 cmudict.dict；不给就联网下载")
    parser.add_argument("--rank-limit", type=int, default=DEFAULT_RANK_LIMIT)
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    with tempfile.TemporaryDirectory() as tmp:
        ecdict = args.ecdict
        if not ecdict:
            ecdict = os.path.join(tmp, "ecdict.csv")
            download(ECDICT_URL, ecdict)
        cmudict = args.cmudict
        if not cmudict:
            cmudict = os.path.join(tmp, "cmudict.dict")
            download(CMUDICT_URL, cmudict)
        build(ecdict, cmudict, args.out, args.rank_limit)


if __name__ == "__main__":
    main()
