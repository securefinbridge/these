---
id: messages
title: Les trois messages
---

# Les trois messages

Dans ce qui suit, **I** est l'initiateur (la plateforme) et **R** le répondant (la passerelle). Chaque message est précédé de sa longueur sur 2 octets.

| Message | Sens | Taille |
|---|---|---|
| M1 | I vers R | 88 octets |
| M2 | R vers I | 162 octets |
| M3 | I vers R | 128 octets |
| Cadrage | 3 fois 2 octets | 6 octets |
| **Total sur le fil** | | **384 octets** |

## M1 : la plateforme se présente

| Champ | Octets | Contenu |
|---|---|---|
| `id_I` | 16 | Identifiant de la plateforme |
| `X` | 32 | Part publique éphémère X25519 |
| `n_I` | 32 | Aléa |
| `suites` | 8 | Quatre suites cryptographiques proposées, 2 octets chacune |

La passerelle cherche `id_I` dans le registre. S'il est inconnu, elle répond `REGISTRE_INCONNU` et s'arrête.

## M2 : la passerelle répond et s'authentifie

| Champ | Octets | Contenu |
|---|---|---|
| `Y` | 32 | Part publique éphémère X25519 |
| `n_R` | 32 | Aléa |
| `suite` | 2 | Suite retenue |
| `emp_R` | 32 | Empreinte SHA-256 de la clé publique de la passerelle |
| `Sig_R` | 64 | Signature ECDSA de la transcription |

La plateforme vérifie que `emp_R` est au registre, que la suite retenue est bien la suite de référence, puis la signature.

## M3 : la plateforme s'authentifie et prouve qu'elle a la clé

| Champ | Octets | Contenu |
|---|---|---|
| `emp_I` | 32 | Empreinte de la clé publique de la plateforme |
| `Sig_I` | 64 | Signature ECDSA de la transcription |
| preuve | 32 | HMAC de la transcription avec la clé de session |

## La transcription et les clés

```text
tr     = "tr" | id_I | X | n_I | suites | Y | n_R | suite | emp_R
Sig_R  = ECDSA(sk_R, "sfb-aka/tr/R" | H(tr))
Sig_I  = ECDSA(sk_I, "sfb-aka/tr/I" | H(tr))

k      = HKDF-Extract(n_I | n_R, X25519(x, Y))
k_enc  = HKDF-Expand(k, "sfb/enc" | H(tr), 16)
k_mac  = HKDF-Expand(k, "sfb/mac" | H(tr), 32)
preuve = HMAC(k_mac, "fin" | H(tr))
```

Comme les deux signatures portent sur la même transcription, modifier un seul champ en transit (par exemple retirer une suite de la liste) fait échouer la vérification.

## La suite de référence

`0x0001` : X25519, ECDSA P-256, SHA-256, AES-128-GCM. Si la suite retenue n'est pas celle-ci, la session est refusée avec `SUITE_REFUSEE`.
