package sfb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

/**
 * Protocole d'établissement de session SFB-AKA (chapitre 6 du mémoire) et
 * traitement des instructions.
 *
 *   M1  I -> R : id_I (16) | X (32) | n_I (32) | suites (2 x 4)                 = 88 octets
 *   M2  R -> I : Y (32) | n_R (32) | suite (2) | emp_R (32) | Sig_R (64)        = 162 octets
 *   M3  I -> R : emp_I (32) | Sig_I (64) | MAC_kmac("fin" | H(tr)) (32)         = 128 octets
 *
 *   tr   = "tr" | id_I | X | n_I | suites | Y | n_R | suite | emp_R
 *   Sig_R = ECDSA(sk_R, "sfb-aka/tr/R" | H(tr)) ; Sig_I = ECDSA(sk_I, "sfb-aka/tr/I" | H(tr))
 *   k    = HKDF-Extract(n_I | n_R, X25519(x, Y)) ;
 *   k_enc = HKDF-Expand(k, "sfb/enc" | H(tr), 16) ; k_mac = HKDF-Expand(k, "sfb/mac" | H(tr), 32)
 *
 * Chaque message est précédé de sa longueur sur 2 octets (6 octets de cadrage au total).
 * Le mode sans étiquettes de rôle (version 1, vulnérable à la réflexion, §7.3.4)
 * n'est disponible que pour la démonstration de l'attaque.
 */
public final class SfbAka {
    private SfbAka() {}

    public static final byte[] SUITE_REFERENCE = {0x00, 0x01};          // X25519-ECDSA_P256-SHA256-AES128GCM
    public static final byte[] SUITES_PROPOSEES = {0x00, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04};
    private static final byte[] ETQ_R = "sfb-aka/tr/R".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ETQ_I = "sfb-aka/tr/I".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ETQ_V1 = "sfb-aka/tr".getBytes(StandardCharsets.US_ASCII);

    /** Motifs de rejet journalisés (chapitre 9). */
    public enum Motif { ACCEPTE, REGISTRE_INCONNU, SIGNATURE_INVALIDE, PREUVE_INVALIDE, SUITE_REFUSEE,
                        INTEGRITE_COMPROMISE, REJEU_DETECTE, POLITIQUE_REFUSEE, CORE_BANKING_REJET,
                        VELOCITE_DEPASSEE, DOUBLON_IDEMPOTENT }

    public static final class Rejet extends Exception {
        public final Motif motif;
        public Rejet(Motif m) { super(m.name()); this.motif = m; }
    }

    /** État d'une session établie. */
    public static final class Session {
        public final byte[] sid, kEnc, kMac, idPartenaire;
        public long dernierCompteurAccepte = 0;   // côté répondant
        public long prochainCompteur = 1;         // côté initiateur
        /** Idempotence (côté répondant) : dernière trame authentique traitée et son statut. */
        public byte[] derniereTrame;
        public int dernierStatut;
        public Session(byte[] sid, byte[] kEnc, byte[] kMac, byte[] idPartenaire) {
            this.sid = sid; this.kEnc = kEnc; this.kMac = kMac; this.idPartenaire = idPartenaire;
        }
    }

    // ---------- cadrage ---------------------------------------------------------
    public static void ecrire(DataOutputStream out, byte[] m) throws IOException {
        out.writeShort(m.length); out.write(m); out.flush();
    }
    public static byte[] lire(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort(); byte[] m = new byte[n]; in.readFully(m); return m;
    }

    private static byte[] tranche(byte[] b, int debut, int lg) { return Arrays.copyOfRange(b, debut, debut + lg); }

    private static byte[] transcription(byte[] idI, byte[] X, byte[] nI, byte[] suites,
                                        byte[] Y, byte[] nR, byte[] suite, byte[] empR) {
        return Crypto.sha256("tr".getBytes(StandardCharsets.US_ASCII), idI, X, nI, suites, Y, nR, suite, empR);
    }

