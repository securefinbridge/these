package sfb;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Banc d'attaque : scénarios du chapitre 9 exécutables contre la passerelle SFB-AKA.
 * Chaque scénario est rejoué n fois ; l'issue observée est le motif renvoyé par la
 * passerelle (et journalisé par elle). Un scénario est « neutralisé » si le motif
 * observé est le motif attendu et qu'aucune instruction adverse n'est acceptée.
 */
public final class Attaques {
    private Attaques() {}

    record Canal(Socket s, DataInputStream in, DataOutputStream out) implements Closeable {
        static Canal ouvrir() throws IOException {
            Socket s = new Socket(Mesure.hote, Mesure.portSfb); s.setTcpNoDelay(true);
            return new Canal(s, new DataInputStream(new BufferedInputStream(s.getInputStream())),
                                new DataOutputStream(new BufferedOutputStream(s.getOutputStream())));
        }
        public void close() throws IOException { s.close(); }
    }

    static String motifDe(byte[] reponse) {
        return reponse.length == 18 ? SfbAka.Motif.values()[reponse[1]].name() : "MESSAGE_" + reponse.length + "_OCTETS";
    }

    static Identites.Identite moi;
    static Identites.Registre reg;

    public static void executer(int n, Path sortie) throws Exception {
        moi = Identites.charger(Mesure.cles, "plateforme");
        reg = new Identites.Registre(Mesure.cles, "passerelle");
        Identites.Identite passerelle = Identites.charger(Mesure.cles, "passerelle");  // seule sa clé PUBLIQUE est utilisée
        Map<String, List<String>> obs = new LinkedHashMap<>();
        Map<String, String> attendu = new LinkedHashMap<>();
        attendu.put("1_rejeu_poignee", "SIGNATURE_INVALIDE");
        attendu.put("2_rejeu_instruction", "REJEU_DETECTE");
        attendu.put("2b_relance_apres_delai", "DOUBLON_IDEMPOTENT");
        attendu.put("3a_alteration_instruction", "INTEGRITE_COMPROMISE");
        attendu.put("3b_escalade_plafond", "POLITIQUE_REFUSEE");
        attendu.put("4_alteration_suites", "SIGNATURE_INVALIDE");
        attendu.put("E1_partenaire_non_enregistre", "REGISTRE_INCONNU");
        attendu.put("R_reflexion", "SIGNATURE_INVALIDE");
        for (String k : attendu.keySet()) obs.put(k, new ArrayList<>());

        for (int i = 0; i < n; i++) {
            obs.get("1_rejeu_poignee").add(rejeuPoignee());
            String[] r = instructions();
            obs.get("2_rejeu_instruction").add(r[0]);
            obs.get("2b_relance_apres_delai").add(r[3]);
            obs.get("3a_alteration_instruction").add(r[1]);
            obs.get("3b_escalade_plafond").add(r[2]);
            obs.get("4_alteration_suites").add(alterationSuites());
            obs.get("E1_partenaire_non_enregistre").add(intrus());
            obs.get("R_reflexion").add(reflexion(passerelle));
        }

        StringBuilder j = new StringBuilder("{\"repetitions\":" + n + ",\"scenarios\":[");
        boolean premier = true;
        for (var e : obs.entrySet()) {
            long conformes = e.getValue().stream().filter(attendu.get(e.getKey())::equals).count();
            Map<String, Long> repartition = new TreeMap<>();
            for (String m : e.getValue()) repartition.merge(m, 1L, Long::sum);
            j.append(premier ? "" : ",").append(String.format(
                "{\"scenario\":\"%s\",\"attendu\":\"%s\",\"conformes\":%d,\"total\":%d,\"observes\":\"%s\"}",
                e.getKey(), attendu.get(e.getKey()), conformes, e.getValue().size(), repartition));
            premier = false;
            System.out.printf("  %-30s attendu %-22s observé %s%n", e.getKey(), attendu.get(e.getKey()), repartition);
        }
        j.append("]}");
        Files.createDirectories(sortie.toAbsolutePath().getParent());
        Files.writeString(sortie, j.toString());
    }

    /** Démonstration isolée de l'attaque par réflexion (contre la version 1 ou 2). */
    public static void executerReflexion(int n, Path sortie) throws Exception {
        Identites.Identite passerelle = Identites.charger(Mesure.cles, "passerelle");
        Map<String, Long> repartition = new TreeMap<>();
        for (int i = 0; i < n; i++) repartition.merge(reflexion(passerelle), 1L, Long::sum);
        System.out.println("  reflexion : " + repartition);
        Files.createDirectories(sortie.toAbsolutePath().getParent());
        Files.writeString(sortie, String.format("{\"scenario\":\"R_reflexion\",\"repetitions\":%d,\"observes\":\"%s\"}", n, repartition));
    }

