#!/bin/bash
# ============================================================
#  Démonstration 2 — le banc d'essai côté core banking (sans la passerelle)
#
#  Montre, en direct, le comportement du bouchon ISO 20022 face à :
#    1. un ordre de virement pacs.008 valide           -> pacs.002 ACCP (HTTP 200)
#    2. le REJEU du même ordre                         -> pacs.002 RJCT/DUPL (HTTP 409)
#    3. un message malformé                            -> pacs.002 RJCT/FF01 (HTTP 400)
#    4. le journal des décisions                       -> GET /journal
#
#  Prérequis : python3 (3.10+) et curl. Aucune dépendance Python.
#  Usage     : ./code/demo_bouchon.sh          (depuis la racine du projet)
# ============================================================
cd "$(dirname "$0")/.."
PORT=8443
B="\033[1;34m"; V="\033[1;32m"; R="\033[1;31m"; J="\033[1;33m"; N="\033[0m"

pause () { printf "${J}   [Entrée pour continuer]${N}"; read -r _; }

printf "\n${B}##### Démonstration 2 — bouchon core banking ISO 20022 #####${N}\n"
python3 code/bouchon_cbs.py $PORT > /tmp/bouchon_demo.log 2>&1 &
BOUCHON=$!
trap 'kill $BOUCHON 2>/dev/null' EXIT
sleep 1
curl -s "http://127.0.0.1:$PORT/sante" >/dev/null && printf "${V}✔${N} bouchon démarré sur le port %s\n" "$PORT" || { printf "${R}✘ le bouchon ne répond pas${N}\n"; exit 1; }

REF="E2E-$(date +%s)"
PACS=$(cat <<EOF
<?xml version="1.0"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
  <FIToFICstmrCdtTrf><CdtTrfTxInf>
    <PmtId><EndToEndId>$REF</EndToEndId></PmtId>
    <IntrBkSttlmAmt Ccy="XOF">25000</IntrBkSttlmAmt>
    <DbtrAcct><Id><Othr><Id>22670000001</Id></Othr></Id></DbtrAcct>
    <CdtrAcct><Id><Othr><Id>BF0010001</Id></Othr></Id></CdtrAcct>
  </CdtTrfTxInf></FIToFICstmrCdtTrf>
</Document>
EOF
)

envoi () {  # $1 = libellé, $2 = corps
  printf "\n${B}--- %s ---${N}\n" "$1"
  CODE=$(curl -s -o /tmp/reponse.xml -w "%{http_code}" -X POST -H "Content-Type: application/xml" --data "$2" "http://127.0.0.1:$PORT/pacs008")
  STATUT=$(grep -o "<TxSts>[A-Z]*</TxSts>" /tmp/reponse.xml | sed 's/<[^>]*>//g')
  MOTIF=$(grep -o "<Prtry>[A-Z0-9]*</Prtry>" /tmp/reponse.xml | sed 's/<[^>]*>//g')
  case "$CODE" in
    200) printf "  ${V}HTTP %s — pacs.002 %s${N}\n" "$CODE" "$STATUT" ;;
    *)   printf "  ${R}HTTP %s — pacs.002 %s (%s)${N}\n" "$CODE" "$STATUT" "$MOTIF" ;;
  esac
}

printf "\n1. Un ordre de virement légitime (référence %s, 25 000 XOF)\n" "$REF"; pause
envoi "pacs.008 valide" "$PACS"

printf "\n2. L'adversaire capture l'ordre et le REJOUE tel quel\n"; pause
envoi "rejeu du même pacs.008" "$PACS"
printf "   ${R}→ rejeté : même EndToEndId déjà traité (protection applicative du core banking)${N}\n"
printf "   ${J}→ avec SFB-AKA, le rejeu est bloqué AVANT d'atteindre le core banking (compteur de session, P4)${N}\n"

printf "\n3. Un message malformé\n"; pause
envoi "pacs.008 malformé" "<Document/>"

printf "\n4. Le journal des décisions du core banking\n"; pause
curl -s "http://127.0.0.1:$PORT/journal" | python3 -m json.tool
printf "\n${V}Fin de la démonstration 2.${N}\n\n"
