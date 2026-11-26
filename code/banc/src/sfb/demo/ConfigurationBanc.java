package sfb.demo;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Relie les propriétés de application.properties au lanceur (qui, lui, ne dépend pas de Spring). */
@Configuration
public class ConfigurationBanc {

    @Bean(initMethod = "demarrer", destroyMethod = "arreter")
    public Lanceur lanceur(@Value("${sfb.dossier}") String dossier,
                           @Value("${sfb.port}") int port,
                           @Value("${sfb.tls-port}") int portTls,
                           @Value("${sfb.bouchon-port}") int portBouchon,
                           @Value("${sfb.etiquettes-role}") boolean etiquettesRole,
                           @Value("${sfb.python}") String python) {
        return new Lanceur(Path.of(dossier), port, portTls, portBouchon, etiquettesRole, python);
    }
}
