# Rapport d'audit de performance — ChessBot

Optimisation d'un solveur de mat en N écrit volontairement de façon naïve en Java.
Chaque optimisation est **justifiée par une mesure**, appliquée dans **un commit dédié** (tag Git), puis **mesurée avant/après** sur les mêmes charges.

## Synthèse

| # | Optimisation | Justification (mesure) | perft 4 | Mat en 3 | Mat en 4 | Alloc. / perft 4 |
|---|---|---|---|---|---|---|
| 0 | Baseline | — | 329,5 ms | 236,6 ms | 14,8 s | 258,8 Mo |
| 1 | Plateau 1D contigu | `Position.copy` : 28 % du CPU, 93 % des allocations | 39,6 ms (×8,3) | 57,4 ms (×4,1) | 3,1 s (×4,7) | 86,7 Mo |
| 2 | Zéro allocation (jouer/annuler, buffers) | `Position.copy` : encore 84 % des allocations | 15,5 ms (×2,6) | 23,5 ms (×2,4) | 1,07 s (×2,9) | 54 o |
| 3 | Tables précalculées + roi mémorisé | détection d'échec : 61 % du CPU | ×1,19 (A/B) | ×1,34 (A/B) | non concluant | 60 o |
| 4 | Table de transposition (Zobrist) | 78 % des nœuds du mat en 4 déjà analysés | ×0,89 (A/B, coût du hachage) | ×1,7 (A/B) | 0,29 s (×3,7) — mat en 5 : ×9,1 | 60 o |
| 5 | Recherche parallèle, table partagée sans verrou | JVM à 12 % du CPU (1 thread sur 8) | — (non parallélisé) | 4,48 ms (×2,6) | 0,08–0,11 s (×2,6 à ×3,2) | 73 o |
| ✗ | *Tables privées par worker (essai)* | *idem* | — | — | *×1,0 : travail ×4,2* | — |
| ✗ | *Réordonner les champs (essai)* | *exploratoire* | *aucun effet : la JVM réordonne déjà (JOL)* | | | |
| **Total** | **Baseline → final** | | **329,5 → 21,2 ms (×16)** | **236,6 → 4,48 ms (×53)** | **14,8 s → 0,11 s (×134)** | **258,8 Mo → 73 o** |

*Temps moyens JMH, intervalle de confiance à 99,9 %. Gain = temps avant / temps après. Mat en 5 : plus de 9 min (arrêté) avec la baseline, 41,9 s après l'optimisation 3, **1,5 s** en version finale. Latence perft 3 : p99 de 54,8 ms à 1,8 ms (×30).*

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

Reproduire : `mvn -Dbuild.dir=target-bench clean test-compile exec:exec@bench -Djmh.args="-prof gc"` et `... exec:exec@profile`.

**Piège rencontré** : l'extension Java de VS Code compile elle aussi dans `target/` et écrasait par moments les classes mesurées (un benchmark a échoué avec « Unresolved compilation problems », message du compilateur Eclipse). Depuis l'optimisation 2, les mesures sont compilées dans un dossier séparé (`target-bench/`).

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

---

## 1. Plateau 1D contigu — *localité mémoire (J1_PM)*

Tag [`opt1-plateau-1d`](../../tree/opt1-plateau-1d).

**Constat.** Dans la baseline, `Position.copy` est la méthode la plus coûteuse du profil CPU (28,2 % de temps propre) et, avec le constructeur de `Position`, elle produit **93,5 % des allocations**.

**Cause racine.** En Java, `char[8][8]` n'est pas un bloc : c'est un tableau de 8 références vers 8 petits tableaux dispersés sur le tas. Chaque copie de position coûtait donc **17 allocations et 592 octets** : l'initialiseur `new char[8][8]` créait 9 tableaux, dont les 8 rangées étaient aussitôt jetées et remplacées par 8 clones. Lire une case demandait en plus deux accès mémoire dépendants (référence de la rangée, puis la case).

