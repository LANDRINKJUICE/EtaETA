#! /usr/bin/env python3
# -*- coding: utf-8 -*-
"""契约类「构造点命名实参 ↔ data class 形参」一致性静态闸（2026-11-15 从根 + 二审闭环）。

背景：b046fc6 删了 AttentionIntent 的 title/body，但某 android 侧构造点仍传这两个命名参数；
纯 JVM 白名单不编 android 侧、本地闸是 git-diff 语义 → 本地全绿、云端 gradle 才炸。本闸全仓、
不依赖 git diff / android.jar，本地 0.2s 挡两类回归：
  (1) 构造点传了 data class 里不存在的命名实参（旧事故）；
  (2) 构造点漏传了**无默认值的必填形参**（加字段忘改构造点，镜像事故）。
同时处理 Kotlin 注释 / 行注释 / raw string / char 字面量，避免误报与漏报。
边界：`.copy(...)` 的接收者类型无法用纯文本可靠判定（会误伤全仓其它 data class 的 copy），
故不纳入本闸；copy 的命名实参错误由 gradle 全量编译（check-compile.sh）兜底，不假装有覆盖。

契约类清单显式登记，误报面极小。它不替代 gradle 全量编译（位置实参顺序等仍由编译兜），
但把「命名实参错/漏」这类无需编译就能判定的缺陷挡在推送前。
"""
import re
import sys
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src")

CONTRACTS = {
    "AttentionIntent": "app/src/main/kotlin/io/github/mangi/eta/agent/notify/AttentionIntent.kt",
    "DisturbContext": "app/src/main/kotlin/io/github/mangi/eta/agent/notify/AttentionDirector.kt",
    "AttentionDecision": "app/src/main/kotlin/io/github/mangi/eta/agent/notify/AttentionDirector.kt",
    "NotificationKey": "app/src/main/kotlin/io/github/mangi/eta/agent/notify/NotificationKey.kt",
    "AdmissionClaim": "app/src/main/kotlin/io/github/mangi/eta/agent/notify/AttentionIntent.kt",
}


def mask_noncode(text: str) -> str:
    """把注释 / 字符串 / char / raw string 的内容替换成等长空格（保留换行与位置），
    只留下真正的代码字符，避免注释里的 `name =` / 不平衡括号 / raw string 污染状态机。"""
    out = list(text)
    n = len(text)
    i = 0
    while i < n:
        c = text[i]
        # 行注释
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            j = n if j < 0 else j
            for k in range(i, j):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
            continue
        # 块注释（不嵌套）
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
            continue
        # raw string """ ... """
        if c == '"' and i + 2 < n and text[i + 1] == '"' and text[i + 2] == '"':
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
            continue
        # 普通字符串
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            for k in range(i, min(j, n)):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
            continue
        # char 字面量（撇号）：必须是 'x' 或 '\x' 形式，否则是普通撇号（如 owner's）不能吞
        if c == "'":
            m = re.match(r"'(\\.|[^\\'\n])'", text[i:])
            if m:
                j = i + m.end()
                for k in range(i, j):
                    if out[k] != '\n':
                        out[k] = ' '
                i = j
                continue
        i += 1
    return ''.join(out)


