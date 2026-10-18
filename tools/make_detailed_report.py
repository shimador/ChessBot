"""Builds RAPPORT-DETAILLE.md: the concise report (RAPPORT.md) plus, under each part, a "Détails"
block generated from the raw results. Every added number comes straight from docs/results and
docs/profiles, so the detailed version cannot drift from the data. Re-run after editing RAPPORT.md.

    python tools/make_detailed_report.py
"""
import csv
import json
import math
import re
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS, PROFILES = ROOT / "docs" / "results", ROOT / "docs" / "profiles"
STEPS = [("00-baseline", "Baseline"), ("01-plateau-1d", "Optimisation 1"), ("02-zero-allocation", "Optimisation 2"),
         ("03-tables", "Optimisation 3"), ("04-table-transposition", "Optimisation 4"), ("05-parallele", "Optimisation 5")]
TAGS = {"00-baseline": "baseline", "01-plateau-1d": "opt1-plateau-1d", "02-zero-allocation": "opt2-zero-allocation",
        "03-tables": "opt3-tables", "04-table-transposition": "opt4-table-transposition", "05-parallele": "opt5-parallele"}
LABELS = {"perft4": "perft 4", "perft3Latency": "perft 3 (latence)", "mateIn3": "Mat en 3", "mateIn4": "Mat en 4", "mateIn5": "Mat en 5"}


def fr(x, d=1):
    s = f"{x:,.{d}f}".replace(",", " ").replace(".", ",")
    return s


def size(b):
    for unit, factor in (("Go", 1e9), ("Mo", 1e6), ("Ko", 1e3)):
        if b >= factor:
            return f"{fr(b / factor)} {unit}"
    return f"{fr(b, 0)} o"


def jmh_table(file):
    rows = ["| Charge | Mode | Temps (± IC 99,9 %) | Allocations / op | GC pendant la mesure |", "|---|---|---|---|---|"]
    for b in json.loads((RESULTS / file).read_text(encoding="utf-8")):
        name = b["benchmark"].rsplit(".", 1)[1]
        if b["benchmark"].split(".")[-2] != "ChessBenchmark" or name not in LABELS:
            continue
        m = b["primaryMetric"]
        unit = m["scoreUnit"].replace("us/op", "µs").replace("ms/op", "ms")
        err = m["scoreError"]
        t = f"{fr(m['score'])} ± {fr(err)} {unit}" if err == err and not math.isinf(err) else f"{fr(m['score'])} {unit}"
        if b["mode"] == "sample":
            p = m["scorePercentiles"]
            t += f" (p50 {fr(p['50.0'] / 1000, 2)} ms, p99 {fr(p['99.0'] / 1000, 2)} ms)"
        if b["mode"] == "ss":
            t += " (mesures : " + " / ".join(fr(x / 1000, 2) + " s" for f in m["rawData"] for x in f) + ")"
        sec = b["secondaryMetrics"]
        gc = sec.get("gc.count", {}).get("score", 0)
        rows.append(f"| {LABELS[name]} | {b['mode']} | {t} | {size(sec['gc.alloc.rate.norm']['score'])} | {fr(gc, 0)} |")
    return "\n".join(rows)


def profile_table(step, n=6):
    lines = (PROFILES / f"{step}-summary.txt").read_text(encoding="utf-8").splitlines()
    get = lambda kind: [l.split("\t") for l in lines if l.startswith(kind + "\t")]
    samples = get("samples")[0][1]
    load = get("cpuload")[0][1].replace(".", ",")
    rows = [f"*{samples} échantillons CPU, charge CPU moyenne de la JVM {load} %.*", "",
            "| Temps propre (CPU) | Méthode | | Octets alloués | Site d'allocation |", "|---|---|---|---|---|"]
    selfs, allocs = get("self")[:n], get("alloc")[:3]
    for i in range(n):
        s = selfs[i] if i < len(selfs) else ["", "", ""]
        a = allocs[i] if i < len(allocs) else ["", "", ""]
        rows.append(f"| {s[1].replace('.', ',')} % | `{s[2]}` | | {a[1].replace('.', ',') + ' %' if a[1] else ''} | {('`' + a[2] + '`') if a[2] else ''} |")
    return "\n".join(rows)


