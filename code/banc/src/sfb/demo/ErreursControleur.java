package sfb.demo;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sfb.SfbAka;

/**
 * Toutes les erreurs sortent en JSON, avec un message fixe : rien de ce qu'a envoyé
 * l'appelant (chemin, paramètre, corps) n'est recopié dans la réponse.
 */
@RestControllerAdvice
public class ErreursControleur {
    private static final Logger JOURNAL = LoggerFactory.getLogger(ErreursControleur.class);

    @ExceptionHandler(ErreurConsole.class)
    public ResponseEntity<Map<String, Object>> console(ErreurConsole e) {
        Map<String, Object> corps = corps(e.getMessage());
        if (e.champ() != null) corps.put("champ", e.champ());
        return ResponseEntity.status(e.statut()).body(corps);
    }

    /** La plateforme a refusé la réponse de la passerelle pendant la poignée de main. */
    @ExceptionHandler(SfbAka.Rejet.class)
    public ResponseEntity<Map<String, Object>> rejet(SfbAka.Rejet e) {
        Map<String, Object> corps = corps("La session SFB-AKA n'a pas pu être établie.");
        corps.put("motif", e.motif.name());
        return ResponseEntity.status(502).body(corps);
    }

    @ExceptionHandler(SocketTimeoutException.class)
    public ResponseEntity<Map<String, Object>> delai(SocketTimeoutException e) {
        return ResponseEntity.status(504).body(corps("La passerelle n'a pas répondu dans le délai imparti."));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> liaison(IOException e) {
        JOURNAL.warn("liaison avec la passerelle : {}", e.toString());
        return ResponseEntity.status(503).body(corps("La passerelle est injoignable ou a fermé la connexion."));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> illisible(Exception e) {
        return ResponseEntity.status(400).body(corps("Requête invalide : JSON ou paramètre illisible."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> autre(Exception e) {
        if (e instanceof ErrorResponse r) {              // erreurs ordinaires de Spring MVC : 404, 405, 415...
            int statut = r.getStatusCode().value();
            return ResponseEntity.status(statut).body(corps(switch (statut) {
                case 400 -> "Requête invalide.";
                case 404 -> "Ressource introuvable.";
                case 405 -> "Méthode non autorisée pour ce chemin.";
                case 406 -> "Seul le JSON est proposé en réponse.";
                case 415 -> "Type de contenu non pris en charge : envoyez du JSON (Content-Type: application/json).";
                default -> "Requête refusée.";
            }));
        }
        JOURNAL.error("erreur interne", e);
        return ResponseEntity.status(500).body(corps("Erreur interne du démonstrateur."));
    }

    private static Map<String, Object> corps(String message) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("erreur", message);
        return corps;
    }
}