def params_of(class_name: str, def_file: str):
    """返回 (全部形参名集合, 无默认值的必填形参名集合)。"""
    text = mask_noncode(open(os.path.join(ROOT, def_file), encoding="utf-8").read())
    m = re.search(r"(?:data\s+)?class\s+" + re.escape(class_name) + r"\s*\(", text)
    if not m:
        return set(), set()
    i, depth = m.end(), 1
    while i < len(text) and depth:
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
        i += 1
    body = text[m.end():i - 1]
    # 按顶层逗号切形参
    parts, depth, start = [], 0, 0
    for k, ch in enumerate(body):
        if ch in '(<[':
            depth += 1
        elif ch in ')>]':
            depth -= 1
        elif ch == ',' and depth == 0:
            parts.append(body[start:k]); start = k + 1
    parts.append(body[start:])
    allp, required = set(), set()
    for part in parts:
        pm = re.search(r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:", part)
        if pm:
            name = pm.group(1)
            allp.add(name)
            if '=' not in part:
                required.add(name)
    return allp, required


def top_level_named_args(masked: str, open_paren: int):
    """从 '(' 起配平（字符串/char 已被 mask 成空格），只收**本调用顶层**的 `name =`。"""
    out = []
    i = open_paren + 1
    depth, brace = 1, 0
    n = len(masked)
    while i < n and depth > 0:
        c = masked[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                break
        elif c == '{':
            brace += 1
        elif c == '}':
            brace = max(0, brace - 1)
        elif c == '=' and depth == 1 and brace == 0:
            j = i - 1
            while j >= 0 and masked[j] in ' \t\r\n':
                j -= 1
            k = j
            while k >= 0 and (masked[k].isalnum() or masked[k] == '_'):
                k -= 1
            name = masked[k + 1:j + 1]
            # 排除 ==/!=/>=/<=/-> 等运算符与关键字
            if name and not (i + 1 < n and masked[i + 1] == '=') and (j < 0 or masked[j] not in '=!<>') \
                    and name not in ('val', 'var', 'else'):
                out.append((name, i))
        i += 1
    return out


def main() -> int:
    legal = {}
    required = {}
    for c, f in CONTRACTS.items():
        allp, req = params_of(c, f)
        if not allp:
            print("契约类 %s 未在 %s 找到 data class 形参，检查脚本是否失准" % (c, f), file=sys.stderr)
            return 1
        legal[c], required[c] = allp, req
    bad = []
    for dp, _, files in os.walk(APP):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dp, fn)
            rel = p.replace(ROOT + "/", "")
            text = open(p, encoding="utf-8").read()
            masked = mask_noncode(text)
            for c in CONTRACTS:
                # 构造调用 `X(` 与 `.copy(`（copy 的接收者须是该契约类型的变量，这里做宽匹配：
                # 仅当 copy 参数里出现契约类形参名之一才检查，避免误伤别的 data class 的 copy）
                call_sites = []
                for m in re.finditer(r"\b" + re.escape(c) + r"\s*\(", masked):
                    prefix = masked[max(0, m.start() - 24):m.start()]
                    if re.search(r"\bclass\s*$", prefix) or re.search(r"\bobject\s*$", prefix):
                        continue
                    call_sites.append(("new", m.end() - 1))
                for kind, open_paren in call_sites:
                    args = top_level_named_args(masked, open_paren)
                    names = set()
                    for name, off in args:
                        line = text.count("\n", 0, off) + 1
                        if name not in legal[c]:
                            import difflib
                            near = difflib.get_close_matches(name, sorted(legal[c]), n=1)
                            hint = ("，是否想写 " + near[0]) if near else ""
                            bad.append("%s:%d  %s(...) 的命名实参 `%s` 不在形参表 %s 中%s"
                                       % (rel, line, c, name, sorted(legal[c]), hint))
                        names.add(name)
                    # 必填形参齐备性：仅对**全命名实参**调用（无位置实参混入）做判定；
                    # 有位置实参时无法静态判定顺序/数量，交给 gradle 全量编译兜。
                    if required[c] and args:
                        missing = required[c] - names
                        if missing:
                            line = text.count("\n", 0, open_paren) + 1
                            bad.append("%s:%d  %s(...) 漏传必填形参 %s（加字段后忘改构造点）"
                                       % (rel, line, c, sorted(missing)))
    if bad:
        print("=== 契约构造点闸：❌ 存在与 data class 定义不一致的构造点 ===", file=sys.stderr)
        for b in bad:
            print("  - " + b, file=sys.stderr)
        print("处置：改 data class 时同步改全部构造点；本闸不受 git diff / android.jar 限制，本地即挡。", file=sys.stderr)
        return 1
    print("契约构造点闸：✅ %d 个契约类全仓构造点命名实参合法、必填齐备" % len(CONTRACTS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
