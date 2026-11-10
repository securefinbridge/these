package sfb;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;

/**
 * Client de mesure du banc d'essai (annexe D).
 *
 *   java sfb.Mesure mesure  <sfb|tls> <profil> <répétitions> <sessions> <chauffe> <sortie.json>
 *   java sfb.Mesure attaques <n> <sortie.json>
 *   java sfb.Mesure reflexion <n> <sortie.json>   (attaque par réflexion seule)
 *   java sfb.Mesure composition <n> <sortie.json> (scénario 5)
 *   java sfb.Mesure menace <n> <sortie.json>      (scénarios 6a et 6b)
 *   java sfb.Mesure debit <sfb|tls> <clients> <durée_s> <ordres_par_session> <sortie.json>
 *
 * Une session = connexion TCP, établissement (SFB-AKA ou TLS 1.3 mutuel), envoi d'un
 * ordre pacs.008, réception de la réponse. Pour chaque session sont relevés : latence
 * d'établissement, latence de bout en bout, octets applicatifs échangés, temps CPU
 * (primitives et poignée de main complète) côté client et côté passerelle.
 */
public final class Mesure {
    static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();
    static final Random ALEA = new Random();
    static String hote = env("SFB_HOTE", "passerelle");
    static int portSfb = Integer.parseInt(env("SFB_PORT", "7000")), portTls = Integer.parseInt(env("TLS_PORT", "7443"));
    static Path cles = Path.of(env("SFB_CLES", "/cles")), repTls = Path.of(env("TLS_REP", "/tls"));

