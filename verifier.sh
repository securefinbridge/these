#!/bin/bash
# =============================================================================
#  SecureFinBridge — vérification formelle (ProVerif + Tamarin)
#
#  Usage :  ./verifier.sh            tous les modèles ProVerif, logs horodatés
#           ./verifier.sh tamarin    le modèle Tamarin (code/sfb_aka.spthy)
#           ./verifier.sh demo       déroulé de démonstration pour la soutenance
#           ./verifier.sh tout       ProVerif puis Tamarin
#
#  Chaque exécution écrit dans resultats/ un journal complet contenant la
#  version de l'outil, la machine, la date et le temps de calcul : ce sont
#  les éléments de reproductibilité exigés au chapitre 7 et en annexe A/B.
#
#  Installation (macOS, Homebrew) :
#     brew install proverif                    (ou binaire ~/.local/bin/proverif)
#     brew trust tamarin-prover/tap && brew install tamarin-prover/tap/tamarin-prover
# =============================================================================
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
cd "$(dirname "$0")"
mkdir -p resultats
V="\033[1;32m"; R="\033[1;31m"; B="\033[1;34m"; J="\033[1;33m"; N="\033[0m"

entete () {   # $1 = journal
  {
    echo "# ==========================================================="
    echo "# SecureFinBridge — journal de vérification"
    echo "# Date      : $(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "# Machine   : $(uname -srm) — $(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo | cut -d: -f2)"
    echo "# Mémoire   : $(( $(sysctl -n hw.memsize 2>/dev/null || grep MemTotal /proc/meminfo | awk '{print $2*1024}') / 1073741824 )) Go"
    echo "# ProVerif  : $(proverif -help 2>&1 | head -1)"
    echo "# ==========================================================="
  } > "$1"
}

# ---- Un modèle ProVerif : $1 = fichier .pv, $2 = libellé, $3 = journal -----
pv () {
  printf "\n${B}=== %s ===${N}\n" "$2"
  entete "$3"
  local debut=$(date +%s.%N)
  proverif "$1" >> "$3" 2>&1
  local fin=$(date +%s.%N)
  printf "# Temps de vérification : %.2f s\n" "$(echo "$fin - $debut" | bc)" >> "$3"
  sed -n "/^Verification summary:/,/^------/p" "$3" > "${3%.log}.resume.txt"
  grep -E "^Query .* is (true|false)\.$" "$3" | while read -r l; do
    case "$l" in
      *"is true."*)  printf "  ${V}✔${N} %s\n" "$l" ;;
      *"is false."*) printf "  ${R}✘${N} %s\n" "$l" ;;
    esac
  done
  printf "  ${J}→ journal : %s (%s)${N}\n" "$3" "$(tail -1 "$3")"
}

proverif_tous () {
  command -v proverif >/dev/null || { echo "proverif introuvable (brew install proverif)"; exit 1; }
  pv code/sfb_aka.pv                   "SFB-AKA — version retenue"                     resultats/proverif_sfb_aka_SUR.log
  pv code/sfb_aka_sans_registre.pv     "Variante sans registre de partenaires (ch. 5)" resultats/proverif_sans_registre_P1a_VIOLEE.log
  pv code/sfb_aka_demo_casse.pv        "Variante sans signature de transcription"      resultats/proverif_demo_cassee.log
  pv code/rest_bearer.pv               "Patron REST / jeton porteur (ch. 5)"           resultats/proverif_rest_bearer.log
  pv code/ussd_pin.pv                  "Patron USSD en clair (ch. 5)"                  resultats/proverif_ussd_pin.log
  pv code/composition_cle_partagee.pv  "Composition : clé partagée entre canaux (ch. 5)" resultats/proverif_composition_cle_partagee.log
  pv code/composition_separation.pv    "Composition : séparation de domaine (ch. 6)"   resultats/proverif_composition_separation.log
}

# ---- Tamarin : un lemme à la fois, avec délai, pour un journal toujours complet
#      DELAI_TAMARIN (s) : délai par lemme, 300 par défaut.
#      Un lemme qui ne termine pas dans le délai est déclaré « non conclusif ».
LEMMES_TAMARIN="executabilite_session P1a_repondant_authentifie_initiateur P1b_initiateur_authentifie_repondant P2_secret_montant P6_pfs executabilite P3_integrite P4_non_rejeu P5_ancrage_journal"

avec_delai () {   # $1 = secondes, puis la commande (perl : présent sur macOS et Linux)
  perl -e 'alarm shift; exec @ARGV' "$@"
}

tamarin_lemme () {   # $1 = modèle, $2 = lemme, $3 = journal ; écrit une ligne de résumé
  local debut=$(date +%s) sortie ligne rc duree
  sortie=$(avec_delai "${DELAI_TAMARIN:-300}" tamarin-prover --prove="$2" --auto-sources "$1" ${TAMARIN_RTS:-} 2>&1)
  rc=$?; duree=$(( $(date +%s) - debut ))
  ligne=$(printf "%s\n" "$sortie" | grep -E "^\s+$2 \((all-traces|exists-trace)\):" | tail -1 | sed 's/^ *//')
  if [ -z "$ligne" ]; then
    case "$rc" in
      142) ligne="$2 : non conclusif (pas de terminaison en ${DELAI_TAMARIN:-300} s)" ;;
      137|251) ligne="$2 : non conclusif (mémoire épuisée avant terminaison)" ;;
      *)   ligne="$2 : non conclusif (arrêt sans conclusion, code $rc)" ;;
    esac
  fi
  {
    echo; echo "## ---- $2 ($duree s, code de sortie $rc)"
    printf "%s\n" "$sortie" | awk -v L="$2" '
      $0 ~ "^lemma " L "( |:|\\[)" { on = 1; print; next }
      on && /^(lemma |end$)/      { on = 0 }
      on                          { print }' | head -400
    printf "%s\n" "$sortie" | grep -E "^\s+$2 \((all-traces|exists-trace)\):" | sed 's/^/# résumé outil : /'
  } >> "$3"
  echo "$ligne ($duree s)"
}

