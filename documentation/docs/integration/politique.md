---
id: politique
title: Règles de politique
---

# Règles de politique

Après les contrôles d'intégrité et de fraîcheur, la passerelle applique des règles métier lues dans un fichier `politique.properties`. Une équipe de conformité peut les modifier sans toucher au code.

```properties
# Montant maximal d'un ordre (FCFA)
plafond_par_ordre = 2000000

# Fenêtre glissante des règles de vélocité (secondes)
fenetre_secondes = 60

# Nombre maximal d'ordres acceptés par partenaire dans la fenêtre
ordres_max_par_fenetre = 1200

# Montant cumulé maximal accepté par partenaire dans la fenêtre (FCFA)
montant_cumule_max_par_fenetre = 20000000
```

| Règle | Effet si elle est dépassée |
|---|---|
| `plafond_par_ordre` | `POLITIQUE_REFUSEE` |
| `ordres_max_par_fenetre` | `VELOCITE_DEPASSEE` |
| `montant_cumule_max_par_fenetre` | `VELOCITE_DEPASSEE` |

Les règles de vélocité s'appliquent **par partenaire**.

## À quoi servent les règles de vélocité

Elles limitent les dégâts si la clé d'un partenaire est compromise. L'attaquant détient alors une identité valide, et la cryptographie ne peut plus rien contre lui : les plafonds confinent ce qu'il peut faire avant d'être détecté.

Les valeurs en vigueur sur la démonstration se lisent avec :

```bash
curl https://securefinbridge.emmanuelsehou.com/api/v1/etat
```
