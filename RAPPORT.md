# Rapport d'audit de performance — ChessBot

Optimisation d'un solveur de mat en N écrit volontairement de façon naïve en Java.
Chaque optimisation est **justifiée par une mesure**, appliquée dans **un commit dédié** (tag Git), puis **mesurée avant/après** sur les mêmes charges.

## Synthèse

| # | Optimisation | Justification (mesure) | perft 4 | Mat en 3 | Mat en 4 | Alloc. / perft 4 |
|---|---|---|---|---|---|---|
| 0 | Baseline | — | | | | |

*Temps moyens JMH, intervalle de confiance à 99,9 %. Gain = temps avant / temps après.*

## Protocole de mesure

- **Machine** : Intel Core i5-8300H (4 cœurs / 8 threads, 2,3 GHz), 8 Go RAM, Windows 11, portable sur secteur. JDK Temurin 25.0.4.
- **Banc d'essai** : [JMH 1.37](src/test/java/com/chessbot/bench/ChessBenchmark.java) (équivalent Java de `testing.B` + `benchstat`) : 3 JVM séparées (forks), 3 itérations de chauffe JIT, 5 itérations mesurées de 2 s, tas fixé à 2 Go. Le résultat des fonctions est renvoyé à JMH (Blackhole) pour éviter l'élimination de code mort.
- **Validité statistique** : JMH donne un intervalle de confiance à 99,9 % ; un gain est retenu seulement si les intervalles avant/après ne se recouvrent pas.
- **Allocations** : profileur `-prof gc` de JMH (octets alloués par opération, nombre de GC).
- **Profils CPU** : JFR (Java Flight Recorder) sur la [charge de profilage](src/test/java/com/chessbot/bench/ProfileWorkload.java), converti en flamegraph par [tools/JfrToFlame.java](tools/JfrToFlame.java) + `flamegraph.pl`.
- **Non-régression** : les [tests perft](src/test/java/com/chessbot/PerftTest.java) (nombres de positions de référence de chessprogramming.org) et les [tests de mat](src/test/java/com/chessbot/MateFinderTest.java) passent à chaque étape. Une optimisation qui casse un résultat n'est pas retenue.

| Charge | Ce qu'elle mesure |
|---|---|
| `perft4` | génération de coups seule : les 197 281 positions à 4 demi-coups de la position initiale |
| `perft3Latency` | distribution des temps (p50 / p99) d'un perft 3 |
| `mateIn3` | recherche complète de mat en 3 sur la position Kiwipete (aucun mat : rien n'est élagué tôt) |
| `mateIn4` | même recherche, un coup plus profond (mesure en coups uniques, car longue) |

Reproduire : `mvn test-compile exec:exec@bench -Djmh.args="-prof gc"` et `mvn test-compile exec:exec@profile`.

---

## 0. Baseline

Tag [`baseline`](../../tree/baseline). Code naïf : plateau `char[8][8]` copié à chaque coup testé, `ArrayList<Move>` créée à chaque nœud, légalité vérifiée en jouant le coup sur une copie, roi cherché sur les 64 cases à chaque test d'échec.

| Charge | Temps | Allocations |
|---|---|---|
| perft 4 | 329,5 ± 120,7 ms | 258,8 Mo / op |
| perft 3 (latence) | p50 10,7 ms · p99 54,8 ms · p99,9 177,9 ms | 11,7 Mo / op |
| Mat en 3 | 236,6 ± 35,3 ms | 254,8 Mo / op |
| Mat en 4 | 14,8 s (3 mesures : 14,9 / 14,1 / 15,3 s) | **15,5 Go / op** |

Débit d'allocation : environ **1 Go/s** en continu. Résultats bruts : [docs/results/00-baseline.txt](docs/results/00-baseline.txt).

**Profil CPU** ([flamegraph](docs/flamegraphs/00-baseline.svg), 2 552 échantillons JFR à 1 ms) :

| Temps propre | Méthode |
|---|---|
| 28,2 % | `Position.copy` |
| 20,3 % | `MoveGenerator.legalMoves` |
| 19,4 % | `MoveGenerator.attackedBySlide` |
| 3,0 % | `Position.<init>` |

**Profil d'allocation** (JFR) : `Position.<init>` 48,2 % + `Position.copy` 45,4 % = **93,5 % de toutes les allocations**.

**Remarque sur le bruit** : les itérations varient jusqu'à ×2 (perft 4 : de 211 à 572 ms) alors que le GC ne prend que 64 ms sur 30 s de mesure. Ce bruit vient de la machine (portable, turbo CPU, tâches de fond Windows) ; il élargit les intervalles de confiance mais reste bien inférieur aux gains mesurés ensuite.

![Flamegraph baseline](docs/flamegraphs/00-baseline.svg)
