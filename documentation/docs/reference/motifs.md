---
id: motifs
title: Motifs de rejet
---

# Motifs de rejet

Chaque réponse de la passerelle porte un motif. Les codes sont ceux de l'octet de motif dans la réponse TCP.

| Code | Motif | Quand | Que faire |
|---|---|---|---|
| 0 | `ACCEPTE` | L'ordre est passé | Rien |
| 1 | `REGISTRE_INCONNU` | Identité ou clé absente du registre | Faire inscrire la clé publique |
| 2 | `SIGNATURE_INVALIDE` | Signature de la transcription refusée, ou message de taille inattendue | Vérifier la clé, l'étiquette de rôle et le calcul de la transcription |
| 3 | `PREUVE_INVALIDE` | La preuve de possession de M3 ne correspond pas | Vérifier la dérivation de `k_mac` |
| 4 | `SUITE_REFUSEE` | La suite de référence n'est pas proposée ou pas retenue | Proposer la suite `0x0001` |
| 5 | `INTEGRITE_COMPROMISE` | Trame altérée, mauvaise session, MAC faux | Ne pas renvoyer tel quel : rouvrir une session |
| 6 | `REJEU_DETECTE` | Compteur déjà utilisé ou trop ancien | Incrémenter le compteur |
| 7 | `POLITIQUE_REFUSEE` | Montant nul ou au-dessus du plafond | Corriger le montant |
| 8 | `CORE_BANKING_REJET` | La banque a refusé ou n'a pas répondu | Voir le rapport pacs.002 |
| 9 | `VELOCITE_DEPASSEE` | Trop d'ordres ou cumul trop élevé dans la fenêtre | Attendre la fin de la fenêtre |
| 10 | `DOUBLON_IDEMPOTENT` | Relance à l'identique de la dernière trame traitée | Lire le statut renvoyé : c'est celui du premier envoi |

## Dans le journal

Le journal d'audit note la décision sous la forme `ACCP` (accepté), `RJCT` (rejeté) ou `DOUBLON`, avec le motif correspondant.
