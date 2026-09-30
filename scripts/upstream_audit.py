#!/usr/bin/env python3
"""上游血统与死代码审计（去上游化路线图的度量工具）。

对 app 模块做三件事：
1. 血统三分类：以仓库根提交（上游 OpenMinis 整包导入）为基线，把现存 Kotlin
   文件分为「上游原样未动 / 上游改动过 / Novex 新增」。
2. 可达性闭包：从 AndroidManifest 注册组件出发，沿 import、同包符号、
   全限定名内联调用三种引用方式传播，得到活代码集合；余下即死代码候选。
3. 混合件相似度（P3.4 第四维）：「上游改动过」桶的每个文件与基线同路径
   文件做文本相似度（剥注释 + 空白归一 + 大小写折叠的行级 SequenceMatcher
   比对），按 ≥80% / 40-80% / <40% 三档量化真实剩余重写量——路径三分类
   数不出「改名换路径的直译件」（会落进 Novex 新增桶），也数不出「改动桶
   里其实已经基本自有」的文件；相似度把这两头都钉死。

引用解析覆盖：
- `import a.b.C` / `import a.b.*`（星号导入使整包存活，方向保守）
- 同包引用（Kotlin 同包无需 import，按符号表 + 词法匹配）
- 全限定名内联调用 `a.b.C(...)`（不走 import，曾导致图片查看器两件套误判死代码）

盲区声明：反射、按名字的 DI、纯字符串类名不可静态发现，结果一律按「活」
处理（fail-open）。删除执行前仍须编译 + 全量测试 + 冒烟清单兜底。
相似度口径为保守估计（净眼口径）：剥 // 与 /* */（Kotlin 块注释可嵌套、
字符串字面量内的注释符不剥）、空白归一、大小写折叠；标识符折叠刻意不做
（太激进）。协议事实面（DTO 字段名、DDL、Room 列声明）天然高相似，判定
时按冻结面记档而非缺陷。

用法（在仓库根执行）：
    python3 scripts/upstream_audit.py                # 文本报告（含相似度表）
    python3 scripts/upstream_audit.py --json out.json
"""

import argparse
import difflib
import json
import os
import re
import subprocess
import sys
from collections import defaultdict

APP_MAIN = "src/android/app/src/main/java"
MANIFEST = "src/android/app/src/main/AndroidManifest.xml"
PKGS = ("com.openminis.app", "novex.")

pat_pkg = re.compile(r"^package\s+([\w.]+)", re.M)
pat_imp = re.compile(r"^import\s+([\w.]+)\.(\w+|\*)", re.M)
pat_decl = re.compile(
    r"^(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|abstract\s+|sealed\s+|"
    r"open\s+|data\s+|enum\s+)*(?:class|object|interface)\s+(\w+)", re.M)
pat_tlfun = re.compile(
    r"^(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+)?(?:suspend\s+)?"
    r"fun\s+(?:<[^>]+>\s+)?(?:[\w.]+\.)?(\w+)\s*[(<]", re.M)
pat_tlval = re.compile(
    r"^(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+)?(?:val|var)\s+(\w+)", re.M)
pat_fqn = re.compile(r"\b(?:com\.openminis\.app|novex)(?:\.\w+)+")
pat_man_name = re.compile(r'android:(?:name|targetActivity)="([^"]+)"')


def load_files():
    files = {}
    for dp, _, ns in os.walk(APP_MAIN):
        for n in ns:
            if not n.endswith(".kt"):
                continue
            p = os.path.join(dp, n)
            src = open(p, encoding="utf-8", errors="replace").read()
            m = pat_pkg.search(src)
            files[p] = {
                "pkg": m.group(1) if m else "",
                "imports": [x.group(0).split()[1] for x in pat_imp.finditer(src)],
                "loc": src.count("\n") + 1,
                "src": src,
                "tokens": set(re.findall(r"\b[A-Za-z_]\w+\b", src)),
            }
    return files


def build_symbol_index(files):
    index = defaultdict(list)
    for p, info in files.items():
        names = {os.path.splitext(os.path.basename(p))[0]}
        for rx in (pat_decl, pat_tlfun, pat_tlval):
            names |= {d.group(1) for d in rx.finditer(info["src"])}
        for name in names:
            index[(info["pkg"], name)].append(p)
    return index


