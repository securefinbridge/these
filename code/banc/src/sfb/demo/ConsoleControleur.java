package sfb.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sfb.ScenariosDemo;

/**
 * Console de démonstration : la passerelle ne parle pas HTTP, ces points d'accès tiennent donc
 * le rôle de la plateforme Mobile Money devant elle (sessions SFB-AKA, ordres, scénarios d'attaque,
 * lecture du journal chaîné).
 *
 * Le service est public : entrées bornées, opérations simultanées plafonnées, délais de garde.
 */
@RestController
@RequestMapping("/api/v1")
public class ConsoleControleur {
    private final Lanceur lanceur;
    private final ClientPlateforme plateforme;
    private final JournalLecteur journal;
    private final Semaphore operations = new Semaphore(4, true);     // sessions et ordres simultanés
    private final Semaphore attaques = new Semaphore(1, true);       // un scénario à la fois
    private final ExecutorService fils = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "scenario-attaque"); t.setDaemon(true); return t; });

    public ConsoleControleur(Lanceur lanceur, ClientPlateforme plateforme, JournalLecteur journal) throws Exception {
        this.lanceur = lanceur;
        this.plateforme = plateforme;
        this.journal = journal;
        ScenariosDemo.configurer("127.0.0.1", lanceur.portSfb(), lanceur.cles());
    }

    /** Ouvre une vraie session SFB-AKA et renvoie les trois messages échangés, mesurés sur le fil. */
    @PostMapping("/sessions")
    public ClientPlateforme.Poignee session() throws Exception {
        prendre(operations, 2, "La console traite déjà plusieurs demandes, réessayez dans un instant.");
        try { return plateforme.ouvrirSession(); }
        finally { operations.release(); }
    }

    /** Construit un pacs.008, le scelle dans une session neuve et renvoie la décision de la passerelle. */
    @PostMapping(value = "/ordres", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ClientPlateforme.Accuse ordre(@RequestBody JsonNode corps) throws Exception {
        if (corps == null || !corps.isObject())
            throw new ErreurConsole(400, "Un objet JSON est attendu : montant, compteDebiteur, compteCrediteur.");
        JsonNode m = corps.get("montant");
        if (m == null || !m.isIntegralNumber() || !m.canConvertToLong())
            throw new ErreurConsole(400, "montant : un nombre entier est attendu.", "montant");
        long montant = m.longValue();
        if (montant < 1 || montant > plateforme.montantMax())
            throw new ErreurConsole(400, "montant : entre 1 et " + plateforme.montantMax()
                    + " (dix fois le plafond de la politique ; au-delà du plafond, c'est la passerelle qui refuse).", "montant");
        String debiteur = texte(corps, "compteDebiteur", ClientPlateforme.COMPTE, true,
                "4 à 24 lettres ou chiffres, sans espace ni ponctuation.");
        String crediteur = texte(corps, "compteCrediteur", ClientPlateforme.COMPTE, true,
                "4 à 24 lettres ou chiffres, sans espace ni ponctuation.");
        String reference = texte(corps, "reference", ClientPlateforme.REFERENCE, false,
                "1 à 40 lettres, chiffres ou tirets.");
        prendre(operations, 2, "La console traite déjà plusieurs demandes, réessayez dans un instant.");
        try { return plateforme.envoyerOrdre(montant, debiteur, crediteur, reference); }
        finally { operations.release(); }
    }

    /** Rejoue une fois un scénario du banc d'attaque (code de sfb.Attaques, inchangé). */
    @PostMapping("/attaques/{scenario}")
    public Map<String, Object> attaque(@PathVariable String scenario) throws Exception {
        if (!ScenariosDemo.noms().contains(scenario))
            throw new ErreurConsole(404, "Scénario inconnu. Scénarios disponibles : " + String.join(", ", ScenariosDemo.noms()) + ".");
        if (scenario.equals("rejeu") || scenario.equals("alteration")) verifierJournalDuBouchon();
        prendre(attaques, 5, "Un scénario est déjà en cours, réessayez dans quelques secondes.");
        long t0 = System.nanoTime();
        Future<ScenariosDemo.Issue> tache;
        try {
            tache = fils.submit(() -> { try { return ScenariosDemo.jouer(scenario); } finally { attaques.release(); } });
        } catch (RuntimeException refus) { attaques.release(); throw refus; }
        ScenariosDemo.Issue issue;
        try { issue = tache.get(15, TimeUnit.SECONDS); }
        catch (TimeoutException e) { throw new ErreurConsole(504, "Le scénario n'a pas abouti dans le délai imparti."); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("scenario", issue.scenario());
        corps.put("referenceBanc", issue.reference());
        corps.put("description", issue.description());
        corps.put("attendu", issue.attendu());
        corps.put("observe", issue.observe());
        corps.put("conforme", issue.conforme());
        if (issue.remarque() != null) corps.put("remarque", issue.remarque());
        corps.put("dureeMs", Math.round((System.nanoTime() - t0) / 1e3) / 1e3);
        return corps;
    }

    /** Les n dernières entrées du journal d'audit de la passerelle et l'état du chaînage. */
    @GetMapping("/journal")
    public JournalLecteur.Lecture journal(@RequestParam(defaultValue = "20") int n) throws IOException {
        return journal.lire(Math.max(1, Math.min(n, JournalLecteur.MEMOIRE)));
    }

    // ---------- garde-fous ------------------------------------------------------------
    private static void prendre(Semaphore s, int secondes, String message) throws InterruptedException {
        if (!s.tryAcquire(secondes, TimeUnit.SECONDS)) throw new ErreurConsole(503, message);
    }

    private static String texte(JsonNode corps, String champ, Pattern motif, boolean requis, String regle) {
        JsonNode v = corps.get(champ);
        if (v == null || v.isNull()) {
            if (requis) throw new ErreurConsole(400, champ + " : champ requis.", champ);
            return null;
        }
        if (!v.isTextual() || !motif.matcher(v.textValue()).matches())
            throw new ErreurConsole(400, champ + " : " + regle, champ);
        return v.textValue();
    }

    /**
     * Le scénario du banc vérifie auprès du bouchon qu'un ordre relancé n'a été exécuté qu'une fois ;
     * il lit pour cela l'adresse fixée par la variable SFB_CBS_JOURNAL (port 8443 par défaut).
     * Si le bouchon a dû prendre un autre port, mieux vaut le dire que rendre un résultat faux.
     */
    private void verifierJournalDuBouchon() {
        URI lu = URI.create(ScenariosDemo.journalBouchon());
        if (lu.getPort() != lanceur.portBouchon())
            throw new ErreurConsole(503, "Scénario indisponible : le banc d'attaque lit le journal du bouchon sur le port "
                    + lu.getPort() + ", alors que le bouchon écoute sur le port " + lanceur.portBouchon()
                    + ". Relancez le démonstrateur avec SFB_CBS_JOURNAL=http://127.0.0.1:" + lanceur.portBouchon() + "/journal.");
    }
}
