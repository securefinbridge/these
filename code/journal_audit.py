"""Journal d'audit a integrite verifiable, support technique de P5."""

import hashlib
import json
from dataclasses import dataclass, asdict

GENESE = "0" * 64


@dataclass(frozen=True)
class EntreeAudit:
    horodatage: str
    id_session: str
    compteur: int
    empreinte_instruction: str
    decision: str
    empreinte_precedente: str

    def empreinte(self) -> str:
        """Condensat canonique de l'entree, chainage inclus.

        La serialisation canonique (cles triees, separateurs fixes) est
        indispensable : sans elle, deux representations semantiquement
        identiques produiraient des condensats differents.
        """
        charge = json.dumps(asdict(self), sort_keys=True,
                            separators=(",", ":"))
        return hashlib.sha256(charge.encode("utf-8")).hexdigest()


def verifier_chaine(journal: list[EntreeAudit],
                    tete_publiee: str | None = None) -> tuple[bool, int | None]:
    """Verifie l'integrite du journal complet.

    Retourne (True, None) si la chaine est intacte, sinon (False, i) ou i
    est l'index de la premiere entree dont l'integrite n'est plus garantie.
    Une alteration de l'entree i rompt le chainage a l'entree i + 1 ; celle
    de la derniere entree n'est detectable que par comparaison avec le
    condensat de tete publie vers un support externe (tete_publiee).
    """
    precedente = GENESE
    for index, entree in enumerate(journal):
        if entree.empreinte_precedente != precedente:
            return False, max(index - 1, 0)
        precedente = entree.empreinte()
    if tete_publiee is not None and precedente != tete_publiee:
        return False, len(journal) - 1
    return True, None
