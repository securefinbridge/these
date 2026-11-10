package sfb;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/**
 * Identités à long terme et registre des partenaires (exigence E1).
 * Chaque partenaire a un identifiant de 16 octets et une bi-clé ECDSA P-256 ;
 * son empreinte est le SHA-256 de sa clé publique encodée.
 * Les clés sont stockées dans un répertoire : <nom>.pub (X.509) et <nom>.key (PKCS#8).
 */
public final class Identites {
    private Identites() {}

    public record Identite(String nom, byte[] id, PrivateKey sk, PublicKey pk, byte[] emp) {}

    public static byte[] idDe(String nom) {
        byte[] b = Arrays.copyOf(nom.getBytes(StandardCharsets.US_ASCII), 16);
        return b;
    }

    public static byte[] empreinte(PublicKey pk) {
        try { return MessageDigest.getInstance("SHA-256").digest(pk.getEncoded()); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static void generer(Path rep, String... noms) throws Exception {
        Files.createDirectories(rep);
        for (String nom : noms) {
            KeyPair p = Crypto.ecdsaPaire();
            Files.write(rep.resolve(nom + ".pub"), p.getPublic().getEncoded());
            Files.write(rep.resolve(nom + ".key"), p.getPrivate().getEncoded());
        }
    }

    public static Identite charger(Path rep, String nom) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey pk = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(rep.resolve(nom + ".pub"))));
        PrivateKey sk = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(rep.resolve(nom + ".key"))));
        return new Identite(nom, idDe(nom), sk, pk, empreinte(pk));
    }

    /** Registre immuable : identifiant -> clé publique et empreinte -> clé publique. */
    public static final class Registre {
        private final Map<String, PublicKey> parId = new HashMap<>();
        private final Map<String, PublicKey> parEmp = new HashMap<>();

        public Registre(Path rep, String... noms) throws Exception {
            KeyFactory kf = KeyFactory.getInstance("EC");
            for (String nom : noms) {
                PublicKey pk = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(rep.resolve(nom + ".pub"))));
                parId.put(HexFormat.of().formatHex(idDe(nom)), pk);
                parEmp.put(HexFormat.of().formatHex(empreinte(pk)), pk);
            }
        }
        public PublicKey parIdentifiant(byte[] id) { return parId.get(HexFormat.of().formatHex(id)); }
        public PublicKey parEmpreinte(byte[] emp) { return parEmp.get(HexFormat.of().formatHex(emp)); }
    }

    /** Génère les clés du banc : java sfb.Identites <répertoire> <nom>... */
    public static void main(String[] a) throws Exception {
        generer(Path.of(a[0]), Arrays.copyOfRange(a, 1, a.length));
    }
}
