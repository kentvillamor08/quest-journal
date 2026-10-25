#!/usr/bin/env python3
"""Generates signature-only stubs of the Minecraft/Forge classes used by the mod from spec.txt (sandbox compile check only)."""
import os, re, sys
spec, out = sys.argv[1], sys.argv[2]
classes = []  # (fqn, header, [lines])
for line in open(spec, encoding='utf-8'):
    line = line.rstrip('\n')
    if not line.strip() or line.lstrip().startswith('#'):
        continue
    if line.startswith('@ '):
        fqn, header = [p.strip() for p in line[2:].split('|', 1)]
        classes.append((fqn, header, []))
    else:
        classes[-1][2].append(line)
pkgs = sorted({c[0].rsplit('.', 1)[0] for c in classes})
for fqn, header, body in classes:
    pkg, name = fqn.rsplit('.', 1)
    imports = ['import java.util.*;', 'import java.util.function.*;', 'import java.util.concurrent.*;', 'import java.nio.file.Path;']
    imports += ['import %s.*;' % p for p in pkgs if p != pkg]
    d = os.path.join(out, *pkg.split('.')); os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, name + '.java'), 'w') as f:
        f.write('package %s;\n%s\n@SuppressWarnings("all")\npublic %s {\n%s\n}\n' % (pkg, '\n'.join(imports), header, '\n'.join(body)))
print(len(classes), 'stub classes generated')
