---
id: proprietes
title: Propriétés de sécurité
---

# Propriétés de sécurité

Le protocole vise six propriétés, face à un adversaire qui contrôle entièrement le réseau (modèle de Dolev et Yao) : il lit, bloque, modifie, rejoue et injecte des messages, mais ne casse pas la cryptographie. Deux pouvoirs lui sont ajoutés : voler une clé à long terme, et agir sur le canal de signalisation télécom.

| | Propriété | En une phrase | Établie par |
|---|---|---|---|
| P1 | Authentification mutuelle | Chacun parle bien à celui qu'il croit, une seule fois par session | ProVerif et Tamarin |
| P2 | Confidentialité | Compte, montant et éléments d'authentification restent secrets | ProVerif et Tamarin |
| P3 | Intégrité | Toute altération en transit est détectée avant traitement | Tamarin, modèle modulaire |
| P4 | Non-rejeu | Un ordre déjà traité n'est pas validé une seconde fois | Tamarin, modèle modulaire |
| P5 | Non-répudiation | Ni l'émission ni la réception ne peuvent être niées | Tamarin, modèle modulaire |
| P6 | Confidentialité persistante | Voler une clé à long terme ne révèle pas les sessions passées | ProVerif et Tamarin |

## Ce que « prouvé » veut dire ici

- **P1, P2 et P6** sont prouvées par deux outils indépendants, ProVerif 2.05 et Tamarin 1.12.0, pour un nombre non borné de sessions.
- **P3, P4 et P5** sont prouvées par Tamarin dans un **modèle modulaire** de la couche d'instructions. Dans la théorie complète, la recherche de preuve ne termine pas dans le temps imparti. L'assemblage des deux modèles est justifié par raisonnement, pas par la machine.

Une preuve formelle porte sur le **modèle** du protocole, pas sur le code. C'est pourquoi l'implémentation est en plus soumise à un banc d'attaque.

## Sur le banc d'essai

Les scénarios d'attaque ont été rejoués contre la passerelle. Dans la campagne principale, les 70 tentatives ont toutes été neutralisées. Deux scénarios sont traités à part : la composition par clé partagée, et la clé compromise. Pour une clé compromise avant la session, l'attaquant détient une identité valide : l'attaque n'est pas empêchée, elle est confinée par les règles de vélocité.

## Rejouer les preuves

```bash
git clone https://github.com/securefinbridge/these.git
cd these
./verifier.sh tout
```

Prérequis : ProVerif 2.05, Tamarin Prover 1.12 et Maude 3.5. Les journaux de vérification sont dans le dossier `resultats`.
