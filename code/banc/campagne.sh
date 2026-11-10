#!/bin/bash
# =============================================================================
#  Campagne de mesures SecureFinBridge (chapitre 9 du mémoire, annexe D)
#
#  Usage :   ./campagne.sh            campagne complète (30 répétitions x 5 sessions)
#            ./campagne.sh rapide     essai (3 répétitions x 2 sessions), pour vérifier le banc
#
#  Prérequis : Docker Desktop démarré ; python3 (fourni avec macOS).
#  Pendant la campagne : Mac sur secteur, aucune autre application, ne pas mettre en veille.
#  Résultats : code/banc/mesures/*.json  puis  resultats/banc/*.tex (tableaux du chapitre 9).
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"
MODE="${1:-complet}"
if [ "$MODE" = rapide ]; then REP=3; SESS=2; CHAUFFE=5; ATT=3; else REP=30; SESS=5; CHAUFFE=30; ATT=10; fi
PROFILS="LAN 3G 3G-DEGRADE EDGE GPRS"
DC="docker compose"
V="\033[1;32m"; B="\033[1;34m"; N="\033[0m"

mkdir -p mesures
rm -f mesures/*.json mesures/*.jsonl mesures/*.txt
printf "${B}=== Construction et démarrage du banc ===${N}\n"
$DC up -d --build
$DC exec -T client sh -c 'until python3 -c "import socket;socket.create_connection((\"passerelle\",7000),2)" 2>/dev/null; do sleep 1; done'

# ---- vérification : l'émulation réseau (tc netem) est-elle disponible ? ------
if ! $DC exec -T client sh -c 'tc qdisc add dev lo root netem delay 1ms && tc qdisc del dev lo root' >/dev/null 2>&1; then
  echo "ERREUR : le noyau de Docker ne fournit pas tc netem ; les profils réseau ne peuvent pas être appliqués."
  echo "Mettez Docker Desktop à jour, ou lancez la campagne sur un serveur Linux (module sch_netem)."
  $DC down; exit 1
fi
echo "émulation réseau (tc netem) : disponible"
[ "$MODE" = rapide ] || echo "Durée estimée de la campagne complète : environ 1 heure. Ne pas mettre le Mac en veille."

# ---- environnement de mesure (annexe D, tableau D.1) ----------------------
{
  echo "date        : $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "mode        : $MODE (répétitions=$REP, sessions/répétition=$SESS, chauffe=$CHAUFFE)"
  echo "machine     : $(uname -srm)"
  echo "processeur  : $(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo | cut -d: -f2)"
  echo "coeurs      : $(sysctl -n hw.ncpu 2>/dev/null || nproc)"
  echo "memoire_go  : $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))"
  echo "docker      : $(docker version --format '{{.Server.Version}}')"
  echo "noyau_banc  : $($DC exec -T client uname -r)"
  echo "java        : $($DC exec -T client java -version 2>&1 | grep -m1 version)"
} > mesures/environnement.txt
cat mesures/environnement.txt

appliquer () {   # $1 = profil, appliqué à l'interface des deux conteneurs (les deux sens)
  for c in passerelle client; do $DC exec -T $c ./profil.sh "$1" eth0 >/dev/null; done
  # contrôle du profil : 10 pings, archivés avec la série
  $DC exec -T client ping -c 10 -q passerelle | tail -1 | sed "s/^/$1 : /" >> mesures/controle_profils.txt
}

# ---- mesures de performance -------------------------------------------------
for P in $PROFILS; do
  printf "\n${B}=== Profil %s ===${N}\n" "$P"
  appliquer "$P"
  for M in $(printf "sfb\ntls\n" | awk 'BEGIN{srand()}{print rand()"\t"$0}' | sort | cut -f2); do   # ordre aléatoire
    $DC exec -T client java -cp build sfb.Mesure mesure "$M" "$P" "$REP" "$SESS" "$CHAUFFE" "/mesures/${P}_${M}.json"
  done
done
appliquer RESET

# ---- campagne d'attaque (profil LAN) ----------------------------------------
printf "\n${B}=== Campagne d'attaque ===${N}\n"
$DC exec -T client java -cp build sfb.Mesure attaques "$ATT" /mesures/attaques.json
printf "\n${B}=== Attaque par réflexion : version 1 puis version 2 ===${N}\n"
$DC exec -T -e SFB_HOTE=passerelle-v1 client java -cp build sfb.Mesure reflexion "$ATT" /mesures/reflexion_v1.json
$DC exec -T client java -cp build sfb.Mesure reflexion "$ATT" /mesures/reflexion_v2.json

$DC down
# ---- scénarios 5 et 6 (profil LAN) ----------------------------------------
$DC exec -T client java -cp build sfb.Mesure composition "$ATT" /mesures/complements/composition.json
$DC exec -T -e SFB_JOURNAL=/mesures/journal_passerelle.jsonl client java -cp build sfb.Mesure menace "$ATT" /mesures/complements/menace.json
# ---- débit sous charge (profil LAN ; règles de vélocité neutralisées) ------
# La passerelle doit être lancée avec SFB_POLITIQUE=politique_charge.properties pour cette étape ;
# en campagne par lots, voir lot_vm.sh (modes debit-sfb et debit-tls, 3 séries de 30 s).
printf "\n${B}=== Analyse ===${N}\n"
python3 analyse.py mesures ../../resultats/banc
printf "\n${V}Campagne terminée. Recompilez le mémoire : make final${N}\n"
