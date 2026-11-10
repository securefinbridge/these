#!/bin/sh
# Test de fumée du bouchon core banking déployé (ou local).
#   ./test_fumee.sh [URL]        défaut : http://127.0.0.1:8443
# Attendus : 200 ACCP, puis 409 (rejeu, DUPL), puis 400 (malformé, FF01).
URL="${1:-http://127.0.0.1:8443}"
REF="E2E-FUMEE-$(date +%s)"
PACS="<?xml version=\"1.0\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"><FIToFICstmrCdtTrf><CdtTrfTxInf><PmtId><EndToEndId>$REF</EndToEndId></PmtId><IntrBkSttlmAmt Ccy=\"XOF\">25000</IntrBkSttlmAmt><DbtrAcct><Id><Othr><Id>22670000001</Id></Othr></Id></DbtrAcct><CdtrAcct><Id><Othr><Id>BF0010001</Id></Othr></Id></CdtrAcct></CdtTrfTxInf></FIToFICstmrCdtTrf></Document>"
ECHEC=0
envoi () {  # $1 libellé, $2 code attendu, $3 corps
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/xml" --data "$3" "$URL/pacs008")
  if [ "$CODE" = "$2" ]; then echo "OK    $1 -> HTTP $CODE"; else echo "ECHEC $1 -> HTTP $CODE (attendu $2)"; ECHEC=1; fi
}
curl -sf "$URL/sante" >/dev/null || { echo "ECHEC bouchon injoignable sur $URL"; exit 1; }
envoi "ordre valide      " 200 "$PACS"
envoi "rejeu du même ordre" 409 "$PACS"
envoi "message malformé  " 400 "<Document/>"
[ $ECHEC = 0 ] && echo "TEST DE FUMÉE : OK" || { echo "TEST DE FUMÉE : ÉCHEC"; exit 1; }
