---
id: limites
title: Limites du prototype
---

# Limites du prototype

Cette page dit où le prototype s'arrête. Elle fait partie de la documentation parce qu'un outil de sécurité qui ne dit pas ses limites n'est pas digne de confiance.

## Ce que la démonstration en ligne n'est pas

- **Pas une banque.** Le core banking est un bouchon ISO 20022 qui ne tient aucun compte. Aucun argent ne circule.
- **Pas de vraies clés.** Les clés sont générées au démarrage et jetables. La console détient la clé de la plateforme pour pouvoir jouer son rôle.
- **Pas de production.** Aucune garantie de disponibilité. Le service peut être redémarré, et le journal repart alors de zéro.
- **Pas d'intégration réelle en ligne.** Le port TCP de la passerelle n'est pas ouvert sur Internet.

## Ce qui reste à faire, côté recherche

| Sujet | État |
|---|---|
| Composition des preuves P3 à P5 | Non mécanisée : modèle modulaire, assemblage par raisonnement |
| Module matériel de clés (HSM) | Absent : les clés sont dans des fichiers |
| Validation chez un participant réel | Non faite : banc émulé uniquement |
| Couche radio et signal | Non modélisées : le réseau est émulé au niveau du lien |
| Consommation d'énergie | Non mesurée |
| Disponibilité | Hors des six propriétés visées |

## Ce que les mesures disent, et ne disent pas

Les mesures viennent d'un banc émulé (profils LAN, 3G, 3G dégradé, EDGE, GPRS), pas d'un réseau d'opérateur. Sur le profil 3G, la cible de 500 ms pour l'ouverture de session n'est pas atteinte : 632,9 ms en médiane, contre 680,2 ms pour TLS 1.3 dans les mêmes conditions. Le délai vient du réseau : la cryptographie représente 0,98 % de la latence de bout en bout.

## Signaler un problème

Les anomalies, de sécurité ou non, se signalent par une *issue* sur le [dépôt GitHub](https://github.com/securefinbridge/these).
