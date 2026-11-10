#!/bin/bash
# Un lot de la campagne sans Docker (voir campagne_vm.sh) : démarre bouchon + passerelle
# dans l'espace de noms réseau, applique le profil, exécute un appel à sfb.Mesure.
# Usage : unshare -rn ./lot_vm.sh <profil> <sfb|tls|attaques|reflexion> <rep|n> <sess> <chauffe> <sortie.json>
set -euo pipefail
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS; export PATH="$JAVA_HOME/bin:$PATH" LANG=C.UTF-8
W=${SFB_TRAVAIL:-$HOME/sfbwork}; export SFB_CLES=$W/cles TLS_REP=$W/tls SFB_HOTE=127.0.0.1
mkdir -p $W; javac -d $W/build src/sfb/*.java
[ -d $W/cles ] || sh generer_cles.sh $W/cles $W/tls $W/build >/dev/null
export SFB_JOURNAL=${SFB_JOURNAL:-$HOME/sfbwork/journal_${2}.jsonl}   # disque local de la machine du banc
P=$1; M=$2; T=$(mktemp -d); trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$T"' EXIT
ip link set lo up
python3 ../bouchon_cbs.py 8443 > $T/b.log 2>&1 &
SFB_POLITIQUE=${SFB_POLITIQUE:-politique.properties} java -cp $W/build sfb.Passerelle > $T/p.log 2>&1 &
SFB_PORT=7100 TLS_PORT=7543 SFB_ETIQUETTES_ROLE=false SFB_JOURNAL=$T/j1.jsonl java -cp $W/build sfb.Passerelle > $T/p1.log 2>&1 &
until python3 -c "import socket;[socket.create_connection(('127.0.0.1',p),2) for p in (7000,7100,8443)]" 2>/dev/null; do sleep 0.5; done
case "$P" in 3G) X="2mbit 150ms 30ms 0.5%";; 3G-DEGRADE) X="384kbit 300ms 80ms 2%";;
  EDGE) X="236kbit 300ms 60ms 1%";; GPRS) X="56kbit 450ms 100ms 3%";; *) X="";; esac
if [ -n "$X" ]; then set -- $X
  tc qdisc add dev lo root handle 1: prio bands 4 priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
  for b in 2 3; do
    tc qdisc add dev lo parent 1:$b handle ${b}0: tbf rate "$1" burst 32kbit latency 400ms
    tc qdisc add dev lo parent ${b}0:1 handle ${b}1: netem delay "$2" "$3" distribution normal loss "$4"
  done
  for port in 7000 7443; do
    tc filter add dev lo parent 1: protocol ip prio 1 u32 match ip dport $port 0xffff flowid 1:2
    tc filter add dev lo parent 1: protocol ip prio 1 u32 match ip sport $port 0xffff flowid 1:3
  done
  set -- "$P" "$M" "${@:5}"
fi
shift 2 2>/dev/null || true
A=("$@"); P=${P}; 
case "$M" in
  sfb|tls) java -cp $W/build sfb.Mesure mesure "$M" "$P" "${LOT_REP}" "${LOT_SESS}" "${LOT_CHAUFFE}" "$LOT_SORTIE" ;;
  attaques) java -cp $W/build sfb.Mesure attaques "$LOT_REP" "$LOT_SORTIE" ;;
  reflexion1) SFB_PORT=7100 TLS_PORT=7543 java -cp $W/build sfb.Mesure reflexion "$LOT_REP" "$LOT_SORTIE" ;;
  reflexion2) java -cp $W/build sfb.Mesure reflexion "$LOT_REP" "$LOT_SORTIE" ;;
  composition) java -cp $W/build sfb.Mesure composition "$LOT_REP" "$LOT_SORTIE" ;;
  menace) java -cp $W/build sfb.Mesure menace "$LOT_REP" "$LOT_SORTIE" ;;
  debit-sfb|debit-tls) java -cp $W/build sfb.Mesure debit "${M#debit-}" "$LOT_CLIENTS" "$LOT_REP" "$LOT_SESS" "$LOT_SORTIE" ;;
  controle) python3 - "$P" <<'PY'
import socket, statistics, sys, time
r=[]
for _ in range(10):
    t=time.perf_counter()
    try: socket.create_connection(("127.0.0.1",7000),10).close(); r.append((time.perf_counter()-t)*1000)
    except OSError: pass
    time.sleep(0.2)
print(f"{sys.argv[1]} : connexion TCP vers 7000 (1 aller-retour), {len(r)}/10 réussies, médiane {statistics.median(r):.1f} ms, min {min(r):.1f}, max {max(r):.1f}")
PY
  ;;
esac
