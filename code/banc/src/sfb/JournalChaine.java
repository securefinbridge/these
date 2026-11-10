package sfb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.HexFormat;

/** Journal d'audit chaîné par SHA-256, en ajout seul (même format que journal_audit.py). */
public final class JournalChaine {
    private final Path fichier;
    private String precedente = "0".repeat(64);

    public JournalChaine(Path fichier) { this.fichier = fichier; }

    public synchronized void ecrire(String sid, long compteur, String empreinteInstr, String decision, String motif) {
        String entree = String.format(
            "{\"compteur\":%d,\"decision\":\"%s\",\"empreinte_instruction\":\"%s\",\"empreinte_precedente\":\"%s\",\"horodatage\":\"%s\",\"id_session\":\"%s\",\"motif\":\"%s\"}",
            compteur, decision, empreinteInstr, precedente, Instant.now(), sid, motif);
        precedente = HexFormat.of().formatHex(Crypto.sha256(entree.getBytes(StandardCharsets.UTF_8)));
        try { Files.writeString(fichier, entree + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