**Technique.** Un seul tableau `char[64]` indexé par `ligne * 8 + colonne` : une copie = **2 allocations, 176 octets**, un seul `clone()` contigu ; une case = un seul accès mémoire. Le reste du code (génération de coups, recherche) est inchangé.

```java
final char[] board;                         // avant : final char[][] board = new char[8][8];
static int sq(int row, int col) { return row * 8 + col; }
Position p = new Position(board.clone());   // avant : 8 appels à board[r].clone()
```

**Résultat.**

| Charge | Avant | Après | Gain |
|---|---|---|---|
| perft 4 | 329,5 ± 120,7 ms | **39,6 ± 8,3 ms** | **×8,3** |
| perft 3 p50 / p99 | 10,7 / 54,8 ms | **1,42 / 3,33 ms** | ×7,6 / **×16** |
| Mat en 3 | 236,6 ± 35,3 ms | **57,4 ± 15,2 ms** | **×4,1** |
| Mat en 4 | 14,8 s | **3,1 s** (3,3 / 3,1 / 2,9) | **×4,7** |
| Allocations perft 4 | 258,8 Mo/op | 86,7 Mo/op | −66 % |

Les intervalles de confiance ne se recouvrent sur aucune charge : le gain est significatif.

**Analyse.** Le gain (×8) dépasse la seule baisse des octets alloués (÷3) : passer de 17 à 2 allocations par coup évite aussi la mise à zéro de 9 tableaux, 8 appels de copie séparés et les accès indirects. On ne peut pas séparer précisément ces effets, qui viennent tous de la même structure de données. La queue de latence (p99) baisse encore plus que la médiane, car le GC a 3 fois moins d'objets à traiter.

