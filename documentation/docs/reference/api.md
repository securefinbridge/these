---
id: api
title: API de la console
---

# API de la console

La console de démonstration expose une API HTTP. Elle tient le rôle de la plateforme Mobile Money : derrière chaque appel, elle parle SFB-AKA à la passerelle.

**Adresse de base :** `https://securefinbridge.emmanuelsehou.com`

:::note
Cette API sert à la démonstration. Elle n'est pas l'interface d'intégration d'un partenaire, qui se fait en TCP : voir [Intégrer un partenaire](/docs/integration/partenaire).
:::

| Méthode et chemin | Rôle |
|---|---|
| `GET /sante` | État de la passerelle et du bouchon |
| `GET /api/v1/etat` | Version, politique en vigueur, bornes de la console |
| `POST /api/v1/sessions` | Ouvre une session SFB-AKA et renvoie les trois messages |
| `POST /api/v1/ordres` | Scelle et envoie un ordre de virement |
| `POST /api/v1/attaques/{scenario}` | Rejoue un scénario du banc d'attaque |
| `GET /api/v1/journal?n=20` | Dernières entrées du journal chaîné, chaînage vérifié |

Toutes les réponses sont en JSON. Les exemples ci-dessous sont des réponses réelles de la console ; les identifiants, empreintes et durées changent à chaque appel.

## État du service

### `GET /sante`

Répond `200` si la passerelle et le bouchon bancaire répondent, `503` sinon. Les sondes du déploiement s'appuient sur ce point d'accès.

```json
{ "statut": "OK", "passerelle": true, "bouchon": true }
```

### `GET /api/v1/etat`

Version et commit en service, heure de démarrage, règles de politique en vigueur, et ce que la console accepte en entrée.

```json
{
  "service": "SecureFinBridge : passerelle SFB-AKA",
  "avertissement": "Prototype de démonstration. Banque simulée par un bouchon ISO 20022. Clés de démonstration régénérées à chaque démarrage. Non destiné à la production.",
  "version": "1.0.0",
  "commit": "6b00c94f4fe1fed3520bece13a2f21357b579e91",
  "demarreLe": "2026-11-26T16:52:17.946380719Z",
  "portSfbAka": 7000,
  "portTls13": 7443,
  "etiquettesRole": true,
  "passerelleRepond": true,
  "bouchonRepond": true,
  "politique": {
    "fenetre_secondes": "60",
    "montant_cumule_max_par_fenetre": "20000000",
    "ordres_max_par_fenetre": "1200",
    "plafond_par_ordre": "2000000"
  },
  "console": {
    "montantMin": 1,
    "montantMax": 20000000,
    "plafondPolitique": 2000000,
    "corpsMaxOctets": 4096,
    "scenarios": [
      {
        "nom": "rejeu",
        "reference": "2_rejeu_instruction",
        "description": "Dans une session légitime, une instruction déjà acceptée puis dépassée est renvoyée telle quelle.",
        "attendu": "REJEU_DETECTE"
      }
    ]
  }
}
```

Les ports `portSfbAka` et `portTls13` sont ceux de la passerelle à l'intérieur du déploiement ; ils ne sont pas ouverts sur Internet.

## Sessions

### `POST /api/v1/sessions`

Ouvre une session SFB-AKA réelle avec la passerelle, puis referme la connexion. La requête n'a pas de corps.

La réponse donne l'identifiant de session, la taille de chacun des trois messages et leur découpage en champs. Les tailles sont relevées sur le fil à chaque appel, elles ne sont pas écrites en dur.

```json
{
  "idSession": "780c57263712a07f",
  "tailles": { "M1": 88, "M2": 162, "M3": 128 },
  "messages": [
    {
      "nom": "M1",
      "sens": "plateforme → passerelle",
      "octets": 88,
      "champs": [
        { "nom": "id_I (identifiant de l'initiateur)", "octets": 16, "hex": "706c617465666f726d65000000000000" },
        { "nom": "X (part éphémère X25519)", "octets": 32, "hex": "8ac316d59f92507b…" },
        { "nom": "n_I (aléa)", "octets": 32, "hex": "4ca0b2c68ce53d45…" },
        { "nom": "suites proposées", "octets": 8, "hex": "0001000200030004" }
      ]
    }
  ],
  "octetsMessages": 378,
  "octetsCadrage": 6,
  "octetsSurLeFil": 384,
  "dureeMs": 3.454
}
```

| Champ | Sens |
|---|---|
| `tailles` | Taille en octets de M1, M2 et M3 |
| `messages` | Les trois messages, avec leurs champs en hexadécimal (seul M1 est montré ci-dessus) |
| `octetsMessages` | Somme des trois messages : 88 + 162 + 128 = 378 |
| `octetsCadrage` | Longueur sur 2 octets devant chaque message : 6 |
| `octetsSurLeFil` | Total échangé pendant la poignée de main : 384 |
| `dureeMs` | Durée de cet essai, connexion TCP comprise |

