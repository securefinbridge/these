package sfb.demo;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import sfb.Identites;

/**
 * Prépare et lance le banc en local : clés de démonstration, bouchon core banking, passerelle.
 *
 * Cette classe ne dépend pas de Spring et ne modifie rien au banc : elle lance
 * sfb.Passerelle telle quelle, dans son propre processus, en lui passant sa configuration
 * par les variables d'environnement qu'elle lit déjà (SFB_CLES, TLS_REP, SFB_PORT, ...).
 */
public final class Lanceur {
    private static final String MDP_TLS = "banc-sfb";       // même valeur que generer_cles.sh

    private final Path dossier;
    private final boolean etiquettesRole;
    private final String python;
    private int portSfb, portTls, portBouchon;
    private Process bouchon, passerelle;
    private Path politique;
    private Instant demarreLe = Instant.now();

    public Lanceur(Path dossier, int portSfb, int portTls, int portBouchon, boolean etiquettesRole, String python) {
        this.dossier = dossier.toAbsolutePath();
        this.portSfb = portSfb; this.portTls = portTls; this.portBouchon = portBouchon;
        this.etiquettesRole = etiquettesRole; this.python = python;
    }

    public int portSfb() { return portSfb; }
    public int portTls() { return portTls; }
    public int portBouchon() { return portBouchon; }
    public boolean etiquettesRole() { return etiquettesRole; }
    public Instant demarreLe() { return demarreLe; }
    public Path cles() { return dossier.resolve("cles"); }
    public Path journal() { return dossier.resolve("journal_passerelle.jsonl"); }

