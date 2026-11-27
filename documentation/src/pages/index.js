import React from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import CodeBlock from '@theme/CodeBlock';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import s from './index.module.css';

const messages = [
  { n: 'M1', sens: 'Plateforme vers passerelle', octets: 88, contenu: 'Identité, part éphémère, aléa, suites proposées' },
  { n: 'M2', sens: 'Passerelle vers plateforme', octets: 162, contenu: 'Part éphémère, aléa, suite retenue, empreinte, signature', retour: true },
  { n: 'M3', sens: 'Plateforme vers passerelle', octets: 128, contenu: 'Empreinte, signature, preuve de possession de la clé' },
];

const chiffres = [
  { v: '3', d: 'messages pour ouvrir une session' },
  { v: '384', d: 'octets échangés à l\'ouverture' },
  { v: '6', d: 'propriétés de sécurité visées' },
  { v: '2', d: 'outils de preuve, ProVerif et Tamarin' },
];

const etapes = [
  { n: '1', t: 'Ouvrir une session', d: 'Trois messages, 384 octets, et les deux parties partagent une clé de session.' },
  { n: '2', t: 'Envoyer un ordre', d: 'Un message ISO 20022 pacs.008, scellé avec la clé de session et numéroté.' },
  { n: '3', t: 'Lire la décision', d: 'Accepté ou rejeté, avec un motif explicite et une trace dans le journal.' },
];

