package bf.ujkz.ibam.securefinbridge.passerelle;

import org.springframework.stereotype.Service;

/**
 * Valide une instruction financiere entrante conformement aux
 * proprietes P3 a P5 visees par le modele formel SFB-AKA (lemmes Tamarin
 * P3_integrite, P4_non_rejeu, P5_ancrage_journal).
 * Toute deviation entraine un rejet atomique et journalise.
 */
@Service
public class ValidateurInstruction {

    private final RegistreSession registre;
    private final ServiceCryptographique crypto;
    private final JournalAudit journal;
    private final MoteurPolitique politique;

    public ValidateurInstruction(RegistreSession registre,
                                 ServiceCryptographique crypto,
                                 JournalAudit journal,
                                 MoteurPolitique politique) {
        this.registre = registre;      // injection par constructeur (Spring)
        this.crypto = crypto;
        this.journal = journal;
        this.politique = politique;
    }

    public ResultatValidation valider(InstructionEntrante instruction) {

        SessionSecurisee session = registre
                .rechercher(instruction.getIdentifiantSession())
                .orElseThrow(() -> new SessionInconnueException(
                        instruction.getIdentifiantSession()));

        // P3 - Integrite : verification du code d'authentification
        byte[] empreinte = crypto.condensat(instruction.transcription());
        if (!crypto.verifierMac(empreinte,
                                instruction.getMac(),
                                session.getCleIntegrite())) {
            return rejeter(instruction, Motif.INTEGRITE_COMPROMISE);
        }

        // P4 - Non-rejeu : compteur monotone et fenetre glissante
        if (!session.compteurAcceptable(instruction.getCompteur())) {
            return rejeter(instruction, Motif.REJEU_DETECTE);
        }

        // Regles metier formellement specifiees
        DecisionPolitique decision = politique.evaluer(instruction, session);
        if (decision.estRefusee()) {
            return rejeter(instruction, decision.motif());
        }

        // P5 - Non-repudiation : journalisation chainee prealable
        session.avancerCompteur(instruction.getCompteur());
        journal.enregistrer(EntreeAudit.acceptation(instruction, empreinte));

        return ResultatValidation.acceptee(instruction, session);
    }

    private ResultatValidation rejeter(InstructionEntrante instruction,
                                       Motif motif) {
        journal.enregistrer(EntreeAudit.rejet(instruction, motif));
        return ResultatValidation.rejetee(motif);
    }
}
