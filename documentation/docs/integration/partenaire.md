---
id: partenaire
title: Intégrer un partenaire
---

# Intégrer un partenaire

Cette page décrit comment une plateforme s'intégrerait à la passerelle en parlant directement SFB-AKA en TCP, sans passer par la console.

:::info État de la démonstration en ligne
En ligne, seul le port HTTP de la console est ouvert. Le port TCP de la passerelle n'est pas exposé sur Internet à cette étape. Le parcours ci-dessous se rejoue donc **en local**, avec le banc d'essai.
:::

## 1. Obtenir une identité

Chaque partenaire a un identifiant de 16 octets et une paire de clés ECDSA P-256.

```bash
cd these/code/banc
javac -d build src/sfb/*.java
java -cp build sfb.Identites ./cles plateforme
```

Deux fichiers sont créés : `plateforme.key` (clé privée, à garder secrète) et `plateforme.pub` (clé publique).

## 2. S'inscrire au registre

L'opérateur de la passerelle ajoute votre clé publique à son registre. Dans le prototype, le registre est la liste donnée par la variable `SFB_REGISTRE` et les fichiers `.pub` du dossier de clés. Sans inscription, toute tentative est refusée avec `REGISTRE_INCONNU`.

## 3. Ouvrir une session et envoyer un ordre

```java
import java.io.*;
import java.net.Socket;
import java.nio.file.Path;
import sfb.*;

Path cles = Path.of("./cles");
Identites.Identite moi = Identites.charger(cles, "plateforme");
Identites.Registre registre = new Identites.Registre(cles, "passerelle");

try (Socket s = new Socket("127.0.0.1", 7000)) {
    var in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
    var out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

    // Poignée de main : M1, M2, M3
    SfbAka.Session session = SfbAka.initier(in, out, moi, registre);

    // Un ordre pacs.008, scellé et numéroté
    byte[] ordre = pacs008.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    SfbAka.ecrire(out, SfbAka.scellerInstruction(session, session.prochainCompteur++, ordre));

    // Réponse : statut (1 octet), motif (1 octet), mesures (16 octets)
    byte[] reponse = SfbAka.lire(in);
    boolean accepte = reponse[0] == 0;
    SfbAka.Motif motif = SfbAka.Motif.values()[reponse[1]];
}
```

## 4. Ce qu'il faut respecter

- **Un compteur strictement croissant** par session. Réutiliser un compteur déclenche `REJEU_DETECTE`.
- **Une référence `EndToEndId` unique** par ordre.
- **Ne jamais réutiliser** la clé SFB-AKA pour signer autre chose (notifications, jetons). C'est la réutilisation d'une clé entre canaux qui ouvre l'attaque par composition.
- **Vérifier le registre côté plateforme** aussi : la passerelle doit y figurer.

## Dans une autre langue que Java

Le protocole ne dépend pas de Java. Toute bibliothèque qui fournit X25519, ECDSA P-256, HKDF, AES-GCM et HMAC-SHA-256 suffit. Les pages [Les trois messages](/docs/protocole/messages) et [Les ordres scellés](/docs/protocole/instructions) donnent les formats à l'octet près.
