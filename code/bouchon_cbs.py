"""
Bouchon Core Banking ISO 20022 : banc d'essai SecureFinBridge (périmètre retenu,
bouchon ISO 20022 au lieu d'un commutateur complet).

Expose un point de terminaison HTTP qui accepte un ordre de virement
pacs.008 (FIToFICustomerCreditTransfer) et répond par un rapport de statut
pacs.002 (FIToFIPaymentStatusReport) : ACCP (accepté) ou RJCT (rejeté).

Le bouchon ne tient aucun compte : il valide la structure du message, vérifie
l'unicité de la référence de bout en bout (EndToEndId) sur la durée de vie du
processus, ce qui permet d'observer le comportement d'un core banking face à
un rejeu applicatif, et journalise chaque décision. Il est volontairement
minimal : l'objet de la mesure est la passerelle, pas le core banking.

Usage :  python3 bouchon_cbs.py [port]     (défaut : 8443)
"""
import json
import sys
import time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NS_008 = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"
NS_002 = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"

REFERENCES_VUES: set[str] = set()      # EndToEndId déjà traités (anti-rejeu applicatif)
JOURNAL: list[dict] = []


def extraire_ordre(xml_bytes: bytes) -> dict:
    """Extrait les champs utiles d'un pacs.008 ; lève ValueError si malformé."""
    racine = ET.fromstring(xml_bytes)
    ns = {"p": NS_008}
    tx = racine.find(".//p:CdtTrfTxInf", ns)
    if tx is None:
        raise ValueError("CdtTrfTxInf absent")
    champ = lambda chemin: tx.findtext(chemin, default=None, namespaces=ns)
    ordre = {
        "end_to_end_id": champ("p:PmtId/p:EndToEndId"),
        "montant":       champ("p:IntrBkSttlmAmt"),
        "devise":        tx.find("p:IntrBkSttlmAmt", ns).get("Ccy") if tx.find("p:IntrBkSttlmAmt", ns) is not None else None,
        "debiteur":      champ("p:DbtrAcct/p:Id/p:Othr/p:Id"),
        "beneficiaire":  champ("p:CdtrAcct/p:Id/p:Othr/p:Id"),
    }
    manquants = [k for k, v in ordre.items() if v is None]
    if manquants:
        raise ValueError(f"champs manquants : {', '.join(manquants)}")
    if float(ordre["montant"]) <= 0:
        raise ValueError("montant non positif")
    return ordre


def rapport_statut(end_to_end_id: str, statut: str, motif: str | None = None) -> bytes:
    """Construit un pacs.002 minimal (ACCP ou RJCT)."""
    doc = ET.Element("Document", xmlns=NS_002)
    rpt = ET.SubElement(doc, "FIToFIPmtStsRpt")
    hdr = ET.SubElement(rpt, "GrpHdr")
    ET.SubElement(hdr, "MsgId").text = f"CBS-{int(time.time() * 1000)}"
    ET.SubElement(hdr, "CreDtTm").text = time.strftime("%Y-%m-%dT%H:%M:%S")
    tx = ET.SubElement(rpt, "TxInfAndSts")
    ET.SubElement(tx, "OrgnlEndToEndId").text = end_to_end_id or "NOTPROVIDED"
    ET.SubElement(tx, "TxSts").text = statut
    if motif:
        rsn = ET.SubElement(ET.SubElement(tx, "StsRsnInf"), "Rsn")
        ET.SubElement(rsn, "Prtry").text = motif
    return ET.tostring(doc, encoding="utf-8", xml_declaration=True)


class Gestionnaire(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"         # connexions persistantes : la passerelle réutilise ses connexions
    disable_nagle_algorithm = True        # sans quoi en-têtes et corps attendent l'accusé différé (~40 ms)
    def log_message(self, *_):            # silence du journal HTTP par défaut
        pass

    def _repondre(self, code: int, corps: bytes, type_mime: str = "application/xml"):
        self.send_response(code)
        self.send_header("Content-Type", type_mime)
        self.send_header("Content-Length", str(len(corps)))
        self.end_headers()
        self.wfile.write(corps)

    def do_GET(self):
        if self.path == "/sante":
            self._repondre(200, b'{"statut":"ok"}', "application/json")
        elif self.path == "/journal":
            self._repondre(200, json.dumps(JOURNAL, ensure_ascii=False).encode(), "application/json")
        else:
            self._repondre(404, b"")

    def do_POST(self):
        if self.path != "/pacs008":
            return self._repondre(404, b"")
        taille = int(self.headers.get("Content-Length", 0))
        corps = self.rfile.read(taille)
        debut = time.perf_counter()
        try:
            ordre = extraire_ordre(corps)
        except (ET.ParseError, ValueError) as err:
            JOURNAL.append({"t": time.time(), "statut": "RJCT", "motif": f"FORMAT:{err}"})
            return self._repondre(400, rapport_statut(None, "RJCT", "FF01"))
        ref = ordre["end_to_end_id"]
        if ref in REFERENCES_VUES:                       # rejeu applicatif
            JOURNAL.append({"t": time.time(), "ref": ref, "statut": "RJCT", "motif": "DUPL"})
            return self._repondre(409, rapport_statut(ref, "RJCT", "DUPL"))
        REFERENCES_VUES.add(ref)
        JOURNAL.append({"t": time.time(), "ref": ref, "montant": ordre["montant"],
                        "statut": "ACCP", "traitement_ms": round((time.perf_counter() - debut) * 1000, 3)})
        self._repondre(200, rapport_statut(ref, "ACCP"))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8443
    print(f"Bouchon Core Banking ISO 20022 à l'écoute sur :{port}  (POST /pacs008, GET /sante, GET /journal)")
    ThreadingHTTPServer.request_queue_size = 256      # file d'attente large : le bouchon ne doit pas limiter les mesures de débit
    ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer(("0.0.0.0", port), Gestionnaire).serve_forever()
