#!/bin/sh
# Génère les clés du banc : identités SFB-AKA (ECDSA P-256) et PKI TLS 1.3
# (autorité + certificats serveur et client ECDSA P-256 : chaîne de 2).
# Usage : ./generer_cles.sh <rép_cles_sfb> <rép_tls> <classpath>
set -e
CLES="$1"; TLS="$2"; CP="$3"; MDP=banc-sfb
mkdir -p "$CLES" "$TLS"
java -cp "$CP" sfb.Identites "$CLES" plateforme passerelle
cd "$TLS"
kt () { keytool "$@" -storepass $MDP -noprompt >/dev/null 2>&1; }
kt -genkeypair -alias ca -keyalg EC -groupname secp256r1 -dname CN=SFB-AC -ext bc:c -validity 3650 -keystore ca.p12
kt -exportcert -alias ca -keystore ca.p12 -rfc -file ca.pem
for nom in serveur client; do
  kt -genkeypair -alias $nom -keyalg EC -groupname secp256r1 -dname CN=$nom -validity 3650 -keystore $nom.p12
  kt -certreq -alias $nom -keystore $nom.p12 -file $nom.csr
  kt -gencert -alias ca -keystore ca.p12 -infile $nom.csr -outfile $nom.pem -rfc -validity 3650 \
     -ext san=dns:passerelle,dns:localhost,ip:127.0.0.1
  kt -importcert -alias ca -file ca.pem -keystore $nom.p12
  kt -importcert -alias $nom -file $nom.pem -keystore $nom.p12
done
kt -importcert -alias ca -file ca.pem -keystore confiance.p12
rm -f *.csr
echo "clés générées dans $CLES et $TLS"
