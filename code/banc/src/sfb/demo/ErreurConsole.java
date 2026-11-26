package sfb.demo;

/** Refus de la console, rendu en JSON : statut HTTP, message fixe, champ en cause le cas échéant. */
public class ErreurConsole extends RuntimeException {
    private final int statut;
    private final String champ;

    public ErreurConsole(int statut, String message) { this(statut, message, null); }

    public ErreurConsole(int statut, String message, String champ) {
        super(message);
        this.statut = statut;
        this.champ = champ;
    }

    public int statut() { return statut; }
    public String champ() { return champ; }
}
