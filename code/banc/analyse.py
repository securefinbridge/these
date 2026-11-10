#!/usr/bin/env python3
"""
Analyse de la campagne de mesures SecureFinBridge (annexe D, « Traitement et
présentation des résultats »). Bibliothèque standard uniquement.

    python3 analyse.py <rép_mesures> <rép_sortie_tex>

Produit, dans <rép_sortie_tex>, les tableaux LaTeX inclus tels quels au
chapitre 9 (aucune valeur saisie à la main) et un fichier de macros.

Statistiques : médiane, 95e et 99e centiles sur l'ensemble des sessions d'une
configuration ; intervalle de confiance à 95 % de la médiane par bootstrap
(10 000 rééchantillonnages des répétitions, qui sont les unités indépendantes) ;
valeurs aberrantes comptées par la règle de Tukey (1,5 x écart interquartile),
jamais supprimées.
"""
import json, os, random, statistics, sys, glob

PROFILS = ["LAN", "3G", "3G-DEGRADE", "EDGE", "GPRS"]
NOM_PROFIL = {"LAN": "LAN", "3G": "3G", "3G-DEGRADE": "3G dégradé", "EDGE": "EDGE", "GPRS": "GPRS"}
SEUIL_H3 = 15.0
B = 10_000
random.seed(20261105)


def centile(v, p):
    v = sorted(v)
    if not v: return float("nan")
    k = (len(v) - 1) * p / 100
    f = int(k); c = min(f + 1, len(v) - 1)
    return v[f] + (v[c] - v[f]) * (k - f)


def ic_mediane(reps, cle):
    """Bootstrap par répétitions : IC à 95 % de la médiane des sessions."""
    meds = []
    for _ in range(B):
        tirage = [random.choice(reps) for _ in reps]
        meds.append(statistics.median([cle(s) for r in tirage for s in r]))
    return centile(meds, 2.5), centile(meds, 97.5)


def tukey(v):
    q1, q3 = centile(v, 25), centile(v, 75)
    e = 1.5 * (q3 - q1)
    return sum(1 for x in v if x < q1 - e or x > q3 + e)


def fr(x, d=1):
    if x != x: return "--"
    s = f"{x:,.{d}f}".replace(",", " ").replace(".", ",")
    return s.replace(" ", "\\,")


def charger(rep):
    data = {}
    for f in glob.glob(os.path.join(rep, "*_sfb.json")) + glob.glob(os.path.join(rep, "*_tls.json")):
        d = json.load(open(f))
        data[(d["profil"], d["mecanisme"])] = d["repetitions"]
    return data


def stats(reps):
    sessions = [s for r in reps for s in r]
    ok = [s for s in sessions if s["statut"] == 0]
    g = lambda cle: [cle(s) for s in sessions]
    poignee, bout = g(lambda s: s["poignee_ms"]), g(lambda s: s["bout_en_bout_ms"])
    crypto = g(lambda s: (s["cpu_crypto_client_ns"] + s["cpu_crypto_serveur_ns"]) / 1e6)
    cpu_poignee = g(lambda s: (s["cpu_poignee_client_ns"] + s["cpu_poignee_serveur_ns"]) / 1e6)
    return {
        "n": len(sessions), "acceptees": len(ok), "repetitions": len(reps),
        "poignee": (statistics.median(poignee), centile(poignee, 95), centile(poignee, 99)),
        "poignee_ic": ic_mediane(reps, lambda s: s["poignee_ms"]),
        "bout": (statistics.median(bout), centile(bout, 95), centile(bout, 99)),
        "bout_ic": ic_mediane(reps, lambda s: s["bout_en_bout_ms"]),
        "crypto": statistics.median(crypto), "cpu_poignee": statistics.median(cpu_poignee),
        "octets_poignee": statistics.median(g(lambda s: s["octets_poignee"])),
        "octets_poignee_min": min(g(lambda s: s["octets_poignee"])),
        "octets_poignee_max": max(g(lambda s: s["octets_poignee"])),
        "octets_total": statistics.median(g(lambda s: s["octets_total"])),
        "aberrantes": tukey(poignee) + tukey(bout),
    }


