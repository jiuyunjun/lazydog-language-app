# -*- coding: utf-8 -*-
"""生成 app/src/main/assets/word_frequency_en.txt。

两份数据求交集：

1. **词频**来自 hermitdave/FrequencyWords 的 2018 英文 50k 表，语料是
   OpenSubtitles2018（OPUS）。选它而不是网页词频表的原因见 DECISIONS.md D-059：
   google-10000-english 连 abandon 和 reluctant 都没有，前 2000 名里却有
   sex / porn / xxx / php / html。
2. **词元**来自 WordNet 3.1 的 index.{noun,verb,adj,adv}。只留在 WordNet 里
   出现的单词形式，一次解决三个问题——字幕语料里混着纯功能词（you/the/of）、
   变形（paid/ran/tells/gotten）、人名和口语拼写（nikki/caleb/doin/talkin）。
   WordNet 只收四类实词的**原型**，正好把这三类全挡在外面。这一步不是锦上添花：
   不做的话 A1 拿到的候选前十二个是 "you i the to a it and that of is in what"。

所以产物里的排名是**实词内部的排名**，不是原始语料排名："最常用 3000 词"
指的是"最常用的 3000 个可学实词"。对学习者来说这个口径本来就更有意义。

产物格式：# 开头是注释，其余每行一个词，**行号就是排名**。
代码只认顺序，不存排名数字——排名换一份语料就全变，存进去只会有两处事实。

用法：
    python tools/build_word_frequency.py                    # 全部联网下载
    python tools/build_word_frequency.py --source en_50k.txt --wordnet-dir dict/
"""
from __future__ import print_function

import argparse
import io
import os
import re
import sys

SOURCE_URL = (
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords"
    "/master/content/2018/en/en_50k.txt"
)

WORDNET_BASE = (
    "https://raw.githubusercontent.com/extjwnl/extjwnl-data-wn31/master/"
    "src/main/resources/net/sf/extjwnl/data/wordnet/wn31/"
)
WORDNET_FILES = ("index.noun", "index.verb", "index.adj", "index.adv")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "word_frequency_en.txt")

# 表里保留多少个词。12000 覆盖到 C1 还绰绰有余，再往后是长尾专业词，
# 对"这个词值不值得现在学"已经给不出有用的区分了。
KEEP = 12000

WORD_RE = re.compile(r"^[a-z]+$")

# 只在这个表里出现、不该被推荐去学的词。表是我们自己的，唯一消费方是学习 App，
# 与其在下游到处过滤，不如一开始就不收。数量极少，对排名的影响可以忽略。
BLOCKLIST = {
    "fuck", "fucking", "fucked", "fucker", "fuckin", "motherfucker", "shit",
    "shitty", "bullshit", "bitch", "bitches", "asshole", "cunt", "dick",
    "pussy", "cock", "whore", "slut", "fag", "faggot", "nigger", "nigga",
    "porn", "sex", "sexy", "damn", "goddamn", "hell",
}

# 长度 1 的词绝大多数是切分残渣（'s 的 s、罗马数字 i），只有这两个是真词。
KEEP_SINGLE = {"a", "i"}

# WordNet 收了一批功能词的同形异义名词——in=铟、he=氦、are=公亩、me=缅因州、
# a=维生素 A——于是 "the/of/in/he" 这类混进了词表最前面，把 A1 的整个取词窗口占满。
# 这些词学习者早就会，也不需要词卡，直接按表排掉。
FUNCTION_WORDS = {
    "a", "an", "the", "i", "you", "he", "she", "it", "we", "they", "me", "him",
    "her", "us", "them", "my", "your", "his", "its", "our", "their", "mine",
    "yours", "hers", "ours", "theirs", "myself", "yourself", "himself",
    "herself", "itself", "ourselves", "yourselves", "themselves", "this",
    "that", "these", "those", "who", "whom", "whose", "which", "what", "where",
    "when", "why", "how", "be", "am", "is", "are", "was", "were", "been",
    "being", "have", "has", "had", "having", "do", "does", "did", "doing",
    "will", "would", "shall", "should", "can", "could", "may", "might", "must",
    "of", "in", "on", "at", "to", "for", "with", "by", "from", "up", "down",
    "out", "off", "over", "under", "again", "into", "onto", "upon", "about",
    "as", "and", "or", "but", "if", "because", "while", "than", "then",
    "so", "not", "no", "nor", "yes", "too", "very", "just", "only", "also",
    "there", "here", "all", "any", "some", "each", "every", "both", "few",
    "more", "most", "other", "such", "own", "same", "now", "ever", "never",
    "always", "already", "still", "yet", "well", "get", "got", "let", "one",
    "two", "don", "ll", "re", "ve", "em", "gonna", "gotta", "wanna",
}

# 以 s 结尾但本身就是独立词，不是复数——不排除会被下面的变形规则误伤。
NOT_PLURAL = {
    "news", "series", "species", "means", "clothes", "glasses", "thanks",
    "physics", "politics", "economics", "mathematics", "ethics", "athletics",
    "gas", "bus", "yes", "plus", "campus", "virus", "bonus", "focus", "status",
    "crisis", "basis", "analysis", "process", "business", "address", "across",
    "always", "perhaps", "unless", "less", "boss", "class", "glass", "grass",
    "pass", "miss", "kiss", "cross", "press", "dress", "stress", "guess",
    "chess", "mess", "loss", "toss", "was", "his", "this", "us", "is", "as",
}