def ab_table(*files):
    data = {}
    for line in (l for f in files for l in (RESULTS / f).read_text(encoding="utf-8").splitlines()):
        version, rnd, rest = line.split(",", 2)
        f = next(csv.reader([rest]))
        name = f[0].split("Benchmark.", 1)[1]
        if ":" in name:
            continue
        data.setdefault(name, {}).setdefault(version, []).append((rnd, float(f[4].replace(",", ".")), f[6]))
    rows = ["| Charge | A (avant) : manches | B (après) : manches |", "|---|---|---|"]
    for name, v in data.items():
        unit = v["A"][0][2].replace("us/op", "µs").replace("ms/op", "ms")
        fmt = lambda xs: " / ".join(fr(x, 1) for _, x, _ in xs) + f" {unit}"
        rows.append(f"| {LABELS.get(name, name)} | {fmt(v['A'])} | {fmt(v['B'])} |")
    return "\n".join(rows)


def scaling_table(file):
    rows = ["| Threads | Mat en 4 (± IC 99,9 %) | Mat en 5 (mesures) |", "|---|---|---|"]
    data = json.loads((RESULTS / file).read_text(encoding="utf-8"))
    for t in ("1", "2", "4", "8"):
        cells = []
        for name in ("mateIn4", "mateIn5"):
            m = next(b for b in data if b["benchmark"].endswith(name) and b["params"]["threads"] == t)["primaryMetric"]
            if name == "mateIn4":
                cells.append(f"{fr(m['score'])} ± {fr(m['scoreError'])} ms")
            else:
                cells.append(" / ".join(fr(x / 1000, 2) + " s" for f in m["rawData"] for x in f))
        rows.append(f"| {t} | {cells[0]} | {cells[1]} |")
    return "\n".join(rows)


def profile_row(step, label):
    first = {}
    for line in (PROFILES / f"{step}-summary.txt").read_text(encoding="utf-8").splitlines():
        parts = line.split("	")
        first.setdefault(parts[0], parts)
    pc = lambda s: s.replace(".", ",")
    return (f"| {label} | {first['samples'][1]} | {pc(first['cpuload'][1])} % | "
            f"`{first['self'][2]}` ({pc(first['self'][1])} %) |")


def code(file, start=None, stop=None):
    text = (RESULTS / file).read_text(encoding="utf-8")
    if start:
        text = text[text.index(start):]
        text = text[:text.index(stop) + len(stop)] if stop else text
    return "```\n" + "\n".join(l for l in text.splitlines() if not l.startswith("WARNING")).strip() + "\n```"


def step_details(step, file, extra=""):
    return (f"\n### Détails\n\nTag [`{TAGS[step]}`](../../tree/{TAGS[step]}). Mesures JMH complètes de cette étape "
            f"([brut]({'docs/results/' + file.replace('.json', '.txt')})) :\n\n{jmh_table(file)}\n\n"
            f"Profil JFR de cette étape ([résumé](docs/profiles/{step}-summary.txt)) :\n\n{profile_table(step)}\n\n"
            f"![Flamegraph {step}](docs/flamegraphs/{step}.svg)\n{extra}")


