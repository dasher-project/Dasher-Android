#!/usr/bin/env python3
"""Generate Android vector drawables from Lucide SVGs (RFC 0002).

Provenance for app/src/main/res/drawable/ic_lucide_*.xml — the icons the
IME's classic-View toolbars render. Checked in after rev 1 of the IME icon
work silently dropped every <rect> (4 of 9 icons rendered as broken
glyphs); rerun + diff to regenerate when icons change:

    ./scripts/gen_lucide_icons.py icons/  # dir of lucide-static SVGs
    # or fetch: curl -sfL https://unpkg.com/lucide-static@1.62.0/icons/<name>.svg

Lucide is ISC-licensed; keep the licence header the template writes.
"""
import os
import re
import sys

TEMPLATE = '''<!-- Lucide {name} (lucide-static v1.62.0, ISC licence — dasher-project/governance RFC 0002).
     Stroke-only 24dp vector; tint applied at the ImageView for theming. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
{paths}
</vector>
'''

PATH_T = '    <path\n        android:pathData="{d}"\n        android:strokeColor="#FF000000"\n        android:strokeWidth="2"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n        android:fillColor="#00000000" />'


def rect_to_path(x, y, w, h, rx):
    """Rounded rect as a stroke path — <rect> has no vector-drawable equivalent."""
    if rx > 0:
        return (f"M{x+rx} {y} h{w-2*rx} a{rx} {rx} 0 0 1 {rx} {rx} v{h-2*rx} "
                f"a{rx} {rx} 0 0 1 {-rx} {rx} h{-(w-2*rx)} a{rx} {rx} 0 0 1 {-rx} {-rx} "
                f"v{-(h-2*rx)} a{rx} {rx} 0 0 1 {rx} {-rx} z")
    return f"M{x} {y} h{w} v{h} h{-w} z"


def parse_svg(svg):
    parts = list(re.findall(r'<path d="([^"]+)"', svg))
    for m in re.finditer(r'<rect([^/]*)/>', svg):
        attrs = dict(re.findall(r'(\w+)="([^"]+)"', m.group(1)))
        x = float(attrs.get('x', 0))
        y = float(attrs.get('y', 0))
        w = float(attrs.get('width', 0))
        h = float(attrs.get('height', 0))
        rx = float(attrs.get('rx', attrs.get('ry', 0)))
        parts.append(rect_to_path(x, y, w, h, rx))
    return parts


def main(src_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for f in sorted(os.listdir(src_dir)):
        if not f.endswith('.svg'):
            continue
        name = f[:-4]
        parts = parse_svg(open(os.path.join(src_dir, f)).read())
        if not parts:
            sys.exit(f"NO GEOMETRY PARSED from {f} — generator or source broken")
        xml = TEMPLATE.format(name=name, paths="\n".join(PATH_T.format(d=d) for d in parts))
        out = os.path.join(out_dir, "ic_lucide_" + name.replace('-', '_') + ".xml")
        open(out, 'w').write(xml)
        print(f"{name}: {len(parts)} pathDatas -> {os.path.basename(out)}")


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else
         "app/src/main/res/drawable")