    /** Scénario 1 : l'adversaire enregistre une poignée de main légitime et rejoue M1 puis M3. */
    static String rejeuPoignee() throws Exception {
        byte[] m1, m3;
        try (Canal c = Canal.ouvrir()) {
            // poignée de main légitime, messages enregistrés au passage
            ByteArrayOutputStream tampon = new ByteArrayOutputStream();
            DataOutputStream enreg = new DataOutputStream(new OutputStream() {
                public void write(int b) throws IOException { tampon.write(b); c.out().write(b); }
                public void write(byte[] b, int o, int l) throws IOException { tampon.write(b, o, l); c.out().write(b, o, l); }
                public void flush() throws IOException { c.out().flush(); }
            });
            SfbAka.initier(c.in(), enreg, moi, reg);
            DataInputStream lu = new DataInputStream(new ByteArrayInputStream(tampon.toByteArray()));
            m1 = SfbAka.lire(lu); m3 = SfbAka.lire(lu);
        }
        try (Canal c = Canal.ouvrir()) {
            SfbAka.ecrire(c.out(), m1);
            SfbAka.lire(c.in());                         // nouveau M2 : aléa et part éphémère frais
            SfbAka.ecrire(c.out(), m3);                  // M3 rejoué, signé sur l'ancienne transcription
            return motifDe(SfbAka.lire(c.in()));
        }
    }

    /** Nombre d'acceptations du bouchon core banking pour une référence de bout en bout. */
    static long executions(String ref) throws Exception {
        var r = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create(Mesure.env("SFB_CBS_JOURNAL", "http://127.0.0.1:8443/journal"))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var m = java.util.regex.Pattern.compile("\\{[^}]*\"ref\": \"" + ref + "\"[^}]*\"statut\": \"ACCP\"").matcher(r.body());
        long n = 0; while (m.find()) n++;
        return n;
    }

