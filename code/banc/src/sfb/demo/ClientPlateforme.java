package sfb.demo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import sfb.Comptage;
import sfb.Crypto;
import sfb.Identites;
import sfb.SfbAka;

/**
 * La console tient le rôle de la plateforme Mobile Money : elle détient la clé « plateforme »,
 * ouvre de vraies sessions SFB-AKA vers la passerelle locale et y envoie des ordres scellés.
 *
 * Le protocole n'est pas réécrit ici : la poignée de main est SfbAka.initier, le scellement
 * SfbAka.scellerInstruction, le socket est le Comptage du client de mesure (Mesure.sessionSfb).
 * Les messages sont enregistrés au passage pour être montrés ; rien n'est écrit en dur.
 */
@Component
public class ClientPlateforme {
    static final Pattern COMPTE = Pattern.compile("[A-Za-z0-9]{4,24}");
    static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9-]{1,40}");
    /** La console laisse passer jusqu'à dix fois le plafond : au-delà du plafond, c'est la politique qui refuse. */
    static final int FACTEUR_BORNE = 10;
    private static final int DELAI_CONNEXION_MS = 2000, DELAI_LECTURE_MS = 5000;
    private static final HexFormat HEX = HexFormat.of();

    private final Lanceur lanceur;
    private final Identites.Identite moi;
    private final Identites.Registre registre;
    private final long plafond;

    public ClientPlateforme(Lanceur lanceur) throws Exception {
        this.lanceur = lanceur;
        this.moi = Identites.charger(lanceur.cles(), "plateforme");
        this.registre = new Identites.Registre(lanceur.cles(), "passerelle");
        this.plafond = Long.parseLong(lanceur.politique().getOrDefault("plafond_par_ordre", "2000000").trim());
    }

    public long plafond() { return plafond; }
    public long montantMax() { return plafond * FACTEUR_BORNE; }

    public record Champ(String nom, int octets, String hex) {}
    public record Message(String nom, String sens, int octets, List<Champ> champs) {}
    public record Poignee(String idSession, Map<String, Integer> tailles, List<Message> messages, int octetsMessages,
                          int octetsCadrage, long octetsSurLeFil, double dureeMs) {}
    public record Accuse(boolean accepte, String statut, String motif, String idSession, long compteur,
                         String reference, long montant, String empreinteInstruction, int octetsInstruction,
                         double dureePoigneeMs, double dureeTotaleMs) {}

    /** Connexion TCP à la passerelle ; les octets du protocole sont recopiés au passage. */
    private static final class Canal implements Closeable {
        final Comptage socket = new Comptage();
        final ByteArrayOutputStream emis = new ByteArrayOutputStream(), recus = new ByteArrayOutputStream();
        final DataInputStream in;
        final DataOutputStream out;

        Canal(int port) throws IOException {
            socket.connect(new InetSocketAddress("127.0.0.1", port), DELAI_CONNEXION_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(DELAI_LECTURE_MS);
            in = new DataInputStream(new FilterInputStream(new BufferedInputStream(socket.getInputStream())) {
                @Override public int read() throws IOException { int b = super.read(); if (b >= 0) recus.write(b); return b; }
                @Override public int read(byte[] b, int o, int l) throws IOException {
                    int n = super.read(b, o, l); if (n > 0) recus.write(b, o, n); return n; }
            });
            OutputStream sortie = new BufferedOutputStream(socket.getOutputStream());
            out = new DataOutputStream(new FilterOutputStream(sortie) {
                @Override public void write(int b) throws IOException { emis.write(b); sortie.write(b); }
                @Override public void write(byte[] b, int o, int l) throws IOException { emis.write(b, o, l); sortie.write(b, o, l); }
            });
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    /** Ouvre une session SFB-AKA, relève les trois messages échangés, puis referme la connexion. */
    public Poignee ouvrirSession() throws Exception {
        long t0 = System.nanoTime();
        try (Canal c = new Canal(lanceur.portSfb())) {
            SfbAka.Session s = SfbAka.initier(c.in, c.out, moi, registre);
            long t1 = System.nanoTime();
            long surLeFil = c.socket.envoyes + c.socket.recus;
            String sid = HEX.formatHex(s.sid);
            effacer(s);

            // M1 et M3 ont été émis, M2 reçu ; chacun est précédé de sa longueur sur 2 octets.
            DataInputStream e = new DataInputStream(new ByteArrayInputStream(c.emis.toByteArray()));
            byte[] m1 = SfbAka.lire(e), m3 = SfbAka.lire(e);
            byte[] m2 = SfbAka.lire(new DataInputStream(new ByteArrayInputStream(c.recus.toByteArray())));
            List<Message> messages = List.of(
                new Message("M1", "plateforme → passerelle", m1.length, decouper(m1,
                    "id_I (identifiant de l'initiateur)", 16, "X (part éphémère X25519)", 32, "n_I (aléa)", 32,
                    "suites proposées", 8)),
                new Message("M2", "passerelle → plateforme", m2.length, decouper(m2,
                    "Y (part éphémère X25519)", 32, "n_R (aléa)", 32, "suite retenue", 2,
                    "emp_R (empreinte de la clé de la passerelle)", 32, "Sig_R (signature ECDSA P-256)", 64)),
                new Message("M3", "plateforme → passerelle", m3.length, decouper(m3,
                    "emp_I (empreinte de la clé de la plateforme)", 32, "Sig_I (signature ECDSA P-256)", 64,
                    "preuve de possession de clé (HMAC-SHA256)", 32)));
            Map<String, Integer> tailles = new LinkedHashMap<>();
            for (Message m : messages) tailles.put(m.nom(), m.octets());
            int octetsMessages = m1.length + m2.length + m3.length;
            int cadrage = c.emis.size() + c.recus.size() - octetsMessages;
            return new Poignee(sid, tailles, messages, octetsMessages, cadrage, surLeFil, ms(t0, t1));
        }
    }

    /** Ouvre une session, scelle un ordre pacs.008, l'envoie et renvoie la décision de la passerelle. */
    public Accuse envoyerOrdre(long montant, String compteDebiteur, String compteCrediteur, String reference)
            throws Exception {
        String ref = reference != null ? reference : "E2E-" + UUID.randomUUID();
        byte[] ordre = pacs008(ref, montant, compteDebiteur, compteCrediteur);
        long t0 = System.nanoTime();
        try (Canal c = new Canal(lanceur.portSfb())) {
            SfbAka.Session s = SfbAka.initier(c.in, c.out, moi, registre);
            long t1 = System.nanoTime();
            long compteur = s.prochainCompteur++;
            byte[] trame = SfbAka.scellerInstruction(s, compteur, ordre);
            SfbAka.ecrire(c.out, trame);
            byte[] r = SfbAka.lire(c.in);
            long t2 = System.nanoTime();
            String sid = HEX.formatHex(s.sid);
            effacer(s);
            // Réponse de la passerelle : statut (0 accepté, 1 rejeté) | motif | deux temps CPU (2 x 8 octets)
            boolean lisible = r.length == 18 && r[1] >= 0 && r[1] < SfbAka.Motif.values().length;
            boolean accepte = lisible && r[0] == 0;
            String motif = lisible ? SfbAka.Motif.values()[r[1]].name() : "MESSAGE_" + r.length + "_OCTETS";
            return new Accuse(accepte, accepte ? "ACCP" : "RJCT", motif, sid, compteur, ref, montant,
                    HEX.formatHex(Crypto.sha256(ordre)), trame.length, ms(t0, t1), ms(t0, t2));
        }
    }

    /** Même gabarit que Mesure.pacs008, avec la référence, le montant et les comptes demandés. */
    static byte[] pacs008(String reference, long montant, String debiteur, String crediteur) {
        if (!REFERENCE.matcher(reference).matches() || !COMPTE.matcher(debiteur).matches()
                || !COMPTE.matcher(crediteur).matches() || montant < 1)
            throw new IllegalArgumentException("ordre hors gabarit");
        return ("<?xml version=\"1.0\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">"
              + "<FIToFICstmrCdtTrf><CdtTrfTxInf><PmtId><EndToEndId>" + reference + "</EndToEndId></PmtId>"
              + "<IntrBkSttlmAmt Ccy=\"XOF\">" + montant + "</IntrBkSttlmAmt>"
              + "<DbtrAcct><Id><Othr><Id>" + debiteur + "</Id></Othr></Id></DbtrAcct>"
              + "<CdtrAcct><Id><Othr><Id>" + crediteur + "</Id></Othr></Id></CdtrAcct>"
              + "</CdtTrfTxInf></FIToFICstmrCdtTrf></Document>").getBytes(StandardCharsets.UTF_8);
    }

    /** Découpe un message selon les champs annoncés ; ce qui dépasserait est montré tel quel. */
    private static List<Champ> decouper(byte[] m, Object... nomsEtTailles) {
        List<Champ> champs = new ArrayList<>();
        int debut = 0;
        for (int i = 0; i < nomsEtTailles.length && debut < m.length; i += 2) {
            int lg = Math.min((Integer) nomsEtTailles[i + 1], m.length - debut);
            champs.add(new Champ((String) nomsEtTailles[i], lg, HEX.formatHex(m, debut, debut + lg)));
            debut += lg;
        }
        if (debut < m.length) champs.add(new Champ("octets non attendus", m.length - debut, HEX.formatHex(m, debut, m.length)));
        return champs;
    }

    /** Les clés de session ne servent qu'à cet échange : elles sont effacées dès la réponse reçue. */
    private static void effacer(SfbAka.Session s) {
        Arrays.fill(s.kEnc, (byte) 0);
        Arrays.fill(s.kMac, (byte) 0);
    }

    private static double ms(long debut, long fin) { return Math.round((fin - debut) / 1e3) / 1e3; }
}
