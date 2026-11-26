package sfb.demo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sfb.ScenariosDemo;

/** État du démonstrateur : sert aux sondes de déploiement et à la page d'accueil. */
@RestController
public class EtatControleur {
    static final String AVERTISSEMENT = "Prototype de démonstration. Banque simulée par un bouchon ISO 20022. "
            + "Clés de démonstration régénérées à chaque démarrage. Non destiné à la production.";

    private final Lanceur lanceur;
    private final ClientPlateforme plateforme;
    private final String version, commit;

    public EtatControleur(Lanceur lanceur, ClientPlateforme plateforme, ObjectProvider<BuildProperties> construction,
                          @Value("${sfb.commit}") String commit) {
        this.lanceur = lanceur;
        this.plateforme = plateforme;
        BuildProperties infos = construction.getIfAvailable();
        this.version = infos != null ? infos.getVersion() : "inconnue";
        this.commit = commit.matches("[0-9a-fA-F]{7,40}|local") ? commit : "inconnu";
    }

    /** 200 si la passerelle et le bouchon répondent, 503 sinon. */
    @GetMapping("/sante")
    public ResponseEntity<Map<String, Object>> sante() {
        boolean passerelle = lanceur.passerelleRepond(), bouchon = lanceur.bouchonRepond();
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("statut", passerelle && bouchon ? "OK" : "INDISPONIBLE");
        corps.put("passerelle", passerelle);
        corps.put("bouchon", bouchon);
        return ResponseEntity.status(passerelle && bouchon ? 200 : 503).body(corps);
    }

    @GetMapping("/api/v1/etat")
    public Map<String, Object> etat() {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("service", "SecureFinBridge : passerelle SFB-AKA");
        corps.put("avertissement", AVERTISSEMENT);
        corps.put("version", version);
        corps.put("commit", commit);
        corps.put("demarreLe", lanceur.demarreLe().toString());
        corps.put("portSfbAka", lanceur.portSfb());
        corps.put("portTls13", lanceur.portTls());
        corps.put("etiquettesRole", lanceur.etiquettesRole());
        corps.put("passerelleRepond", lanceur.passerelleRepond());
        corps.put("bouchonRepond", lanceur.bouchonRepond());
        corps.put("politique", lanceur.politique());

        // Ce que la console accepte en entrée, et les scénarios du banc qu'elle sait rejouer.
        Map<String, Object> console = new LinkedHashMap<>();
        console.put("montantMin", 1);
        console.put("montantMax", plateforme.montantMax());
        console.put("plafondPolitique", plateforme.plafond());
        console.put("corpsMaxOctets", GardeFous.CORPS_MAX);
        console.put("scenarios", ScenariosDemo.scenarios());
        corps.put("console", console);
        return corps;
    }
}
