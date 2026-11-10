#!/bin/sh
# ============================================================
#  Profils d'émulation réseau du banc d'essai (tc / netem)
#  Usage :  ./profil.sh {LAN|3G|3G-DEGRADE|EDGE|GPRS|INTERMITTENT|RESET} [iface]
#  Les valeurs sont celles du tableau « Paramètres d'émulation réseau » de
#  l'annexe D ; elles s'appliquent dans les deux sens sur l'interface.
# ============================================================
PROFIL="${1:-LAN}"; IF="${2:-eth0}"

reset () { tc qdisc del dev "$IF" root 2>/dev/null || true; }
appliquer () {  # $1 débit, $2 latence, $3 gigue, $4 perte
  reset
  tc qdisc add dev "$IF" root handle 1: tbf rate "$1" burst 32kbit latency 400ms
  tc qdisc add dev "$IF" parent 1:1 handle 10: netem delay "$2" "$3" distribution normal loss "$4"
}

case "$PROFIL" in
  LAN)          reset ;;
  3G)           appliquer 2mbit   150ms 30ms 0.5% ;;
  3G-DEGRADE)   appliquer 384kbit 300ms 80ms 2%   ;;
  EDGE)         appliquer 236kbit 300ms 60ms 1%   ;;
  GPRS)         appliquer 56kbit  450ms 100ms 3%  ;;
  INTERMITTENT) appliquer 384kbit 300ms 80ms 2%
                # coupures de 2 s toutes les 20 s, pendant 10 min
                ( i=0; while [ $i -lt 30 ]; do sleep 18; tc qdisc change dev "$IF" parent 1:1 handle 10: netem loss 100%; sleep 2; tc qdisc change dev "$IF" parent 1:1 handle 10: netem delay 300ms 80ms distribution normal loss 2%; i=$((i+1)); done ) & ;;
  RESET)        reset ;;
  *) echo "profil inconnu : $PROFIL"; exit 1 ;;
esac
echo "profil $PROFIL appliqué sur $IF"; tc qdisc show dev "$IF"
