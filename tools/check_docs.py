# -*- coding: utf-8 -*-
"""校验根目录文档的元信息。

检查四件事：

1. DOCS.md §6 版本表列出的每份文档都存在，且带有 frontmatter。
2. frontmatter 字段齐全，且 tier / status 取值合法。
3. frontmatter 与版本表逐字段相等（层级、状态、版本、更新日期）。
4. 根目录没有漏登记的 markdown 文档。

用法：python tools/check_docs.py
"""
from __future__ import print_function

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX = "DOCS.md"

REQUIRED_FIELDS = ["doc", "tier", "status", "version", "updated", "authority", "index"]
VALID_TIERS = [
    "L0 入口",
    "L1 工作规则",
    "L2 产品定义",
    "L3 技术契约",
    "L4 专项设计",
    "L5 过程记录",
]
VALID_STATUS = ["生效", "部分落地", "已归档"]

VERSION_RE = re.compile(r"^\d+\.\d+$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def read(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def parse_frontmatter(text):
    """返回 frontmatter 的字段字典，没有 frontmatter 时返回 None。"""
    if not text.startswith("---\n"):
        return None
    end = text.find("\n---\n", 3)
    if end == -1:
        return None
    fields = {}
    for line in text[4:end].split("\n"):
        if not line.strip():
            continue
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
            value = value[1:-1]
        fields[key.strip()] = value
    return fields


def parse_version_table(text):
    """解析 DOCS.md §6 的版本表，返回 {doc: (tier, status, version, updated)}。"""
    section = text.split("## 6. 版本表", 1)
    if len(section) != 2:
        return None
    body = section[1].split("\n## ", 1)[0]

    rows = {}
    for line in body.split("\n"):
        line = line.strip()
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) != 5:
            continue
        name = cells[0].strip("`")
        if name in ("文档", "---") or set(name) <= set("- "):
            continue
        rows[name] = (cells[1], cells[2], cells[3], cells[4])
    return rows


def main():
    errors = []
    index_text = read(os.path.join(ROOT, INDEX))

    table = parse_version_table(index_text)
    if table is None:
        print("ERROR: 在 %s 里找不到 '## 6. 版本表'" % INDEX)
        return 1
    if not table:
        print("ERROR: %s §6 版本表是空的" % INDEX)
        return 1

    for doc in sorted(table):
        path = os.path.join(ROOT, doc)
        if not os.path.exists(path):
            errors.append("%s: 版本表登记了这份文档，但文件不存在" % doc)
            continue

        fields = parse_frontmatter(read(path))
        if fields is None:
            errors.append("%s: 缺少 frontmatter（文件第一行应为 '---'）" % doc)
            continue

        for key in REQUIRED_FIELDS:
            if key not in fields:
                errors.append("%s: frontmatter 缺字段 '%s'" % (doc, key))

        if fields.get("doc") != doc:
            errors.append("%s: frontmatter 的 doc 是 '%s'，与文件名不符"
                          % (doc, fields.get("doc")))
        if fields.get("index") != INDEX:
            errors.append("%s: frontmatter 的 index 应为 '%s'" % (doc, INDEX))
        if fields.get("tier") not in VALID_TIERS:
            errors.append("%s: tier '%s' 不是合法层级，取值见 DOCS.md §2"
                          % (doc, fields.get("tier")))
        if fields.get("status") not in VALID_STATUS:
            errors.append("%s: status '%s' 不合法，应为 %s"
                          % (doc, fields.get("status"), " / ".join(VALID_STATUS)))
        if not VERSION_RE.match(fields.get("version", "")):
            errors.append("%s: version '%s' 不是 主.次 形式"
                          % (doc, fields.get("version")))
        if not DATE_RE.match(fields.get("updated", "")):
            errors.append("%s: updated '%s' 不是 YYYY-MM-DD"
                          % (doc, fields.get("updated")))
        if not fields.get("authority", "").strip():
            errors.append("%s: authority 不能为空，一句话说明本文是什么的事实来源" % doc)

        tier, status, version, updated = table[doc]
        for label, in_doc, in_table in (
            ("层级", fields.get("tier"), tier),
            ("状态", fields.get("status"), status),
            ("版本", fields.get("version"), version),
            ("更新日期", fields.get("updated"), updated),
        ):
            if in_doc != in_table:
                errors.append(
                    "%s: %s 不一致 — frontmatter '%s' vs %s 版本表 '%s'"
                    % (doc, label, in_doc, INDEX, in_table))

    for name in sorted(os.listdir(ROOT)):
        if name.endswith(".md") and name not in table:
            errors.append("%s: 根目录多出一份未登记的文档，请加进 %s §3 和 §6"
                          % (name, INDEX))

    if errors:
        print("文档元信息校验失败，共 %d 项：\n" % len(errors))
        for err in errors:
            print("  - %s" % err)
        print("\n维护规则见 %s §5。" % INDEX)
        return 1

    print("文档元信息校验通过：%d 份文档，frontmatter 与 %s 版本表一致。"
          % (len(table), INDEX))
    return 0


if __name__ == "__main__":
    sys.exit(main())
