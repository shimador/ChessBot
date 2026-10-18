#!/usr/bin/env bash
# Reproduit toutes les mesures du rapport en une commande.
#
#   ./run_benchmarks.sh           mesure complète (~25 min)
#   ./run_benchmarks.sh --quick   vérification rapide de la chaîne (~5 min, chiffres moins précis)
#
# Prérequis : JDK 25 (java dans le PATH), curl, perl (pour le flamegraph, facultatif).
# Maven et flamegraph.pl sont téléchargés dans .tools/ s'ils sont absents.
# Résultats : bench-results/<date>/ (JMH texte + JSON, profil JFR, flamegraph SVG, résumé du profil).
set -euo pipefail
cd "$(dirname "$0")"

MAVEN_VERSION=3.9.9
TOOLS=.tools
OUT="bench-results/$(date +%Y%m%d-%H%M%S)"
QUICK=false
[[ "${1:-}" == "--quick" ]] && QUICK=true

step() { printf '\n==> %s\n' "$*"; }

# --- 0. Outils -------------------------------------------------------------
step "Vérification de l'environnement"
command -v java >/dev/null || { echo "java introuvable : installez un JDK 25" >&2; exit 1; }
java -version 2>&1 | head -1
mkdir -p "$TOOLS" "$OUT"

if command -v mvn >/dev/null; then
  MVN=mvn
else
  MVN="$TOOLS/apache-maven-$MAVEN_VERSION/bin/mvn"
  if [[ ! -x "$MVN" ]]; then
    step "Téléchargement de Maven $MAVEN_VERSION"
    curl -sSfL -o "$TOOLS/maven.zip" \
      "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip"
    (cd "$TOOLS" && jar xf maven.zip && rm maven.zip)
    chmod +x "$MVN"
  fi
fi
if [[ ! -f "$TOOLS/flamegraph.pl" ]]; then
  curl -sSfL -o "$TOOLS/flamegraph.pl" https://raw.githubusercontent.com/brendangregg/FlameGraph/master/flamegraph.pl || true
fi
MVN_OPTS=(-q -B -Dbuild.dir=target-bench)

# --- 1. Machine ------------------------------------------------------------
step "Description de la machine"
{
  echo "Date : $(date)"
  java -version 2>&1
  if command -v powershell >/dev/null; then
    powershell -NoProfile -Command "Get-CimInstance Win32_Processor | Format-List Name,NumberOfCores,NumberOfLogicalProcessors,L2CacheSize,L3CacheSize; (Get-CimInstance Win32_OperatingSystem).Caption + ' ' + (Get-CimInstance Win32_OperatingSystem).Version; 'RAM (Go) : ' + [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)"
  elif command -v lscpu >/dev/null; then
    lscpu; free -g; uname -a
  fi
} > "$OUT/machine.txt" 2>&1
cat "$OUT/machine.txt"

# --- 2. Non-régression -----------------------------------------------------
step "Compilation propre et tests (perft, mat, Zobrist, table)"
"$MVN" "${MVN_OPTS[@]}" clean test

# --- 3. Benchmarks JMH -----------------------------------------------------
if $QUICK; then
  JMH="-f 1 -wi 1 -i 2 -e mateIn5"
  SECONDS_PROFILE=5
else
  JMH="-e ChessBenchmark.mateIn5"
  SECONDS_PROFILE=20
fi
step "Benchmarks JMH ($([[ $QUICK == true ]] && echo rapide || echo complet))"
"$MVN" "${MVN_OPTS[@]}" exec:exec@bench \
  -Djmh.args="$JMH -prof gc -rf json -rff $OUT/jmh.json" | tee "$OUT/jmh.txt" | grep -E "^(Benchmark|ChessBenchmark|ScalingBenchmark)" | grep -v "gc\.\(churn\|count\|time\)" || true

# --- 4. Profil CPU et allocations ------------------------------------------
step "Profil JFR (${SECONDS_PROFILE} s) et flamegraph"
"$MVN" "${MVN_OPTS[@]}" exec:exec@profile -Dprofile.out="$OUT/profile.jfr" -Dprofile.seconds="$SECONDS_PROFILE" | grep Rounds || true
java tools/JfrToFlame.java "$OUT/profile.jfr" "$OUT/profile" | grep -E "^(samples|cpuload|self)" | head -8
jfr view --width 120 allocation-by-site "$OUT/profile.jfr" > "$OUT/allocations.txt" || true
if command -v perl >/dev/null && [[ -s "$TOOLS/flamegraph.pl" ]]; then
  perl "$TOOLS/flamegraph.pl" --title "ChessBot - version finale" --width 1400 "$OUT/profile-cpu.collapsed" > "$OUT/flamegraph.svg"
else
  echo "perl ou flamegraph.pl absent : flamegraph non généré"
fi

# --- 5. Cohérence du rapport -----------------------------------------------
step "Vérification des chiffres du rapport (résultats de docs/results)"
if command -v python >/dev/null; then PY=python; elif command -v python3 >/dev/null; then PY=python3; else PY=; fi
if [[ -n "$PY" ]]; then PYTHONIOENCODING=utf-8 "$PY" tools/verify_report.py | tail -1; else echo "python absent : vérification ignorée"; fi

step "Terminé. Résultats dans $OUT/"
ls "$OUT"
