#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""资源引用完整性闸：全仓 R.string / R.plurals / R.array 引用必须能在 res/values* 找到定义。

为什么需要：本机 aapt2 只验 XML 合法性、纯 JVM 基线不编译 Android res，
三方合并若丢了 strings.xml 里的 <string name=...>，本地全绿、云端 Kotlin 编译才报
`Unresolved reference 'R.string.xxx'`（2026-11-14 上游 v3.4.2~3.4.4 合并踩过）。
本脚本纯文本扫描、无 Android 依赖，本地/CI 都能跑，把这类回归挡在推送前。

只查「主 values 目录 + 所有翻译目录」的并集：字符串可以在任意 values*/ 文件里定义。
R 引用扫所有 .kt（含 test）。返回 0=全部有定义，1=有缺失（打印缺失名与示例引用处）。
"""
import re
import sys
import os
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src")
RES = os.path.join(APP, "main", "res")

# 收集所有 res/values* 目录下的 name="..." 定义（含 <string>/<plurals>/<string-array>/<integer>/<color> 等，
# 统一按 name 汇总；R.string/plurals/array 的引用名都在这个集合里）
defined = set()
for f in glob.glob(os.path.join(RES, "values*", "**", "*.xml"), recursive=True):
    try:
        defined |= set(re.findall(r'\bname="([^"]+)"', open(f, encoding="utf-8").read()))
    except (OSError, UnicodeDecodeError):
        pass

# 扫描全仓 Kotlin 的 R.string / R.plurals / R.array 引用
refs = {}
for dp, _, files in os.walk(APP):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dp, fn)
        try:
            text = open(p, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        for m in re.finditer(r'\bR\.(?:string|plurals|array)\.([A-Za-z0-9_]+)', text):
            refs.setdefault(m.group(1), p.replace(ROOT + "/", ""))

missing = {n: p for n, p in refs.items() if n not in defined}
if missing:
    print("=== 资源引用闸：❌ 有 R 引用找不到定义（云端 Kotlin 编译必报 Unresolved reference）===", file=sys.stderr)
    for n in sorted(missing):
        print(f"  - {n}   (示例: {missing[n]})", file=sys.stderr)
    print(f"共 {len(missing)} 个缺失；定义 {len(defined)} 个、引用 {len(refs)} 个。", file=sys.stderr)
    sys.exit(1)
print(f"资源引用闸：✅ {len(refs)} 个 R 引用全部有定义（定义 {len(defined)} 个）")
sys.exit(0)
