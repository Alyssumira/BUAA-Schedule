"""Measure how many of the app's own profile rules survive into the *shipped* profile.

The dex-based audit answers "does the source still declare this signature". This answers
the harder question, because AGP's own intermediates are on disk:

    mergeReleaseArtProfile          -> text profile as packaged (ours + every AAR's)
    expandReleaseArtProfileWildcards-> same, with `Lfoo/*;` expanded against this build
    minifyReleaseWithR8             -> R8's remapped result == what becomes baseline.prof

Names in the last file are obfuscated, so they are mapped back to their original
`com.buaa.schedule.*` form through R8's mapping.txt. Only classes that map back into the
app package are counted, which keeps library rules out of the denominator.

Usage:
  python docs/tools/profile_landed.py \
      --mapping app/build/outputs/mapping/release/mapping.txt \
      --expanded app/build/intermediates/r8_art_profile/release/expandReleaseArtProfileWildcards/baseline-prof.txt \
      --shipped app/build/intermediates/r8_art_profile/release/minifyReleaseWithR8/baseline-prof.txt \
      --out .tmp/T79/landed-before.md
"""

import argparse
import collections
import re
import sys

CLASS_LINE = re.compile(r'^(\S+) -> ([^:]+):$')
RULE_CLASS = re.compile(r'^[HSP]*L([^;]+);')
APP_ORIG = 'com.buaa.schedule.'
APP_DESC = 'Lcom/buaa/schedule/'


def load_mapping(path):
    """obfuscated class descriptor -> original class name (app package only)."""
    out = {}
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            if not line or line[0] in ' \t\n' or line.startswith('pg'):
                continue
            m = CLASS_LINE.match(line.rstrip('\n'))
            if not m:
                continue
            orig, obf = m.group(1), m.group(2)
            if orig.startswith(APP_ORIG):
                out['L' + obf.replace('.', '/') + ';'] = 'L' + orig.replace('.', '/') + ';'
    return out


def count(path, obf2orig=None):
    """-> (rules, app_rules) ; app_rules attributed back to the app package if mapping given."""
    rules, app = 0, 0
    seen = set()
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            rules += 1
            m = RULE_CLASS.match(line)
            if not m:
                continue
            desc = 'L' + m.group(1) + ';'
            if desc.startswith(APP_DESC):
                app += 1
            elif obf2orig and desc in obf2orig:
                app += 1
                seen.add(obf2orig[desc])
    return rules, app, seen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--mapping', required=True)
    ap.add_argument('--expanded', required=True)
    ap.add_argument('--shipped', required=True)
    ap.add_argument('--out')
    args = ap.parse_args()

    obf2orig = load_mapping(args.mapping)
    er, eapp, _ = count(args.expanded)
    sr, sapp, survivors = count(args.shipped, obf2orig)

    lines = []
    w = lines.append
    w('# 实际落到二进制 profile 里的规则数（R8 之后，按 mapping 反查归属）')
    w('')
    w('| 阶段 | 全部规则 | 归属 com.buaa.schedule |')
    w('|---|---|---|')
    w('| expandReleaseArtProfileWildcards（R8 的输入） | %d | %d |' % (er, eapp))
    w('| minifyReleaseWithR8（R8 的输出，进包的那份） | %d | %d |' % (sr, sapp))
    w('')
    w('R8 输入侧 %d 条本应用规则 -> 输出侧 %d 条，**存活 %.1f%%**。'
      % (eapp, sapp, 100.0 * sapp / max(eapp, 1)))
    w('')
    w('存活规则按包族分布（反混淆后）：')
    w('')
    fam = collections.Counter()
    for desc in survivors:
        parts = desc[1:].split('/')
        fam['/'.join(parts[1:-1]) if len(parts) > 2 else '(root)'] += 1
    # explicit name tiebreak: Counter.most_common() leaves equal counts in insertion
    # order, which depends on dict iteration and would make this report non-reproducible
    for name, n in sorted(fam.items(), key=lambda kv: (-kv[1], kv[0])):
        w('- %s: %d' % (name, n))
    w('')
    w('⚠️ 口径：输出侧的名字是 R8 混淆后的，类被合/被 keep 与否都会影响归属；'
      '这里的"存活"是**类级**归属，方法级只能数条数，不做逐条对账。'
      '类被 R8 整个删掉的规则在这里和 dex 对表里都算丢了，两把尺子互相印证。')

    report = '\n'.join(lines) + '\n'
    sys.stdout.buffer.write(report.encode('utf-8', 'backslashreplace'))
    if args.out:
        with open(args.out, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(report)


if __name__ == '__main__':
    main()
