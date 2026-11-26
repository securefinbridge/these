---
id: introduction
title: Introduction
sidebar_position: 1
slug: /introduction
---

# Introduction

**SecureFinBridge** est une passerelle de médiation placée entre une plateforme de Mobile Money et le système d'une banque (le *core banking*). Elle fait une seule chose : s'assurer que chaque ordre qui passe de l'une à l'autre vient bien du bon partenaire, n'a pas été modifié, n'est pas rejoué, et laisse une trace.

Pour cela, elle s'appuie sur **SFB-AKA**, un protocole d'accord de clé authentifié (*Authenticated Key Agreement*) conçu pendant un travail de recherche à l'IBAM, Université Joseph KI-ZERBO.

:::warning Prototype de démonstration
Ce que vous utilisez en ligne est un prototype. La banque y est simulée par un bouchon ISO 20022, les clés sont des clés de démonstration, et rien ici n'est destiné à la production. La page [Limites](/docs/securite/limites) dit précisément où le prototype s'arrête.
:::

## Les trois acteurs

| Acteur | Rôle | Dans la démonstration |
|---|---|---|
| **Plateforme** | Initie la session et envoie les ordres | La console en ligne joue ce rôle |
| **Passerelle** | Authentifie, vérifie, applique la politique, journalise, transmet | Le logiciel SecureFinBridge |
| **Core banking** | Exécute l'ordre et répond | Un bouchon ISO 20022 (pacs.008 vers pacs.002) |

## Le parcours d'un ordre

1. La plateforme ouvre une session avec la passerelle : **trois messages**, 384 octets.
2. Elle envoie un ordre **pacs.008**, scellé avec la clé de session et numéroté par un compteur.
3. La passerelle vérifie l'intégrité, puis la fraîcheur, puis la politique.
4. Elle inscrit la décision dans le **journal d'audit chaîné**, puis transmet l'ordre à la banque.
5. Elle renvoie le statut et le motif à la plateforme.

## Par où commencer

- Vous voulez voir tourner la passerelle : [Démarrage rapide](/docs/demarrage-rapide).
- Vous voulez comprendre le protocole : [Vue d'ensemble](/docs/protocole/vue-ensemble).
- Vous voulez intégrer un partenaire : [Guide d'intégration](/docs/integration/partenaire).
- Vous voulez savoir ce qui est prouvé : [Propriétés de sécurité](/docs/securite/proprietes).