def main(rep, sortie):
    os.makedirs(sortie, exist_ok=True)
    data = charger(rep)
    profils = [p for p in PROFILS if (p, "sfb") in data and (p, "tls") in data]
    S = {(p, m): stats(data[(p, m)]) for p in profils for m in ("sfb", "tls")}
    env = open(os.path.join(rep, "environnement.txt")).read() if os.path.exists(os.path.join(rep, "environnement.txt")) else ""
    n_rep = S[(profils[0], "sfb")]["repetitions"]; n_sess = S[(profils[0], "sfb")]["n"] // n_rep

    # ---- latences -------------------------------------------------------------
    L = [r"\begin{table}[H]", r"\centering",
         rf"\caption{{Latence (ms) : médiane [p95], {n_rep} répétitions de {n_sess} sessions par configuration}}",
         r"\label{tab:latence}", r"\small",
         r"\begin{tabularx}{\textwidth}{|L{0.15\textwidth}|X|X|X|X|}", r"\hline",
         r"& \multicolumn{2}{c|}{\textbf{Établissement de session}} & \multicolumn{2}{c|}{\textbf{Bout en bout (avec instruction)}}\\ \hline",
         r"\textbf{Profil} & \textbf{\aka} & \textbf{TLS~1.3} & \textbf{\aka} & \textbf{TLS~1.3}\\ \hline"]
    for p in profils:
        c = []
        for cle in ("poignee", "bout"):
            for m in ("sfb", "tls"):
                med, p95, _ = S[(p, m)][cle]
                c.append(f"{fr(med)} [{fr(p95)}]")
        L.append(f"{NOM_PROFIL[p]} & {c[0]} & {c[1]} & {c[2]} & {c[3]}\\\\ \\hline")
    L += [r"\end{tabularx}", r"\begin{flushleft}\footnotesize", r"\smallskip",
          "Intervalles de confiance à 95\\,\\% des médianes (bootstrap sur les répétitions) : fichier "
          r"\texttt{resultats/banc/synthese.txt}. Valeurs aberrantes (règle de Tukey), comptées et non supprimées : "
          + ", ".join(f"{NOM_PROFIL[p]} {S[(p,'sfb')]['aberrantes']}/{S[(p,'tls')]['aberrantes']}" for p in profils)
          + r" (\aka/TLS).", r"\end{flushleft}", r"\end{table}"]
    open(os.path.join(sortie, "tab_latence.tex"), "w").write("\n".join(L) + "\n")

    # ---- surcoût (H3) -----------------------------------------------------------
    T = [r"\begin{table}[H]", r"\centering",
         r"\caption{Surcoût cryptographique de \aka rapporté à la latence de bout en bout de TLS~1.3 (définition de l'annexe~\ref{ann:experimental})}",
         r"\label{tab:surcout}", r"\small",
         r"\begin{tabularx}{\textwidth}{|L{0.15\textwidth}|X|X|X|X|}", r"\hline",
         r"\textbf{Profil} & \textbf{CPU des primitives \aka (ms)} & \textbf{CPU poignée TLS~1.3 (ms)} & \textbf{Bout en bout TLS~1.3 (ms)} & \textbf{Surcoût (\%)}\\ \hline"]
    surcouts = {}
    for p in profils:
        cr = S[(p, "sfb")]["crypto"]; tls_b = S[(p, "tls")]["bout"][0]
        surcouts[p] = 100 * cr / tls_b
        T.append(f"{NOM_PROFIL[p]} & {fr(cr, 2)} & {fr(S[(p,'tls')]['cpu_poignee'], 2)} & {fr(tls_b)} & {fr(surcouts[p], 2)}\\\\ \\hline")
    T += [r"\end{tabularx}", r"\begin{flushleft}\footnotesize", r"\smallskip",
          r"Temps CPU médian cumulé, client et passerelle, des primitives de la poignée de main \aka (X25519, ECDSA P-256, HKDF, HMAC, SHA-256) ; "
          r"pour TLS~1.3, temps CPU de la poignée de main complète. Cible de l'hypothèse~\ref{hyp:h3} : moins de "
          + fr(SEUIL_H3, 0) + r"\,\% sur le profil 3G.", r"\end{flushleft}", r"\end{table}"]
    open(os.path.join(sortie, "tab_surcout.tex"), "w").write("\n".join(T) + "\n")

    # ---- volume -----------------------------------------------------------------
    p0 = profils[0]
    V = [r"\begin{table}[H]", r"\centering",
         r"\caption{Volume mesuré des échanges (octets applicatifs, médiane)}", r"\label{tab:volume-mesure}",
         r"\begin{tabularx}{\textwidth}{|L{0.40\textwidth}|X|X|}", r"\hline",
         r"\textbf{Mécanisme} & \textbf{Établissement de session} & \textbf{Session avec une instruction}\\ \hline",
         rf"\aka & {fr(S[(p0,'sfb')]['octets_poignee'],0)} & {fr(S[(p0,'sfb')]['octets_total'],0)}\\ \hline",
         rf"TLS~1.3, authentification mutuelle (ECDSA P-256, chaîne de 2) & {fr(S[(p0,'tls')]['octets_poignee'],0)} & {fr(S[(p0,'tls')]['octets_total'],0)}\\ \hline",
         r"\end{tabularx}", r"\begin{flushleft}\footnotesize", r"\smallskip",
         r"Octets émis et reçus au-dessus de TCP, cadrage compris ; en-têtes TCP/IP exclus. Pour \aka, 378 octets de charge utile "
         r"(\S\ref{sec:budget}) et 6 octets de cadrage. Plage observée sur toutes les sessions : \aka "
         + f"{fr(min(S[(p,'sfb')]['octets_poignee_min'] for p in profils),0)}--{fr(max(S[(p,'sfb')]['octets_poignee_max'] for p in profils),0)}, TLS~1.3 "
         + f"{fr(min(S[(p,'tls')]['octets_poignee_min'] for p in profils),0)}--{fr(max(S[(p,'tls')]['octets_poignee_max'] for p in profils),0)}.",
         r"\end{flushleft}", r"\end{table}"]
    open(os.path.join(sortie, "tab_volume_mesure.tex"), "w").write("\n".join(V) + "\n")

    # ---- attaques -----------------------------------------------------------------
    LIB = {"1_rejeu_poignee": ("1", "Rejeu d'une poignée de main enregistrée"),
           "2_rejeu_instruction": ("2", "Rejeu d'une instruction acceptée et dépassée"),
           "2b_relance_apres_delai": ("2", "Relance à l'identique de la dernière instruction (après délai)"),
           "3a_alteration_instruction": ("3", "Altération d'une instruction en transit"),
           "3b_escalade_plafond": ("3", "Instruction hors habilitation (plafond)"),
           "4_alteration_suites": ("4", "Réécriture de la liste des suites"),
           "E1_partenaire_non_enregistre": ("--", "Partenaire non inscrit au registre (E1)"),
           "R_reflexion": ("--", "Attaque par réflexion (\\S\\ref{sec:reflexion})")}
    A = [r"\begin{table}[H]", r"\centering", r"\caption{Campagne d'attaque contre la passerelle \aka (profil LAN)}",
         r"\label{tab:attaques-banc}", r"\small",
         r"\begin{tabularx}{\textwidth}{|C{0.06\textwidth}|X|L{0.27\textwidth}|C{0.13\textwidth}|}", r"\hline",
         r"\textbf{Sc.} & \textbf{Attaque} & \textbf{Issue attendue} & \textbf{Conforme}\\ \hline"]
    att = json.load(open(os.path.join(rep, "attaques.json"))) if os.path.exists(os.path.join(rep, "attaques.json")) else {"scenarios": []}
    tout_ok = True
    for s in att["scenarios"]:
        num, lib = LIB.get(s["scenario"], ("?", s["scenario"]))
        tout_ok &= s["conformes"] == s["total"]
        A.append(f"{num} & {lib} & \\texttt{{{s['attendu'].replace('_', chr(92)+'_')}}} & {s['conformes']}/{s['total']}\\\\ \\hline")
    refl = {}
    for v in ("v1", "v2"):
        f = os.path.join(rep, f"reflexion_{v}.json")
        if os.path.exists(f): refl[v] = json.load(open(f))
    A += [r"\end{tabularx}", r"\begin{flushleft}\footnotesize", r"\smallskip",
          "Une attaque est neutralisée si la passerelle la rejette avec le motif attendu, journalisé, et n'accepte aucune instruction adverse. "
          "Pour la relance à l'identique, l'issue attendue est la réponse idempotente : le statut initial (acceptée) est renvoyé et le bouchon \\emph{core banking} confirme une seule exécution."]
    if refl:
        def issue(v):
            o = refl.get(v, {}).get("observes", "")
            acc = sum(int(x.split("=")[1]) for x in o.strip("{}").split(", ") if x.startswith("ATTAQUE_REUSSIE"))
            tot = sum(int(x.split("=")[1]) for x in o.strip("{}").split(", ") if "=" in x)
            return acc, tot
        a1, t1 = issue("v1"); a2, t2 = issue("v2")
        A.append(rf" Attaque par réflexion rejouée contre la version~1 du protocole (sans étiquettes de rôle) : instruction forgée acceptée dans {a1} cas sur {t1} ; contre la version~2 : {t2 - a2} rejets sur {t2} (\texttt{{SIGNATURE\_INVALIDE}}).")
    A += [r"\end{flushleft}", r"\end{table}"]
    open(os.path.join(sortie, "tab_attaques.tex"), "w").write("\n".join(A) + "\n")

    # ---- macros et synthèse ------------------------------------------------------------
    ref = "3G" if "3G" in surcouts else profils[0]
    verdict = "confirmée" if surcouts[ref] < SEUIL_H3 else "infirmée"
    M = [f"\\newcommand{{\\resSurcoutRef}}{{{fr(surcouts[ref], 2)}\\,\\%}}",
         f"\\newcommand{{\\resProfilRef}}{{{NOM_PROFIL[ref]}}}",
         f"\\newcommand{{\\resVerdictHtrois}}{{{verdict}}}",
         f"\\newcommand{{\\resRepetitions}}{{{n_rep}}}",
         f"\\newcommand{{\\resSessions}}{{{n_sess}}}",
         f"\\newcommand{{\\resPoigneeSfbRef}}{{{fr(S[(ref,'sfb')]['poignee'][0])}}}",
         f"\\newcommand{{\\resPoigneeTlsRef}}{{{fr(S[(ref,'tls')]['poignee'][0])}}}",
         f"\\newcommand{{\\resBoutSfbRef}}{{{fr(S[(ref,'sfb')]['bout'][0])}}}",
         f"\\newcommand{{\\resBoutTlsRef}}{{{fr(S[(ref,'tls')]['bout'][0])}}}",
         f"\\newcommand{{\\resCryptoSfbRef}}{{{fr(S[(ref,'sfb')]['crypto'], 2)}}}",
         f"\\newcommand{{\\resOctetsSfb}}{{{fr(S[(p0,'sfb')]['octets_poignee'], 0)}}}",
         f"\\newcommand{{\\resOctetsTls}}{{{fr(S[(p0,'tls')]['octets_poignee'], 0)}}}",
         f"\\newcommand{{\\resAttaquesToutes}}{{{'toutes' if tout_ok else 'pas toutes'}}}"]
    open(os.path.join(sortie, "macros.tex"), "w").write("\n".join(M) + "\n")

    syn = ["Synthèse de la campagne SecureFinBridge", "=" * 40, env.strip(), ""]
    for p in profils:
        for m in ("sfb", "tls"):
            s = S[(p, m)]
            syn.append(f"{p:11s} {m:4s} n={s['n']:4d} acceptées={s['acceptees']:4d} "
                       f"poignée méd={s['poignee'][0]:8.1f} ms IC95=[{s['poignee_ic'][0]:.1f};{s['poignee_ic'][1]:.1f}] "
                       f"p95={s['poignee'][1]:8.1f} | bout méd={s['bout'][0]:8.1f} IC95=[{s['bout_ic'][0]:.1f};{s['bout_ic'][1]:.1f}] "
                       f"| crypto={s['crypto']:.2f} ms | octets={s['octets_poignee']:.0f}/{s['octets_total']:.0f} | aberrantes={s['aberrantes']}")
        syn.append(f"{p:11s} surcoût H3 = {surcouts[p]:.2f} %")
    syn.append(f"\nH3 (profil {ref}, seuil {SEUIL_H3} %) : {verdict}")
    syn.append(f"Attaques : {'toutes neutralisées' if tout_ok else 'NON toutes neutralisées'}")
    for v, d in refl.items(): syn.append(f"Réflexion {v} : {d['observes']}")
    open(os.path.join(sortie, "synthese.txt"), "w").write("\n".join(syn) + "\n")
    print("\n".join(syn))