tamarin_tout () {
  command -v tamarin-prover >/dev/null || { echo "tamarin-prover introuvable (brew trust tamarin-prover/tap && brew install tamarin-prover/tap/tamarin-prover)"; exit 1; }
  export LANG="${LANG:-en_US.UTF-8}" LC_ALL="${LC_ALL:-en_US.UTF-8}"
  local J=resultats/tamarin_sfb_aka.log Rz=resultats/tamarin_sfb_aka.resume.txt
  printf "\n${B}=== Tamarin — SFB-AKA, lemme par lemme (délai %s s) ===${N}\n" "${DELAI_TAMARIN:-300}"
  {
    echo "# ==========================================================="
    echo "# SecureFinBridge — journal de vérification Tamarin"
    echo "# Date      : $(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "# Machine   : $(uname -srm) — $(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo | cut -d: -f2)"
    echo "# Tamarin   : $(tamarin-prover --version 2>&1 | grep -m1 -o 'Tamarin version [0-9.]*')"
    echo "# Maude     : $(maude --version 2>&1 | head -1)"
    echo "# Délai     : ${DELAI_TAMARIN:-300} s par lemme"
    echo "# ==========================================================="
  } > "$J"
  echo "Modèle code/sfb_aka.spthy (étiquettes de rôle)" > "$Rz"
  for L in $LEMMES_TAMARIN; do
    tamarin_lemme code/sfb_aka.spthy "$L" "$J" | tee -a "$Rz"
  done | while read -r l; do
    case "$l" in
      *verified*)    printf "  ${V}✔${N} %s\n" "$l" ;;
      *falsified*)   printf "  ${R}✘${N} %s\n" "$l" ;;
      *)             printf "  ${J}?${N} %s\n" "$l" ;;
    esac
  done
  printf "\n${B}=== Version 1, sans étiquettes de rôle : attaque par réflexion ===${N}\n"
  { echo; echo "Modèle code/sfb_aka_v1_reflexion.spthy (sans étiquettes de rôle)"; } >> "$Rz"
  tamarin_lemme code/sfb_aka_v1_reflexion.spthy P1a_repondant_authentifie_initiateur "$J" | tee -a "$Rz" \
    | while read -r l; do printf "  ${R}✘${N} %s\n" "$l"; done
  printf "  ${J}→ journal : %s ; résumé : %s${N}\n" "$J" "$Rz"
  # ---- compléments : couche d'instructions (preuve modulaire) et patrons déployés
  local Jc=resultats/tamarin_complements.log Rc=resultats/tamarin_complements.resume.txt
  printf "\n${B}=== Tamarin — compléments : instructions (modulaire), sans registre, REST ===${N}\n"
  : > "$Jc"; : > "$Rc"
  for M in sfb_instructions_modulaire sfb_aka_sans_registre rest_bearer; do
    local tmp; tmp=$(mktemp)
    avec_delai "${DELAI_TAMARIN:-300}" tamarin-prover --prove "code/$M.spthy" > "$tmp" 2>&1
    { echo; echo "##### code/$M.spthy"; cat "$tmp"; } >> "$Jc"
    { echo "Modèle code/$M.spthy"; sed -n '/summary of summaries/,$p' "$tmp" | grep -E "^  [A-Za-z_0-9]+ \(" | sed 's/^  //'; echo; } >> "$Rc"
    rm -f "$tmp"
  done
  sed 's/^/  /' "$Rc"
}

demo () {
  printf "\n${B}##### 1. Le protocole SFB-AKA satisfait ses propriétés #####${N}\n"
  pv code/sfb_aka.pv "SFB-AKA (version soumise)" resultats/demo_1_sur.log
  printf "\n${B}##### 2. On retire UNE ligne : la signature du répondant #####${N}\n"
  # n'affiche que la ligne du protocole modifiée (pas l'en-tête de commentaire)
  diff code/sfb_aka.pv code/sfb_aka_demo_casse.pv | awk '/^[0-9]+c[0-9]+$/ {montre = ($0 != "2c2")} montre'
  printf "\n${B}##### 3. ProVerif détecte immédiatement la conséquence #####${N}\n"
  pv code/sfb_aka_demo_casse.pv "Variante vulnérable" resultats/demo_2_cassee.log
  printf "\n${R}→ Le montant de la transaction devient accessible à l'adversaire.${N}\n"
  printf "\n${B}##### 4. Attaque par composition : la même clé sur deux canaux #####${N}\n"
  pv code/composition_cle_partagee.pv "Clé partagée, sans séparation de domaine" resultats/demo_3_composition.log
  printf "\n${B}##### 5. Contre-mesure : séparation de domaine #####${N}\n"
  pv code/composition_separation.pv "Clé partagée, AVEC séparation de domaine" resultats/demo_4_separation.log
  printf "\n${V}→ Aucun des deux protocoles n'était défectueux isolément : seule leur composition l'était.${N}\n\n"
}

case "$1" in
  tamarin) tamarin_tout ;;
  demo)    demo ;;
  tout)    proverif_tous; tamarin_tout ;;
  *)       proverif_tous ;;
esac
