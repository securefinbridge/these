# Déploiement du démonstrateur SecureFinBridge

**https://securefinbridge.emmanuelsehou.com** — prototype de démonstration. Banque simulée par un
bouchon ISO 20022, clés de démonstration régénérées à chaque démarrage, non destiné à la production.

## Architecture

```
Internet ──https──▶ Traefik (k3s, 51.77.201.18) ──▶ Service sfb-demo :80 ──▶ pod sfb-demo :8080
                    certificat Let's Encrypt,                               │ console HTTP (Spring Boot)
                    redirection https,                                      ├─▶ passerelle  127.0.0.1:7000 (SFB-AKA), :7443 (TLS 1.3)
                    limitation de débit                                     └─▶ bouchon     127.0.0.1:8443 (ISO 20022)
```

- **Un pod, un conteneur, trois processus.** La console (`sfb.demo`) génère les clés, lance le bouchon
  Python et `sfb.Passerelle` (code du banc, inchangé), puis joue le rôle de la plateforme Mobile Money.
- **Seul le port 8080 sort du pod.** Les ports 7000, 7443 et 8443 ne sont exposés ni sur Internet ni
  aux autres pods ; le pod n'a aucune sortie réseau (`30-reseau.yaml`).
- **Rien n'est persistant.** Clés et journal vivent dans un `emptyDir` : un redémarrage repart de zéro.
- **Espace de noms `securefinbridge`**, partagé avec la documentation, plafonné par un quota
  (2 cœurs, 2 Gi, 5 pods) pour ne jamais gêner la production du même cluster.

| Fichier | Contenu |
|---|---|
| `k8s/00-namespace.yaml` | espace de noms, `ResourceQuota`, `LimitRange` |
| `k8s/10-passerelle.yaml` | `Deployment` et `Service` `sfb-demo` |
| `k8s/20-ingress.yaml` | `Ingress`, redirection https, limitation de débit |
| `k8s/30-reseau.yaml` | `NetworkPolicy` (entrée 8080 seulement, aucune sortie) |
| `../code/banc/Dockerfile.demo` | image (contexte : `code/`) |
| `../.github/workflows/deploiement.yml` | construction de l'image, puis déploiement |

## Secrets à créer

Aucun secret n'est dans le dépôt, qui est public.

- **`KUBECONFIG_B64`** (GitHub > Settings > Secrets and variables > Actions) : kubeconfig du cluster en
  base64, sur une ligne. C'est la clé d'administration du cluster : ne jamais l'afficher ni la partager.

  ```bash
  gh secret set KUBECONFIG_B64 --repo securefinbridge/these     # colle la valeur, puis Ctrl-D
  ```

  Le certificat client de k3s ne vaut qu'**un an**. Le pipeline affiche sa date d'expiration et avertit
  30 jours avant. Pour le renouveler, sur le VPS :

  ```bash
  sudo sed 's/127.0.0.1/51.77.201.18/' /etc/rancher/k3s/k3s.yaml > /tmp/kc.yaml
  kubectl --kubeconfig /tmp/kc.yaml get namespace securefinbridge
  base64 -w0 /tmp/kc.yaml; echo; rm /tmp/kc.yaml
  ```

- **Paquet GHCR `passerelle-demo`** : à rendre **public** une fois (page du paquet > Package settings >
  Change visibility). Tant qu'il est privé, le pipeline crée un secret de tirage provisoire
  (`ghcr-tirage`) dont le jeton expire à la fin du pipeline ; il le supprime dès que le paquet est public.

## Redéployer

Le travail se fait sur `main` ; le déploiement part d'un push sur `prod`.

```bash
git push origin main:prod            # si code/banc/**, code/bouchon_cbs.py ou deploiement/** ont changé
```

ou, sans changement de code : GitHub > Actions > « Démonstrateur (passerelle SFB-AKA) » > Run workflow.
Le pipeline construit l'image `ghcr.io/securefinbridge/passerelle-demo:<commit>`, applique les
manifestes, fixe l'image, attend le pod, puis vérifie `/sante` en ligne. En cas d'échec, il affiche
les pods, les événements et les 80 dernières lignes de logs. Comptez moins d'une minute
d'interruption par déploiement (stratégie `Recreate`) : ne déployez pas pendant la soutenance.

À la main, depuis un poste qui a accès au cluster (si GitHub ne joint pas le port 6443) :

```bash
kubectl apply -f deploiement/k8s/
kubectl set image deployment/sfb-demo sfb-demo=ghcr.io/securefinbridge/passerelle-demo:<commit> -n securefinbridge
kubectl rollout status deployment/sfb-demo -n securefinbridge --timeout=300s
curl -s https://securefinbridge.emmanuelsehou.com/sante
```

## Diagnostiquer

```bash
kubectl get deploy,pods,ingress,certificate -n securefinbridge -o wide
kubectl logs deploy/sfb-demo -n securefinbridge --tail=80
kubectl get events -n securefinbridge --sort-by=.lastTimestamp | tail -n 25
```

`/sante` répond 503 dès que la passerelle ou le bouchon ne répond plus ; la sonde de vivacité
redémarre alors le conteneur, avec de nouvelles clés et un journal vide.

## Limites connues

- La limitation de débit (120 requêtes par minute, rafales de 60) n'est « par adresse » que si Traefik
  voit l'adresse réelle du client, ce qui dépend du réglage `externalTrafficPolicy` de son Service.
- Toute la console est le partenaire `plateforme` : dix ordres de 2 000 000 FCFA en une minute
  saturent la fenêtre de vélocité, et les scénarios `rejeu` et `alteration` répondent alors
  « non conforme » pendant une minute (leurs ordres préalables sont refusés).
- Le journal et la mémoire du bouchon grossissent avec l'usage ; le volume est borné à 128 Mi et un
  redémarrage remet tout à zéro.

## Tout retirer

```bash
kubectl delete namespace securefinbridge
```

Cette commande supprime aussi la documentation, qui vit dans le même espace de noms. Pour ne retirer
que le démonstrateur : `kubectl delete -f deploiement/k8s/10-passerelle.yaml -f deploiement/k8s/20-ingress.yaml -f deploiement/k8s/30-reseau.yaml`.
Supprimez ensuite, au besoin, le paquet GHCR, le secret `KUBECONFIG_B64` et l'enregistrement DNS.