def complements(rep, sortie):
    """Scénarios 5, 6a, 6b et débit sous charge (répertoire mesures/complements)."""
    c = os.path.join(rep, "complements")
    if not os.path.isdir(c):
        return
    lignes = []
    comp = json.load(open(os.path.join(c, "composition.json")))
    men = json.load(open(os.path.join(c, "menace.json")))
    def compte(obs, cle):
        return sum(int(x.split("=")[1]) for x in obs.strip("{}").split(", ") if x.startswith(cle))
    v = {d["separation"]: d["observes"] for d in comp["variantes"]}
    n = comp["repetitions"]
    T = [r"\begin{table}[H]", r"\centering", r"\caption{Scénarios 5 et 6 exécutés sur le banc (profil LAN)}",
         r"\label{tab:scenarios-5-6}", r"\small",
         r"\begin{tabularx}{\textwidth}{|C{0.06\textwidth}|L{0.36\textwidth}|X|}", r"\hline",
         r"\textbf{Sc.} & \textbf{Situation} & \textbf{Issue observée}\\ \hline",
         f"5 & Service de notification partageant la clé de la passerelle, \\emph{{sans}} séparation de domaine & Session usurpée établie avec la plateforme : {compte(v[False], 'ATTAQUE_REUSSIE')}/{n}\\\\ \\hline",
         f"5 & Même service, \\emph{{avec}} étiquette de domaine (\\aka) & Signature rejetée (\\texttt{{SIGNATURE\\_INVALIDE}}) : {compte(v[True], 'SIGNATURE_INVALIDE')}/{n}\\\\ \\hline",
         f"6a & Clés à long terme et état persistant de la passerelle (journal, clés) livrés après la session & Aucune trace de clé de session : {compte(men['6a']['observes'], 'AUCUNE_TRACE')}/{men['6a']['repetitions']}\\\\ \\hline"]
    b = men["6b"]
    mil = lambda x: f"{x:,}".replace(",", "\\,")
    T.append(f"6b & Clé de la plateforme compromise avant la session ; ordres de {mil(b['montant_unitaire'])} FCFA en rafale, chacun sous le plafond"
             + f" & {b['acceptes']} ordres acceptés ({mil(b['cumul_accepte'])} FCFA), puis refus \\texttt{{{b['premier_refus'].replace('_', chr(92)+'_')}}} au {b['envoyes']}\\textsuperscript{{e}}" + r"\\ \hline")
    T += [r"\end{tabularx}", r"\end{table}"]
    open(os.path.join(sortie, "tab_scenarios_5_6.tex"), "w").write("\n".join(T) + "\n")
    # débit
    res = {}
    for f in glob.glob(os.path.join(c, "debit_*_r*.json")):
        d = json.load(open(f)); res.setdefault((d["mecanisme"], d["ordres_par_session"]), []).append(d)
    D = [r"\begin{table}[H]", r"\centering", r"\caption{Débit sous charge (profil LAN, 16 clients concurrents, 3 séries de 30~s)}",
         r"\label{tab:debit}", r"\small",
         r"\begin{tabularx}{\textwidth}{|L{0.26\textwidth}|L{0.14\textwidth}|X|X|X|}", r"\hline",
         r"\textbf{Configuration} & \textbf{Mécanisme} & \textbf{Ordres acceptés/s (médiane [min ; max])} & \textbf{Latence par ordre, médiane (ms)} & \textbf{Refus}\\ \hline"]
    for k, lib in ((1, "Une session par ordre"), (100, "Session réutilisée (100 ordres)")):
        for m, nom in (("sfb", r"\aka"), ("tls", r"TLS~1.3")):
            L = res.get((m, k), [])
            if not L: continue
            t = sorted(x["debit_tps"] for x in L); l = sorted(x["latence_mediane_ms"] for x in L)
            D.append(f"{lib} & {nom} & {fr(t[len(t)//2], 0)} [{fr(t[0], 0)} ; {fr(t[-1], 0)}] & {fr(l[len(l)//2], 2)} & {sum(x['refuses'] for x in L)}\\\\ \\hline")
            lignes.append(f"débit {m} {k} ordres/session : {t} tx/s ; latences {l}")
    D += [r"\end{tabularx}", r"\begin{flushleft}\footnotesize", r"\smallskip",
          r"Règles de vélocité neutralisées pour cette mesure (\texttt{politique\_charge.properties}) ; la voie \aka inclut la journalisation chaînée de chaque ordre, absente de la référence TLS~1.3. Cible du tableau~\ref{tab:metriques} : plus de 200 transactions par seconde.",
          r"\end{flushleft}", r"\end{table}"]
    open(os.path.join(sortie, "tab_debit.tex"), "w").write("\n".join(D) + "\n")
    with open(os.path.join(sortie, "synthese.txt"), "a") as f:
        f.write("\nComplements\n" + json.dumps(comp, ensure_ascii=False) + "\n" + json.dumps(men, ensure_ascii=False) + "\n" + "\n".join(lignes) + "\n")
    print("\n".join(lignes))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
    complements(sys.argv[1], sys.argv[2])
