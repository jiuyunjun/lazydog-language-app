# -*- coding: utf-8 -*-
"""生成 app/src/main/assets/zh_t2s.txt —— 繁体字到简体字的逐字对照（D-098 R3）。

来源：OpenCC（BYVoid/OpenCC，Apache-2.0）的 data/dictionary/TSCharacters.txt。
一个繁体字对应多个简体候选时取第一个（OpenCC 自己的默认）。逐字转换不处理"一对多看语境"的词
（如「乾」在「乾隆」里不该转成「干」），对读小说来说偶尔一处不准可以接受；要更准需要词组表，暂不做。

用途：维基文库的中文书多是繁体，读者用简体，导入时整本转一遍。

产物格式：# 开头是注释；其余每行「繁 简」两个字符，空格分隔。

用法：
    python tools/build_t2s.py                       # 联网下载
    python tools/build_t2s.py --source TSCharacters.txt
"""
from __future__ import print_function

import argparse
import io
import os
import sys
import tempfile
import urllib.request

SOURCE_URL = "https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/TSCharacters.txt"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "zh_t2s.txt")


def parse(lines):
    """[(繁, 简)]：只收单字到单字，第一个候选；同形的（转了等于没转）不收。"""
    pairs = []
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "\t" not in line:
            continue
        key, values = line.split("\t", 1)
        first = values.split()[0] if values.split() else ""
        if len(key) == 1 and len(first) == 1 and key != first:
            pairs.append((key, first))
    return pairs


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--source")
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    source = args.source
    if not source:
        source = os.path.join(tempfile.mkdtemp(), "TSCharacters.txt")
        req = urllib.request.Request(SOURCE_URL, headers={"User-Agent": "t2s-builder"})
        with urllib.request.urlopen(req) as resp, open(source, "wb") as f:
            f.write(resp.read())
    with io.open(source, encoding="utf-8") as f:
        pairs = parse(f)
    with io.open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 繁体到简体逐字对照。来源：OpenCC TSCharacters.txt（Apache-2.0）。\n")
        f.write("# 由 tools/build_t2s.py 生成，不要手改。\n")
        for t, s in pairs:
            f.write(t + " " + s + "\n")
    print("%d 对 -> %s" % (len(pairs), os.path.relpath(args.out, ROOT)))


if __name__ == "__main__":
    main()