    static String reference(byte[] pacs008) {
        var m = java.util.regex.Pattern.compile("<EndToEndId>([^<]+)</EndToEndId>").matcher(new String(pacs008, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : "?";
    }

    /**
     * Scénarios 2, 2b, 3a, 3b dans une session légitime établie.
     *  2  : rejeu d'une instruction déjà acceptée et dépassée (compteur ancien) ;
     *  2b : relance à l'identique de la dernière instruction (après expiration d'un délai) :
     *       statut initial renvoyé, sans seconde exécution (vérifiée auprès du bouchon) ;
     *  3a : altération en transit ; 3b : instruction hors plafond.
     */
    static String[] instructions() throws Exception {
        try (Canal c = Canal.ouvrir()) {
            SfbAka.Session s = SfbAka.initier(c.in(), c.out(), moi, reg);
            byte[] trameA = SfbAka.scellerInstruction(s, s.prochainCompteur++, Mesure.pacs008(25_000));
            SfbAka.ecrire(c.out(), trameA);
            String a = motifDe(SfbAka.lire(c.in()));
            byte[] ordreB = Mesure.pacs008(40_000);
            byte[] trameB = SfbAka.scellerInstruction(s, s.prochainCompteur++, ordreB);
            SfbAka.ecrire(c.out(), trameB);
            String b = motifDe(SfbAka.lire(c.in()));
            if (!"ACCEPTE".equals(a) || !"ACCEPTE".equals(b)) return new String[]{"ECHEC_" + a + "_" + b, "-", "-", "-"};
            SfbAka.ecrire(c.out(), trameA);                                     // rejeu d'une instruction dépassée
            String rejeu = motifDe(SfbAka.lire(c.in()));
            SfbAka.ecrire(c.out(), trameB);                                     // relance à l'identique de la dernière
            byte[] rep = SfbAka.lire(c.in());
            long n = executions(reference(ordreB));
            String relance = motifDe(rep);
            if ("DOUBLON_IDEMPOTENT".equals(relance) && (rep[0] != 0 || n != 1))
                relance = "INCOHERENT_statut" + rep[0] + "_executions" + n;
            byte[] alteree = SfbAka.scellerInstruction(s, s.prochainCompteur++, Mesure.pacs008(25_000));
            alteree[30] ^= 0x01;                                                 // un bit du chiffré modifié en transit
            SfbAka.ecrire(c.out(), alteree);
            String alteration = motifDe(SfbAka.lire(c.in()));
            SfbAka.ecrire(c.out(), SfbAka.scellerInstruction(s, s.prochainCompteur++, Mesure.pacs008(5_000_000)));
            String escalade = motifDe(SfbAka.lire(c.in()));
            return new String[]{rejeu, alteration, escalade, relance};
        }
    }

    /** Scénario 4 : la liste des suites de M1 est réécrite en transit (une suite retirée). */
    static String alterationSuites() throws Exception {
        try (Canal c = Canal.ouvrir()) {
            KeyPair eph = Crypto.x25519Paire();
            byte[] X = Crypto.x25519Brut(eph.getPublic()), nI = Crypto.alea(32);
            byte[] reecrites = {0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01};    // ce que reçoit la passerelle
            SfbAka.ecrire(c.out(), Crypto.concat(moi.id(), X, nI, reecrites));
            byte[] m2 = SfbAka.lire(c.in());
            // l'initiateur vérifie la signature sur la transcription qu'IL a émise
            byte[] Y = Arrays.copyOfRange(m2, 0, 32), nR = Arrays.copyOfRange(m2, 32, 64);
            byte[] suite = Arrays.copyOfRange(m2, 64, 66), empR = Arrays.copyOfRange(m2, 66, 98);
            byte[] sigR = Arrays.copyOfRange(m2, 98, 162);
            byte[] hTr = Crypto.sha256("tr".getBytes(StandardCharsets.US_ASCII), moi.id(), X, nI,
                    SfbAka.SUITES_PROPOSEES, Y, nR, suite, empR);
            boolean ok = Crypto.verifier(reg.parEmpreinte(empR), sigR,
                    "sfb-aka/tr/R".getBytes(StandardCharsets.US_ASCII), hTr);
            return ok ? "ACCEPTE" : "SIGNATURE_INVALIDE";
        }
    }

    /** Partenaire non inscrit au registre (patron sans registre, chapitre 5). */
    static String intrus() throws Exception {
        Path tmp = Files.createTempDirectory("intrus");
        Identites.generer(tmp, "intrus");
        Identites.Identite intrus = Identites.charger(tmp, "intrus");
        try (Canal c = Canal.ouvrir()) {
            KeyPair eph = Crypto.x25519Paire();
            SfbAka.ecrire(c.out(), Crypto.concat(intrus.id(), Crypto.x25519Brut(eph.getPublic()), Crypto.alea(32),
                    SfbAka.SUITES_PROPOSEES));
            return motifDe(SfbAka.lire(c.in()));
        }
    }

    /**
     * Attaque par réflexion (§7.3.4) : l'adversaire, sans aucune clé à long terme,
     * se présente à la passerelle sous l'identité de la passerelle elle-même et lui
     * renvoie sa propre signature de M2 comme signature de M3. Rejetée si la
     * passerelle distingue les rôles ; acceptée, instruction comprise, sinon.
     */
    static String reflexion(Identites.Identite cible) throws Exception {
        try (Canal c = Canal.ouvrir()) {
            KeyPair eph = Crypto.x25519Paire();                       // exposant connu de l'adversaire
            byte[] X = Crypto.x25519Brut(eph.getPublic()), nI = Crypto.alea(32);
            SfbAka.ecrire(c.out(), Crypto.concat(cible.id(), X, nI, SfbAka.SUITES_PROPOSEES));
            byte[] m2 = SfbAka.lire(c.in());
            if (m2.length != 162) return motifDe(m2);
            byte[] Y = Arrays.copyOfRange(m2, 0, 32), nR = Arrays.copyOfRange(m2, 32, 64);
            byte[] suite = Arrays.copyOfRange(m2, 64, 66), empR = Arrays.copyOfRange(m2, 66, 98);
            byte[] sigR = Arrays.copyOfRange(m2, 98, 162);
            byte[] hTr = Crypto.sha256("tr".getBytes(StandardCharsets.US_ASCII), cible.id(), X, nI,
                    SfbAka.SUITES_PROPOSEES, Y, nR, suite, empR);
            byte[] dh = Crypto.x25519Accord(eph.getPrivate(), Crypto.x25519DepuisBrut(Y));
            byte[] k = Crypto.hkdfExtract(Crypto.concat(nI, nR), dh);
            byte[] kMac = Crypto.hkdfExpand(k, Crypto.concat("sfb/mac".getBytes(StandardCharsets.US_ASCII), hTr), 32);
            byte[] kEnc = Crypto.hkdfExpand(k, Crypto.concat("sfb/enc".getBytes(StandardCharsets.US_ASCII), hTr), 16);
            byte[] preuve = Crypto.hmac(kMac, "fin".getBytes(StandardCharsets.US_ASCII), hTr);
            SfbAka.ecrire(c.out(), Crypto.concat(empR, sigR, preuve));       // signature de M2 réfléchie
            // si la passerelle accepte la session, l'adversaire tente une instruction
            c.s().setSoTimeout(3000);
            byte[] sid = Arrays.copyOfRange(Crypto.sha256("sid".getBytes(StandardCharsets.US_ASCII), hTr), 0, 8);
            var s = new SfbAka.Session(sid, kEnc, kMac, cible.id());
            SfbAka.ecrire(c.out(), SfbAka.scellerInstruction(s, 1, Mesure.pacs008(1_000_000)));
            String m = motifDe(SfbAka.lire(c.in()));
            return "ACCEPTE".equals(m) ? "ATTAQUE_REUSSIE_INSTRUCTION_ACCEPTEE" : m;
        } catch (SocketTimeoutException | EOFException e) {
            return "CONNEXION_FERMEE";
        }
    }

    // ---------------------------------------------------------------------------
    //  Scénario 5 : composition par clé partagée. Un service de notification signe,
    //  avec la clé à long terme de la passerelle, les messages qu'on lui soumet.
    //  L'adversaire se fait passer pour la passerelle auprès de la plateforme et
    //  obtient de ce service la signature dont il a besoin pour M2.
    // ---------------------------------------------------------------------------
    public static void executerComposition(int n, Path sortie) throws Exception {
        Identites.Identite plateforme = Identites.charger(Mesure.cles, "plateforme");
        Identites.Registre regP = new Identites.Registre(Mesure.cles, "passerelle");
        Identites.Identite passerelle = Identites.charger(Mesure.cles, "passerelle");
        StringBuilder j = new StringBuilder("{\"repetitions\":" + n + ",\"variantes\":[");
        boolean premier = true;
        for (boolean separation : new boolean[]{false, true}) {
            Map<String, Long> rep = new TreeMap<>();
            for (int i = 0; i < n; i++) rep.merge(composition(plateforme, regP, passerelle, separation), 1L, Long::sum);
            System.out.printf("  composition, service %s séparation de domaine : %s%n", separation ? "avec" : "sans", rep);
            j.append(premier ? "" : ",").append(String.format("{\"separation\":%b,\"observes\":\"%s\"}", separation, rep));
            premier = false;
        }
        j.append("]}");
        Files.createDirectories(sortie.toAbsolutePath().getParent());
        Files.writeString(sortie, j.toString());
    }

    /** Service de notification partageant la clé de la passerelle. */
    static byte[] oracleNotification(Identites.Identite p, byte[] message, boolean separation) throws Exception {
        return separation ? Crypto.signer(p.sk(), "sfb-notif".getBytes(StandardCharsets.US_ASCII), message)
                          : Crypto.signer(p.sk(), message);
    }

    static String composition(Identites.Identite plateforme, Identites.Registre regP, Identites.Identite passerelle,
                              boolean separation) throws Exception {
        try (ServerSocket faux = new ServerSocket(0)) {
            var issue = new java.util.concurrent.CompletableFuture<String>();
            Thread.ofPlatform().start(() -> {                         // la plateforme, victime, initie la session
                try (Socket s = new Socket("127.0.0.1", faux.getLocalPort())) {
                    SfbAka.initier(new DataInputStream(new BufferedInputStream(s.getInputStream())),
                            new DataOutputStream(new BufferedOutputStream(s.getOutputStream())), plateforme, regP);
                    issue.complete("ATTAQUE_REUSSIE_SESSION_ETABLIE");
                } catch (SfbAka.Rejet r) { issue.complete(r.motif.name()); }
                  catch (Exception e) { issue.complete("ERREUR_" + e.getClass().getSimpleName()); }
            });
            try (Socket a = faux.accept()) {                           // l'adversaire, sans la clé de la passerelle
                var in = new DataInputStream(new BufferedInputStream(a.getInputStream()));
                var out = new DataOutputStream(new BufferedOutputStream(a.getOutputStream()));
                byte[] m1 = SfbAka.lire(in);
                byte[] idI = Arrays.copyOfRange(m1, 0, 16), X = Arrays.copyOfRange(m1, 16, 48);
                byte[] nI = Arrays.copyOfRange(m1, 48, 80), suites = Arrays.copyOfRange(m1, 80, 88);
                KeyPair eph = Crypto.x25519Paire();
                byte[] Y = Crypto.x25519Brut(eph.getPublic()), nR = Crypto.alea(32);
                byte[] hTr = Crypto.sha256("tr".getBytes(StandardCharsets.US_ASCII), idI, X, nI, suites, Y, nR,
                        SfbAka.SUITE_REFERENCE, passerelle.emp());
                // message soumis au service de notification : exactement ce que la plateforme vérifiera
                byte[] sig = oracleNotification(passerelle,
                        Crypto.concat("sfb-aka/tr/R".getBytes(StandardCharsets.US_ASCII), hTr), separation);
                SfbAka.ecrire(out, Crypto.concat(Y, nR, SfbAka.SUITE_REFERENCE, passerelle.emp(), sig));
                a.setSoTimeout(3000);
                try { SfbAka.lire(in); } catch (IOException ignore) { }
            }
            return issue.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------------------
    //  Scénario 6a : clé à long terme divulguée APRÈS la session. L'adversaire obtient
    //  les clés à long terme et tout l'état persistant de la passerelle (journal,
    //  répertoire des clés) ; on vérifie qu'aucune clé de session ne s'y trouve.
    //  Scénario 6b : clé de la plateforme compromise AVANT la session. L'adversaire
    //  est un partenaire légitime aux yeux du protocole ; on mesure ce que la
    //  politique le laisse faire accepter avant de le bloquer.
    // ---------------------------------------------------------------------------
    public static void executerMenaceInterne(int n, Path sortie) throws Exception {
        moi = Identites.charger(Mesure.cles, "plateforme");
        reg = new Identites.Registre(Mesure.cles, "passerelle");
        Path journal = Path.of(Mesure.env("SFB_JOURNAL", "/tmp/journal_passerelle.jsonl"));
        Map<String, Long> rep6a = new TreeMap<>();
        for (int i = 0; i < n; i++) rep6a.merge(cleApresSession(journal), 1L, Long::sum);
        System.out.println("  6a : " + rep6a);
        // 6b : ordres de 1 900 000 FCFA, chacun sous le plafond, envoyés en rafale
        long montant = 1_900_000L, acceptes = 0, cumul = 0; String premierRefus = "-"; int envoyes = 0;
        try (Canal c = Canal.ouvrir()) {
            SfbAka.Session s = SfbAka.initier(c.in(), c.out(), moi, reg);
            for (int i = 0; i < 100; i++) {
                SfbAka.ecrire(c.out(), SfbAka.scellerInstruction(s, s.prochainCompteur++, Mesure.pacs008(montant)));
                envoyes++;
                String m = motifDe(SfbAka.lire(c.in()));
                if ("ACCEPTE".equals(m)) { acceptes++; cumul += montant; }
                else { premierRefus = m; break; }
            }
        }
        System.out.printf("  6b : %d ordres acceptés (%d FCFA) avant refus %s ; %d envoyés%n", acceptes, cumul, premierRefus, envoyes);
        Files.createDirectories(sortie.toAbsolutePath().getParent());
        Files.writeString(sortie, String.format(
            "{\"6a\":{\"repetitions\":%d,\"observes\":\"%s\"},\"6b\":{\"montant_unitaire\":%d,\"envoyes\":%d,"
          + "\"acceptes\":%d,\"cumul_accepte\":%d,\"premier_refus\":\"%s\"}}",
            n, rep6a, montant, envoyes, acceptes, cumul, premierRefus));
    }

    static String cleApresSession(Path journal) throws Exception {
        byte[] kEnc, kMac;
        try (Canal c = Canal.ouvrir()) {
            SfbAka.Session s = SfbAka.initier(c.in(), c.out(), moi, reg);
            SfbAka.ecrire(c.out(), SfbAka.scellerInstruction(s, s.prochainCompteur++, Mesure.pacs008(15_000)));
            SfbAka.lire(c.in());
            kEnc = s.kEnc.clone(); kMac = s.kMac.clone();
        }
        List<Path> etat = new ArrayList<>();
        if (Files.exists(journal)) etat.add(journal);
        try (var f = Files.walk(Mesure.cles)) { f.filter(Files::isRegularFile).forEach(etat::add); }
        for (Path p : etat) {
            byte[] b = Files.readAllBytes(p);
            String t = new String(b, StandardCharsets.ISO_8859_1).toLowerCase();
            for (byte[] k : new byte[][]{kEnc, kMac})
                if (indexOf(b, k) >= 0 || t.contains(java.util.HexFormat.of().formatHex(k))
                        || t.contains(java.util.Base64.getEncoder().encodeToString(k).toLowerCase()))
                    return "CLE_DE_SESSION_TROUVEE";
        }
        return "AUCUNE_TRACE_DE_CLE_DE_SESSION";
    }

    static int indexOf(byte[] h, byte[] n) {
        outer: for (int i = 0; i + n.length <= h.length; i++) { for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer; return i; }
        return -1;
    }
}
