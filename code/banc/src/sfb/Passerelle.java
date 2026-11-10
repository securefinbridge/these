package sfb;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.regex.*;
import javax.net.ssl.*;

/**
 * Passerelle de médiation SecureFinBridge (banc d'essai).
 *
 *  port 7000 : SFB-AKA, puis instructions scellées, transmises au bouchon core banking ;
 *  port 7443 : référence TLS 1.3 à authentification mutuelle (certificats ECDSA P-256,
 *              chaîne de 2), puis instruction en clair dans le canal TLS, transmise au même bouchon.
 *
 * Réponse à chaque instruction : statut (1 octet : 0 accepté, 1 rejeté) | motif (1 octet)
 * | temps CPU serveur de la session (8 octets, ns) : il sert à la mesure du surcoût.
 */
public final class Passerelle {
    static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();
    static final boolean DEBUG = System.getenv("SFB_DEBUG") != null;
    static Politique regles;                                          // moteur de politique (politique.properties)

    static Identites.Identite moi;
    static Identites.Registre registre;
    static boolean etiquettesRole;
    static HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static URI cbs;
    static JournalChaine journal;

    public static void main(String[] a) throws Exception {
        Path cles = Path.of(env("SFB_CLES", "/cles"));
        moi = Identites.charger(cles, env("SFB_IDENTITE", "passerelle"));
        registre = new Identites.Registre(cles, env("SFB_REGISTRE", "plateforme,passerelle").split(","));
        etiquettesRole = !"false".equals(env("SFB_ETIQUETTES_ROLE", "true"));
        cbs = URI.create(env("SFB_CBS_URL", "http://127.0.0.1:8443/pacs008"));
        journal = new JournalChaine(Path.of(env("SFB_JOURNAL", "/tmp/journal_passerelle.jsonl")));
        regles = Politique.charger(Path.of(env("SFB_POLITIQUE", "politique.properties")));
        System.out.printf("politique : plafond %d, fenêtre %d s, %d ordres, cumul %d%n",
                regles.plafond, regles.fenetreMs / 1000, regles.ordresMax, regles.cumulMax);

        var executeur = Executors.newCachedThreadPool();          // fils système : temps CPU par fil mesurable
        ServerSocket sfb = new ServerSocket(Integer.parseInt(env("SFB_PORT", "7000")), 256);
        SSLServerSocket tls = serveurTls(Path.of(env("TLS_REP", "/tls")), Integer.parseInt(env("TLS_PORT", "7443")));
        System.out.printf("passerelle prête : SFB-AKA :%d (étiquettes de rôle : %s), TLS 1.3 :%d%n",
                sfb.getLocalPort(), etiquettesRole, tls.getLocalPort());
        Thread.ofPlatform().start(() -> boucle(tls, executeur, true));
        boucle(sfb, executeur, false);
    }

