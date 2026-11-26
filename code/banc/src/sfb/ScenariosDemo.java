package sfb;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mince adaptateur pour le démonstrateur (paquet sfb.demo) : il donne accès aux scénarios
 * du banc d'attaque, qui sont en visibilité paquet, sans modifier Attaques ni Mesure.
 *
 * Aucune logique d'attaque ici : chaque scénario appelle tel quel le code de sfb.Attaques
 * utilisé pour la campagne du chapitre 9. Java pur, aucune dépendance.
 */
public final class ScenariosDemo {
    private ScenariosDemo() {}

    /** Un scénario exposé : son nom dans le banc, ce qu'il fait et le motif attendu. */
    public record Scenario(String nom, String reference, String description, String attendu) {}

    /** Issue d'une exécution : le motif observé est celui que renvoie le code du banc. */
    public record Issue(String scenario, String reference, String description, String attendu,
                        String observe, boolean conforme, String remarque) {}

    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();
    static {
        for (Scenario s : new Scenario[]{
            new Scenario("rejeu", "2_rejeu_instruction",
                "Dans une session légitime, une instruction déjà acceptée puis dépassée est renvoyée telle quelle.",
                "REJEU_DETECTE"),
            new Scenario("alteration", "3a_alteration_instruction",
                "Un bit du chiffré d'une instruction est modifié en transit.",
                "INTEGRITE_COMPROMISE"),
            new Scenario("intrus", "E1_partenaire_non_enregistre",
                "Un partenaire dont la clé n'est pas inscrite au registre tente d'ouvrir une session.",
                "REGISTRE_INCONNU"),
            new Scenario("suites", "4_alteration_suites",
                "La liste des suites cryptographiques de M1 est réécrite en transit ; la plateforme vérifie "
              + "la signature de la passerelle sur la transcription qu'elle a elle-même émise.",
                "SIGNATURE_INVALIDE")})
            SCENARIOS.put(s.nom(), s);
    }

    private static boolean configure;

    public static List<Scenario> scenarios() { return List.copyOf(SCENARIOS.values()); }
    public static Set<String> noms() { return SCENARIOS.keySet(); }

    /** Adresse du journal du bouchon que lit Attaques.executions (variable SFB_CBS_JOURNAL du banc). */
    public static String journalBouchon() {
        return Mesure.env("SFB_CBS_JOURNAL", "http://127.0.0.1:8443/journal");
    }

    /** Pointe le banc d'attaque sur la passerelle locale et charge les clés de la plateforme. */
    public static synchronized void configurer(String hote, int portSfb, Path cles) throws Exception {
        Mesure.hote = hote;
        Mesure.portSfb = portSfb;
        Mesure.cles = cles;
        Attaques.moi = Identites.charger(cles, "plateforme");
        Attaques.reg = new Identites.Registre(cles, "passerelle");
        configure = true;
    }

    /** Exécute une fois le scénario demandé, avec le code du banc. */
    public static synchronized Issue jouer(String nom) throws Exception {
        Scenario s = SCENARIOS.get(nom);
        if (s == null) throw new IllegalArgumentException("scénario inconnu");
        if (!configure) throw new IllegalStateException("banc d'attaque non configuré");
        String observe, remarque = null;
        switch (nom) {
            case "rejeu", "alteration" -> {
                // [0] rejeu, [1] altération, [2] escalade de plafond, [3] relance à l'identique
                String[] r = Attaques.instructions();
                observe = r[nom.equals("rejeu") ? 0 : 1];
                if (observe.startsWith("ECHEC_") || observe.equals("-")) {
                    observe = r[0];
                    remarque = "Précondition non remplie : les deux ordres légitimes qui précèdent l'attaque "
                             + "n'ont pas tous été acceptés par la passerelle (le banc renvoie ECHEC suivi de leurs "
                             + "deux motifs). Le scénario n'a pas pu être joué ; ce n'est pas une attaque réussie.";
                }
            }
            case "intrus" -> {
                long debut = System.currentTimeMillis();
                try { observe = Attaques.intrus(); }
                finally { nettoyerClesIntrus(debut); }
            }
            case "suites" -> {
                observe = Attaques.alterationSuites();
                remarque = "Détection côté plateforme : la passerelle ne journalise rien pour ce scénario.";
            }
            default -> throw new IllegalArgumentException("scénario inconnu");
        }
        return new Issue(s.nom(), s.reference(), s.description(), s.attendu(), observe,
                s.attendu().equals(observe), remarque);
    }

    /**
     * Attaques.intrus écrit la bi-clé de l'intrus dans un dossier temporaire qu'il ne supprime pas.
     * Sur un service qui tourne en continu, on retire les dossiers créés pendant l'appel.
     */
    private static void nettoyerClesIntrus(long debut) {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> dossiers = Files.newDirectoryStream(tmp, "intrus*")) {
            for (Path d : dossiers) {
                if (!Files.isDirectory(d) || Files.getLastModifiedTime(d).toMillis() < debut - 1000) continue;
                Set<String> contenu;
                try (var f = Files.list(d)) { contenu = f.map(p -> p.getFileName().toString()).collect(Collectors.toSet()); }
                if (!Set.of("intrus.pub", "intrus.key").containsAll(contenu)) continue;
                for (String n : contenu) Files.deleteIfExists(d.resolve(n));
                Files.deleteIfExists(d);
            }
        } catch (IOException ignore) { /* nettoyage au mieux */ }
    }
}
