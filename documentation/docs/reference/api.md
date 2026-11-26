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

## État du service

### `GET /sante`

Répond `200` si la passerelle et le bouchon bancaire répondent, `503` sinon.

```json
{ "statut": "OK", "passerelle": true, "bouchon": true }
```

### `GET /api/v1/etat`

Heure de démarrage, ports, étiquettes de rôle et règles de politique en vigueur.

```json
{
  "service": "SecureFinBridge : passerelle SFB-AKA",
  "demarreLe": "2026-11-26T16:17:03Z",
  "portSfbAka": 7000,
  "etiquettesRole": true,
  "politique": { "plafond_par_ordre": "2000000", "fenetre_secondes": "60" }
}
```

## Sessions

### `POST /api/v1/sessions`

Ouvre une session SFB-AKA réelle avec la passerelle. Renvoie la taille de chacun des trois messages, la durée de la poignée de main et un identifiant de session.

## Ordres

### `POST /api/v1/ordres`

| Champ | Type | Contrainte |
|---|---|---|
| `montant` | entier | En FCFA, supérieur à zéro |
| `compteDebiteur` | texte | Alphanumérique court |
| `compteCrediteur` | texte | Alphanumérique court |

```bash
curl -X POST https://securefinbridge.emmanuelsehou.com/api/v1/ordres \
  -H "Content-Type: application/json" \
  -d '{"montant": 25000, "compteDebiteur": "22670000001", "compteCrediteur": "BF0010001"}'
```

La réponse donne le statut (`ACCEPTE` ou `REJETE`) et le [motif](/docs/reference/motifs).

## Attaques

### `POST /api/v1/attaques/{scenario}`

Rejoue un scénario du banc d'essai et renvoie le résultat attendu, le résultat observé et leur conformité.

| Scénario | Ce qui est tenté | Rejet attendu |
|---|---|---|
| `rejeu` | Rejouer une poignée de main ou un ordre enregistré | `REJEU_DETECTE` ou échec de la preuve |
| `alteration` | Modifier un ordre en transit | `INTEGRITE_COMPROMISE` |
| `intrus` | Se présenter avec une clé hors registre | `REGISTRE_INCONNU` |
| `suites` | Retirer une suite de la liste de M1 en transit | `SIGNATURE_INVALIDE` |

La réponse de la console fait foi : elle indique pour chaque exécution le motif réellement observé.

## Journal

### `GET /api/v1/journal?n=20`

Les `n` dernières entrées du journal d'audit chaîné.

```json
{
  "compteur": 1,
  "decision": "ACCP",
  "empreinte_instruction": "9f2c…",
  "empreinte_precedente": "0000…",
  "horodatage": "2026-11-26T16:20:11Z",
  "id_session": "a41b…",
  "motif": "-"
}
```

Chaque entrée contient l'empreinte SHA-256 de la précédente.

## Limites d'usage

Le service est public et limité en débit par adresse. Les montants sont bornés par le plafond de la politique.