def base_forms(word):
    """这个词如果是规则变形，它可能的原型有哪些。只做形态猜测，不判断对错。"""
    out = []
    if word.endswith("ies") and len(word) > 4:
        out.append(word[:-3] + "y")
    if word.endswith("es") and len(word) > 3:
        out.append(word[:-2])
    if word.endswith("s") and not word.endswith("ss") and len(word) > 3:
        out.append(word[:-1])
    if word.endswith("ed") and len(word) > 3:
        out.append(word[:-1])          # moved -> move
        out.append(word[:-2])          # ordered -> order
        if len(word) > 4 and word[-3] == word[-4]:
            out.append(word[:-3])      # stopped -> stop
    if word.endswith("ing") and len(word) > 4:
        out.append(word[:-3])          # spending -> spend
        out.append(word[:-3] + "e")    # moving -> move
        if len(word) > 5 and word[-4] == word[-5]:
            out.append(word[:-4])      # running -> run
    return out


def fetch(url):
    from urllib.request import urlopen
    print("下载 %s" % url)
    return urlopen(url, timeout=180).read().decode("utf-8", "replace")


def load_source(path):
    if path:
        with io.open(path, encoding="utf-8") as fh:
            return fh.read()
    return fetch(SOURCE_URL)


def load_wordnet_lemmas(directory):
    """WordNet index 文件里的单词词元集合。

    每行第一个字段是词元；开头两个空格的是版权头。多词词条用 `_` 连接，
    连同带数字的条目（"1750s"、"11th"）一起丢掉——那些不是要教的东西。
    """
    lemmas = set()
    for name in WORDNET_FILES:
        if directory:
            with io.open(os.path.join(directory, name), encoding="utf-8",
                         errors="replace") as fh:
                text = fh.read()
        else:
            text = fetch(WORDNET_BASE + name)
        for line in text.split("\n"):
            if not line or line.startswith("  "):
                continue
            lemma = line.split(" ")[0]
            if WORD_RE.match(lemma):
                lemmas.add(lemma)
    return lemmas


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", help="本地的 en_50k.txt，不给就联网下载")
    parser.add_argument("--wordnet-dir",
                        help="本地放着 index.noun/verb/adj/adv 的目录，不给就联网下载")
    args = parser.parse_args()

    raw = load_source(args.source)
    lemmas = load_wordnet_lemmas(args.wordnet_dir)
    print("WordNet 单词词元：%d 个" % len(lemmas))

    stats = {"total": 0, "not_alpha": 0, "too_short": 0, "blocked": 0,
             "function": 0, "not_lemma": 0, "inflected": 0, "dup": 0}

    # 第一遍：按词频顺序留下所有够格的实词原型候选。
    pass1 = []
    seen = set()
    for line in raw.split("\n"):
        line = line.strip()
        if not line:
            continue
        stats["total"] += 1
        word = line.split(" ")[0].strip().lower()

        if not WORD_RE.match(word):
            stats["not_alpha"] += 1
        elif len(word) == 1 and word not in KEEP_SINGLE:
            stats["too_short"] += 1
        elif word in BLOCKLIST:
            stats["blocked"] += 1
        elif word in FUNCTION_WORDS:
            stats["function"] += 1
        elif word not in lemmas:
            # 人名、口语拼写（doin/talkin）和多数变形在这一步掉队。
            stats["not_lemma"] += 1
        elif word in seen:
            stats["dup"] += 1
        else:
            seen.add(word)
            pass1.append(word)

    # 第二遍：WordNet 把 moved / spending / names 这类当成独立词条收了，所以
    # 光靠它挡不住变形。这里再过一道：原型也在表里的规则变形一律不收——
    # 学 move 的人不需要再来一张 moved 的卡。
    accepted = set(pass1)
    words = []
    for word in pass1:
        if word not in NOT_PLURAL and any(b in accepted for b in base_forms(word)):
            stats["inflected"] += 1
            continue
        words.append(word)
        if len(words) == KEEP:
            break

    if len(words) < KEEP:
        print("警告：清洗后只剩 %d 个词，少于目标 %d" % (len(words), KEEP))

    header = [
        "# 英语实词词频表，行号即排名（跳过 # 注释行）。",
        "# 词频语料：OpenSubtitles2018 (OPUS)，经 hermitdave/FrequencyWords 统计。",
        "# 词元过滤：WordNet 3.1 的名词/动词/形容词/副词索引。",
        "# 因此排名是**实词内部**的排名，不是原始语料排名。",
        "# 生成：python tools/build_word_frequency.py —— 不要手改这个文件。",
        "# 口径与已知偏差见 DECISIONS.md D-059。",
        "# 词数：%d" % len(words),
    ]

    outdir = os.path.dirname(OUT)
    if not os.path.isdir(outdir):
        os.makedirs(outdir)
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(header) + "\n")
        fh.write("\n".join(words) + "\n")

    size = os.path.getsize(OUT)
    print("写入 %s" % OUT)
    print("  收录 %d 词，%.1f KB" % (len(words), size / 1024.0))
    print("  源行 %(total)d，丢弃：非纯字母 %(not_alpha)d、单字母 %(too_short)d、"
          "屏蔽 %(blocked)d、功能词 %(function)d、非实词原型 %(not_lemma)d、"
          "规则变形 %(inflected)d、重复 %(dup)d" % stats)
    print("  抽查：" + "、".join(
        "%s=%d" % (w, words.index(w) + 1) if w in seen else "%s=缺失" % w
        for w in ("the", "go", "receive", "abandon", "reluctant", "bizarre")
    ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
