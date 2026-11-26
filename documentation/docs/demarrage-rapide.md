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

L'application génère des clés de démonstration, lance le bouchon bancaire et la passerelle, puis écoute sur le port 8080. Si un port est déjà pris (le 7000 l'est souvent sur macOS), elle prend le suivant et le signale.

```bash
export SFB=http://localhost:8080
curl $SFB/sante
```

</TabItem>
</Tabs>

La réponse attendue :

```json
{ "statut": "OK", "passerelle": true, "bouchon": true }
```

La même démonstration existe sous forme de page : ouvrez `$SFB/` dans un navigateur.

## 1. Ouvrir une session

```bash
curl -X POST $SFB/api/v1/sessions
```

La console exécute une vraie poignée de main SFB-AKA avec la passerelle. Elle renvoie les trois messages échangés, leur taille relevée sur le fil et la durée de l'essai :

```json
{
  "idSession": "780c57263712a07f",
  "tailles": { "M1": 88, "M2": 162, "M3": 128 },
  "octetsMessages": 378,
  "octetsCadrage": 6,
  "octetsSurLeFil": 384,
  "dureeMs": 3.454
}
```

Le champ `messages`, omis ici, détaille chaque message champ par champ.

## 2. Envoyer un ordre

```bash
curl -X POST $SFB/api/v1/ordres \
  -H "Content-Type: application/json" \
  -d '{"montant": 25000, "compteDebiteur": "22670000001", "compteCrediteur": "BF0010001"}'
```

L'ordre est transformé en message pacs.008, scellé, envoyé à la passerelle, puis transmis au bouchon bancaire. Extrait de la réponse :

```json
{
  "accepte": true,
  "statut": "ACCP",
  "motif": "ACCEPTE",
  "idSession": "096894860c7f9caa",
  "compteur": 1,
  "reference": "E2E-b0d8d3a5-aa87-4d4f-8fe8-1eb99c180c8a",
  "montant": 25000
}
```

## 3. Voir un rejet par la politique

```bash
curl -X POST $SFB/api/v1/ordres \
  -H "Content-Type: application/json" \
  -d '{"montant": 5000000, "compteDebiteur": "22670000001", "compteCrediteur": "BF0010001"}'
```

Le plafond par ordre est de 2 000 000 FCFA : la passerelle répond `POLITIQUE_REFUSEE` sans rien transmettre à la banque. Extrait de la réponse :

```json
{ "accepte": false, "statut": "RJCT", "motif": "POLITIQUE_REFUSEE", "montant": 5000000 }
```

La réponse porte le code HTTP `200` : la passerelle a bien répondu, et sa réponse est un refus.

## 4. Rejouer une attaque

```bash
curl -X POST $SFB/api/v1/attaques/rejeu
```

La console rejoue un scénario du banc d'essai et compare le résultat attendu au résultat observé. Extrait de la réponse :

```json
{
  "scenario": "rejeu",
  "attendu": "REJEU_DETECTE",
  "observe": "REJEU_DETECTE",
  "conforme": true
}
```

Les autres scénarios : `alteration`, `intrus` et `suites`.

## 5. Lire le journal d'audit

```bash
curl "$SFB/api/v1/journal?n=10"
```

```json
{
  "total": 48,
  "chainageVerifie": true,
  "liensVerifies": 48,
  "premiereRupture": null,
  "tete": "a460d949cee9…",
  "entrees": [
    {
      "numero": 47,
      "horodatage": "2026-11-26T16:52:41.637961757Z",
      "id_session": "b8cdf42a32758fe0",
      "compteur": 4,
      "decision": "RJCT",
      "motif": "POLITIQUE_REFUSEE",
      "empreinte_precedente": "26ef647ba176…",
      "empreinte": "ef3724f93f66…",
      "chainee": true
    }
  ]
}
```

Une seule entrée est montrée ici.

Chaque entrée porte l'empreinte de la précédente, et la console recalcule toute la chaîne à chaque lecture. Modifier une entrée ancienne casse la chaîne à l'entrée suivante ; la dernière entrée n'est protégée que si son empreinte de tête est publiée ailleurs.

## Et ensuite

- Le détail des points d'accès : [API de la console](/docs/reference/api).
- La signification de chaque refus : [Motifs de rejet](/docs/reference/motifs).
