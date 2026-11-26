---
id: vue-ensemble
title: Vue d'ensemble
---

# Le protocole SFB-AKA

SFB-AKA signifie **SecureFinBridge Authenticated Key Agreement** : un accord de clé authentifié. À la fin de l'échange, les deux parties ont vérifié l'identité l'une de l'autre et partagent une clé de session que personne d'autre ne connaît.

## L'idée en quatre étapes

1. **Avant tout échange**, chaque partenaire possède une paire de clés à long terme (ECDSA P-256). La clé publique est inscrite dans le **registre des partenaires** tenu par la passerelle.
2. **À l'ouverture**, chaque partie tire une valeur éphémère (X25519) et un aléa de 32 octets, valables pour cette session seulement.
3. **Chaque partie signe** la *transcription*, c'est-à-dire l'empreinte de tout ce qui vient d'être échangé.
4. **L'autre vérifie** la signature avec la clé publique trouvée dans le registre.

Une signature valide ne suffit pas : la clé doit être **inscrite au registre**. C'est la leçon de l'analyse des schémas existants.

## Ce qui est utilisé

| Besoin | Mécanisme |
|---|---|
| Accord de clé | X25519, parts éphémères |
| Signature | ECDSA P-256 |
| Empreinte | SHA-256 |
| Dérivation des clés | HKDF |
| Chiffrement des ordres | AES-128-GCM |
| Authentification des ordres | HMAC-SHA-256 |

Toute la cryptographie vient du JDK 21. Le cœur du protocole n'a aucune dépendance externe.

## Deux précautions qui comptent

**Les étiquettes de rôle.** La passerelle signe avec l'étiquette `sfb-aka/tr/R`, la plateforme avec `sfb-aka/tr/I`. Sans cette distinction, une signature produite par un rôle pouvait être renvoyée à son auteur : c'est l'**attaque par réflexion**, trouvée par l'outil Tamarin pendant la conception, puis corrigée.

**La séparation des clés.** La clé de chiffrement et la clé d'authentification sont dérivées séparément, avec des étiquettes distinctes (`sfb/enc`, `sfb/mac`). Une clé ne sert jamais à deux usages.

## Pourquoi pas simplement TLS 1.3

TLS protège un canal entre deux points. Il ne dit rien du non-rejeu des ordres, de la non-répudiation ni de la réutilisation de clés entre canaux. SFB-AKA est conçu pour ce contexte précis, et son ouverture de session pèse 384 octets contre 2 618 pour TLS 1.3 à authentification mutuelle dans nos mesures. Il est **mieux adapté** à ce cas d'usage, pas « meilleur » dans l'absolu.

Suite : [les trois messages, champ par champ](/docs/protocole/messages).
