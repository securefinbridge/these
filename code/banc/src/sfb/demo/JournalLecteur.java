package sfb.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Lit le journal d'audit de la passerelle (une entrée JSON par ligne, écrite par sfb.JournalChaine)
 * et en vérifie le chaînage : l'« empreinte_precedente » de chaque entrée doit être le SHA-256 de
 * la ligne qui la précède, la première partant de l'empreinte de genèse (64 zéros).
 *
 * Rien n'est gardé d'une lecture à l'autre : chaque appel relit le fichier en entier et recalcule
 * toutes les empreintes depuis la genèse. Une entrée ancienne modifiée après coup est donc vue à la
 * lecture suivante. La dernière entrée, elle, n'est protégée que par une tête publiée ailleurs.
 */
@Component
public class JournalLecteur {
    static final String GENESE = "0".repeat(64);
    static final int MEMOIRE = 100;                       // nombre d'entrées récentes rendues au plus
    private static final int LIGNE_MAX = 4096;            // une entrée du journal fait environ 300 octets
    private static final byte[] CLE = "\"empreinte_precedente\":\"".getBytes(StandardCharsets.US_ASCII);

    /**
     * liensVerifies : nombre d'entrées, depuis la genèse, dont le renvoi à la précédente est exact.
     * premiereRupture : numéro de la première entrée qui ne renvoie pas à l'empreinte de celle qui la
     * précède ; c'est alors l'entrée d'avant (ou ce renvoi) qui a été modifiée.
     */
    public record Lecture(long total, boolean chainageVerifie, long liensVerifies, Long premiereRupture,
                          String tete, List<Map<String, Object>> entrees) {}

    private record Ligne(long numero, byte[] octets, boolean tronquee, String empreinte, boolean chainee) {}

    private final Path fichier;
    private final ObjectMapper json;

    public JournalLecteur(Lanceur lanceur, ObjectMapper json) {
        this.fichier = lanceur.journal();
        this.json = json;
    }

    /** Les n dernières entrées, la plus récente d'abord, et l'état du chaînage sur tout le journal. */
    public synchronized Lecture lire(int n) throws IOException {
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        ArrayDeque<Ligne> recentes = new ArrayDeque<>();
        ByteArrayOutputStream debut = new ByteArrayOutputStream(512);     // début de la ligne en cours
        long total = 0, longueur = 0;
        Long rupture = null;                                              // numéro de la première entrée mal chaînée
        String tete = GENESE;

        try (InputStream f = Files.newInputStream(fichier)) {
            byte[] bloc = new byte[1 << 16];
            for (int lus; (lus = f.read(bloc)) > 0; ) {
                int depart = 0;
                for (int i = 0; i < lus; i++) {
                    if (bloc[i] != '\n') continue;
                    longueur += accumuler(sha, debut, bloc, depart, i - depart);
                    String empreinte = HexFormat.of().formatHex(sha.digest());          // remet aussi le calcul à zéro
                    byte[] octets = debut.toByteArray();
                    boolean chainee = tete.equals(empreintePrecedente(octets));
                    total++;
                    if (!chainee && rupture == null) rupture = total;
                    recentes.addLast(new Ligne(total, octets, longueur > LIGNE_MAX, empreinte, chainee));
                    if (recentes.size() > n) recentes.removeFirst();
                    tete = empreinte;
                    debut.reset(); longueur = 0; depart = i + 1;
                }
                longueur += accumuler(sha, debut, bloc, depart, lus - depart);
            }
        } catch (NoSuchFileException vide) { /* aucune décision encore journalisée */ }
        // Une dernière ligne sans saut de ligne est en cours d'écriture par la passerelle : elle est ignorée.

        List<Map<String, Object>> entrees = new ArrayList<>();
        for (Iterator<Ligne> it = recentes.descendingIterator(); it.hasNext(); ) entrees.add(decoder(it.next()));
        return new Lecture(total, rupture == null, rupture == null ? total : rupture - 1, rupture, tete, entrees);
    }

    private static int accumuler(MessageDigest sha, ByteArrayOutputStream debut, byte[] bloc, int depuis, int lg) {
        if (lg <= 0) return 0;
        sha.update(bloc, depuis, lg);
        int place = LIGNE_MAX - debut.size();
        if (place > 0) debut.write(bloc, depuis, Math.min(lg, place));
        return lg;
    }

    /** Valeur du champ empreinte_precedente telle qu'écrite par JournalChaine, ou null si elle manque. */
    private static String empreintePrecedente(byte[] ligne) {
        recherche:
        for (int i = 0; i + CLE.length + 64 <= ligne.length; i++) {
            for (int j = 0; j < CLE.length; j++) if (ligne[i + j] != CLE[j]) continue recherche;
            return new String(ligne, i + CLE.length, 64, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private Map<String, Object> decoder(Ligne l) {
        Map<String, Object> entree;
        try {
            if (l.tronquee()) throw new IOException("ligne trop longue");
            entree = json.readValue(l.octets(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException illisible) {
            entree = new LinkedHashMap<>();
            entree.put("illisible", true);
        }
        entree.put("numero", l.numero());
        entree.put("empreinte", l.empreinte());
        entree.put("chainee", l.chainee());
        return entree;
    }
}