Aucune clé de session n'est renvoyée ni conservée : les messages montrés sont ceux qui circulent en clair sur le réseau.

:::caution
`dureeMs` est mesurée entre la console et la passerelle, qui tournent sur la même machine. Elle ne dit rien d'un réseau mobile.
:::

## Ordres

### `POST /api/v1/ordres`

Construit un ordre de virement pacs.008, ouvre une session neuve, scelle l'ordre et l'envoie. Le corps est en JSON (`Content-Type: application/json`).

| Champ | Type | Contrainte |
|---|---|---|
| `montant` | entier | En FCFA, de 1 à 20 000 000 |
| `compteDebiteur` | texte | 4 à 24 lettres ou chiffres |
| `compteCrediteur` | texte | 4 à 24 lettres ou chiffres |
| `reference` | texte, facultatif | 1 à 40 lettres, chiffres ou tirets ; tirée au hasard si elle est absente |

La console laisse passer jusqu'à dix fois le plafond de la politique (2 000 000 FCFA) : au-dessus du plafond, c'est donc bien la passerelle qui refuse, avec le motif `POLITIQUE_REFUSEE`.

```bash
curl -X POST https://securefinbridge.emmanuelsehou.com/api/v1/ordres \
  -H "Content-Type: application/json" \
  -d '{"montant": 25000, "compteDebiteur": "22670000001", "compteCrediteur": "BF0010001"}'
```

```json
{
  "accepte": true,
  "statut": "ACCP",
  "motif": "ACCEPTE",
  "idSession": "096894860c7f9caa",
  "compteur": 1,
  "reference": "E2E-b0d8d3a5-aa87-4d4f-8fe8-1eb99c180c8a",
  "montant": 25000,
  "empreinteInstruction": "5bb48c89a1a38b3c447240bdcec5a662e46d783c565fccf3b306fa3beb38867e",
  "octetsInstruction": 479,
  "dureePoigneeMs": 5.254,
  "dureeTotaleMs": 8.692
}
```

| Champ | Sens |
|---|---|
| `accepte` | `true` si la passerelle a accepté l'ordre et que le bouchon l'a pris |
| `statut` | `ACCP` ou `RJCT` |
| `motif` | `ACCEPTE` ou le [motif de rejet](/docs/reference/motifs) |
| `idSession`, `compteur` | Session ouverte pour cet ordre et numéro de l'instruction ; on les retrouve dans le journal |
| `reference` | Référence de bout en bout de l'ordre (`EndToEndId`) |
| `empreinteInstruction` | SHA-256 de l'ordre pacs.008 ; c'est l'`empreinte_instruction` de l'entrée du journal |
| `octetsInstruction` | Taille de la trame scellée |

Une décision de la passerelle, acceptation ou rejet, est toujours renvoyée avec le code HTTP `200`. Les codes d'erreur sont réservés aux requêtes mal formées ou à un service indisponible.

**Renvoyer le même ordre.** Chaque appel ouvre une session neuve. Renvoyer un ordre avec la `reference` d'un ordre déjà accepté donne `CORE_BANKING_REJET` : la passerelle le juge authentique et conforme, le journalise (`ACCP`) et le transmet, puis le bouchon refuse la référence déjà traitée. Le rejeu d'une trame au niveau du protocole est le scénario d'attaque `rejeu`.

## Attaques

### `POST /api/v1/attaques/{scenario}`

Rejoue une fois un scénario du banc d'attaque, avec le code utilisé pour la campagne du mémoire. La requête n'a pas de corps.

| Scénario | Référence du banc | Ce qui est tenté | Rejet attendu |
|---|---|---|---|
| `rejeu` | `2_rejeu_instruction` | Dans une session légitime, renvoyer une instruction déjà acceptée puis dépassée | `REJEU_DETECTE` |
| `alteration` | `3a_alteration_instruction` | Modifier un bit du chiffré d'une instruction en transit | `INTEGRITE_COMPROMISE` |
| `intrus` | `E1_partenaire_non_enregistre` | Se présenter avec une clé hors registre | `REGISTRE_INCONNU` |
| `suites` | `4_alteration_suites` | Réécrire la liste des suites de M1 en transit | `SIGNATURE_INVALIDE` |

```bash
curl -X POST https://securefinbridge.emmanuelsehou.com/api/v1/attaques/rejeu
```

```json
{
  "scenario": "rejeu",
  "referenceBanc": "2_rejeu_instruction",
  "description": "Dans une session légitime, une instruction déjà acceptée puis dépassée est renvoyée telle quelle.",
  "attendu": "REJEU_DETECTE",
  "observe": "REJEU_DETECTE",
  "conforme": true,
  "dureeMs": 10.162
}
```