**Nouveau profil** ([flamegraph](docs/flamegraphs/01-plateau-1d.svg)) : la copie ne pèse plus que 0,4 % du CPU mais reste **83,9 % des allocations** (justifie l'optimisation 2). Le nouveau point chaud est la détection d'échec : `attackedBySlide` 34,8 % de temps propre, `isKingAttacked` 34,2 % du temps total (justifie l'optimisation 3).

---

## 2. Zéro allocation — *pile vs tas, buffers préalloués (J2_AM)*

Tag [`opt2-zero-allocation`](../../tree/opt2-zero-allocation).

**Constat.** Après l'optimisation 1, le profil JFR montre que `Position.copy` produit encore **83,9 % des allocations** : 86,7 Mo par perft 4, 5,2 Go par mat en 4.

**Cause racine.** Chaque coup *testé* (pas seulement joué) crée une nouvelle `Position`, et chaque nœud crée une `ArrayList<Move>` remplie d'objets `Move`. Tous ces objets sortent de la fonction qui les crée (ils sont retournés ou stockés dans une liste) : l'escape analysis du JIT ne peut pas les garder sur la pile.

**Technique.**
- **Jouer / annuler sur un seul plateau** : `make(move)` modifie la position en place et `unmake(move)` la restaure à partir d'une petite pile d'annulation préallouée (pièce capturée, droits de roque, case en passant).
- **Coups encodés en `int`** (case de départ, case d'arrivée, promotion sur 15 bits) au lieu d'objets `Move`.
- **Buffers préalloués** : un tableau `int[320]` par profondeur, créé une seule fois par thread et réutilisé à chaque appel (`ThreadLocal`), à la place des `ArrayList`.

```java
for (int i = 0; i < count; i++) {
    p.make(buffer[i]);                                   // avant : Position next = p.makeMove(m);
    if (!MoveGenerator.isKingAttacked(p, !p.whiteToMove))
        nodes += perft(p, moves, depth - 1, ply + 1);
    p.unmake(buffer[i]);
}
```

**Diagnostic d'échappement en cours de route.** La première version de l'optimisation allouait encore **21,7 Mo par mat en 3**. JFR a désigné la source : 99 % des allocations restantes venaient de `for (int dc : new int[]{-1, 1})` dans la génération des coups de pion. Ce tableau littéral ne sort pourtant jamais de la fonction, mais l'escape analysis de C2 ne l'a pas éliminé dans ce contexte. Le remplacer par une constante `static final` a supprimé ces allocations. Leçon : l'escape analysis n'est pas une garantie, il faut vérifier par la mesure (équivalent de `go build -gcflags="-m"`).

**Résultat** (avant = optimisation 1).

| Charge | Avant | Après | Gain |
|---|---|---|---|
| perft 4 | 39,6 ± 8,3 ms | **15,5 ± 0,3 ms** | **×2,6** |
| perft 3 p50 / p99 / p99,9 | 1,42 / 3,33 / 4,33 ms | **0,82 / 1,95 / 3,39 ms** | ×1,7 / ×1,7 / ×1,3 |
| Mat en 3 | 57,4 ± 15,2 ms | **23,5 ± 1,0 ms** | **×2,4** |
| Mat en 4 | 3,1 s | **1,07 s** (1,07 / 1,09 / 1,05) | **×2,9** |
| Allocations perft 4 | 86,7 Mo/op | **54 o/op** | −100 % |
| Allocations mat en 4 | 5,2 Go/op | **6,8 Ko/op** | −100 % |
| Nombre de GC pendant la mesure | 15 à 30 | **≈ 0** | |

Les quelques octets restants sont alloués une fois par appel hors du chemin critique (résultat `Optional<Move>`, infrastructure JMH). Les intervalles de confiance sont aussi **8 à 25 fois plus étroits** : une partie du bruit des mesures précédentes venait des allocations elles-mêmes.

**Nouveau profil** ([flamegraph](docs/flamegraphs/02-zero-allocation.svg)) : il ne reste aucune allocation applicative (les allocations visibles viennent de JFR lui-même). Le CPU est maintenant dominé par la détection d'échec : `attackedBySlide` **53,2 % de temps propre**, `isKingAttacked` **60,6 % du temps total**, dont 10,3 % de temps propre passé à **chercher le roi case par case** (justifie l'optimisation 3).

---

## 3. Tables précalculées + roi mémorisé — *compromis espace-temps (J1_AM)*

Tag [`opt3-tables`](../../tree/opt3-tables).

**Constat.** Après l'optimisation 2, la détection d'échec représente **60,6 % du temps CPU**. `isKingAttacked` passe 10,3 % du temps à parcourir les 64 cases pour retrouver le roi, et `attackedBySlide` 53,2 % à suivre les lignes des tours, fous et dames.

**Cause racine.** À chaque test, le code recalcule ce qui ne dépend que de la case : les déplacements du cavalier et du roi (8 décalages, chacun avec 4 tests de bord du plateau), les rayons des pièces glissantes (une conversion ligne/colonne et un test de bord à chaque pas). Et la position du roi, qui ne change que quand le roi bouge, est recherchée à chaque fois.

**Technique.** Échanger un peu de mémoire contre du calcul :
- **tables calculées une fois au démarrage** (environ 30 Ko) : `KNIGHT_TARGETS[64]`, `KING_TARGETS[64]`, `PAWN_ATTACKS[2][64]`, `ROOK_RAYS[64][4]`, `BISHOP_RAYS[64][4]`. Le chemin critique ne fait plus que lire des listes de cases, sans test de bord ;
- **position des rois mémorisée** dans `Position`, mise à jour par `make` / `unmake` : `isKingAttacked` passe d'un parcours O(64) à un accès O(1).

```java
for (int s : KNIGHT_TARGETS[sq]) if (b[s] == knight) return true;   // avant : 8 décalages + 4 tests de bord chacun
int king = whiteKing ? p.whiteKing : p.blackKing;                    // avant : boucle sur les 64 cases
```

**Mesure : pourquoi un A/B alterné.** La mesure JMH habituelle a donné un résultat incohérent : perft 3 ×1,6 plus rapide, mais perft 4 (exactement le même code) plus lent, 15,5 → 17,3 ms, avec des itérations qui ralentissent au fil du fork (17 → 22 ms). À ce niveau (quelques ms), la baisse de fréquence d'un portable qui chauffe pèse plus que l'optimisation. Les deux versions ont donc été compilées côte à côte et mesurées **en alternance** (A, B, A, B, A, B), pour qu'elles subissent les mêmes conditions ([résultats](docs/results/03-ab-vs-opt2.csv)).

**Résultat** (A = optimisation 2, B = optimisation 3, 3 manches alternées, 5 itérations chacune).

| Charge | A : manches 1 / 2 / 3 | B : manches 1 / 2 / 3 | Gain (médianes) |
|---|---|---|---|
| perft 4 | 18,4 / 15,4 / 15,9 ms | **13,5 / 13,4 / 12,6 ms** | **×1,19** |
| perft 3 (moyenne) | 958 / 821 / 826 µs | **530 / 537 / 501 µs** | **×1,56** |
| Mat en 3 | 23,9 / 21,5 / 21,2 ms | **17,1 / — / 14,9 ms** | **×1,34** |

B est plus rapide que A dans **chaque** manche et sur chaque charge. Une itération de B (mat en 3, manche 2) a duré plusieurs secondes au lieu de 15 ms, un gel ponctuel de la machine : elle est exclue et signalée. Le mat en 4 (1,07 s dans les deux cas, 3 mesures seulement, écart 0,87–1,30 s) ne permet pas de conclure.

**Analyse.** Gain réel mais plus modeste que les optimisations précédentes : on a enlevé des calculs (tests de bord, recherche du roi), mais le nombre de cases à lire reste le même. Le nouveau [profil](docs/flamegraphs/03-tables.svg) montre que la détection d'échec domine toujours : `attackedAlongRays` 30,3 % + `isSquareAttacked` 22,2 % de temps propre. Aller plus loin à ce niveau demanderait des bitboards (plateau sous forme d'entiers 64 bits). La suite s'attaque plutôt au **nombre de positions visitées**.

---

## 4. Table de transposition — *mémoïsation, macro-optimisation (J1_AM), cache borné (J4_PM)*

Tag [`opt4-table-transposition`](../../tree/opt4-table-transposition).

**Constat.** Les optimisations 1 à 3 rendent chaque position moins chère, mais le nombre de positions explose avec la profondeur : le mat en 5 prend encore **41 s**. Un [diagnostic](src/test/java/com/chessbot/TranspositionDiagnostic.java) rejoue la recherche et compte les nœuds d'attaque (position + nombre de coups restants) déjà rencontrés ([résultats](docs/results/04-transpositions.txt)) :

| Recherche | Nœuds d'attaque visités | Distincts | **Déjà analysés** |
|---|---|---|---|
| Mat en 3 | 2 406 | 1 328 | **44,8 %** |
| Mat en 4 | 116 067 | 25 133 | **78,3 %** |

**Cause racine.** Des ordres de coups différents mènent à la même position (1.Cf3 Cf6 2.Cc3 = 1.Cc3 Cf6 2.Cf3). Sans mémoire, la recherche refait à chaque fois toute la sous-recherche, et cette redondance croît avec la profondeur.

**Technique.**
- **Hachage Zobrist** : une clé aléatoire de 64 bits par (pièce, case), trait, droits de roque et colonne en passant ; l'empreinte d'une position est le XOR des clés, mise à jour **incrémentalement** par `make` (quelques XOR par coup) et restaurée par `unmake`. Un [test](src/test/java/com/chessbot/ZobristTest.java) vérifie à chaque nœud qu'elle égale un recalcul complet.
- **[Table de transposition](src/main/java/com/chessbot/MateTable.java)** : pour chaque (empreinte, n), elle mémorise « mat forcé en n : oui / non ». Un mat en k vaut aussi pour tout n ≥ k, une absence de mat en k vaut pour tout n ≤ k.
- **Taille bornée** (2²⁰ entrées, 12 Mo, comme le cache LRU borné anti-OOM du cours) : chaque empreinte a une seule case, une nouvelle entrée remplace l'ancienne (accès O(1), sans liste LRU). Un compteur de génération vide la table en O(1) à chaque recherche : un appel ne profite jamais du précédent, la mesure reste honnête.
- **Non-régression** : un [test](src/test/java/com/chessbot/MateTableTest.java) compare le verdict avec et sans table sur 6 positions × 3 profondeurs et vérifie que chaque coup trouvé gagne vraiment.

```java
if (ply > 0) {
    int known = table.probe(p.hash, n);                   // déjà analysée ?
    if (known != MateTable.UNKNOWN) return known == MateTable.MATE ? FOUND : NONE;
}
... recherche normale ...
if (ply > 0) table.store(p.hash, n, result != NONE);
```

**Résultat** (A/B alterné, A = optimisation 3, B = optimisation 4 ; [résultats](docs/results/04-ab-vs-opt3.csv), [mat en 5](docs/results/04-ab-mateIn5.csv)).

| Charge | A (médiane) | B (médiane) | Effet |
|---|---|---|---|
| Mat en 3 | 21,6 ms | **12,9 ms** | **×1,7** |
| Mat en 4 (mesure JMH normale) | 1,07 s | **0,29 s** (0,28 / 0,32 / 0,27) | **×3,7** |
| Mat en 5 | 41,5 / 42,3 s | **4,57 / 4,63 s** | **×9,1** |
| perft 4 | 17,5 ms | 19,7 ms | **−11 %** |
| perft 3 | 667 µs | 743 µs | **−10 %** |

**Analyse.** Le gain grandit avec la profondeur (×1,7 → ×3,7 → ×9,1), exactement comme la part de positions redondantes mesurée par le diagnostic : c'est une réduction du **nombre** d'opérations, pas de leur coût. Contrepartie mesurée : perft, qui n'utilise pas la table, ralentit de 10 % car `make` met à jour l'empreinte à chaque coup (`Position.make` passe de 10,7 % à 17,9 % de temps propre dans le [profil](docs/flamegraphs/04-table-transposition.svg)). Compromis accepté : le but du programme est la recherche de mat. *(Les A/B ont tourné sur une machine plus chaude que celle de l'optimisation 3 : les valeurs absolues de A sont plus élevées, seul le rapport A/B compte.)*

---

## 5. Recherche parallèle — *worker pool borné, mémoire partagée sans verrou (J3)*

Tag [`opt5-parallele`](../../tree/opt5-parallele).

**Constat.** Pendant la charge de profilage de l'optimisation 4, JFR mesure une charge CPU de la JVM de **11,9 %** en moyenne, soit 1 thread logique sur 8 (12,5 %) : **7/8 du processeur restent inutilisés**.

**Technique.** Les coups de départ (racine) sont répartis sur un **pool de threads de taille fixe**, créé une fois et réutilisé. Chaque worker a ses propres buffers (thread-local, aucun partage). Le résultat reste **déterministe** : on retient le premier coup gagnant dans l'ordre de génération, comme la version séquentielle ; dès qu'il est connu, un drapeau `AtomicBoolean` fait abandonner les autres workers.

### Première tentative : une table de transposition par worker — échec

Pour éviter tout partage de mémoire, chaque worker avait d'abord **sa propre table**. Résultat ([mesures](docs/results/05-scaling-private-tables.txt), tag [`essai-parallele-tables-privees`](../../tree/essai-parallele-tables-privees)) : **aucun gain** (mat en 4 : 311 ms avec 1 thread, 304 ms avec 8 ; mat en 5 : 5,4 s → 4,9 s, dans la marge d'erreur).

Un [diagnostic](src/test/java/com/chessbot/bench/ParallelDiagnostic.java) mesure le temps CPU total du processus pendant un mat en 5 ([résultats](docs/results/05-parallel-diagnostic-private.txt)) :

| Threads | Temps réel | Cœurs réellement occupés | Travail CPU total (vs 1 thread) |
|---|---|---|---|
| 1 | 5,68 s | 1,05 | ×1,00 |
| 8 | 4,25 s | 5,86 | **×4,19** |

Les cœurs travaillent, mais **le travail total est multiplié par 4,2** : chaque worker refait les transpositions déjà analysées par les autres. L'optimisation 4 avait montré que 78 % des nœuds sont des transpositions ; séparer les tables détruit ce gain.

### Correction : une table partagée, sans verrou

Tous les workers partagent **une seule table**. Un verrou (`synchronized`) sur chaque accès créerait de la contention sur des millions d'accès ; à la place, chaque case stocke **deux `long` : `empreinte ^ donnée` et `donnée`** (« lockless hashing »). Si deux threads écrivent la même case en même temps et qu'un lecteur voit un mélange des deux, le XOR ne redonne plus l'empreinte et l'entrée est ignorée : une entrée erronée ne peut jamais être lue, sans aucune synchronisation. Les 16 tests (dont la comparaison avec et sans table) passent avec la recherche parallèle.

**Résultat** ([JMH](docs/results/05-scaling-shared-table.txt), 8 mesures par point pour le mat en 4, 3 pour le mat en 5).

| Threads | Mat en 4 | Accélération | Mat en 5 | Accélération |
|---|---|---|---|---|
| 1 (séquentiel) | 248,6 ± 46,6 ms | ×1,0 | 4,32 s | ×1,0 |
| 2 | 163,0 ± 39,6 ms | ×1,5 | 2,51 s | ×1,7 |
| 4 | 96,2 ± 5,0 ms | ×2,6 | 1,68 s | ×2,6 |
| 8 | **77,7 ± 4,8 ms** | **×3,2** | **1,53 s** | **×2,8** |
| *8, tables privées* | *304,1 ms* | *×1,0* | *4,87 s* | *×1,1* |

Diagnostic CPU avec table partagée ([résultats](docs/results/05-parallel-diagnostic-shared.txt)) : avec 8 threads, 4,8 cœurs occupés et un travail total de ×1,56 seulement (au lieu de ×4,19).

**Analyse (loi d'Amdahl).** Avec 4 threads, l'accélération de ×2,58 correspond à une fraction parallélisable **P ≈ 82 %** (S = 1 / ((1 − P) + P/4)). La loi prédit alors au mieux ×3,5 avec 8 vrais cœurs ; on mesure ×3,2, cohérent car le i5-8300H n'a que **4 cœurs physiques** (les 8 threads sont de l'hyper-threading). Les 18 % non parallélisables viennent surtout du **déséquilibre de charge** (certains coups de départ demandent beaucoup plus de travail que d'autres : la fin de la recherche attend le plus long) et du travail encore dupliqué entre workers qui analysent la même position au même moment.

**Reproductibilité.** La mesure finale ([résultats](docs/results/05-parallele.txt)), faite plus tard sur une machine plus chaude, redonne une courbe de même forme : 268,6 → 199,3 → 125,2 → 103,7 ms, soit ×2,6 avec 8 threads (contre ×3,2). L'ordre de grandeur est stable, la valeur exacte dépend de l'état thermique du portable.

---

## 6. Optimisation sans effet : réordonner les champs — *struct padding (J2_AM)*

**Pourquoi l'essayer.** Pas de mesure préalable cette fois : c'est un **essai exploratoire** d'une technique du cours. En Go, trier les champs d'une structure par taille décroissante supprime le padding (exemple du cours : `CandidateBad` 40 octets → `CandidateGood` 32 octets).

**Mesure.** [JOL](src/test/java/com/chessbot/FieldLayoutDiagnostic.java) (Java Object Layout, équivalent de `fieldalignment`) affiche la disposition réelle des objets en mémoire ([résultats](docs/results/06-field-layout.txt)). Les deux classes de l'exemple du cours, recopiées en Java, et `Position` avant / après tri de ses champs :

| Classe | Go (cours) | Java (JOL) |
|---|---|---|
| `CandidateBad` (champs désordonnés) | 40 octets | **32 octets** |
| `CandidateGood` (champs triés) | 32 octets | **32 octets**, disposition identique octet par octet |
| `Position`, ordre d'origine | — | 64 octets, 3 octets de padding |
| `Position`, champs triés par taille | — | **64 octets, mêmes offsets** ([résultats](docs/results/06-field-layout-position-sorted.txt)) |

**Explication.** Contrairement au compilateur Go, qui respecte l'ordre de déclaration, la JVM HotSpot **réorganise elle-même les champs** au chargement de la classe (les `long` d'abord, puis les `int`, puis les petits types, et les références à la fin). L'ordre écrit dans le code source n'a donc aucune influence : `Position` occupe déjà exactement **64 octets, une ligne de cache**, avec seulement 3 octets perdus. La disposition mémoire étant identique, le code exécuté l'est aussi : aucun gain de temps n'est possible, et le changement n'a pas été conservé.

**Leçon.** Une technique valable dans un langage ne l'est pas forcément dans un autre : il faut **mesurer avant de conclure**. Le même cas s'est produit dans l'autre sens à l'optimisation 2 : l'escape analysis devait supprimer un petit tableau temporaire, la mesure a montré que ce n'était pas le cas.

---

## Conclusion

| | Baseline | Final | Gain |
|---|---|---|---|
| perft 4 (génération de coups) | 329,5 ms | 21,2 ms | **×16** |
| Latence perft 3, p99 | 54,8 ms | 1,8 ms | **×30** |
| Mat en 3 | 236,6 ms | 4,48 ms | **×53** |
| Mat en 4 | 14,8 s | 0,11 s | **×134** |
| Mat en 5 | > 9 min (arrêté) | 1,5 s | **> ×360** |
| Allocations par perft 4 | 258,8 Mo | 73 o | **≈ −100 %** |

1. **Chaque optimisation a été choisie à partir d'une mesure** (profil CPU, profil d'allocation, comptage des transpositions, charge CPU), sauf le réordonnancement des champs, présenté comme essai exploratoire.
2. **Les gains de structure de données et de mémoire** (optimisations 1 et 2) ont rendu chaque position environ 20 fois moins chère et supprimé le travail du GC ; **les gains d'algorithme et de parallélisme** (optimisations 4 et 5) ont réduit le *nombre* de positions et réparti le travail : ce sont eux qui font passer le mat en 5 de « inutilisable » à 1,5 s.
3. **Deux essais n'ont rien donné et sont documentés** : des tables de transposition privées par worker (le travail total explose, ×4,2), et le réordonnancement des champs (la JVM le fait déjà).
4. **Pièges de mesure rencontrés** : le bruit thermique d'un portable (résolu par des mesures A/B alternées), l'IDE qui écrasait les classes mesurées (build séparé), une mise en veille pendant un benchmark (mesure écartée et refaite), et une escape analysis qui ne s'appliquait pas là où on l'attendait.

**Pistes non réalisées** : bitboards (plateau en entiers 64 bits, pour réduire le coût de la détection d'échec, encore ~45 % du CPU) ; ordonner les coups (échecs d'abord) pour trouver les mats plus tôt ; répartir le travail plus finement que par coup de départ pour réduire le déséquilibre de charge.
