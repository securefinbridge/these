// Cauris : documentation développeur de SecureFinBridge.
const { themes: prism } = require('prism-react-renderer');

const CONSOLE = 'https://securefinbridge.emmanuelsehou.com';
const DEPOT = 'https://github.com/securefinbridge/these';

/** @type {import('@docusaurus/types').Config} */
module.exports = {
  title: 'Cauris',
  tagline: 'La documentation développeur de SecureFinBridge',
  favicon: 'img/favicon.png',
  url: 'https://docs.securefinbridge.emmanuelsehou.com',
  baseUrl: '/',
  organizationName: 'securefinbridge',
  projectName: 'these',
  onBrokenLinks: 'throw',
  i18n: { defaultLocale: 'fr', locales: ['fr'] },
  customFields: { console: CONSOLE, depot: DEPOT },

  presets: [
    ['classic', {
      docs: {
        sidebarPath: require.resolve('./sidebars.js'),
        routeBasePath: 'docs',
        editUrl: DEPOT + '/edit/main/documentation/',
      },
      blog: false,
      theme: { customCss: require.resolve('./src/css/custom.css') },
    }],
  ],

  themeConfig: {
    colorMode: { defaultMode: 'light', respectPrefersColorScheme: false },
    navbar: {
      title: 'Cauris',
      logo: { alt: 'Cauris', src: 'img/logo.png' },
      items: [
        { to: '/docs/demarrage-rapide', label: 'Démarrage rapide', position: 'left' },
        { to: '/docs/protocole/vue-ensemble', label: 'Protocole', position: 'left' },
        { to: '/docs/reference/api', label: 'Référence', position: 'left' },
        { to: '/docs/securite/proprietes', label: 'Sécurité', position: 'left' },
        { href: CONSOLE, label: 'Console en ligne', position: 'right' },
        { href: DEPOT, label: 'GitHub', position: 'right' },
      ],
    },
    footer: {
      style: 'light',
      links: [
        { title: 'Documentation', items: [
          { label: 'Introduction', to: '/docs/introduction' },
          { label: 'Démarrage rapide', to: '/docs/demarrage-rapide' },
          { label: 'Les trois messages', to: '/docs/protocole/messages' },
        ]},
        { title: 'Référence', items: [
          { label: 'API de la console', to: '/docs/reference/api' },
          { label: 'Motifs de rejet', to: '/docs/reference/motifs' },
          { label: 'Limites du prototype', to: '/docs/securite/limites' },
        ]},
        { title: 'Projet', items: [
          { label: 'Console en ligne', href: CONSOLE },
          { label: 'Code et modèles formels', href: DEPOT },
        ]},
      ],
      copyright: 'SecureFinBridge. Travail de recherche, IBAM / Université Joseph KI-ZERBO. Prototype de démonstration : banque simulée, clés de démonstration, non destiné à la production. Code et modèles sous licence MIT.',
    },
    prism: { theme: prism.github, darkTheme: prism.dracula, additionalLanguages: ['java', 'bash', 'json', 'properties'] },
    docs: { sidebar: { hideable: true } },
  },
};
