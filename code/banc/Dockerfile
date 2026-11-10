# Image unique du banc d'essai SecureFinBridge : passerelle SFB-AKA + référence
# TLS 1.3 + bouchon core banking ISO 20022 + client de mesure.
# Toute la cryptographie vient du JDK 21 (aucune dépendance externe).
FROM eclipse-temurin:21-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 iproute2 iputils-ping \
 && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8 SFB_CLES=/cles TLS_REP=/tls
WORKDIR /app
COPY banc/src ./src
COPY banc/generer_cles.sh banc/politique.properties banc/politique_charge.properties bouchon_cbs.py profil.sh ./
RUN javac -d build src/sfb/*.java \
 && sh generer_cles.sh /cles /tls build \
 && chmod +x profil.sh
# Par défaut : bouchon core banking (localhost:8443) + passerelle (7000 SFB-AKA, 7443 TLS 1.3).
# Le bouchon partage la pile réseau de la passerelle : le lien passerelle -> core banking
# n'est pas soumis au profil réseau, seul le segment plateforme -> passerelle l'est.
CMD ["sh", "-c", "python3 bouchon_cbs.py 8443 > /tmp/bouchon.log 2>&1 & exec java -cp build sfb.Passerelle"]