    static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }

    static void boucle(ServerSocket ss, java.util.concurrent.ExecutorService ex, boolean tls) {
        while (true) {
            try {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                ex.submit(() -> { try (s) { if (tls) servirTls((SSLSocket) s); else servirSfb(s); }
                                  catch (Exception e) { /* connexion fermée par le client */ } });
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // ---------- SFB-AKA ---------------------------------------------------------
    static void servirSfb(Socket s) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        Crypto.razCpu();
        long cpu0 = MX.getCurrentThreadCpuTime();
        SfbAka.Session session;
        try {
            session = SfbAka.repondre(in, out, moi, registre, etiquettesRole);
        } catch (SfbAka.Rejet r) {
            journal.ecrire("-", 0, "-", "RJCT", r.motif.name());
            repondre(out, SfbAka.Motif.valueOf(r.motif.name()), Crypto.cpuNs(), MX.getCurrentThreadCpuTime() - cpu0);
            return;
        }
        long cpuPoignee = MX.getCurrentThreadCpuTime() - cpu0;
        long cpuCryptoPoignee = Crypto.cpuNs();
        String partenaire = HexFormat.of().formatHex(session.idPartenaire);
        while (true) {
            byte[] trame;
            try { trame = SfbAka.lire(in); } catch (EOFException e) { return; }
            // Idempotence : relance à l'identique de la dernière trame authentique déjà traitée
            // (typiquement après expiration d'un délai côté plateforme) : le statut initial est
            // renvoyé, l'ordre n'est ni réévalué ni réexécuté.
            if (session.derniereTrame != null && java.util.Arrays.equals(trame, session.derniereTrame)) {
                journal.ecrire(HexFormat.of().formatHex(session.sid), session.dernierCompteurAccepte, "-",
                        "DOUBLON", "REPONSE_IDEMPOTENTE");
                repondre(out, session.dernierStatut, SfbAka.Motif.DOUBLON_IDEMPOTENT, cpuCryptoPoignee, cpuPoignee);
                continue;
            }
            SfbAka.Motif motif;
            try {
                SfbAka.Instruction ins = SfbAka.ouvrirInstruction(session, trame);
                motif = regles.evaluer(partenaire, ins.clair(), System.currentTimeMillis());
                session.dernierCompteurAccepte = ins.compteur();              // P4 : la trame authentique est consommée
                if (motif == SfbAka.Motif.ACCEPTE) {
                    journal.ecrire(HexFormat.of().formatHex(session.sid), ins.compteur(),
                            HexFormat.of().formatHex(Crypto.sha256(ins.clair())), "ACCP", "-");   // P5 : avant la réponse
                    motif = transmettre(ins.clair());
                } else {
                    journal.ecrire(HexFormat.of().formatHex(session.sid), ins.compteur(), "-", "RJCT", motif.name());
                }
                session.derniereTrame = trame;
                session.dernierStatut = motif == SfbAka.Motif.ACCEPTE ? 0 : 1;
            } catch (SfbAka.Rejet r) {
                motif = r.motif;
                journal.ecrire(HexFormat.of().formatHex(session.sid), -1, "-", "RJCT", motif.name());
            }
            repondre(out, motif, cpuCryptoPoignee, cpuPoignee);
        }
    }

    // ---------- Référence TLS 1.3 -------------------------------------------------
    static void servirTls(SSLSocket s) throws Exception {
        long cpu0 = MX.getCurrentThreadCpuTime();
        s.startHandshake();
        long cpuPoignee = MX.getCurrentThreadCpuTime() - cpu0;
        String partenaireTls = s.getSession().getPeerPrincipal().getName();
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        while (true) {
            byte[] clair;
            try { clair = SfbAka.lire(in); } catch (EOFException | SSLException e) { return; }
            SfbAka.Motif motif = regles.evaluer(partenaireTls, clair, System.currentTimeMillis());
            if (motif == SfbAka.Motif.ACCEPTE) motif = transmettre(clair);
            repondre(out, motif, cpuPoignee, cpuPoignee);
        }
    }

    static SSLServerSocket serveurTls(Path rep, int port) throws Exception {
        char[] mdp = env("TLS_MDP", "banc-sfb").toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream f = Files.newInputStream(rep.resolve("serveur.p12"))) { ks.load(f, mdp); }
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream f = Files.newInputStream(rep.resolve("confiance.p12"))) { ts.load(f, mdp); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX"); kmf.init(ks, mdp);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX"); tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(port, 256);
        ss.setEnabledProtocols(new String[]{"TLSv1.3"});
        ss.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256"});
        ss.setNeedClientAuth(true);                        // authentification mutuelle, comme SFB-AKA
        return ss;
    }

    // ---------- politique, transmission, réponse --------------------------------
    static SfbAka.Motif transmettre(byte[] pacs008) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(cbs)
                    .header("Content-Type", "application/xml")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(pacs008)).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() != 200 && DEBUG) System.err.println("bouchon : HTTP " + r.statusCode());
            return r.statusCode() == 200 ? SfbAka.Motif.ACCEPTE : SfbAka.Motif.CORE_BANKING_REJET;
        } catch (Exception e) {
            if (DEBUG) System.err.println("bouchon : " + e);
            return SfbAka.Motif.CORE_BANKING_REJET;
        }
    }

    static void repondre(DataOutputStream out, SfbAka.Motif m, long cpuCrypto, long cpuPoignee) throws IOException {
        repondre(out, m == SfbAka.Motif.ACCEPTE ? 0 : 1, m, cpuCrypto, cpuPoignee);
    }

    static void repondre(DataOutputStream out, int statut, SfbAka.Motif m, long cpuCrypto, long cpuPoignee) throws IOException {
        byte[] r = Crypto.concat(new byte[]{(byte) statut, (byte) m.ordinal()},
                Crypto.u64(cpuCrypto), Crypto.u64(cpuPoignee));
        SfbAka.ecrire(out, r);
    }
}
