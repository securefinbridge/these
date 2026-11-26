---
id: demarrage-rapide
title: Démarrage rapide
sidebar_position: 2
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Démarrage rapide

En cinq minutes : ouvrir une session, envoyer un ordre, rejouer une attaque.

<Tabs groupId="mode">
<TabItem value="ligne" label="En ligne" default>

Rien à installer. La console en ligne tient le rôle de la plateforme.

```bash
export SFB=https://securefinbridge.emmanuelsehou.com
curl $SFB/sante
```

</TabItem>
<TabItem value="local" label="En local">

Prérequis : Java 21, Maven et Python 3.

```bash
git clone https://github.com/securefinbridge/these.git
cd these/code/banc
mvn spring-boot:run
```

L'application génère des clés de démonstration, lance le bouchon bancaire et la passerelle, puis écoute sur le port 8080.

```bash
export SFB=http://localhost:8080
curl $SFB/sante
```

</TabItem>
</Tabs>

## 1. Ouvrir une session

```bash
curl -X POST $SFB/api/v1/sessions
```

La console exécute une vraie poignée de main SFB-AKA avec la passerelle et renvoie la taille de chaque message et la durée.

## 2. Envoyer un ordre

```bash
curl -X POST $SFB/api/v1/ordres \
  -H "Content-Type: application/json" \
  -d '{"montant": 25000, "compteDebiteur": "22670000001", "compteCrediteur": "BF0010001"}'
```

L'ordre est transformé en message pacs.008, scellé, envoyé à la passerelle, puis transmis au bouchon bancaire.

## 3. Voir un rejet par la politique

```bash
curl -X POST $SFB/api/v1/ordres \
  -H "Content-Type: application/json" \
  -d '{"montant": 5000000, "compteDebiteur": "22670000001", "compteCrediteur": "BF0010001"}'
```

Le plafond par ordre est de 2 000 000 FCFA : la passerelle répond `POLITIQUE_REFUSEE` sans rien transmettre à la banque.

## 4. Rejouer une attaque

```bash
curl -X POST $SFB/api/v1/attaques/rejeu
```

La console rejoue un scénario du banc d'essai et compare le résultat attendu au résultat observé.

## 5. Lire le journal d'audit

```bash
curl "$SFB/api/v1/journal?n=10"
```

Chaque entrée porte l'empreinte de la précédente. Modifier une ligne casse la chaîne.

## Et ensuite

- Le détail des points d'accès : [API de la console](/docs/reference/api).
- La signification de chaque refus : [Motifs de rejet](/docs/reference/motifs).