    static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "mesure" -> mesure(a[1], a[2], Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]), Path.of(a[6]));
            case "attaques" -> Attaques.executer(Integer.parseInt(a[1]), Path.of(a[2]));
            case "reflexion" -> Attaques.executerReflexion(Integer.parseInt(a[1]), Path.of(a[2]));
            case "composition" -> Attaques.executerComposition(Integer.parseInt(a[1]), Path.of(a[2]));
            case "menace" -> Attaques.executerMenaceInterne(Integer.parseInt(a[1]), Path.of(a[2]));
            case "debit" -> debit(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]), Path.of(a[5]));
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    /** Ordre de virement pacs.008 ; montant tiré selon le profil PaySim de l'annexe D. */
    static byte[] pacs008(long montant) {
        String ref = "E2E-" + UUID.randomUUID();
        return ("<?xml version=\"1.0\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">"
              + "<FIToFICstmrCdtTrf><CdtTrfTxInf><PmtId><EndToEndId>" + ref + "</EndToEndId></PmtId>"
              + "<IntrBkSttlmAmt Ccy=\"XOF\">" + montant + "</IntrBkSttlmAmt>"
              + "<DbtrAcct><Id><Othr><Id>22670000001</Id></Othr></Id></DbtrAcct>"
              + "<CdtrAcct><Id><Othr><Id>BF0010001</Id></Othr></Id></CdtrAcct>"
              + "</CdtTrfTxInf></FIToFICstmrCdtTrf></Document>").getBytes(StandardCharsets.UTF_8);
    }

    /** Log-normale de médiane 5 000 FCFA et de 95e centile 150 000 FCFA, plafonnée à 2 000 000. */
    static long montantPaySim() {
        double sigma = (Math.log(150_000) - Math.log(5_000)) / 1.6449;
        return Math.min(2_000_000L, Math.max(100L, Math.round(Math.exp(Math.log(5_000) + sigma * ALEA.nextGaussian()))));
    }

    // ---------- TLS 1.3 client (authentification mutuelle) ----------------------
    static SSLContext contexteTls() throws Exception {
        char[] mdp = env("TLS_MDP", "banc-sfb").toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream f = Files.newInputStream(repTls.resolve("client.p12"))) { ks.load(f, mdp); }
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream f = Files.newInputStream(repTls.resolve("confiance.p12"))) { ts.load(f, mdp); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX"); kmf.init(ks, mdp);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX"); tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    record Resultat(double poigneeMs, double boutEnBoutMs, long octetsPoignee, long octetsTotal,
                    long cpuCryptoClient, long cpuPoigneeClient, long cpuCryptoServeur, long cpuPoigneeServeur,
                    int statut, String motif) {}

    static Resultat sessionSfb(Identites.Identite moi, Identites.Registre reg) throws Exception {
        Comptage s = new Comptage();
        long t0 = System.nanoTime();
        s.connect(new InetSocketAddress(hote, portSfb));
        s.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        Crypto.razCpu();
        long c0 = MX.getCurrentThreadCpuTime();
        SfbAka.Session session = SfbAka.initier(in, out, moi, reg);
        long cpuPoignee = MX.getCurrentThreadCpuTime() - c0, cpuCrypto = Crypto.cpuNs();
        long t1 = System.nanoTime();
        long octetsPoignee = s.envoyes + s.recus;
        SfbAka.ecrire(out, SfbAka.scellerInstruction(session, session.prochainCompteur++, pacs008(montantPaySim())));
        byte[] r = SfbAka.lire(in);
        long t2 = System.nanoTime();
        long octetsTotal = s.envoyes + s.recus;
        s.close();
        return resultat(t0, t1, t2, octetsPoignee, octetsTotal, cpuCrypto, cpuPoignee, r);
    }

    static Resultat sessionTls(SSLContext ctx) throws Exception {
        Comptage brut = new Comptage();
        long t0 = System.nanoTime();
        brut.connect(new InetSocketAddress(hote, portTls));
        brut.setTcpNoDelay(true);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(brut, hote, portTls, true);
        s.setEnabledProtocols(new String[]{"TLSv1.3"});
        s.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256"});
        long c0 = MX.getCurrentThreadCpuTime();
        s.startHandshake();
        long cpuPoignee = MX.getCurrentThreadCpuTime() - c0;
        long t1 = System.nanoTime();
        long octetsPoignee = brut.envoyes + brut.recus;
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        SfbAka.ecrire(out, pacs008(montantPaySim()));
        byte[] r = SfbAka.lire(in);
        long t2 = System.nanoTime();
        long octetsTotal = brut.envoyes + brut.recus;
        s.getSession().invalidate();          // pas de reprise de session : poignée complète à chaque fois
        s.close();
        return resultat(t0, t1, t2, octetsPoignee, octetsTotal, cpuPoignee, cpuPoignee, r);
    }

    static Resultat resultat(long t0, long t1, long t2, long oP, long oT, long cc, long cp, byte[] r) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(r);
        int statut = b.get(); int motif = b.get();
        long csC = b.getLong(), csP = b.getLong();
        return new Resultat((t1 - t0) / 1e6, (t2 - t0) / 1e6, oP, oT, cc, cp, csC, csP, statut,
                SfbAka.Motif.values()[motif].name());
    }

    static void mesure(String mecanisme, String profil, int repetitions, int sessions, int chauffe, Path sortie) throws Exception {
        Identites.Identite moi = Identites.charger(cles, "plateforme");
        Identites.Registre reg = new Identites.Registre(cles, "passerelle");
        SSLContext ctx = mecanisme.equals("tls") ? contexteTls() : null;
        for (int i = 0; i < chauffe; i++)                              // chauffe de la JVM, exclue des mesures
            if (ctx == null) sessionSfb(moi, reg); else sessionTls(ctx);
        StringBuilder j = new StringBuilder();
        j.append("{\"mecanisme\":\"").append(mecanisme).append("\",\"profil\":\"").append(profil)
         .append("\",\"java\":\"").append(System.getProperty("java.version"))
         .append("\",\"date\":\"").append(java.time.Instant.now()).append("\",\"repetitions\":[");
        for (int rep = 0; rep < repetitions; rep++) {
            j.append(rep == 0 ? "" : ",").append("[");
            for (int k = 0; k < sessions; k++) {
                Resultat r = ctx == null ? sessionSfb(moi, reg) : sessionTls(ctx);
                j.append(k == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"poignee_ms\":%.3f,\"bout_en_bout_ms\":%.3f,\"octets_poignee\":%d,\"octets_total\":%d,"
                  + "\"cpu_crypto_client_ns\":%d,\"cpu_poignee_client_ns\":%d,\"cpu_crypto_serveur_ns\":%d,"
                  + "\"cpu_poignee_serveur_ns\":%d,\"statut\":%d,\"motif\":\"%s\"}",
                    r.poigneeMs(), r.boutEnBoutMs(), r.octetsPoignee(), r.octetsTotal(), r.cpuCryptoClient(),
                    r.cpuPoigneeClient(), r.cpuCryptoServeur(), r.cpuPoigneeServeur(), r.statut(), r.motif()));
            }
            j.append("]");
            System.out.printf("  %s %s : répétition %d/%d%n", mecanisme, profil, rep + 1, repetitions);
        }
        j.append("]}");
        Files.createDirectories(sortie.toAbsolutePath().getParent());
        Files.writeString(sortie, j.toString());
    }

    /**
     * Débit sous charge : <clients> fils concurrents ouvrent chacun des sessions successives
     * de <ordres> instructions, pendant <durée> secondes (5 s de chauffe exclues).
     * Relève le nombre d'ordres acceptés par seconde et la latence par ordre.
     */
    static void debit(String mecanisme, int clients, int duree, int ordres, Path sortie) throws Exception {
        Identites.Identite moi = Identites.charger(cles, "plateforme");
        Identites.Registre reg = new Identites.Registre(cles, "passerelle");
        SSLContext ctx = mecanisme.equals("tls") ? contexteTls() : null;
        var acceptes = new java.util.concurrent.atomic.LongAdder();
        var refuses = new java.util.concurrent.atomic.LongAdder();
        var latences = new java.util.concurrent.ConcurrentLinkedQueue<Double>();
        long debutChauffe = System.nanoTime(), debutMesure = debutChauffe + 5_000_000_000L;
        long fin = debutMesure + duree * 1_000_000_000L;
        List<Thread> fils = new ArrayList<>();
        for (int c = 0; c < clients; c++) fils.add(Thread.ofPlatform().start(() -> {
            while (System.nanoTime() < fin) {
                try {
                    if (ctx == null) {
                        try (Socket s = new Socket(hote, portSfb)) {
                            s.setTcpNoDelay(true);
                            var in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                            var out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                            SfbAka.Session se = SfbAka.initier(in, out, moi, reg);
                            for (int k = 0; k < ordres && System.nanoTime() < fin; k++) {
                                long t = System.nanoTime();
                                SfbAka.ecrire(out, SfbAka.scellerInstruction(se, se.prochainCompteur++, pacs008(montantPaySim())));
                                byte[] r = SfbAka.lire(in);
                                compter(r, t, debutMesure, acceptes, refuses, latences);
                            }
                        }
                    } else {
                        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(hote, portTls);
                        try (s) {
                            s.setTcpNoDelay(true);
                            s.setEnabledProtocols(new String[]{"TLSv1.3"});
                            s.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256"});
                            s.startHandshake();
                            var in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                            var out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                            for (int k = 0; k < ordres && System.nanoTime() < fin; k++) {
                                long t = System.nanoTime();
                                SfbAka.ecrire(out, pacs008(montantPaySim()));
                                compter(SfbAka.lire(in), t, debutMesure, acceptes, refuses, latences);
                            }
                            s.getSession().invalidate();
                        }
                    }
                } catch (Exception e) { if (System.nanoTime() < fin) MOTIFS_REFUS.merge("EXCEPTION_" + e.getClass().getSimpleName(), 1L, Long::sum); }
            }
        }));
        for (Thread t : fils) t.join();
        double[] l = latences.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double med = l.length == 0 ? 0 : l[l.length / 2], p95 = l.length == 0 ? 0 : l[(int) (l.length * 0.95)];
        double tps = acceptes.sum() / (double) duree;
        System.out.printf(Locale.ROOT, "  débit %s : %d clients, %d ordres/session : %.1f ordres acceptés/s, latence médiane %.2f ms, p95 %.2f ms, refus %d %s%n",
                mecanisme, clients, ordres, tps, med, p95, refuses.sum(), MOTIFS_REFUS);
        Files.createDirectories(sortie.toAbsolutePath().getParent());
        Files.writeString(sortie, String.format(Locale.ROOT,
            "{\"mecanisme\":\"%s\",\"clients\":%d,\"ordres_par_session\":%d,\"duree_s\":%d,\"acceptes\":%d,"
          + "\"refuses\":%d,\"motifs_refus\":\"%s\",\"debit_tps\":%.2f,\"latence_mediane_ms\":%.3f,\"latence_p95_ms\":%.3f}",
            mecanisme, clients, ordres, duree, acceptes.sum(), refuses.sum(), MOTIFS_REFUS, tps, med, p95));
    }

    static final java.util.concurrent.ConcurrentHashMap<String, Long> MOTIFS_REFUS = new java.util.concurrent.ConcurrentHashMap<>();

    static void compter(byte[] r, long t, long debutMesure, java.util.concurrent.atomic.LongAdder ok,
                        java.util.concurrent.atomic.LongAdder ko, java.util.concurrent.ConcurrentLinkedQueue<Double> lat) {
        if (t < debutMesure) return;                                   // chauffe exclue
        if (r[0] == 0) ok.increment(); else { ko.increment(); MOTIFS_REFUS.merge(SfbAka.Motif.values()[r[1]].name(), 1L, Long::sum); }
        lat.add((System.nanoTime() - t) / 1e6);
    }
}
