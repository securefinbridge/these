#!/bin/bash
# =============================================================================
#  Variante SANS Docker de campagne.sh : même protocole, même code, mêmes profils.
#  Le banc tourne dans un espace de noms réseau Linux (unshare -rn). Les profils
#  tc netem/tbf sont appliqués par filtre de port au seul segment
#  plateforme <-> passerelle (ports 7000 et 7443), un qdisc par sens, comme
#  l'egress des deux conteneurs dans campagne.sh ; le lien passerelle -> bouchon
#  core banking (port 8443) n'est pas soumis au profil.
#  Usage : unshare -rn ./campagne_vm.sh [rapide]     (JAVA_HOME = JDK 21)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS
export PATH="$JAVA_HOME/bin:$PATH" LANG=C.UTF-8
MODE="${1:-complet}"
if [ "$MODE" = rapide ]; then REP=3; SESS=2; CHAUFFE=5; ATT=3; else REP=30; SESS=5; CHAUFFE=30; ATT=10; fi
PROFILS="LAN 3G 3G-DEGRADE EDGE GPRS"
T=$(mktemp -d); trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$T"' EXIT
export SFB_CLES=$T/cles TLS_REP=$T/tls SFB_HOTE=127.0.0.1

mkdir -p mesures; rm -f mesures/*.json mesures/*.jsonl mesures/*.txt
ip link set lo up
echo "=== Compilation et clés ==="
javac -d $T/build src/sfb/*.java
sh generer_cles.sh $T/cles $T/tls $T/build >/dev/null

python3 ../bouchon_cbs.py 8443 > $T/bouchon.log 2>&1 &
SFB_JOURNAL=$PWD/mesures/journal_passerelle.jsonl java -cp $T/build sfb.Passerelle > $T/p.log 2>&1 &
SFB_PORT=7100 TLS_PORT=7543 SFB_ETIQUETTES_ROLE=false SFB_JOURNAL=$PWD/mesures/journal_passerelle_v1.jsonl \
  SFB_CBS_URL=http://127.0.0.1:8443/pacs008 java -cp $T/build sfb.Passerelle > $T/p1.log 2>&1 &
until python3 -c "import socket;socket.create_connection(('127.0.0.1',7000),2);socket.create_connection(('127.0.0.1',7100),2);socket.create_connection(('127.0.0.1',8443),2)" 2>/dev/null; do sleep 1; done
echo "banc démarré"

{
  echo "date        : $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "mode        : $MODE (répétitions=$REP, sessions/répétition=$SESS, chauffe=$CHAUFFE)"
  echo "machine     : $(uname -srm) (VM Linux sur le Mac de l'auteur)"
  echo "processeur  : $(grep -m1 -i 'model name\|CPU part' /proc/cpuinfo | cut -d: -f2 | xargs) / $(lscpu 2>/dev/null | grep -m1 'Model name' | cut -d: -f2 | xargs)"
  echo "coeurs      : $(nproc)"
  echo "memoire_go  : $(free -g | awk '/Mem/{print $2}')"
  echo "banc        : espace de noms réseau Linux (unshare -rn), sans Docker ; netem/tbf par filtre de port, un qdisc par sens"
  echo "noyau_banc  : $(uname -r)"
  echo "java        : $(java -version 2>&1 | grep -m1 version)"
  echo "python      : $(python3 --version)"
} > mesures/environnement.txt
cat mesures/environnement.txt

parametres () { case "$1" in
  3G) echo "2mbit 150ms 30ms 0.5%";; 3G-DEGRADE) echo "384kbit 300ms 80ms 2%";;
  EDGE) echo "236kbit 300ms 60ms 1%";; GPRS) echo "56kbit 450ms 100ms 3%";; *) echo "";; esac; }
appliquer () {
  tc qdisc del dev lo root 2>/dev/null || true
  local p; p=$(parametres "$1")
  if [ -n "$p" ]; then set -- $p
    tc qdisc add dev lo root handle 1: prio bands 4 priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
    for b in 2 3; do
      tc qdisc add dev lo parent 1:$b handle ${b}0: tbf rate "$1" burst 32kbit latency 400ms
      tc qdisc add dev lo parent ${b}0:1 handle ${b}1: netem delay "$2" "$3" distribution normal loss "$4"
    done
    for port in 7000 7443; do   # sens montant (vers la passerelle) / sens descendant
      tc filter add dev lo parent 1: protocol ip prio 1 u32 match ip dport $port 0xffff flowid 1:2
      tc filter add dev lo parent 1: protocol ip prio 1 u32 match ip sport $port 0xffff flowid 1:3
    done
  fi
  python3 - "$P" >> mesures/controle_profils.txt <<'PY'
import socket, statistics, sys, time
r=[]
for _ in range(10):
    t=time.perf_counter()
    try: socket.create_connection(("127.0.0.1",7000),10).close(); r.append((time.perf_counter()-t)*1000)
    except OSError: pass
    time.sleep(0.2)
print(f"{sys.argv[1]} : connexion TCP vers 7000, {len(r)}/10 réussies, RTT médian {statistics.median(r):.1f} ms, min {min(r):.1f}, max {max(r):.1f}" if r else f"{sys.argv[1]} : aucune connexion")
PY
}

for P in $PROFILS; do
  echo; echo "=== Profil $P ==="
  appliquer "$P"; tail -1 mesures/controle_profils.txt
  for M in $(printf "sfb\ntls\n" | awk 'BEGIN{srand()}{print rand()"\t"$0}' | sort | cut -f2); do
    java -cp $T/build sfb.Mesure mesure "$M" "$P" "$REP" "$SESS" "$CHAUFFE" "$PWD/mesures/${P}_${M}.json"
  done
done
P=RESET; appliquer RESET

echo; echo "=== Campagne d'attaque ==="
java -cp $T/build sfb.Mesure attaques "$ATT" $PWD/mesures/attaques.json
echo "=== Réflexion : version 1 puis version 2 ==="
SFB_PORT=7100 TLS_PORT=7543 java -cp $T/build sfb.Mesure reflexion "$ATT" $PWD/mesures/reflexion_v1.json
java -cp $T/build sfb.Mesure reflexion "$ATT" $PWD/mesures/reflexion_v2.json

# ---- scénarios 5 et 6 (profil LAN) ----------------------------------------
java -cp $T/build sfb.Mesure composition "$ATT" $PWD/mesures/complements/composition.json
java -cp $T/build sfb.Mesure menace "$ATT" $PWD/mesures/complements/menace.json
# ---- débit sous charge (profil LAN ; règles de vélocité neutralisées) ------
# La passerelle doit être lancée avec SFB_POLITIQUE=politique_charge.properties pour cette étape ;
# en campagne par lots, voir lot_vm.sh (modes debit-sfb et debit-tls, 3 séries de 30 s).
echo; echo "=== Analyse ==="
python3 analyse.py mesures ../../resultats/banc
echo "CAMPAGNE TERMINEE"
