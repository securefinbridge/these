---
id: instructions
title: Les ordres scellés
---

# Les ordres scellés

Une fois la session ouverte, chaque ordre voyage dans une trame scellée.

## Format de la trame

| Champ | Octets | Contenu |
|---|---|---|
| `sid` | 8 | Identifiant de session |
| `c` | 8 | Compteur, strictement croissant |
| chiffré | variable | L'ordre pacs.008 chiffré en AES-128-GCM |
| MAC | 32 | HMAC-SHA-256 sur `"instr"`, `sid`, `c` et l'empreinte de l'ordre |

Le nonce AES-GCM est construit à partir du compteur, et `sid | c` est passé en données authentifiées.

## L'ordre des contrôles

La passerelle applique toujours les contrôles dans le même ordre, identique à celui du modèle formel :

1. **Intégrité.** Identifiant de session, déchiffrement, MAC. En cas d'échec : `INTEGRITE_COMPROMISE`.
2. **Fraîcheur.** Le compteur doit être strictement supérieur au dernier accepté. Sinon : `REJEU_DETECTE`.
3. **Politique.** Plafond par ordre, puis règles de vélocité. Sinon : `POLITIQUE_REFUSEE` ou `VELOCITE_DEPASSEE`.
4. **Journalisation.** La décision est écrite dans le journal chaîné **avant** la réponse.
5. **Transmission** à la banque, puis réponse à la plateforme.

## La réponse

| Champ | Octets | Contenu |
|---|---|---|
| statut | 1 | `0` accepté, `1` rejeté |
| motif | 1 | Code du [motif](/docs/reference/motifs) |
| mesures | 16 | Deux compteurs de temps processeur, utilisés par le banc d'essai |

## Relance à l'identique

Si la plateforme renvoie exactement la dernière trame déjà traitée (par exemple après l'expiration d'un délai), la passerelle renvoie le statut initial avec le motif `DOUBLON_IDEMPOTENT`. L'ordre n'est ni réévalué ni réexécuté.

## L'ordre lui-même

Un message ISO 20022 **pacs.008** minimal :

```xml
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
  <FIToFICstmrCdtTrf><CdtTrfTxInf>
    <PmtId><EndToEndId>E2E-…</EndToEndId></PmtId>
    <IntrBkSttlmAmt Ccy="XOF">25000</IntrBkSttlmAmt>
    <DbtrAcct><Id><Othr><Id>22670000001</Id></Othr></Id></DbtrAcct>
    <CdtrAcct><Id><Othr><Id>BF0010001</Id></Othr></Id></CdtrAcct>
  </CdtTrfTxInf></FIToFICstmrCdtTrf>
</Document>
```

Le bouchon bancaire répond par un **pacs.002** : accepté ou rejeté. Il refuse une référence `EndToEndId` déjà vue.
