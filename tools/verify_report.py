"""Recomputes every number of RAPPORT.md from the raw results (docs/results, docs/profiles) and compares.

    python tools/verify_report.py

Variation = (after - before) / before, rounded like in the report.
"""
import csv
import json
import re
import statistics
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
DOCS = Path(__file__).resolve().parent.parent / "docs"
RESULTS, PROFILES = DOCS / "results", DOCS / "profiles"
failures = checks = 0


def jmh(file, bench, param=None):
    """One JMH benchmark of a JSON result file."""
    for b in json.loads((RESULTS / file).read_text(encoding="utf-8")):
        if b["benchmark"].endswith("." + bench) and (param is None or b.get("params", {}).get("threads") == param):
            return b
    raise KeyError(f"{file}: {bench} {param}")


def score(file, bench, param=None):
    return jmh(file, bench, param)["primaryMetric"]["score"]


def error(file, bench):
    return jmh(file, bench)["primaryMetric"]["scoreError"]


def alloc(file, bench):
    return jmh(file, bench)["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]


def pct(file, bench, p):
    return jmh(file, bench)["primaryMetric"]["scorePercentiles"][p]


def shots(file, bench, param=None):
    return [x for fork in jmh(file, bench, param)["primaryMetric"]["rawData"] for x in fork]


def ab(file):
    """A/B CSV (JMH csv with French decimals, prefixed by version and round) -> {(version, bench): [scores]}."""
    out = {}
    for line in (RESULTS / file).read_text(encoding="utf-8").splitlines():
        version, _round, rest = line.split(",", 2)
        fields = next(csv.reader([rest]))
        name = fields[0].split("Benchmark.", 1)[1]
        if ":" not in name:
            out.setdefault((version, name), []).append(float(fields[4].replace(",", ".")))
    return out


def profile(step, kind, method=None):
    """Value of a line of a profile summary (tools/JfrToFlame.java)."""
    for line in (PROFILES / f"{step}-summary.txt").read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if parts[0] == kind and (method is None or parts[2] == method):
            return float(parts[1])
    raise KeyError(f"{step}: {kind} {method}")


def var(before, after):
    return (after - before) / before * 100


def check(label, reported, computed, decimals=0):
    """reported: value written in the report; computed: value from the raw data."""
    global failures, checks
    checks += 1
    ok = round(computed, decimals) == reported
    failures += not ok
    print(f"{'OK ' if ok else 'ERR'}  {label:<56} rapport {reported:>9}   recalcul {computed:>11.{decimals + 2}f}")


B, O1, O2, O3, O4, O5 = ("00-baseline.json", "01-plateau-1d.json", "02-zero-allocation.json",
                         "03-tables.json", "04-table-transposition.json", "05-parallele.json")
P0, P1, P2, P3, P4, P5 = ("00-baseline", "01-plateau-1d", "02-zero-allocation", "03-tables",
                          "04-table-transposition", "05-parallele")
MB = 1e6
med = statistics.median

print("== Parties 1 et 3 : baseline")
check("perft 4 (ms)", 329.5, score(B, "perft4"), 1)
check("perft 3 p50 (ms)", 10.7, pct(B, "perft3Latency", "50.0") / 1000, 1)
check("perft 3 p99 (ms)", 54.8, pct(B, "perft3Latency", "99.0") / 1000, 1)
check("mat en 3 (ms)", 236.6, score(B, "mateIn3"), 1)
check("mat en 4 (s)", 14.8, score(B, "mateIn4") / 1000, 1)
check("alloc perft 4 (Mo)", 258.8, alloc(B, "perft4") / MB, 1)
check("alloc perft 3 (Mo)", 11.7, alloc(B, "perft3Latency") / MB, 1)
check("alloc mat en 3 (Mo)", 254.8, alloc(B, "mateIn3") / MB, 1)
check("alloc mat en 4 (Go)", 15.5, alloc(B, "mateIn4") / 1e9, 1)
check("profil : Position.copy, temps propre (%)", 25.9, profile(P0, "self", "Position.copy"), 1)
check("profil : legalMoves, temps propre (%)", 25.1, profile(P0, "self", "MoveGenerator.legalMoves"), 1)
check("profil : <init> + copy, octets alloués (%)", 92.4,
      profile(P0, "alloc", "Position.<init>") + profile(P0, "alloc", "Position.copy"), 1)

print("== Partie 4 : plateau 1D (avant = baseline)")
check("perft 4 (ms)", 39.6, score(O1, "perft4"), 1)
check("perft 4 variation (%)", -88, var(score(B, "perft4"), score(O1, "perft4")))
check("perft 3 p50 (ms)", 1.42, pct(O1, "perft3Latency", "50.0") / 1000, 2)
check("perft 3 p99 (ms)", 3.33, pct(O1, "perft3Latency", "99.0") / 1000, 2)
check("perft 3 p50 variation (%)", -87, var(pct(B, "perft3Latency", "50.0"), pct(O1, "perft3Latency", "50.0")))
check("perft 3 p99 variation (%)", -94, var(pct(B, "perft3Latency", "99.0"), pct(O1, "perft3Latency", "99.0")))
check("mat en 3 (ms)", 57.4, score(O1, "mateIn3"), 1)
check("mat en 3 variation (%)", -76, var(score(B, "mateIn3"), score(O1, "mateIn3")))
check("mat en 4 (s)", 3.1, score(O1, "mateIn4") / 1000, 1)
check("mat en 4 variation (%)", -79, var(score(B, "mateIn4"), score(O1, "mateIn4")))
check("alloc perft 4 (Mo)", 86.7, alloc(O1, "perft4") / MB, 1)
check("alloc perft 4 variation (%)", -66, var(alloc(B, "perft4"), alloc(O1, "perft4")))

print("== Partie 5 : zéro allocation (avant = opt 1)")
check("profil opt 1 : Position.copy, octets alloués (%)", 84.4, profile(P1, "alloc", "Position.copy"), 1)
check("alloc mat en 4 opt 1 (Go)", 5.2, alloc(O1, "mateIn4") / 1e9, 1)
check("perft 4 (ms)", 15.5, score(O2, "perft4"), 1)
check("perft 4 variation (%)", -61, var(score(O1, "perft4"), score(O2, "perft4")))
check("perft 3 p50 (ms)", 0.82, pct(O2, "perft3Latency", "50.0") / 1000, 2)
check("perft 3 p99 (ms)", 1.95, pct(O2, "perft3Latency", "99.0") / 1000, 2)
check("perft 3 p50 variation (%)", -43, var(pct(O1, "perft3Latency", "50.0"), pct(O2, "perft3Latency", "50.0")))
check("perft 3 p99 variation (%)", -41, var(pct(O1, "perft3Latency", "99.0"), pct(O2, "perft3Latency", "99.0")))
check("mat en 3 (ms)", 23.5, score(O2, "mateIn3"), 1)
check("mat en 3 variation (%)", -59, var(score(O1, "mateIn3"), score(O2, "mateIn3")))
check("mat en 4 (s)", 1.07, score(O2, "mateIn4") / 1000, 2)
check("mat en 4 variation (%)", -66, var(score(O1, "mateIn4"), score(O2, "mateIn4")))
check("alloc perft 4 (o)", 54, alloc(O2, "perft4"))
check("alloc mat en 4 (Ko)", 6.8, alloc(O2, "mateIn4") / 1000, 1)
check("erreur perft 4 avant (ms)", 8.3, error(O1, "perft4"), 1)
check("erreur perft 4 après (ms)", 0.3, error(O2, "perft4"), 1)

print("== Partie 6 : tables (A/B, avant = opt 2)")
r3 = ab("03-ab-vs-opt2.csv")
mate3_b = [x for x in r3[("B", "mateIn3")] if x < 1000]  # the frozen iteration (> 2 s) is excluded
check("profil opt 2 : isKingAttacked, temps total (%)", 54.2, profile(P2, "total", "MoveGenerator.isKingAttacked"), 1)
check("profil opt 2 : isKingAttacked, temps propre (%)", 9.7, profile(P2, "self", "MoveGenerator.isKingAttacked"), 1)
check("perft 4 avant (ms)", 15.9, med(r3[("A", "perft4")]), 1)
check("perft 4 après (ms)", 13.4, med(r3[("B", "perft4")]), 1)
check("perft 4 variation (%)", -16, var(med(r3[("A", "perft4")]), med(r3[("B", "perft4")])))
check("perft 3 avant (µs)", 826, med(r3[("A", "perft3Latency")]))
check("perft 3 après (µs)", 530, med(r3[("B", "perft3Latency")]))
check("perft 3 variation (%)", -36, var(med(r3[("A", "perft3Latency")]), med(r3[("B", "perft3Latency")])))
check("mat en 3 avant (ms)", 21.5, med(r3[("A", "mateIn3")]), 1)
check("mat en 3 après (ms)", 16.0, med(mate3_b), 1)
check("mat en 3 variation (%)", -25, var(med(r3[("A", "mateIn3")]), med(mate3_b)))
check("profil opt 3 : isSquareAttacked, temps total (%)", 49.7, profile(P3, "total", "MoveGenerator.isSquareAttacked"), 1)
check("mat en 4 opt 3 (s)", 1.07, score(O3, "mateIn4") / 1000, 2)

print("== Partie 7 : table de transposition (A/B, avant = opt 3)")
r4, r5 = ab("04-ab-vs-opt3.csv"), ab("04-ab-mateIn5.csv")
diag = (RESULTS / "04-transpositions.txt").read_text()
for n, reported in ((3, 44.8), (4, 78.3)):
    visits, distinct, repeats = map(int, re.search(rf"Mate in {n} .*?: (\d+) attacker nodes, (\d+) distinct, (\d+)", diag).groups())
    check(f"mat en {n} : nœuds déjà analysés (%)", reported, repeats / visits * 100, 1)
mate5_a, mate5_b = statistics.mean(r5[("A", "mateIn5")]) / 1000, statistics.mean(r5[("B", "mateIn5")]) / 1000
check("mat en 5 avant (s)", 41.9, mate5_a, 1)
check("mat en 5 après (s)", 4.6, mate5_b, 1)
check("mat en 5 variation (%)", -89, var(mate5_a, mate5_b))
check("mat en 3 avant (ms)", 21.6, med(r4[("A", "mateIn3")]), 1)
check("mat en 3 après (ms)", 12.9, med(r4[("B", "mateIn3")]), 1)
check("mat en 3 variation (%)", -40, var(med(r4[("A", "mateIn3")]), med(r4[("B", "mateIn3")])))
check("mat en 4 après (s)", 0.29, score(O4, "mateIn4") / 1000, 2)
check("mat en 4 variation (%)", -73, var(score(O3, "mateIn4"), score(O4, "mateIn4")))
check("perft 4 avant (ms)", 17.5, med(r4[("A", "perft4")]), 1)
check("perft 4 après (ms)", 19.7, med(r4[("B", "perft4")]), 1)
check("perft 4 variation (%)", 12, var(med(r4[("A", "perft4")]), med(r4[("B", "perft4")])))
check("profil opt 3 : Position.make, temps propre (%)", 10.5, profile(P3, "self", "Position.make"), 1)
check("profil opt 4 : Position.make, temps propre (%)", 18.0, profile(P4, "self", "Position.make"), 1)

print("== Parties 8 et 9 : parallèle")
P, S = "05-scaling-private-tables.json", "05-scaling-shared-table.json"
check("profil opt 4 : charge CPU de la JVM (%)", 12.2, profile(P4, "cpuload"), 1)
for t, ms, r4_, s5, r5_ in (("1", 248.6, 0, 4.32, 0), ("2", 163.0, -34, 2.51, -42), ("4", 96.2, -61, 1.68, -61), ("8", 77.7, -69, 1.53, -65)):
    check(f"mat en 4, {t} thread(s) (ms)", ms, score(S, "mateIn4", t), 1)
    check(f"mat en 4, {t} vs 1 thread (%)", r4_, var(score(S, "mateIn4", "1"), score(S, "mateIn4", t)))
    check(f"mat en 5, {t} thread(s) (s)", s5, score(S, "mateIn5", t) / 1000, 2)
    check(f"mat en 5, {t} vs 1 thread (%)", r5_, var(score(S, "mateIn5", "1"), score(S, "mateIn5", t)))
s4 = score(S, "mateIn4", "1") / score(S, "mateIn4", "4")
p = (1 - 1 / s4) / (1 - 1 / 4)
check("Amdahl : accélération avec 4 threads", 2.58, s4, 2)
check("Amdahl : fraction parallélisable (%)", 82, p * 100)
check("Amdahl : variation prédite avec 8 cœurs (%)", -72, var(1, (1 - p) + p / 8))
check("workers 8 vs 4, mat en 4 (%)", -19, var(score(S, "mateIn4", "4"), score(S, "mateIn4", "8")))
check("mesure finale : workers 8 vs 4 (%)", -17, var(score(O5, "mateIn4", "4"), score(O5, "mateIn4", "8")))
check("mesure finale : mat en 4, 8 vs 1 thread (%)", -61, var(score(O5, "mateIn4", "1"), score(O5, "mateIn4", "8")))
check("opt 5 : mat en 3 (ms)", 4.48, score(O5, "mateIn3"), 2)
check("opt 5 : mat en 3 vs opt 4 (%)", -61, var(score(O4, "mateIn3"), score(O5, "mateIn3")))
private = (RESULTS / "05-parallel-diagnostic-private.txt").read_text().replace(",", ".")
shared = (RESULTS / "05-parallel-diagnostic-shared.txt").read_text().replace(",", ".")
row = lambda text, t: [float(x.rstrip("x")) for x in re.search(rf"^\s+{t}\s+(.*)$", text, re.M).group(1).split()]
check("tables privées : mat en 4, 1 thread (ms)", 311, score(P, "mateIn4", "1"))
check("tables privées : mat en 4, 8 threads (ms)", 304, score(P, "mateIn4", "8"))
check("tables privées : mat en 4, 8 vs 1 thread (%)", -2, var(score(P, "mateIn4", "1"), score(P, "mateIn4", "8")))
check("tables privées : temps réel 1 thread (s)", 5.68, row(private, 1)[0], 2)
check("tables privées : temps réel 8 threads (s)", 4.25, row(private, 8)[0], 2)
check("tables privées : temps réel 8 vs 1 (%)", -25, var(row(private, 1)[0], row(private, 8)[0]))
check("tables privées : cœurs occupés 1 thread", 1.05, row(private, 1)[2], 2)
check("tables privées : cœurs occupés 8 threads", 5.86, row(private, 8)[2], 2)
check("tables privées : travail CPU 8 threads (%)", 319, (row(private, 8)[3] - 1) * 100)
check("table partagée : travail CPU 8 threads (%)", 56, (row(shared, 8)[3] - 1) * 100)

print("== Parties 1 et 12 : synthèse (baseline -> final)")
check("perft 4 final (ms)", 21.2, score(O5, "perft4"), 1)
check("perft 4 total (%)", -93.6, var(score(B, "perft4"), score(O5, "perft4")), 1)
check("perft 3 p99 final (ms)", 1.8, pct(O5, "perft3Latency", "99.0") / 1000, 1)
check("perft 3 p99 total (%)", -96.7, var(pct(B, "perft3Latency", "99.0"), pct(O5, "perft3Latency", "99.0")), 1)
check("mat en 3 total (%)", -98.1, var(score(B, "mateIn3"), score(O5, "mateIn3")), 1)
mate4_final = med(shots(O5, "mateIn4")) / 1000
check("mat en 4 final, médiane des 3 mesures (s)", 0.11, mate4_final, 2)
check("opt 5 : mat en 4 vs opt 4 (%)", -63, var(score(O4, "mateIn4") / 1000, mate4_final))
check("mat en 4 total (%)", -99.3, var(score(B, "mateIn4") / 1000, mate4_final), 1)
check("mat en 5 final, 8 threads (s)", 1.5, score(S, "mateIn5", "8") / 1000, 1)
check("alloc perft 4 final (o)", 73, alloc(O5, "perft4"))
check("profil final : isSquareAttacked, temps total (%)", 44.8, profile(P5, "total", "MoveGenerator.isSquareAttacked"), 1)

print(f"\n{checks} valeurs vérifiées. " + ("Tout est cohérent." if failures == 0 else f"{failures} écart(s) trouvé(s)."))
