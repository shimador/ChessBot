"""Annotates a flamegraph.pl SVG: outlines every frame of the given methods, numbers them, and adds
a legend with their share read from the profile summary (tools/JfrToFlame.java).

    python tools/annotate_flamegraph.py in.svg out.svg summary.txt self|alloc Method.a Method.b ...
"""
import html
import re
import sys
from pathlib import Path

INK, MUTED = "#0b0b0b", "#52514e"
FONT = "Segoe UI, Calibri, Arial, sans-serif"
KIND_LABEL = {"self": "du temps CPU (temps propre)", "alloc": "des octets alloués"}


def main(src, dst, summary, kind, methods):
    svg = Path(src).read_text(encoding="utf-8")
    shares = {}
    for line in Path(summary).read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if parts[0] == kind:
            shares.setdefault(parts[2], float(parts[1]))

    frames = re.findall(r"<title>([^<]*)</title>\s*<rect x=\"([\d.]+)\" y=\"([\d.]+)\" width=\"([\d.]+)\" height=\"([\d.]+)\"", svg)
    overlay = []
    for number, method in enumerate(methods, 1):
        hits = [(float(x), float(y), float(w), float(h)) for title, x, y, w, h in frames
                if html.unescape(title).split(" (")[0] == method]
        for x, y, w, h in hits:
            overlay.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" fill="none" '
                           f'stroke="{INK}" stroke-width="2.5" rx="2"/>')
        if hits:  # numbered badge on the widest frame of the method
            x, y, w, h = max(hits, key=lambda r: r[2])
            cx, cy = (x - 14 if x > 28 else x + w + 14), y + h / 2  # left of the frame, never over another one
            overlay.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="12" fill="{INK}"/>'
                           f'<text x="{cx:.1f}" y="{cy + 5:.1f}" font-family="{FONT}" font-weight="700" '
                           f'style="font-size:15px;fill:#ffffff" text-anchor="middle">{number}</text>')

    width = float(re.search(r'<svg[^>]*width="([\d.]+)"', svg).group(1))
    height = float(re.search(r'<svg[^>]*height="([\d.]+)"', svg).group(1))
    legend_h = 24 + 40 * len(methods)
    legend = [f'<rect x="0" y="{height:.1f}" width="{width:.1f}" height="{legend_h}" fill="#ffffff"/>']
    for number, method in enumerate(methods, 1):
        y = height + 38 + 40 * (number - 1)
        share = shares.get(method)
        label = method if share is None else f"{method} : {f'{share:.1f}'.replace('.', ',')} % {KIND_LABEL[kind]}"
        legend.append(f'<circle cx="30" cy="{y - 8:.1f}" r="14" fill="{INK}"/>'
                      f'<text x="30" y="{y - 2:.1f}" font-family="{FONT}" font-weight="700" style="font-size:17px;fill:#ffffff" text-anchor="middle">{number}</text>'
                      f'<text x="54" y="{y:.1f}" font-family="{FONT}"  style="font-size:26px;fill:{INK}">{html.escape(label)}</text>')

    new_h = height + legend_h
    svg = re.sub(r'(<svg[^>]*?)height="[\d.]+"', lambda m: f'{m.group(1)}height="{new_h:.0f}"', svg, count=1)
    svg = re.sub(r'viewBox="0 0 ([\d.]+) [\d.]+"', lambda m: f'viewBox="0 0 {m.group(1)} {new_h:.0f}"', svg, count=1)
    svg = svg.replace("</svg>", "".join(overlay) + "".join(legend) + "</svg>")
    Path(dst).write_text(svg, encoding="utf-8")
    print(dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:])
