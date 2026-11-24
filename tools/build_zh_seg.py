# -*- coding: utf-8 -*-
"""生成中文书母语阅读用的两份资源（D-098 R4，`读书与网络阅读DESIGN.md` §5.2、§5.3）。

1. app/src/main/assets/zh_seg_dict.txt —— 分词词典。
   来源 jieba 的 dict.txt（fxsjy/jieba，MIT），每行「词 词频 词性」。
   裁剪：单字全留（分词兜底要用），多字词留词频 >= MIN_FREQ 的。人名地名不删——分词时要靠它们
   把「林黛玉」切成一个词而不是三个单字，只是替换时会被词性挡掉。
2. app/src/main/assets/zh_en_index.txt —— 中文词反查英文词。
   来源是已经生成好的内置词典库（tools/build_dictionary.py 的产物），每个干净的中文义项一行：
   「中文 原型 词性 义项序号 英文词频排名」。干净 = 2~6 个汉字；形容词、副词义项去掉末尾的「的」「地」
   （ECDICT 写「孤独的」，书里写「孤独」）。只收分词词典里有的中文词：分不出来的词反查了也用不上。

两份都由本脚本生成，不要手改。

用法：
    python tools/build_zh_seg.py                         # 联网下载 jieba 词典
    python tools/build_zh_seg.py --jieba dict.txt
"""
from __future__ import print_function

import argparse
import io
import os
import re
import sqlite3
import sys
import tempfile
import urllib.request

JIEBA_URL = "https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DICTIONARY_DB = os.path.join(ASSETS, "dictionary_en_zh.db")

MIN_FREQ = 5
CLEAN_ZH = re.compile(u"^[一-鿿]{2,6}$")


def load_jieba(path):
    """[(词, 词频, 词性)]，按裁剪规则过滤。"""
    out = []
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 2:
                continue
            word, freq = parts[0], int(parts[1])
            pos = parts[2] if len(parts) > 2 else "x"
            if len(word) == 1 or freq >= MIN_FREQ:
                out.append((word, freq, pos))
    return out


def clean_meaning(meaning, pos):
    """ECDICT 义项 → 能在书里对上的中文词；对不上返回 None。"""
    m = meaning.strip()
    if pos in ("ADJ", "ADV") and len(m) >= 3 and m[-1] in u"的地":
        m = m[:-1]
    return m if CLEAN_ZH.match(m) else None


def build_index(db_path, vocabulary):
    db = sqlite3.connect(db_path)
    rows = db.execute(
        "SELECT s.meaning_zh, s.lemma, s.pos, s.sense_order, w.frq_rank "
        "FROM senses s JOIN words w ON w.lemma = s.lemma ORDER BY s.rowid"
    ).fetchall()
    db.close()
    out = []
    seen = set()
    for meaning, lemma, pos, order, rank in rows:
        zh = clean_meaning(meaning, pos)
        if zh is None or zh not in vocabulary or " " in lemma:
            continue
        key = (zh, lemma, pos)
        if key in seen:  # 同一个词性下同一个中文出现两次，留序号小的那次
            continue
        seen.add(key)
        out.append((zh, lemma, pos, order, rank))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--jieba")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    source = args.jieba
    if not source:
        source = os.path.join(tempfile.mkdtemp(), "dict.txt")
        req = urllib.request.Request(JIEBA_URL, headers={"User-Agent": "zh-seg-builder"})
        with urllib.request.urlopen(req) as resp, open(source, "wb") as f:
            f.write(resp.read())

    words = load_jieba(source)
    seg_path = os.path.join(ASSETS, "zh_seg_dict.txt")
    with io.open(seg_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(u"# 中文分词词典：jieba dict.txt（MIT）裁剪，单字全留、多字词词频 >= %d。\n" % MIN_FREQ)
        f.write(u"# 每行：词 词频 词性。由 tools/build_zh_seg.py 生成，不要手改。\n")
        for word, freq, pos in words:
            f.write(u"%s %d %s\n" % (word, freq, pos))

    vocabulary = set(w for w, _, _ in words)
    index = build_index(DICTIONARY_DB, vocabulary)
    index_path = os.path.join(ASSETS, "zh_en_index.txt")
    with io.open(index_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(u"# 中文词反查英文词：内置词典（ECDICT）的干净中文义项。\n")
        f.write(u"# 每行：中文 原型 词性 义项序号 英文词频排名（0 = 无排名）。由 tools/build_zh_seg.py 生成，不要手改。\n")
        for zh, lemma, pos, order, rank in index:
            f.write(u"%s\t%s\t%s\t%d\t%d\n" % (zh, lemma, pos, order, rank))

    print(u"分词词典 %d 词，%.1f MB；反查表 %d 条（%d 个中文词），%.1f MB" % (
        len(words), os.path.getsize(seg_path) / 1048576.0,
        len(index), len(set(r[0] for r in index)), os.path.getsize(index_path) / 1048576.0))


if __name__ == "__main__":
    main()
