# Cauris : documentation développeur de SecureFinBridge

Site Docusaurus, en ligne sur https://docs.securefinbridge.emmanuelsehou.com

```bash
npm install
npm start        # http://localhost:3000, rechargement à chaud
npm run build    # site statique dans build/
```

- `docs/` : les pages, en Markdown.
- `src/pages/index.js` : la page d'accueil.
- `src/css/custom.css` : le thème (blanc, vert profond, or).
- `docusaurus.config.js` : menus, adresse de la console et du dépôt.

## Déploiement

Un push sur la branche `prod` qui touche `documentation/` lance `.github/workflows/documentation.yml` :
construction de l'image `ghcr.io/securefinbridge/cauris-docs`, puis déploiement dans l'espace de noms
`securefinbridge` du cluster. Le secret `KUBECONFIG_B64` du dépôt est réutilisé.

À faire une seule fois : créer l'enregistrement DNS `docs.securefinbridge` vers l'adresse du cluster,
et rendre public le paquet `cauris-docs` dans l'organisation GitHub après le premier build.