const trait = { fill: 'none', stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round', strokeLinejoin: 'round' };
const garanties = [
  { t: 'Authentification mutuelle', d: 'Chaque partie signe la transcription de l\'échange avec une clé inscrite au registre des partenaires.',
    i: <><circle cx="8" cy="9" r="3" /><circle cx="16" cy="9" r="3" /><path d="M3 20c0-3 2-5 5-5s5 2 5 5" /><path d="M13 16c.8-.7 1.8-1 3-1 3 0 5 2 5 5" /></> },
  { t: 'Confidentialité persistante', d: 'Les clés de session viennent de parts éphémères. Le vol ultérieur d\'une clé à long terme ne révèle pas les sessions passées.',
    i: <><rect x="5" y="11" width="14" height="9" rx="2.5" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></> },
  { t: 'Intégrité des ordres', d: 'Chaque instruction est scellée et authentifiée. Une altération en transit est détectée avant tout traitement.',
    i: <><path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z" /><path d="M9 12l2.2 2.2L15 10" /></> },
  { t: 'Non-rejeu', d: 'Un compteur strictement croissant par session. Un ordre déjà traité n\'est jamais exécuté une seconde fois.',
    i: <><path d="M4 12a8 8 0 1 0 2.6-5.9" /><path d="M4 4v5h5" /></> },
  { t: 'Non-répudiation', d: 'Chaque décision est inscrite dans un journal d\'audit chaîné par empreinte, avant la réponse.',
    i: <><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" /><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" /></> },
  { t: 'Politique lisible', d: 'Plafond par ordre et règles de vélocité dans un simple fichier de propriétés, sans toucher au code.',
    i: <><path d="M5 7h9" /><path d="M18 7h1" /><circle cx="16" cy="7" r="2" /><path d="M5 17h1" /><path d="M10 17h9" /><circle cx="8" cy="17" r="2" /></> },
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
  const plusGrand = Math.max(...messages.map((m) => m.octets));
  return (
    <Layout title="Documentation développeur" description="La documentation développeur de SecureFinBridge : protocole SFB-AKA, passerelle Mobile Money / Core Banking, guide d'intégration.">
      <header className={s.hero}>
        <div className={s.contenu}>
          <p className={s.etiquette}><i />Documentation développeur de SecureFinBridge</p>
          <h1 className={s.titre}>Relier Mobile Money et banque, <span>avec une sécurité prouvée.</span></h1>
          <p className={s.chapo}>
            Une passerelle de médiation entre une plateforme de Mobile Money et un système bancaire.
            Son protocole, SFB-AKA, ouvre une session authentifiée en trois messages, et ses propriétés
            sont vérifiées par deux outils de preuve formelle.
          </p>
          <div className={s.actions}>
            <Link className={s.bouton} to="/docs/demarrage-rapide">Démarrer en cinq minutes</Link>
            <Link className={s.boutonClair} href={urlConsole}>Ouvrir la console en ligne</Link>
          </div>
        </div>

        <div className={s.contenu}>
          <div className={s.scene}>
            <div className={s.voies}><span>Plateforme Mobile Money</span><span>Passerelle</span></div>
            {messages.map((m, i) => (
              <div key={m.n} className={`${s.message} ${m.retour ? s.retour : ''}`} style={{ animationDelay: `${0.25 + i * 0.3}s` }}>
                <span className={s.nom}>{m.n}</span>
                <div className={s.corps}>
                  <strong>{m.sens}</strong>
                  <span>{m.contenu}</span>
                  <div className={s.piste} style={{ width: `${Math.round((100 * m.octets) / plusGrand)}%` }}>
                    <i style={{ animationDelay: `${0.45 + i * 0.3}s` }} />
                  </div>
                </div>
                <span className={s.octets}>{m.octets}<small>octets</small></span>
              </div>
            ))}
            <p className={s.legende}>
              Chaque message est précédé de sa longueur sur deux octets : 384 octets sur le fil.{' '}
              <Link to="/docs/protocole/messages">Lire le détail champ par champ</Link>
            </p>
          </div>
          <dl className={s.chiffres}>
            {chiffres.map((c) => (<div key={c.d}><dt>{c.v}</dt><dd>{c.d}</dd></div>))}
          </dl>
        </div>
      </header>

      <main>
        <section className={s.section}>
          <div className={s.contenu}>
            <h2 className={s.h2}>Comment ça marche</h2>
            <p className={s.intro}>La plateforme parle à la passerelle. La passerelle vérifie, applique la politique, journalise, puis transmet à la banque.</p>
            <div className={s.trois}>
              {etapes.map((e) => (
                <div key={e.n} className={s.carte}>
                  <span className={s.num}>{e.n}</span>
                  <h3>{e.t}</h3>
                  <p>{e.d}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className={s.section}>
          <div className={s.contenu}>
            <h2 className={s.h2}>Ce que la passerelle garantit</h2>
            <p className={s.intro}>Six propriétés visées, chacune reliée à ce qui la prouve ou la mesure.</p>
            <div className={s.trois}>
              {garanties.map((g) => (
                <div key={g.t} className={s.carte}>
                  <span className={s.icone}><svg viewBox="0 0 24 24" aria-hidden="true" {...trait}>{g.i}</svg></span>
                  <h3>{g.t}</h3>
                  <p>{g.d}</p>
                </div>
              ))}
            </div>
            <p className={s.lien}><Link to="/docs/securite/proprietes">Voir ce qui est prouvé, et par quel outil</Link></p>
          </div>
        </section>

        <section className={s.section}>
          <div className={s.contenu}>
            <div className={`${s.panneau} ${s.deux}`}>
              <div>
                <h2 className={s.h2}>Essayez sans rien installer</h2>
                <p>
                  La console en ligne tient le rôle de la plateforme Mobile Money. Elle ouvre de vraies sessions
                  avec la passerelle, envoie des ordres et rejoue les attaques du banc d'essai.
                </p>
                <p className={s.discret}>
                  Un partenaire réel s'intègre directement en TCP, avec sa propre clé inscrite au registre.
                  Le guide d'intégration décrit ce parcours.
                </p>
                <div className={s.actions}>
                  <Link className={s.boutonOr} href={urlConsole}>Ouvrir la console</Link>
                  <Link className={s.boutonNuit} to="/docs/integration/partenaire">Guide d'intégration</Link>
                </div>
              </div>
              <CodeBlock language="bash" title="Dans un terminal">{exemple.replaceAll('$CONSOLE', urlConsole)}</CodeBlock>
            </div>
          </div>
        </section>

        <section className={`${s.section} ${s.derniere}`}>
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