    private static Session deriver(byte[] secretDH, byte[] nI, byte[] nR, byte[] hTr, byte[] idPartenaire) {
        byte[] k = Crypto.hkdfExtract(Crypto.concat(nI, nR), secretDH);
        byte[] kEnc = Crypto.hkdfExpand(k, Crypto.concat("sfb/enc".getBytes(StandardCharsets.US_ASCII), hTr), 16);
        byte[] kMac = Crypto.hkdfExpand(k, Crypto.concat("sfb/mac".getBytes(StandardCharsets.US_ASCII), hTr), 32);
        byte[] sid = tranche(Crypto.sha256("sid".getBytes(StandardCharsets.US_ASCII), hTr), 0, 8);
        Arrays.fill(k, (byte) 0);
        return new Session(sid, kEnc, kMac, idPartenaire);
    }

    // ---------- Initiateur (plateforme Mobile Money) ----------------------------
    public static Session initier(DataInputStream in, DataOutputStream out, Identites.Identite moi,
                                  Identites.Registre registre) throws IOException, GeneralSecurityException, Rejet {
        KeyPair eph = Crypto.x25519Paire();
        byte[] X = Crypto.x25519Brut(eph.getPublic());
        byte[] nI = Crypto.alea(32);
        ecrire(out, Crypto.concat(moi.id(), X, nI, SUITES_PROPOSEES));                         // M1

        byte[] m2 = lire(in);
        if (m2.length != 162) throw new Rejet(Motif.SIGNATURE_INVALIDE);
        byte[] Y = tranche(m2, 0, 32), nR = tranche(m2, 32, 32), suite = tranche(m2, 64, 2);
        byte[] empR = tranche(m2, 66, 32), sigR = tranche(m2, 98, 64);
        PublicKey pkR = registre.parEmpreinte(empR);
        if (pkR == null) throw new Rejet(Motif.REGISTRE_INCONNU);
        if (!Arrays.equals(suite, SUITE_REFERENCE)) throw new Rejet(Motif.SUITE_REFUSEE);         // E10
        byte[] hTr = transcription(moi.id(), X, nI, SUITES_PROPOSEES, Y, nR, suite, empR);
        if (!Crypto.verifier(pkR, sigR, ETQ_R, hTr)) throw new Rejet(Motif.SIGNATURE_INVALIDE);   // P1b

        byte[] dh = Crypto.x25519Accord(eph.getPrivate(), Crypto.x25519DepuisBrut(Y));
        Session s = deriver(dh, nI, nR, hTr, empR);
        byte[] sigI = Crypto.signer(moi.sk(), ETQ_I, hTr);
        byte[] preuve = Crypto.hmac(s.kMac, "fin".getBytes(StandardCharsets.US_ASCII), hTr);
        ecrire(out, Crypto.concat(moi.emp(), sigI, preuve));                                      // M3
        return s;
    }

