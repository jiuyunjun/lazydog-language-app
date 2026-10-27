"""Guard navigation/AI Chat wiring and reviewed non-interactive text exceptions.

Run from any directory: python tools/check_ui_capabilities.py
This is a source guard, not a Kotlin compiler or a replacement for gesture tests.
"""
from collections import Counter
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("app/src/main/java/com/lazydog/english")


def code_mask(source):
    """Blank comments and strings, preserving offsets (including Kotlin interpolation)."""
    masked = list(source)

    def blank(start, end):
        for pos in range(start, end):
            if source[pos] != "\n":
                masked[pos] = " "

    def string_end(pos):
        quote = source[pos]
        if source.startswith('"""', pos):
            end = source.find('"""', pos + 3)
            return len(source) if end < 0 else end + 3
        pos += 1
        while pos < len(source):
            if source[pos] == "\\":
                pos += 2
            elif quote == '"' and source.startswith("${", pos):
                pos = group_end(pos + 1)
            elif source[pos] == quote:
                return pos + 1
            else:
                pos += 1
        return pos

    def group_end(pos):
        depth = 1
        pos += 1
        while pos < len(source) and depth:
            if source[pos] in "\"'":
                pos = string_end(pos)
                continue
            if source[pos] == "{":
                depth += 1
            elif source[pos] == "}":
                depth -= 1
            pos += 1
        return pos

    pos = 0
    while pos < len(source):
        start = pos
        if source.startswith("//", pos):
            end = source.find("\n", pos)
            pos = len(source) if end < 0 else end
        elif source.startswith("/*", pos):
            pos += 2
            depth = 1
            while pos < len(source) and depth:
                if source.startswith("/*", pos):
                    depth += 1
                    pos += 2
                elif source.startswith("*/", pos):
                    depth -= 1
                    pos += 2
                else:
                    pos += 1
        elif source[pos] in "\"'":
            pos = string_end(pos)
        else:
            pos += 1
            continue
        blank(start, pos)
    return "".join(masked)


def closing(mask, start):
    pairs = {"(": ")", "[": "]", "{": "}"}
    stack = [pairs[mask[start]]]
    for pos in range(start + 1, len(mask)):
        char = mask[pos]
        if char in pairs:
            stack.append(pairs[char])
        elif char in ")]}":
            if not stack or char != stack.pop():
                raise ValueError(f"Unbalanced source near offset {pos}")
            if not stack:
                return pos
    raise ValueError("Unclosed source group")


def arguments(source, mask, start, end):
    parts = []
    last = pos = start + 1
    while pos < end:
        if mask[pos] in "([{":
            pos = closing(mask, pos)
        elif mask[pos] == ",":
            parts.append(source[last:pos].strip())
            last = pos + 1
        pos += 1
    parts.append(source[last:end].strip())
    return [part for part in parts if part]


def calls(source, name):
    mask = code_mask(source)
    for match in re.finditer(r"\b" + re.escape(name) + r"\s*\(", mask):
        start = mask.index("(", match.start())
        end = closing(mask, start)
        yield match.start(), end, arguments(source, mask, start, end)


def text_uses(source):
    # Material Text aliases and fully qualified calls are checked too.
    names = {"Text", "BasicText"}
    names.update(re.findall(r"import\s+[\w.]+\.(?:Text|BasicText)\s+as\s+(\w+)", source))
    for name in names:
        for start, _, args in calls(source, name):
            text = next((re.sub(r"^text\s*=\s*", "", a) for a in args if re.match(r"text\s*=", a)), None)
            if text is None and args and not re.match(r"\w+\s*=", args[0]):
                text = args[0]
            if text is None:
                continue
            # Fixed Chinese labels and punctuation cannot contain an English learning object.
            if re.fullmatch(r'"(?:[^"\\$]|\\.)*"', text) and not re.search(r"[A-Za-z]", text):
                continue
            yield start, re.sub(r"\s+", " ", text).strip()


def inspect(root=ROOT):
    files = {p.relative_to(root).as_posix(): p.read_text(encoding="utf-8")
             for p in (root / SOURCE).rglob("*.kt")}
    errors = []
    app_screen = (SOURCE / "AppScreen.kt").as_posix()
    for path, source in files.items():
        if path != app_screen and (re.search(r"import\s+androidx\.navigation\.compose\.composable", source)
                                   or list(calls(source, "composable"))):
            errors.append(f"{path}: 页面必须通过 appScreen 注册")
        if path != app_screen and list(calls(source, "AskHost")) and not path.endswith("feature/ask/AskHost.kt"):
            errors.append(f"{path}: 不能手动包 AskHost；请使用 appScreen")
        for start, end, args in calls(source, "appScreen"):
            if path == app_screen:
                continue
            policy = next((a for a in args if re.match(r"ask\s*=", a)), "")
            if not policy:
                errors.append(f"{path}: appScreen 缺少 ask 策略")
            required = re.fullmatch(r"ask\s*=\s*AskPagePolicy\.Required", policy)
            disabled = re.fullmatch(r'ask\s*=\s*AskPagePolicy\.Disabled\("([^"$]*)"\)', policy)
            if policy and not required and not (disabled and disabled[1].strip()):
                errors.append(f"{path}: ask 策略必须直接声明 Required 或 Disabled(非空原因)")
            if not required:
                continue
            mask = code_mask(source)
            body_start = mask.find("{", end)
            body = source[body_start:closing(mask, body_start) + 1]
            screens = re.findall(r"\b(\w+Screen)\s*\(", body)
            if not screens:
                errors.append(f"{path}: Required 目的地必须直接调用可检查的 *Screen 入口")
            for screen in screens:
                bodies = []
                for candidate in files.values():
                    candidate_mask = code_mask(candidate)
                    match = re.search(r"\bfun\s+" + screen + r"\s*\(", candidate_mask)
                    if match:
                        params = candidate_mask.index("(", match.start())
                        begin = candidate_mask.find("{", closing(candidate_mask, params))
                        bodies.append(candidate[begin:closing(candidate_mask, begin) + 1])
                if len(bodies) != 1 or not list(calls(bodies[0], "ProvideAskContext")):
                    errors.append(f"{path}: {screen} 启用了提问但缺少页面级 ProvideAskContext")
                elif not list(calls(bodies[0], "AskTopBarAction")):
                    errors.append(f"{path}: {screen} 缺少无传感器时的 AskTopBarAction")

    exceptions = json.loads((root / "tools/ui_text_exceptions.json").read_text(encoding="utf-8"))
    actual = {}
    for path, source in files.items():
        for start, expression in text_uses(source):
            key = (path, expression)
            actual.setdefault(key, []).append(source.count("\n", 0, start) + 1)
    allowed = Counter()
    for entry in exceptions:
        if not entry.get("reason", "").strip():
            errors.append(f"{entry}: 普通文本例外必须说明原因")
        allowed[(entry["file"], entry["text"])] += entry.get("count", 1)
    for key, lines in actual.items():
        if allowed[key] != len(lines):
            errors.append(f"{key[0]}:{lines[0]}: 普通 Text 未审查（{len(lines)} 处 / 允许 {allowed[key]}）: {key[1]}")
    for key in allowed:
        if key not in actual:
            errors.append(f"{key[0]}: 删除过期文本例外: {key[1]}")
    return errors


if __name__ == "__main__":
    problems = inspect()
    if problems:
        print("\n".join(problems))
        sys.exit(1)
    print("UI capabilities OK: navigation, AI Chat declarations, reviewed plain-text exceptions")
