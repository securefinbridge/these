package sfb;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.*;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Primitives cryptographiques de SFB-AKA, toutes issues du JDK 21
 * (aucune dépendance externe) : X25519, ECDSA P-256 (format P1363, 64 octets),
 * HKDF-SHA256 (RFC 5869), HMAC-SHA256, AES-128-GCM, SHA-256.
 *
 * Chaque appel est chronométré en temps CPU du fil d'exécution courant :
 * c'est la grandeur « temps CPU imputable aux primitives » de l'annexe D.
 */
public final class Crypto {
    private Crypto() {}

    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<long[]> CPU = ThreadLocal.withInitial(() -> new long[1]);
    private static final SecureRandom ALEA = new SecureRandom();

    /** Remet à zéro le compteur de temps CPU cryptographique du fil courant. */
    public static void razCpu() { CPU.get()[0] = 0; }
    /** Temps CPU cumulé (ns) passé dans les primitives depuis le dernier razCpu. */
    public static long cpuNs() { return CPU.get()[0]; }

    private static long t0() { return MX.getCurrentThreadCpuTime(); }
    private static void t1(long debut) { CPU.get()[0] += MX.getCurrentThreadCpuTime() - debut; }

    public static byte[] alea(int n) { byte[] b = new byte[n]; ALEA.nextBytes(b); return b; }

    // ---------- X25519 ---------------------------------------------------------
    public static KeyPair x25519Paire() {
        long d = t0();
        try { return KeyPairGenerator.getInstance("X25519").generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { t1(d); }
    }

    /** Clé publique X25519 brute (32 octets, petit-boutiste, RFC 7748). */
    public static byte[] x25519Brut(PublicKey pk) {
        byte[] enc = pk.getEncoded();                 // SubjectPublicKeyInfo : 12 + 32 octets
        byte[] b = new byte[32];
        System.arraycopy(enc, enc.length - 32, b, 0, 32);
        return b;
    }

    public static PublicKey x25519DepuisBrut(byte[] brut) throws GeneralSecurityException {
        byte[] prefixe = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00};
        byte[] enc = new byte[44];
        System.arraycopy(prefixe, 0, enc, 0, 12);
        System.arraycopy(brut, 0, enc, 12, 32);
        return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(enc));
    }

    public static byte[] x25519Accord(PrivateKey sk, PublicKey pk) throws GeneralSecurityException {
        long d = t0();
        try {
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(sk);
            ka.doPhase(pk, true);
            return ka.generateSecret();
        } finally { t1(d); }
    }

    // ---------- ECDSA P-256 ----------------------------------------------------
    public static KeyPair ecdsaPaire() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    /** Signature ECDSA P-256 au format brut r||s (64 octets). */
    public static byte[] signer(PrivateKey sk, byte[]... parties) throws GeneralSecurityException {
        long d = t0();
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(sk);
            for (byte[] p : parties) s.update(p);
            return s.sign();
        } finally { t1(d); }
    }

    public static boolean verifier(PublicKey pk, byte[] sig, byte[]... parties) throws GeneralSecurityException {
        long d = t0();
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(pk);
            for (byte[] p : parties) s.update(p);
            return s.verify(sig);
        } catch (SignatureException e) {
            return false;
        } finally { t1(d); }
    }

    // ---------- Condensats, MAC, HKDF -----------------------------------------
    public static byte[] sha256(byte[]... parties) {
        long d = t0();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parties) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        finally { t1(d); }
    }

    public static byte[] hmac(byte[] cle, byte[]... parties) {
        long d = t0();
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(cle, "HmacSHA256"));
            for (byte[] p : parties) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { t1(d); }
    }

    /** HKDF-Extract (RFC 5869). */
    public static byte[] hkdfExtract(byte[] sel, byte[] ikm) { return hmac(sel, ikm); }

    /** HKDF-Expand (RFC 5869) pour une sortie d'au plus 32 octets. */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int longueur) {
        byte[] t = hmac(prk, info, new byte[]{1});
        byte[] r = new byte[longueur];
        System.arraycopy(t, 0, r, 0, longueur);
        return r;
    }

    /** Comparaison en temps constant. */
    public static boolean egaux(byte[] a, byte[] b) { return MessageDigest.isEqual(a, b); }

    // ---------- AES-128-GCM ----------------------------------------------------
    public static byte[] chiffrer(byte[] cle, byte[] nonce, byte[] aad, byte[] clair) throws GeneralSecurityException {
        long d = t0();
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cle, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(clair);
        } finally { t1(d); }
    }

    /** Déchiffre ; renvoie null si l'étiquette d'authentification est invalide. */
    public static byte[] dechiffrer(byte[] cle, byte[] nonce, byte[] aad, byte[] chiffre) {
        long d = t0();
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cle, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(chiffre);
        } catch (GeneralSecurityException e) {
            return null;
        } finally { t1(d); }
    }

    public static byte[] u64(long v) { return ByteBuffer.allocate(8).putLong(v).array(); }
    public static byte[] concat(byte[]... parties) {
        int n = 0; for (byte[] p : parties) n += p.length;
        ByteBuffer b = ByteBuffer.allocate(n);
        for (byte[] p : parties) b.put(p);
        return b.array();
    }
}