def spi_roots(index):
    """META-INF/services 服务注册 → 本仓库源文件。SPI 按类名反射实例化，
    Kotlin 源码零引用，静态扫描盲区（PR#52 误删 ACRA 本地崩溃发送器的根因）。"""
    roots = []
    base = "src/android/app/src/main/resources/META-INF/services"
    if not os.path.isdir(base):
        return roots
    for n in os.listdir(base):
        for line in open(os.path.join(base, n)).read().split():
            line = line.strip()
            if not line:
                continue
            pkg, _, name = line.rpartition(".")
            roots.extend(index.get((pkg, name), []))
    return sorted(set(roots))


def manifest_roots(index):
    """Manifest 注册组件 → 本仓库源文件。解析不了的名字给出告警。"""
    roots, missing = [], []
    xml = open(MANIFEST, encoding="utf-8", errors="replace").read()
    for raw in pat_man_name.findall(xml):
        fqn = ("com.openminis.app." + raw.lstrip(".")) if raw.startswith(".") else raw
        if not fqn.startswith(PKGS):
            continue  # 外部组件（rikka.shizuku.* 等）
        pkg, _, name = fqn.rpartition(".")
        hits = index.get((pkg, name))
        if hits:
            roots.extend(hits)
        else:
            missing.append(raw)  # activity-alias 别名等非类名，可忽略
    return sorted(set(roots)), missing


def resolve_import(imp, index):
    """import 语句 → 文件列表；None 表示解析失败（fail-open 记账）。"""
    if imp.endswith(".*"):
        pkg = imp[:-2]
        return [fp for (p, _), fps in index.items() if p == pkg for fp in fps]
    pkg, _, name = imp.rpartition(".")
    hits = index.get((pkg, name))
    if hits:
        return hits
    outer_pkg = imp.rpartition(".")[0]  # 嵌套类 a.b.C.D → 外层 a.b.C
    op, _, on = outer_pkg.rpartition(".")
    return index.get((op, on))


def closure(roots, files, index):
    live = set(roots)
    stack = list(roots)
    unresolved = 0
    while stack:
        f = stack.pop()
        info = files[f]
        targets = []
        for imp in info["imports"]:
            if not imp.startswith(PKGS):
                continue
            hits = resolve_import(imp, index)
            if hits is None:
                unresolved += 1
            else:
                targets.extend(hits)
        # 全限定名内联调用：从最长后缀往短试，命中符号表即算引用
        for fqn in set(pat_fqn.findall(info["src"])):
            parts = fqn.split(".")
            for cut in range(len(parts) - 1, 1, -1):
                hits = index.get((".".join(parts[:cut]), parts[cut]))
                if hits:
                    targets.extend(hits)
                    break
        # 同包引用：符号名出现在本文件词法集合里
        pkg_syms = defaultdict(list)
        for (p, s), fs in index.items():
            if p == info["pkg"]:
                pkg_syms[s].extend(fs)
        for s, fs in pkg_syms.items():
            if s in info["tokens"]:
                targets.extend(fs)
        for t in targets:
            if t not in live:
                live.add(t)
                stack.append(t)
    return live, unresolved


def root_commit():
    try:
        return subprocess.run(
            ["git", "rev-list", "--max-parents=0", "HEAD"],
            capture_output=True, text=True, check=True).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        sys.exit("错误：需要在仓库根的 git 工作区内运行（找不到 HEAD 提交）。")


# ----------------------------------------------------------------------
# 第四维（P3.4）：混合件与上游基线同路径文件的文本相似度
# ----------------------------------------------------------------------

def strip_kotlin_comments(src):
    """剥 // 行注释与 /* */ 块注释（Kotlin 块注释可嵌套）。

    字符串字面量（含 \"\"\" raw 字符串）与字符字面量内的注释符不剥——
    字符串是行为面（协议键、错误文案），保守保留。块注释内的换行保留，
    维持行结构以支持行级比对。
    """
    out = []
    i, n = 0, len(src)
    depth = 0
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if depth > 0:
            if c == "/" and nxt == "*":
                depth += 1
                i += 2
            elif c == "*" and nxt == "/":
                depth -= 1
                i += 2
            else:
                if c == "\n":
                    out.append(c)
                i += 1
        elif c == "/" and nxt == "/":
            j = src.find("\n", i)
            i = n if j == -1 else j
        elif c == "/" and nxt == "*":
            depth = 1
            i += 2
        elif c == '"' and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            end = n if j == -1 else j + 3
            out.append(src[i:end])
            i = end
        elif c == '"':
            j = i + 1
            while j < n and src[j] not in '"\n':
                j += 2 if src[j] == "\\" else 1
            end = min(j + 1, n)
            out.append(src[i:end])
            i = end
        elif c == "'":
            j = i + 1
            while j < n and src[j] not in "'\n":
                j += 2 if src[j] == "\\" else 1
            end = min(j + 1, n)
            out.append(src[i:end])
            i = end
        else:
            out.append(c)
            i += 1
    return "".join(out)


