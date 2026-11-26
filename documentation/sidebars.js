/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
module.exports = {
  principal: [
    'introduction',
    'demarrage-rapide',
    { type: 'category', label: 'Le protocole SFB-AKA', collapsed: false,
      items: ['protocole/vue-ensemble', 'protocole/messages', 'protocole/instructions'] },
    { type: 'category', label: 'Intégration', collapsed: false,
      items: ['integration/partenaire', 'integration/politique'] },
    { type: 'category', label: 'Référence', collapsed: false,
      items: ['reference/api', 'reference/motifs'] },
    { type: 'category', label: 'Sécurité', collapsed: false,
      items: ['securite/proprietes', 'securite/limites'] },
  ],
};
