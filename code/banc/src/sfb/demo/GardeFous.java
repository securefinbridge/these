package sfb.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sfb.Crypto;

/**
 * Garde-fous d'un service exposé sur Internet, appliqués avant tout contrôleur :
 * corps de requête plafonné à 4 Ko, requêtes d'écriture venues d'une autre origine refusées
 * (aucun CORS n'est ouvert), en-têtes de sécurité sur chaque réponse.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GardeFous extends OncePerRequestFilter {
    static final int CORPS_MAX = 4096;
    private static final Set<String> LECTURE = Set.of("GET", "HEAD", "OPTIONS");

    private final String politiqueDeContenu;

    public GardeFous() throws IOException {
        String page = "";
        try (InputStream f = GardeFous.class.getResourceAsStream("/static/index.html")) {
            if (f != null) page = new String(f.readAllBytes(), StandardCharsets.UTF_8);
        }
        // La page tient en un seul fichier : son script et sa feuille de style sont autorisés par
        // leur empreinte, calculée ici, et rien d'autre ne peut s'exécuter (pas de 'unsafe-inline').
        politiqueDeContenu = "default-src 'none'; script-src" + empreintes(page, "script")
                + "; style-src" + empreintes(page, "style")
                + "; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain suite)
            throws ServletException, IOException {
        reponse.setHeader("Content-Security-Policy", politiqueDeContenu);
        reponse.setHeader("X-Content-Type-Options", "nosniff");
        reponse.setHeader("X-Frame-Options", "DENY");
        reponse.setHeader("Referrer-Policy", "no-referrer");
        reponse.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        reponse.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        reponse.setHeader("Permissions-Policy", "camera=(), geolocation=(), microphone=()");
        if ("https".equalsIgnoreCase(requete.getHeader("X-Forwarded-Proto")))
            reponse.setHeader("Strict-Transport-Security", "max-age=15552000");
        String chemin = requete.getRequestURI();
        if (chemin.startsWith("/api/") || chemin.equals("/sante")) reponse.setHeader("Cache-Control", "no-store");

        if (!LECTURE.contains(requete.getMethod())) {
            if (requete.getHeader("Transfer-Encoding") != null) {
                refuser(reponse, 411, "Longueur du corps requise (en-tête Content-Length).");
                return;
            }
            if (requete.getContentLengthLong() > CORPS_MAX) {
                refuser(reponse, 413, "Corps de requête trop volumineux : 4 Ko au plus.");
                return;
            }
            String origine = requete.getHeader("Origin");
            if (origine != null && !memeOrigine(origine, requete.getHeader("Host"))) {
                refuser(reponse, 403, "Requête venue d'une autre origine : refusée.");
                return;
            }
        }
        suite.doFilter(requete, reponse);
    }

    private static boolean memeOrigine(String origine, String hote) {
        try { return hote != null && hote.equalsIgnoreCase(URI.create(origine).getAuthority()); }
        catch (IllegalArgumentException malformee) { return false; }
    }

    private static void refuser(HttpServletResponse reponse, int statut, String message) throws IOException {
        reponse.setStatus(statut);
        reponse.setHeader("Connection", "close");
        reponse.setContentType("application/json;charset=UTF-8");
        reponse.getWriter().write("{\"erreur\":\"" + message + "\"}");       // messages fixes, sans donnée de l'appelant
    }

    /** Empreintes SHA-256 (au format de Content-Security-Policy) des blocs &lt;script&gt; ou &lt;style&gt; de la page. */
    static String empreintes(String page, String balise) {
        Matcher m = Pattern.compile("<" + balise + ">(.*?)</" + balise + ">", Pattern.DOTALL).matcher(page);
        StringBuilder s = new StringBuilder();
        while (m.find())
            s.append(" 'sha256-").append(Base64.getEncoder().encodeToString(
                    Crypto.sha256(m.group(1).getBytes(StandardCharsets.UTF_8)))).append("'");
        return s.isEmpty() ? " 'none'" : s.toString();
    }
}
