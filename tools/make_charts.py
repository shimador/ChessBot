"""Generates the report charts (static SVG) from the raw JMH results in docs/results.

    python tools/make_charts.py
"""
import json
import math
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS, FIGURES = ROOT / "docs" / "results", ROOT / "docs" / "figures"

# Palette: reference categorical slots 1 and 2, validated (validate_palette.js, light mode: all checks pass)
SERIES_1, SERIES_2 = "#2a78d6", "#eb6834"
SURFACE, TEXT, TEXT_2, GRID = "#ffffff", "#0b0b0b", "#52514e", "#e4e3df"
FONT = "Segoe UI, Calibri, Arial, sans-serif"


def bench(file, name, threads=None):
    for b in json.loads((RESULTS / file).read_text(encoding="utf-8")):
        if b["benchmark"].endswith("." + name) and (threads is None or b.get("params", {}).get("threads") == threads):
            return b["primaryMetric"]
    raise KeyError(name)


def fr(x, decimals):
    return f"{x:.{decimals}f}".replace(".", ",")


def text(x, y, s, size=12, color=TEXT, anchor="middle", weight="normal"):
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FONT}" font-size="{size}" fill="{color}" '
            f'text-anchor="{anchor}" font-weight="{weight}">{s}</text>')


def bar(x, y, w, h, color, r=4):
    """Bar anchored to the baseline: rounded top corners only."""
    r = min(r, h, w / 2)
    return (f'<path d="M{x:.1f},{y + h:.1f} V{y + r:.1f} Q{x:.1f},{y:.1f} {x + r:.1f},{y:.1f} '
            f'H{x + w - r:.1f} Q{x + w:.1f},{y:.1f} {x + w:.1f},{y + r:.1f} V{y + h:.1f} Z" fill="{color}"/>')


def svg(width, height, body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" '
            f'role="img" aria-label="{title}"><rect width="100%" height="100%" fill="{SURFACE}"/>{body}</svg>\n')


def chart_steps():
    """Mate in 4 per step, log scale (values span two orders of magnitude)."""
    final = statistics.median(x for fork in bench("05-parallele.json", "mateIn4")["rawData"] for x in fork)
    steps = [
        ("Baseline", bench("00-baseline.json", "mateIn4")["score"]),
        ("1. Plateau 1D", bench("01-plateau-1d.json", "mateIn4")["score"]),
        ("2. Zéro alloc.", bench("02-zero-allocation.json", "mateIn4")["score"]),
        ("3. Tables", bench("03-tables.json", "mateIn4")["score"]),
        ("4. Transposition", bench("04-table-transposition.json", "mateIn4")["score"]),
        ("5. Parallèle", final),
    ]
    W, H, left, right, top, bottom = 760, 330, 64, 20, 46, 54
    lo, hi = 50, 20000  # ms
    y = lambda v: top + (H - top - bottom) * (1 - (math.log10(v) - math.log10(lo)) / (math.log10(hi) - math.log10(lo)))
    body = [text(left, 22, "Temps d'un mat en 4 sur Kiwipete, par étape (échelle logarithmique)", 14, anchor="start", weight="600")]
    for v, label in ((100, "0,1 s"), (1000, "1 s"), (10000, "10 s")):
        body.append(f'<line x1="{left}" x2="{W - right}" y1="{y(v):.1f}" y2="{y(v):.1f}" stroke="{GRID}" stroke-width="1"/>')
        body.append(text(left - 8, y(v) + 4, label, 11, TEXT_2, "end"))
    slot = (W - left - right) / len(steps)
    bw = slot * 0.42
    for i, (name, ms) in enumerate(steps):
        x = left + slot * i + (slot - bw) / 2
        body.append(bar(x, y(ms), bw, y(lo) - y(ms), SERIES_1))
        value = fr(ms / 1000, 1) + " s" if ms >= 2000 else fr(ms / 1000, 2) + " s"
        body.append(text(x + bw / 2, y(ms) - 7, value, 12, TEXT, weight="600"))
        body.append(text(x + bw / 2, H - bottom + 18, name, 11.5, TEXT_2))
    body.append(f'<line x1="{left}" x2="{W - right}" y1="{y(lo):.1f}" y2="{y(lo):.1f}" stroke="{TEXT_2}" stroke-width="1"/>')
    body.append(text(left, H - 10, "Mesures JMH de chaque étape. Étape 3 : écart non significatif sur cette charge (voir partie 6).", 10.5, TEXT_2, "start"))
    return svg(W, H, "".join(body), "Temps du mat en 4 par étape")


def chart_threads():
    """Mate in 4 vs number of worker threads: shared table vs private tables."""
    threads = ["1", "2", "4", "8"]
    shared = [bench("05-scaling-shared-table.json", "mateIn4", t)["score"] for t in threads]
    private = [bench("05-scaling-private-tables.json", "mateIn4", t)["score"] for t in threads]
    W, H, left, right, top, bottom = 760, 320, 64, 190, 46, 48
    hi = 350
    xs = lambda i: left + (W - left - right) * i / (len(threads) - 1)
    y = lambda v: top + (H - top - bottom) * (1 - v / hi)
    body = [text(left, 22, "Temps d'un mat en 4 selon le nombre de threads", 14, anchor="start", weight="600")]
    for v in (0, 100, 200, 300):
        body.append(f'<line x1="{left}" x2="{W - right}" y1="{y(v):.1f}" y2="{y(v):.1f}" stroke="{GRID if v else TEXT_2}" stroke-width="1"/>')
        body.append(text(left - 8, y(v) + 4, f"{v} ms", 11, TEXT_2, "end"))
    for i, t in enumerate(threads):
        body.append(text(xs(i), H - bottom + 18, f"{t} thread{'s' if t != '1' else ''}", 11.5, TEXT_2))
    for values, color, label in ((private, SERIES_2, "Une table par worker (échec)"), (shared, SERIES_1, "Table partagée (retenu)")):
        pts = " ".join(f"{xs(i):.1f},{y(v):.1f}" for i, v in enumerate(values))
        body.append(f'<polyline points="{pts}" fill="none" stroke="{color}" stroke-width="2" stroke-linejoin="round"/>')
        for i, v in enumerate(values):
            body.append(f'<circle cx="{xs(i):.1f}" cy="{y(v):.1f}" r="4.5" fill="{color}" stroke="{SURFACE}" stroke-width="2"/>')
            body.append(text(xs(i), y(v) - 10, fr(v, 0), 11, TEXT))
        body.append(text(xs(3) + 12, y(values[-1]) + 4, label, 11.5, TEXT, "start"))
    # Legend: identity never carried by colour alone (direct labels + legend)
    lx, ly = left, H - 12
    for k, (color, label) in enumerate(((SERIES_1, "Table partagée"), (SERIES_2, "Une table par worker"))):
        cx = lx + k * 170
        body.append(f'<line x1="{cx}" x2="{cx + 18}" y1="{ly - 4}" y2="{ly - 4}" stroke="{color}" stroke-width="2"/>')
        body.append(f'<circle cx="{cx + 9}" cy="{ly - 4}" r="4" fill="{color}"/>')
        body.append(text(cx + 24, ly, label, 11, TEXT_2, "start"))
    return svg(W, H, "".join(body), "Temps du mat en 4 selon le nombre de threads")


FIGURES.mkdir(parents=True, exist_ok=True)
(FIGURES / "mat4-par-etape.svg").write_text(chart_steps(), encoding="utf-8")
(FIGURES / "mat4-par-threads.svg").write_text(chart_threads(), encoding="utf-8")
print("ok")
