package sfb.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Démonstrateur SecureFinBridge.
 *
 * Au démarrage, l'application prépare des clés de démonstration, lance le bouchon core
 * banking ISO 20022 et la passerelle SFB-AKA (deux processus séparés, comme sur le banc),
 * puis expose une API HTTP devant eux.
 *
 * Prototype de démonstration : la banque est simulée, les clés sont jetables.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