`conforme` vaut `true` lorsque le motif observé est exactement le motif attendu. La réponse fait foi : `observe` est le motif réellement renvoyé à cette exécution. Un champ `remarque` s'ajoute dans deux cas : pour `suites`, dont la détection se fait côté plateforme sans rien inscrire au journal, et lorsque les ordres légitimes qui précèdent l'attaque n'ont pas été acceptés.

À savoir :

- `rejeu` et `alteration` exécutent la même séquence du banc : deux ordres légitimes, le rejeu du premier, la relance à l'identique du second, une altération, puis un ordre au-dessus du plafond. Chaque appel inscrit donc six entrées au journal.
- Ces deux scénarios commencent par deux ordres légitimes. Si la règle de vélocité de la politique est saturée à ce moment-là, ils sont refusés et la réponse indique `conforme: false` avec une `remarque` : le scénario n'a pas pu être joué.
- Un seul scénario s'exécute à la fois.

## Journal

### `GET /api/v1/journal?n=20`

Les `n` dernières entrées du journal d'audit de la passerelle, la plus récente d'abord (`n` de 1 à 100, 20 par défaut), et l'état du chaînage.

```json
{
  "total": 48,
  "chainageVerifie": true,
  "liensVerifies": 48,
  "premiereRupture": null,
  "tete": "a460d949cee9…",
  "entrees": [
    {
      "compteur": 4,
      "decision": "RJCT",
      "empreinte_instruction": "-",
      "empreinte_precedente": "26ef647ba176…",
      "horodatage": "2026-11-26T16:52:41.637961757Z",
      "id_session": "b8cdf42a32758fe0",
      "motif": "POLITIQUE_REFUSEE",
      "numero": 47,
      "empreinte": "ef3724f93f66…",
      "chainee": true
    }
  ]
}
```

| Champ | Sens |
|---|---|
| `total` | Nombre d'entrées du journal depuis le démarrage |
| `chainageVerifie` | `true` si chaque entrée renvoie bien à l'empreinte de la précédente, depuis la genèse |
| `liensVerifies` | Nombre d'entrées, depuis la genèse, dont le renvoi est exact |
| `premiereRupture` | Numéro de la première entrée dont le renvoi est faux, sinon `null` |
| `tete` | Empreinte SHA-256 de la dernière entrée |
| `entrees` | Les entrées telles que la passerelle les a écrites, plus trois champs calculés par la console : `numero`, `empreinte` et `chainee` |

Dans une entrée, `decision` vaut `ACCP`, `RJCT` ou `DOUBLON`. Le `compteur` vaut `-1` lorsque la trame a été rejetée avant que son compteur soit retenu, et `0` pour un rejet pendant la poignée de main.

Le chaînage est recalculé depuis la première entrée à chaque appel : chaque entrée contient l'empreinte SHA-256 de la précédente, la première partant de 64 zéros. Modifier une entrée ancienne rompt le renvoi de l'entrée suivante, et `premiereRupture` la désigne. La dernière entrée, elle, n'est protégée que si sa `tete` a été publiée ailleurs.

Le journal consigne les décisions de la passerelle, pas les réponses de la banque. Il repart de zéro à chaque redémarrage du service.

## Erreurs

Une requête refusée par la console reçoit un message fixe, sans reprise de ce qui a été envoyé :

```json
{ "erreur": "montant : un nombre entier est attendu.", "champ": "montant" }
```

| Code | Cas |
|---|---|
| `400` | Champ manquant ou hors bornes, JSON illisible, paramètre `n` non numérique |
| `403` | Requête d'écriture venue d'une autre origine (aucun CORS n'est ouvert) |
| `404` | Chemin ou scénario inconnu |
| `411`, `413` | Corps sans longueur annoncée, ou de plus de 4 Ko |
| `415` | Corps qui n'est pas du JSON |
| `429` | Trop de requêtes : limitation de débit en amont de la console (réponse en texte, pas en JSON) |
| `502`, `503`, `504` | Session impossible à établir, passerelle injoignable ou console occupée, délai dépassé |

## Limites d'usage

Le service est public et limité en débit en amont de la console : 120 requêtes par minute, avec des rafales de 60. Les montants sont bornés à dix fois le plafond de la politique, les comptes à un motif alphanumérique court, le corps des requêtes à 4 Ko.

Toute la console agit comme un seul partenaire, la plateforme. La règle de vélocité de la politique s'applique donc à l'ensemble des visiteurs : au-delà de 20 000 000 FCFA acceptés en 60 secondes, les ordres suivants reçoivent `VELOCITE_DEPASSEE` jusqu'à ce que la fenêtre se libère.
