package sfb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Moteur de politique de la passerelle : règles déclaratives chargées depuis un
 * fichier (politique.properties), évaluées après l'intégrité et l'anti-rejeu.
 *
 *   plafond_par_ordre               montant maximal d'un ordre
 *   fenetre_secondes                fenêtre glissante des règles de vélocité
 *   ordres_max_par_fenetre          nombre d'ordres acceptés par partenaire et par fenêtre
 *   montant_cumule_max_par_fenetre  montant cumulé accepté par partenaire et par fenêtre
 *
 * Les règles de vélocité bornent ce qu'un partenaire légitime mais compromis
 * (scénario 6b) peut faire accepter avant d'être bloqué.
 */
public final class Politique {
    static final Pattern MONTANT = Pattern.compile("<IntrBkSttlmAmt[^>]*>(\\d+)</IntrBkSttlmAmt>");

    final long plafond, fenetreMs, ordresMax, cumulMax;
    private final Map<String, ArrayDeque<long[]>> historique = new HashMap<>();

    Politique(long plafond, long fenetreS, long ordresMax, long cumulMax) {
        this.plafond = plafond; this.fenetreMs = fenetreS * 1000; this.ordresMax = ordresMax; this.cumulMax = cumulMax;
    }

    static Politique charger(Path fichier) throws IOException {
        Properties p = new Properties();
        if (Files.exists(fichier))
            try (Reader r = Files.newBufferedReader(fichier, StandardCharsets.UTF_8)) { p.load(r); }
        else System.out.println("politique : " + fichier + " absent, valeurs par défaut");
        return new Politique(
                Long.parseLong(p.getProperty("plafond_par_ordre", "2000000").trim()),
                Long.parseLong(p.getProperty("fenetre_secondes", "60").trim()),
                Long.parseLong(p.getProperty("ordres_max_par_fenetre", "1200").trim()),
                Long.parseLong(p.getProperty("montant_cumule_max_par_fenetre", "20000000").trim()));
    }

    static long montant(byte[] pacs008) {
        Matcher m = MONTANT.matcher(new String(pacs008, StandardCharsets.UTF_8));
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Évalue un ordre ; s'il est accepté, il est compté dans la fenêtre du partenaire. */
    synchronized SfbAka.Motif evaluer(String partenaire, byte[] pacs008, long maintenantMs) {
        long montant = montant(pacs008);
        if (montant <= 0 || montant > plafond) return SfbAka.Motif.POLITIQUE_REFUSEE;
        ArrayDeque<long[]> h = historique.computeIfAbsent(partenaire, k -> new ArrayDeque<>());
        while (!h.isEmpty() && h.peekFirst()[0] <= maintenantMs - fenetreMs) h.pollFirst();
        long cumul = 0;
        for (long[] e : h) cumul += e[1];
        if (h.size() + 1 > ordresMax || cumul + montant > cumulMax) return SfbAka.Motif.VELOCITE_DEPASSEE;
        h.addLast(new long[]{maintenantMs, montant});
        return SfbAka.Motif.ACCEPTE;
    }
}
