# SecureFinBridge — artefacts de recherche

Sécurité formellement vérifiée de l'interconnexion **Mobile Money / Core Banking** dans l'espace UEMOA.

Ce dépôt contient les artefacts du mémoire de Master 2 *« SecureFinBridge : framework de sécurité
formellement vérifié pour l'interconnexion Mobile Money / Core Banking dans l'espace UEMOA »*
(Emmanuel Gaspard Koami SEHOU, IBAM / Université Joseph KI-ZERBO, sous la direction du
Pr Tiguiane YELEMOU), socle du projet de thèse du même nom.

> La version citée par le mémoire est l'étiquette **`v1.0-memoire-m2`**.
> La branche principale évolue avec les travaux de thèse.

## Contenu

| Répertoire | Contenu |
|---|---|
| `code/*.pv` | Modèles **ProVerif** : protocole SFB-AKA, variantes dégradées, patrons d'interconnexion déployés (sans registre, REST à jeton porteur, USSD), attaque par composition et contre-mesure |
| `code/*.spthy` | Théories **Tamarin** : SFB-AKA complet, version 1 (attaque par réflexion), couche d'instructions en preuve modulaire, patrons sans registre et REST |
| `code/banc/` | Banc d'essai : passerelle SFB-AKA et référence TLS 1.3 (Java 21, bibliothèque standard uniquement), moteur de politique, client de mesure, banc d'attaque, scripts de campagne, analyse statistique |
| `code/banc/mesures/` | Mesures brutes (JSON) de la campagne du 9–10 novembre 2026 |
| `code/` (autres) | Bouchon *core banking* ISO 20022, journal d'audit chaîné, validateur d'instructions, profils réseau `tc netem`, manifestes Kubernetes |
| `resultats/` | Journaux de vérification ProVerif et Tamarin, tableaux et synthèse de la campagne |
| `verifier.sh` | Rejoue toutes les vérifications formelles |

## Rejouer les résultats

### Vérification formelle

Prérequis : ProVerif 2.05, Tamarin Prover 1.12 et Maude 3.5.

```bash
./verifier.sh tout        # ProVerif puis Tamarin ; journaux dans resultats/
./verifier.sh             # ProVerif seul
./verifier.sh tamarin     # Tamarin seul (300 s par lemme)
```

### Banc d'essai

Prérequis : Java 21 et Python 3.10 ou plus ; Linux avec `tc netem` pour les profils réseau.

```bash
cd code/banc
./campagne.sh rapide      # vérification du banc avec Docker (quelques minutes)
./campagne.sh             # campagne complète avec Docker
./campagne_vm.sh rapide   # même banc sans Docker (espace de noms réseau Linux)
python3 analyse.py mesures ../../resultats/banc   # tableaux à partir des mesures brutes
```

## Principaux résultats

- **Vérification formelle** : authentification mutuelle injective, confidentialité et confidentialité
  persistante de SFB-AKA prouvées par ProVerif et Tamarin sur un nombre non borné de sessions ;
  intégrité, non-rejeu et ancrage du journal prouvés par Tamarin dans un modèle modulaire ;
  attaque par réflexion découverte par Tamarin et corrigée par des étiquettes de rôle.
- **Patrons déployés** : violations établies dans les quatre patrons modélisés (ProVerif),
  confirmées par Tamarin pour les patrons sans registre et REST.
- **Performances** (1 500 sessions, profils LAN à GPRS) : surcoût cryptographique de 0,98 % de la
  latence de bout en bout sur 3G ; latence inférieure à TLS 1.3 sur tous les profils ;
  échange initial de 384 octets ; environ 1 700 ordres/s avec une session par ordre.
- **Banc d'attaque** : six scénarios exécutés ; attaques neutralisées ou, pour une clé compromise,
  confinées par les règles de vélocité.

## Citer ce travail

```
SEHOU, E. G. K. (2026). SecureFinBridge : framework de sécurité formellement vérifié pour
l'interconnexion Mobile Money / Core Banking dans l'espace UEMOA. Mémoire de Master 2,
IBAM / Université Joseph KI-ZERBO, Ouagadougou.
```

## Licence

Code et modèles sous licence MIT (voir `LICENSE`).
Aucune clé n'est versionnée : les clés du banc sont générées à chaque lancement.