    // ---------- cycle de vie --------------------------------------------------------
    public void demarrer() throws Exception {
        Files.createDirectories(dossier);
        try {       // les clés privées de démonstration ne sont lisibles que par l'utilisateur du service
            Files.setPosixFilePermissions(dossier, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException sansDroitsPosix) { /* système de fichiers sans droits POSIX */ }
        preparerCles();
        archiverJournal();
        portBouchon = portLibre(portBouchon, "bouchon core banking");
        portSfb = portLibre(portSfb, "SFB-AKA");
        portTls = portLibre(portTls, "référence TLS 1.3");
        politique = trouver("politique.properties", "banc/politique.properties", "code/banc/politique.properties");
        Path script = trouver("../bouchon_cbs.py", "bouchon_cbs.py", "code/bouchon_cbs.py");

        bouchon = lancer("bouchon", Map.of(), List.of(python, script.toString(), String.valueOf(portBouchon)));
        attendre("le bouchon core banking", this::bouchonRepond, bouchon);

        Map<String, String> env = new TreeMap<>();
        env.put("SFB_CLES", cles().toString());
        env.put("TLS_REP", dossier.resolve("tls").toString());
        env.put("SFB_PORT", String.valueOf(portSfb));
        env.put("TLS_PORT", String.valueOf(portTls));
        env.put("SFB_CBS_URL", "http://127.0.0.1:" + portBouchon + "/pacs008");
        env.put("SFB_JOURNAL", journal().toString());
        env.put("SFB_POLITIQUE", politique.toString());
        env.put("SFB_ETIQUETTES_ROLE", String.valueOf(etiquettesRole));
        passerelle = lancer("passerelle", env, commandeJava("sfb.Passerelle"));
        attendre("la passerelle SFB-AKA", this::passerelleRepond, passerelle);

        demarreLe = Instant.now();
        System.out.printf("SecureFinBridge prêt : SFB-AKA sur le port %d, TLS 1.3 sur %d, bouchon sur %d, dossier %s%n",
                portSfb, portTls, portBouchon, dossier);
    }

    public void arreter() {
        for (Process p : new Process[]{passerelle, bouchon}) if (p != null) p.destroy();
    }

    // ---------- état ----------------------------------------------------------------
    public boolean passerelleRepond() {
        if (passerelle == null || !passerelle.isAlive()) return false;
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", portSfb), 500); return true; }
        catch (IOException e) { return false; }
    }

    public boolean bouchonRepond() {
        if (bouchon == null || !bouchon.isAlive()) return false;
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + portBouchon + "/sante").toURL().openConnection();
            c.setConnectTimeout(500); c.setReadTimeout(1000);
            int code = c.getResponseCode(); c.disconnect();
            return code == 200;
        } catch (IOException e) { return false; }
    }

    /** Règles de politique en vigueur, telles que la passerelle les lit. */
    public Map<String, String> politique() {
        Map<String, String> regles = new TreeMap<>();
        if (politique == null) return regles;
        Properties p = new Properties();
        try (var f = Files.newBufferedReader(politique)) { p.load(f); } catch (IOException e) { return regles; }
        p.forEach((k, v) -> regles.put(k.toString(), v.toString()));
        return regles;
    }

    // ---------- clés de démonstration -------------------------------------------------
    /**
     * Mêmes clés que generer_cles.sh : identités ECDSA P-256 et PKI TLS 1.3 (autorité, serveur, client).
     * Elles sont régénérées à chaque démarrage : des clés de démonstration ne survivent pas à un
     * redémarrage, même si le dossier de travail, lui, a été conservé.
     */
    private void preparerCles() throws Exception {
        Path cles = cles(), tls = dossier.resolve("tls");
        for (Path rep : new Path[]{cles, tls}) {
            if (Files.exists(rep)) try (var fichiers = Files.list(rep)) { for (Path f : fichiers.toList()) Files.delete(f); }
            Files.createDirectories(rep);
        }
        Identites.generer(cles, "plateforme", "passerelle");
        keytool(tls, "-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=SFB-AC",
                "-ext", "bc:c", "-validity", "3650", "-keystore", "ca.p12");
        keytool(tls, "-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-rfc", "-file", "ca.pem");
        for (String nom : new String[]{"serveur", "client"}) {
            keytool(tls, "-genkeypair", "-alias", nom, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + nom,
                    "-validity", "3650", "-keystore", nom + ".p12");
            keytool(tls, "-certreq", "-alias", nom, "-keystore", nom + ".p12", "-file", nom + ".csr");
            keytool(tls, "-gencert", "-alias", "ca", "-keystore", "ca.p12", "-infile", nom + ".csr", "-outfile", nom + ".pem",
                    "-rfc", "-validity", "3650", "-ext", "san=dns:passerelle,dns:localhost,ip:127.0.0.1");
            keytool(tls, "-importcert", "-alias", "ca", "-file", "ca.pem", "-keystore", nom + ".p12");
            keytool(tls, "-importcert", "-alias", nom, "-file", nom + ".pem", "-keystore", nom + ".p12");
            Files.deleteIfExists(tls.resolve(nom + ".csr"));
        }
        keytool(tls, "-importcert", "-alias", "ca", "-file", "ca.pem", "-keystore", "confiance.p12");
    }

    /**
     * La passerelle repart de l'empreinte de genèse à chaque démarrage (JournalChaine) tout en
     * écrivant à la suite du fichier : le journal du démarrage précédent est donc mis de côté,
     * sans quoi le chaînage serait rompu à la jointure.
     */
    private void archiverJournal() throws IOException {
        if (Files.exists(journal()))
            Files.move(journal(), dossier.resolve("journal_passerelle.precedent.jsonl"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void keytool(Path rep, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        cmd.addAll(List.of("-storepass", MDP_TLS, "-noprompt"));
        Process p = new ProcessBuilder(cmd).directory(rep.toFile()).redirectErrorStream(true).start();
        String sortie = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException("keytool " + args[0] + " a échoué : " + sortie);
    }

    // ---------- processus -------------------------------------------------------------
    private Process lancer(String nom, Map<String, String> env, List<String> commande) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(commande).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(dossier.resolve(nom + ".log").toFile()));
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().putAll(env);
        return pb.start();
    }

    /**
     * Commande qui lance une classe du banc dans un processus à part. Depuis Maven ou un IDE,
     * le chemin de classes courant suffit. Depuis le jar exécutable de Spring Boot, les classes
     * sont rangées dans BOOT-INF : on passe alors par le lanceur de Spring Boot.
     */
    private static List<String> commandeJava(String classe) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        boolean jarExecutable = !cp.contains(File.pathSeparator) && cp.endsWith(".jar");
        if (jarExecutable)
            return List.of(java, "-cp", cp, "-Dloader.main=" + classe, "org.springframework.boot.loader.launch.PropertiesLauncher");
        return List.of(java, "-cp", cp, classe);
    }

    private void attendre(String quoi, java.util.function.BooleanSupplier pret, Process p) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (pret.getAsBoolean()) return;
            if (!p.isAlive()) break;
            Thread.sleep(100);
        }
        arreter();
        for (String nom : new String[]{"bouchon", "passerelle"}) afficherFin(dossier.resolve(nom + ".log"));
        throw new IllegalStateException("Impossible de démarrer " + quoi + ". Voir les journaux dans " + dossier);
    }

    /** Recopie la fin du journal d'un processus sur la sortie d'erreur : c'est elle que montre `kubectl logs`. */
    private static void afficherFin(Path fichier) {
        try {
            List<String> lignes = Files.readAllLines(fichier);
            System.err.println("--- " + fichier.getFileName() + " (40 dernières lignes) ---");
            lignes.subList(Math.max(0, lignes.size() - 40), lignes.size()).forEach(System.err::println);
        } catch (IOException | RuntimeException absent) { /* pas de journal : rien à montrer */ }
    }

    /** Renvoie le port demandé s'il est libre, sinon le premier port libre qui suit, en le signalant. */
    private static int portLibre(int souhaite, String usage) {
        for (int port = souhaite; port < souhaite + 50; port++) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port));
                if (port != souhaite)
                    System.out.printf("Port %d occupé (%s) : utilisation du port %d.%n", souhaite, usage, port);
                return port;
            } catch (IOException occupe) { /* essayer le suivant */ }
        }
        throw new IllegalStateException("Aucun port libre à partir de " + souhaite + " pour " + usage);
    }

    /** Cherche un fichier du banc, que l'application soit lancée depuis code/banc, code ou la racine du dépôt. */
    private static Path trouver(String... candidats) {
        for (String c : candidats) {
            Path p = Path.of(c).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException("Fichier introuvable (" + String.join(", ", candidats)
                + "). Lancez l'application depuis le dossier code/banc.");
    }
}
