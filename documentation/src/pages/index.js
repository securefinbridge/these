import React from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import CodeBlock from '@theme/CodeBlock';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import s from './index.module.css';

const messages = [
  { n: 'M1', sens: 'Plateforme vers passerelle', octets: 88, contenu: 'Identité, part éphémère, aléa, suites proposées' },
  { n: 'M2', sens: 'Passerelle vers plateforme', octets: 162, contenu: 'Part éphémère, aléa, suite retenue, empreinte, signature' },
  { n: 'M3', sens: 'Plateforme vers passerelle', octets: 128, contenu: 'Empreinte, signature, preuve de possession de la clé' },
];

const garanties = [
  { t: 'Authentification mutuelle', d: 'Chaque partie signe la transcription de l\'échange avec une clé inscrite au registre des partenaires.' },
  { t: 'Confidentialité persistante', d: 'Les clés de session viennent de parts éphémères. Le vol ultérieur d\'une clé à long terme ne révèle pas les sessions passées.' },
  { t: 'Intégrité des ordres', d: 'Chaque instruction est scellée et authentifiée. Une altération en transit est détectée avant tout traitement.' },
  { t: 'Non-rejeu', d: 'Un compteur strictement croissant par session. Un ordre déjà traité n\'est jamais exécuté une seconde fois.' },
  { t: 'Non-répudiation', d: 'Chaque décision est inscrite dans un journal d\'audit chaîné par empreinte, avant la réponse.' },
  { t: 'Politique lisible', d: 'Plafond par ordre et règles de vélocité dans un simple fichier de propriétés, sans toucher au code.' },
];

const etapes = [
  { n: '1', t: 'Ouvrir une session', d: 'Trois messages, 384 octets, et les deux parties partagent une clé de session.' },
  { n: '2', t: 'Envoyer un ordre', d: 'Un message ISO 20022 pacs.008, scellé avec la clé de session et numéroté.' },
  { n: '3', t: 'Lire la décision', d: 'Accepté ou rejeté, avec un motif explicite et une trace dans le journal.' },
];

const exemple = `# 1. Ouvrir une vraie session SFB-AKA
curl -X POST $CONSOLE/api/v1/sessions

# 2. Envoyer un ordre de 25 000 FCFA
curl -X POST $CONSOLE/api/v1/ordres \\
  -H "Content-Type: application/json" \\
  -d '{"montant": 25000, "compteDebiteur": "22670000001", "compteCrediteur": "BF0010001"}'

# 3. Rejouer une attaque et constater le rejet
curl -X POST $CONSOLE/api/v1/attaques/rejeu`;

export default function Accueil() {
  const { siteConfig } = useDocusaurusContext();
  const { console: urlConsole, depot } = siteConfig.customFields;
  return (
    <Layout title="Documentation développeur" description="Cauris, la documentation développeur de SecureFinBridge : protocole SFB-AKA, passerelle Mobile Money / Core Banking, guide d'intégration.">
      <header className={s.hero}>
        <div className={s.contenu}>
          <p className={s.surtitre}>Cauris, la documentation de SecureFinBridge</p>
          <h1 className={s.titre}>Relier Mobile Money et banque,<br />avec une sécurité prouvée.</h1>
          <p className={s.chapo}>
            SecureFinBridge est une passerelle de médiation entre une plateforme de Mobile Money et un système bancaire.
            Son protocole, SFB-AKA, ouvre une session authentifiée en trois messages, et ses propriétés de sécurité
            sont vérifiées par deux outils de preuve formelle.
          </p>
          <div className={s.actions}>
            <Link className={s.bouton} to="/docs/demarrage-rapide">Démarrer en cinq minutes</Link>
            <Link className={s.boutonClair} href={urlConsole}>Ouvrir la console en ligne</Link>
          </div>
          <dl className={s.chiffres}>
            <div><dt>3</dt><dd>messages pour ouvrir une session</dd></div>
            <div><dt>384</dt><dd>octets échangés à l'ouverture</dd></div>
            <div><dt>6</dt><dd>propriétés de sécurité visées</dd></div>
            <div><dt>2</dt><dd>outils de preuve, ProVerif et Tamarin</dd></div>
          </dl>
        </div>
      </header>

      <main>
        <section className={s.section}>
          <div className={s.contenu}>
            <h2 className={s.h2}>Comment ça marche</h2>
            <p className={s.intro}>La plateforme parle à la passerelle. La passerelle vérifie, applique la politique, journalise, puis transmet à la banque.</p>
            <div className={s.etapes}>
              {etapes.map((e) => (
                <div key={e.n} className={s.etape}>
                  <span className={s.num}>{e.n}</span>
                  <h3>{e.t}</h3>
                  <p>{e.d}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className={`${s.section} ${s.doux}`}>
          <div className={s.contenu}>
            <h2 className={s.h2}>Les trois messages de SFB-AKA</h2>
            <p className={s.intro}>Chaque message est précédé de sa longueur sur deux octets. Total sur le fil : 384 octets.</p>
            <div className={s.messages}>
              {messages.map((m) => (
                <div key={m.n} className={s.message}>
                  <span className={s.nom}>{m.n}</span>
                  <div className={s.corps}>
                    <strong>{m.sens}</strong>
                    <span>{m.contenu}</span>
                  </div>
                  <span className={s.octets}>{m.octets} octets</span>
                </div>
              ))}
            </div>
            <p className={s.lien}><Link to="/docs/protocole/messages">Lire le détail champ par champ</Link></p>
          </div>
        </section>

        <section className={s.section}>
          <div className={s.contenu}>
            <h2 className={s.h2}>Ce que la passerelle garantit</h2>
            <div className={s.grille}>
              {garanties.map((g) => (
                <div key={g.t} className={s.carte}>
                  <h3>{g.t}</h3>
                  <p>{g.d}</p>
                </div>
              ))}
            </div>
            <p className={s.lien}><Link to="/docs/securite/proprietes">Voir ce qui est prouvé, et par quel outil</Link></p>
          </div>
        </section>

        <section className={`${s.section} ${s.doux}`}>
          <div className={`${s.contenu} ${s.deux}`}>
            <div>
              <h2 className={s.h2}>Essayez sans rien installer</h2>
              <p className={s.intro}>
                La console en ligne tient le rôle de la plateforme Mobile Money. Elle ouvre de vraies sessions
                avec la passerelle, envoie des ordres et rejoue les attaques du banc d'essai.
              </p>
              <p className={s.note}>
                Un partenaire réel s'intègre directement en TCP, avec sa propre clé inscrite au registre.
                Le guide d'intégration décrit ce parcours.
              </p>
              <Link className={s.bouton} to="/docs/integration/partenaire">Guide d'intégration</Link>
            </div>
            <CodeBlock language="bash" title="Dans un terminal">{exemple.replaceAll('$CONSOLE', urlConsole)}</CodeBlock>
          </div>
        </section>

        <section className={s.section}>
          <div className={`${s.contenu} ${s.fin}`}>
            <h2 className={s.h2}>Un travail de recherche, ouvert</h2>
            <p className={s.intro}>
              Les modèles formels, le code de la passerelle, le banc d'essai et les mesures sont publics.
              Ce site décrit un prototype de démonstration : la banque y est simulée et les limites sont documentées.
            </p>
            <div className={s.actions}>
              <Link className={s.bouton} href={depot}>Voir le dépôt GitHub</Link>
              <Link className={s.boutonClair} to="/docs/securite/limites">Lire les limites du prototype</Link>
            </div>
          </div>
        </section>
      </main>
    </Layout>
  );
}