def normalized_lines(src):
    """剥注释 → 逐行空白归一 + 大小写折叠，空行丢弃。

    标识符折叠刻意不做（净眼口径：太激进）；本函数输出是保守估计的下界
    相似度原料——改了变量名的直译件仍会得到高相似度，这正是要的效果。
    """
    return [norm for line in strip_kotlin_comments(src).split("\n")
            if (norm := " ".join(line.split()).casefold())]


def line_similarity(a, b):
    """行级 SequenceMatcher ratio（0..1）。autojunk 关闭——大文件的重复行
    （import 段、数据类字段）会被自动当作 junk 扭曲比值。"""
    if not a and not b:
        return 1.0
    return difflib.SequenceMatcher(None, a, b, autojunk=False).ratio()


def baseline_similarities(base, paths, files):
    """混合件 × 基线同路径 blob 的相似度。

    基线内容按 `git cat-file --batch`（git show 82c2eb0:<path> 的批量等价）
    单进程流式获取：逐块读取、算完即弃，任何时刻内存里至多一个 blob。
    返回 {path: (similarity, base_loc)}；基线缺路径的记 None。
    """
    if not paths:
        return {}
    want = sorted(paths)
    try:
        proc = subprocess.Popen(
            ["git", "cat-file", "--batch"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    except FileNotFoundError:
        sys.exit("错误：找不到 git，无法取基线 blob。")
    proc.stdin.write("".join(f"{base}:{p}\n" for p in want).encode())
    proc.stdin.close()
    result = {}
    for p in want:
        header = proc.stdout.readline().decode()
        parts = header.split()
        if len(parts) >= 3 and parts[2].isdigit():
            raw = proc.stdout.read(int(parts[2]))
            proc.stdout.read(1)  # 块尾换行
            if parts[1] == "blob":
                base_lines = normalized_lines(raw.decode("utf-8", errors="replace"))
                cur_lines = normalized_lines(files[p]["src"])
                result[p] = (line_similarity(cur_lines, base_lines), len(base_lines))
            else:
                result[p] = None  # tree 等非 blob：无文本可比
        else:
            result[p] = None  # missing：基线无此路径
    proc.stdout.close()
    proc.wait()
    return result


SIM_TIERS = (
    (80.0, "≥80%", "仍上游主体", "still_upstream"),
    (40.0, "40-80%", "半血甄别", "half_blood"),
    (-1.0, "<40%", "基本自有", "mostly_own"),
)


def sim_tier(pct):
    for floor, rng, label, key in SIM_TIERS:
        if pct >= floor:
            return label, key
    return SIM_TIERS[-1][2], SIM_TIERS[-1][3]


def git_changed_files(base):
    out = subprocess.run(
        ["git", "diff", "--name-only", base, "HEAD", "--", "src/"],
        capture_output=True, text=True, check=True).stdout
    return set(out.split())


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--json", help="把结构化结果写入该路径")
    args = ap.parse_args()

    files = load_files()
    if not files:
        sys.exit(f"错误：在 {APP_MAIN} 下没找到 Kotlin 文件，请在仓库根运行。")
    if not os.path.exists(MANIFEST):
        sys.exit(f"错误：找不到 {MANIFEST}，请在仓库根运行。")
    index = build_symbol_index(files)
    roots, missing_roots = manifest_roots(index)
    spi = spi_roots(index)
    roots = sorted(set(roots) | set(spi))
    live, unresolved = closure(roots, files, index)
    dead = {p: i for p, i in files.items() if p not in live}

    base = root_commit()
    changed = git_changed_files(base)
    cur = set(files)
    common = cur & {p for p in
                    subprocess.run(["git", "ls-tree", "-r", "--name-only", base,
                                    "--", "src/"], capture_output=True,
                                   text=True, check=True).stdout.split()
                    if p.endswith(".kt")}
    unchanged = common - changed
    modified = common & changed
    added = cur - common

    def loc(sel):
        return sum(files[p]["loc"] for p in sel)

    def matrix(sel):
        return {
            "上游未动": [sorted(unchanged & sel), loc(unchanged & sel)],
            "上游改动": [sorted(modified & sel), loc(modified & sel)],
            "Novex新增": [sorted(added & sel), loc(added & sel)],
        }

    dead_by_pkg = defaultdict(list)
    for p in sorted(dead):
        dead_by_pkg[files[p]["pkg"]].append(
            {"file": p, "loc": files[p]["loc"]})

    live_by_pkg = defaultdict(lambda: [0, 0])
    for p in live:
        live_by_pkg[files[p]["pkg"]][0] += 1
        live_by_pkg[files[p]["pkg"]][1] += files[p]["loc"]

    print(f"基线（上游导入）: {base}")
    print(f"根组件: {len(roots)} 个已解析（Manifest+SPI {len(spi)}）, 未解析名 {len(missing_roots)}"
          f"（别名等非类名）, fail-open import {unresolved}")
    print()
    print("== 活代码血统构成 ==")
    for k, (fs, l) in matrix(live).items():
        print(f"  {k}: {len(fs)} 文件 {l} 行")
    print()

    # 第四维（P3.4）：混合件相似度。上游未动桶与基线逐字节一致（相似度
    # 恒 100%），Novex 新增桶无同路径基线可比，均不算——只算改动桶。
    mixed = sorted(modified & live)
    sims = baseline_similarities(base, mixed, files)
    sim_rows = []
    for p in mixed:
        entry = sims.get(p)
        if entry is None:
            sim_rows.append({"file": p, "loc": files[p]["loc"],
                             "similarity": None, "verdict": "无基线"})
            continue
        pct = entry[0] * 100.0
        label, _ = sim_tier(pct)
        sim_rows.append({"file": p, "loc": files[p]["loc"],
                         "similarity": round(pct, 1), "verdict": label})
    sim_rows.sort(key=lambda r: (-(r["similarity"] if r["similarity"] is not None else -1.0),
                                 r["file"]))
    tiers_stat = {key: [0, 0] for _, _, _, key in SIM_TIERS}
    for r in sim_rows:
        if r["similarity"] is None:
            continue
        _, key = sim_tier(r["similarity"])
        tiers_stat[key][0] += 1
        tiers_stat[key][1] += r["loc"]

    print(f"== 混合件与上游基线相似度（{len(mixed)} 件，降序；保守口径：剥注释+空白归一+大小写折叠，标识符不折叠）==")
    for floor, rng, label, key in SIM_TIERS:
        n_f, n_l = tiers_stat[key]
        share = n_l / max(1, loc(mixed)) * 100
        print(f"  [{rng} {label}] {n_f} 文件 {n_l} 行（占改动桶 {share:.1f}%）")
    print(f"  {'相似度':>7}  {'行数':>5}  {'基线净行':>6}  判定        文件")
    for r in sim_rows:
        pct = "-" if r["similarity"] is None else f"{r['similarity']:.1f}%"
        b = sims.get(r["file"])
        base_loc = "-" if b is None else b[1]
        print(f"  {pct:>7}  {r['loc']:>5}  {base_loc:>6}  {r['verdict']:<10}  {r['file']}")
    print()
    print(f"== 死代码: {len(dead)} 文件 {loc(dead)} 行 ==")
    for pkg in sorted(dead_by_pkg, key=lambda x: -sum(f["loc"] for f in dead_by_pkg[x])):
        files_list = dead_by_pkg[pkg]
        total = sum(f["loc"] for f in files_list)
        print(f"  [{pkg}] {len(files_list)} 文件 {total} 行")
        for f in files_list:
            print(f"      {os.path.basename(f['file'])} ({f['loc']})")
    print()
    print("== 活代码按包（前 20）==")
    for pkg in sorted(live_by_pkg, key=lambda x: -live_by_pkg[x][1])[:20]:
        n, l = live_by_pkg[pkg]
        print(f"  {pkg}: {n} 文件 {l} 行")

    if args.json:
        json.dump({
            "base_commit": base,
            "roots": roots,
            "unresolved_imports": unresolved,
            "provenance_live": {k: {"files": v[0], "loc": v[1]}
                                for k, v in matrix(live).items()},
            "mixed_similarity": {
                "method": "strip-comments + whitespace-normalize + casefold, "
                          "line-level SequenceMatcher (autojunk off)",
                "tiers": {key: {"files": tiers_stat[key][0], "loc": tiers_stat[key][1]}
                          for _, _, _, key in SIM_TIERS},
                "files": sim_rows,
            },
            "dead": {pkg: dead_by_pkg[pkg] for pkg in dead_by_pkg},
            "live_by_pkg": {p: {"files": v[0], "loc": v[1]}
                            for p, v in live_by_pkg.items()},
        }, open(args.json, "w"), ensure_ascii=False, indent=1)
        print(f"\nJSON 已写入 {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