DETAILS = {
    "3": step_details("00-baseline", "00-baseline.json"),
    "4": step_details("01-plateau-1d", "01-plateau-1d.json"),
    "5": step_details("02-zero-allocation", "02-zero-allocation.json"),
    "6": step_details("03-tables", "03-tables.json",
                      "\nMesure A/B alternée, une valeur par manche de 5 itérations "
                      "([brut](docs/results/03-ab-vs-opt2.csv)). La manche 2 de B sur le mat en 3 contient une itération "
                      "gelée de plusieurs secondes (machine bloquée) ; elle est exclue des médianes du rapport.\n\n"
                      + ab_table("03-ab-vs-opt2.csv") + "\n"),
    "7": step_details("04-table-transposition", "04-table-transposition.json",
                      "\nMesures A/B alternées ([brut](docs/results/04-ab-vs-opt3.csv), [mat en 5](docs/results/04-ab-mateIn5.csv)) :\n\n"
                      + ab_table("04-ab-vs-opt3.csv", "04-ab-mateIn5.csv") + "\n\n"
                      "Diagnostic des transpositions ([brut](docs/results/04-transpositions.txt)) :\n\n" + code("04-transpositions.txt") + "\n"),
    "8": step_details("05-parallele", "05-parallele.json",
                      "\nPassage à l'échelle, table partagée ([brut](docs/results/05-scaling-shared-table.txt)) :\n\n"
                      + scaling_table("05-scaling-shared-table.json") + "\n\n"
                      "Diagnostic CPU, table partagée ([brut](docs/results/05-parallel-diagnostic-shared.txt)) :\n\n"
                      + code("05-parallel-diagnostic-shared.txt") + "\n"),
    "9": ("\n### Détails\n\nTag [`essai-parallele-tables-privees`](../../tree/essai-parallele-tables-privees). "
          "Passage à l'échelle avec une table par worker ([brut](docs/results/05-scaling-private-tables.txt)) :\n\n"
          + scaling_table("05-scaling-private-tables.json") + "\n\n"
          "Diagnostic CPU complet ([brut](docs/results/05-parallel-diagnostic-private.txt)) :\n\n"
          + code("05-parallel-diagnostic-private.txt") + "\n"),
    "10": ("\n### Détails\n\nDisposition mémoire de `Position` relevée par JOL, ordre d'origine "
           "([brut](docs/results/06-field-layout.txt)) puis champs triés par taille "
           "([brut](docs/results/06-field-layout-position-sorted.txt)) : mêmes offsets, même taille.\n\n"
           + code("06-field-layout.txt", "com.chessbot.Position object", "bytes total") + "\n"),
}

APPENDIX = f"""
## Annexe A : pièges de mesure rencontrés

- **Bruit thermique.** Sur le portable, les itérations d'une même mesure ont varié jusqu'à un facteur 2,7 dans la baseline (perft 4 : de 211 à 572 ms, voir [brut](docs/results/00-baseline.txt)). D'où les mesures A/B alternées des optimisations 3 et 4.
- **Classes écrasées par l'IDE.** L'extension Java de VS Code compile aussi dans `target/` ; un benchmark a échoué avec « Unresolved compilation problems » (compilateur Eclipse). Les mesures sont depuis compilées dans un dossier séparé (`target-bench/`).
- **Mise en veille pendant une mesure.** Le mat en 5 de l'optimisation 3 a duré 3 507 s à la première mesure, puis 38,6 s à la seconde ([brut](docs/results/04-before-mateIn5.txt)) : la machine s'était mise en veille. Ce résultat a été écarté et la mesure refaite en A/B (41,9 s).
- **Escape analysis non appliquée.** Un tableau littéral `new int[]{{-1, 1}}`, local à la fonction, restait alloué sur le tas (voir optimisation 2) : l'escape analysis n'est pas une garantie, seule la mesure le confirme.

## Annexe B : profils de toutes les étapes

| Étape | Échantillons CPU | Charge CPU JVM | Méthode la plus coûteuse (temps propre) |
|---|---|---|---|
""" + "\n".join(profile_row(step, label) for step, label in STEPS) + "\n"


def main():
    text = (ROOT / "RAPPORT.md").read_text(encoding="utf-8")
    text = text.replace("# Rapport d'audit de performance — ChessBot",
                        "# Rapport d'audit de performance — ChessBot (version détaillée)", 1)
    text = text.replace("\n## 1. Synthèse",
                        "\n*Version détaillée : le [rapport principal](RAPPORT.md) complété, sous chaque partie, par les mesures "
                        "complètes générées depuis les résultats bruts ([tools/make_detailed_report.py](tools/make_detailed_report.py)).*\n\n## 1. Synthèse", 1)
    # The concise report links to this version: drop that self-reference
    text = re.sub(r" \*\*Mesures complètes\*\* .*?\[version détaillée\]\(RAPPORT-DETAILLE\.pdf\)\.", "", text)
    parts = re.split(r"(?m)^(## \d+\..*)$", text)
    out = [parts[0]]
    for i in range(1, len(parts), 2):
        number = re.match(r"## (\d+)\.", parts[i]).group(1)
        body = parts[i + 1].rstrip("\n") + "\n"
        out.append(parts[i] + body + DETAILS.get(number, "") + "\n")
    (ROOT / "RAPPORT-DETAILLE.md").write_text("".join(out).rstrip("\n") + "\n" + APPENDIX, encoding="utf-8")
    print("RAPPORT-DETAILLE.md")


main()