    // ---------- Répondant (passerelle / core banking) ----------------------------
    public static Session repondre(DataInputStream in, DataOutputStream out, Identites.Identite moi,
                                   Identites.Registre registre, boolean etiquettesRole)
            throws IOException, GeneralSecurityException, Rejet {
        byte[] m1 = lire(in);
        if (m1.length != 88) throw new Rejet(Motif.REGISTRE_INCONNU);
        byte[] idI = tranche(m1, 0, 16), X = tranche(m1, 16, 32), nI = tranche(m1, 48, 32);
        byte[] suites = tranche(m1, 80, 8);
        PublicKey pkI = registre.parIdentifiant(idI);
        if (pkI == null) throw new Rejet(Motif.REGISTRE_INCONNU);                                  // E1, P1a
        boolean propose = false;
        for (int i = 0; i < suites.length; i += 2)
            if (suites[i] == SUITE_REFERENCE[0] && suites[i + 1] == SUITE_REFERENCE[1]) propose = true;
        if (!propose) throw new Rejet(Motif.SUITE_REFUSEE);

        KeyPair eph = Crypto.x25519Paire();
        byte[] Y = Crypto.x25519Brut(eph.getPublic());
        byte[] nR = Crypto.alea(32);
        byte[] hTr = transcription(idI, X, nI, suites, Y, nR, SUITE_REFERENCE, moi.emp());
        byte[] sigR = Crypto.signer(moi.sk(), etiquettesRole ? ETQ_R : ETQ_V1, hTr);
        byte[] dh = Crypto.x25519Accord(eph.getPrivate(), Crypto.x25519DepuisBrut(X));
        Session s = deriver(dh, nI, nR, hTr, idI);
        ecrire(out, Crypto.concat(Y, nR, SUITE_REFERENCE, moi.emp(), sigR));                       // M2

        byte[] m3 = lire(in);
        if (m3.length != 128) throw new Rejet(Motif.SIGNATURE_INVALIDE);
        byte[] empI = tranche(m3, 0, 32), sigI = tranche(m3, 32, 64), preuve = tranche(m3, 96, 32);
        if (!Crypto.egaux(empI, Identites.empreinte(pkI))) throw new Rejet(Motif.REGISTRE_INCONNU);
        if (!Crypto.verifier(pkI, sigI, etiquettesRole ? ETQ_I : ETQ_V1, hTr))                    // P1a
            throw new Rejet(Motif.SIGNATURE_INVALIDE);
        byte[] attendue = Crypto.hmac(s.kMac, "fin".getBytes(StandardCharsets.US_ASCII), hTr);
        if (!Crypto.egaux(preuve, attendue)) throw new Rejet(Motif.PREUVE_INVALIDE);
        return s;
    }

    // ---------- Instructions : sid | c | nonce | AES-GCM(instr) | MAC ------------
    public static byte[] scellerInstruction(Session s, long compteur, byte[] clair) throws GeneralSecurityException {
        byte[] c = Crypto.u64(compteur);
        byte[] nonce = Crypto.concat(new byte[4], c);
        byte[] chiffre = Crypto.chiffrer(s.kEnc, nonce, Crypto.concat(s.sid, c), clair);
        byte[] mac = Crypto.hmac(s.kMac, "instr".getBytes(StandardCharsets.US_ASCII), s.sid, c, Crypto.sha256(clair));
        return Crypto.concat(s.sid, c, chiffre, mac);
    }

    public record Instruction(long compteur, byte[] clair) {}

    /**
     * Ordre des contrôles identique à la règle R_Recoit_Instruction du modèle
     * Tamarin : intégrité (P3), puis fraîcheur (P4). La politique et la
     * journalisation (P5) sont appliquées ensuite par la passerelle.
     */
    public static Instruction ouvrirInstruction(Session s, byte[] trame) throws Rejet {
        if (trame.length < 8 + 8 + 16 + 32) throw new Rejet(Motif.INTEGRITE_COMPROMISE);
        byte[] sid = tranche(trame, 0, 8), c = tranche(trame, 8, 8);
        byte[] chiffre = tranche(trame, 16, trame.length - 16 - 32);
        byte[] mac = tranche(trame, trame.length - 32, 32);
        if (!Crypto.egaux(sid, s.sid)) throw new Rejet(Motif.INTEGRITE_COMPROMISE);
        byte[] clair = Crypto.dechiffrer(s.kEnc, Crypto.concat(new byte[4], c), Crypto.concat(sid, c), chiffre);
        if (clair == null) throw new Rejet(Motif.INTEGRITE_COMPROMISE);                            // P3
        byte[] attendu = Crypto.hmac(s.kMac, "instr".getBytes(StandardCharsets.US_ASCII), sid, c, Crypto.sha256(clair));
        if (!Crypto.egaux(mac, attendu)) throw new Rejet(Motif.INTEGRITE_COMPROMISE);              // P3
        long compteur = java.nio.ByteBuffer.wrap(c).getLong();
        if (compteur <= s.dernierCompteurAccepte) throw new Rejet(Motif.REJEU_DETECTE);            // P4
        return new Instruction(compteur, clair);
    }
}
