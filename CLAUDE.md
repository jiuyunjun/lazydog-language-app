---
doc: "CLAUDE.md"
tier: "L0 入口"
status: "生效"
version: "1.0"
updated: "2026-09-06"
authority: "Claude Code 的会话入口，只做转发"
index: "DOCS.md"
maintenance: "改本文须同步 DOCS.md 的版本表，校验命令 python tools/check_docs.py"
---

# CLAUDE.md

Claude Code 的会话入口。**这里不放规则，只做转发**，避免规则出现第二份、然后两份慢慢说不一样的话。

开工前按顺序读这两份：

1. **[AGENTS.md](AGENTS.md)** — 强制工程规则、Git 方式、实现流程七步、完成定义。适用于本仓库全部目录。
2. **[DOCS.md](DOCS.md)** — 文档地图：每份文档管什么、什么时候读、冲突了听谁的、现在是哪一版。

其余文档**按需加载**，不要一次读完：最大的两份加起来超过 12 万字，全读只会挤掉真正要干的活。要查什么去哪儿，见 `DOCS.md` §1 的三十秒版本。

三条最容易踩的：

- **专项设计（L4）写了不等于已经实现。** 它们描述目标形态。要判断现状，读 `ROADMAP.md` 的勾选状态和 `DECISIONS.md`，再看代码。
- **架构变更预算为 0。** 新增层、新框架、改依赖方向、造平行抽象都要先停下来提请求，见 `ARCHITECTURE.md` §0。
- **改了文档口径就要动版本。** 该文 frontmatter 的 `version` / `updated`、`DOCS.md` §6 版本表、§7 变更日志，三处一起改，`python tools/check_docs.py` 会检查前两处。

常用命令：

```bash
./gradlew assembleDebug                    # 构建
./gradlew lintDebug testDebugUnitTest      # 验证
python tools/check_docs.py                 # 文档元信息校验
```
