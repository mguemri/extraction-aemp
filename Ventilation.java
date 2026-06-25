import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Algorithme de ventilation (proratisation).
 *
 * Contexte:
 *  - Tableau du BAS  : plusieurs lignes, chacune avec une période (du / au).
 *      ex: 01/06/2025 -> 01/06/2025
 *          04/06/2025 -> 05/06/2025
 *          10/06/2025 -> 12/06/2025
 *  - Tableau du HAUT : une seule ligne couvrant la période globale (ex: 01/06/2025 -> 30/06/2025)
 *    sur laquelle l'utilisateur saisit UNE seule fois les montants/quantités.
 *
 * Règle:
 *  - La "date de paye" saisie en haut est recopiée telle quelle sur chaque ligne du bas.
 *  - Les montants (paye, icccp, ifm, primes) et la quantité saisis en haut sont
 *    ventilés sur les lignes du bas au prorata du nombre de jours de chaque période.
 *
 *    valeurLigne = valeurSaisie * (nbJoursLigne / nbJoursTotalDesLignes)
 *
 *    Le nombre de jours est compté en INCLUANT les deux bornes
 *    (du 01/06 au 04/06 = 4 jours).
 *
 *  - La répartition utilise la méthode du plus fort reste afin que la somme
 *    des lignes ventilées soit STRICTEMENT égale au montant saisi (pas de perte
 *    ni de gain dû aux arrondis).
 */
public class Ventilation {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Une ligne du tableau du bas: une période et les valeurs ventilées. */
    static class LignePeriode {
        final LocalDate dateDebut;
        final LocalDate dateFin;
        final long nbJours;

        // Valeurs reçues par ventilation
        String datePaye;
        BigDecimal quantite = BigDecimal.ZERO;
        BigDecimal paye = BigDecimal.ZERO;
        BigDecimal icccp = BigDecimal.ZERO;
        BigDecimal ifm = BigDecimal.ZERO;
        BigDecimal primes = BigDecimal.ZERO;

        LignePeriode(LocalDate dateDebut, LocalDate dateFin) {
            if (dateFin.isBefore(dateDebut)) {
                throw new IllegalArgumentException(
                        "Période invalide: " + dateDebut.format(FMT) + " -> " + dateFin.format(FMT));
            }
            this.dateDebut = dateDebut;
            this.dateFin = dateFin;
            // +1 car les deux bornes sont incluses
            this.nbJours = ChronoUnit.DAYS.between(dateDebut, dateFin) + 1;
        }

        LignePeriode(String dateDebut, String dateFin) {
            this(LocalDate.parse(dateDebut, FMT), LocalDate.parse(dateFin, FMT));
        }
    }

    /** La saisie unique du tableau du haut. */
    static class SaisieHaut {
        String datePaye;          // recopiée sur chaque ligne
        BigDecimal quantite;
        BigDecimal paye;
        BigDecimal icccp;
        BigDecimal ifm;
        BigDecimal primes;

        SaisieHaut(String datePaye, BigDecimal quantite, BigDecimal paye,
                   BigDecimal icccp, BigDecimal ifm, BigDecimal primes) {
            this.datePaye = datePaye;
            this.quantite = quantite;
            this.paye = paye;
            this.icccp = icccp;
            this.ifm = ifm;
            this.primes = primes;
        }
    }

    /**
     * Ventile la saisie du haut sur les lignes du bas, au prorata du nombre de jours.
     *
     * @param lignes   lignes du tableau du bas (modifiées en place)
     * @param saisie   saisie unique du tableau du haut
     * @param decimales nombre de décimales à conserver pour les montants ventilés
     */
    static void ventiler(List<LignePeriode> lignes, SaisieHaut saisie, int decimales) {
        if (lignes == null || lignes.isEmpty()) {
            throw new IllegalArgumentException("Aucune ligne à ventiler.");
        }

        long totalJours = 0;
        for (LignePeriode l : lignes) {
            totalJours += l.nbJours;
        }
        if (totalJours <= 0) {
            throw new IllegalArgumentException("Le nombre total de jours doit être strictement positif.");
        }

        // Poids = nb de jours de chaque ligne
        long[] poids = new long[lignes.size()];
        for (int i = 0; i < lignes.size(); i++) {
            poids[i] = lignes.get(i).nbJours;
        }

        BigDecimal[] quantites = repartir(saisie.quantite, poids, totalJours, decimales);
        BigDecimal[] payes     = repartir(saisie.paye,     poids, totalJours, decimales);
        BigDecimal[] icccps    = repartir(saisie.icccp,    poids, totalJours, decimales);
        BigDecimal[] ifms      = repartir(saisie.ifm,      poids, totalJours, decimales);
        BigDecimal[] primess   = repartir(saisie.primes,   poids, totalJours, decimales);

        for (int i = 0; i < lignes.size(); i++) {
            LignePeriode l = lignes.get(i);
            l.datePaye = saisie.datePaye; // la date de paye est recopiée à l'identique
            l.quantite = quantites[i];
            l.paye = payes[i];
            l.icccp = icccps[i];
            l.ifm = ifms[i];
            l.primes = primess[i];
        }
    }

    /**
     * Répartit un montant total selon des poids (nb de jours), en garantissant que
     * la somme des parts est exactement égale au total (méthode du plus fort reste).
     */
    static BigDecimal[] repartir(BigDecimal total, long[] poids, long totalPoids, int decimales) {
        BigDecimal[] resultat = new BigDecimal[poids.length];
        if (total == null) {
            for (int i = 0; i < poids.length; i++) {
                resultat[i] = BigDecimal.ZERO.setScale(decimales, RoundingMode.HALF_UP);
            }
            return resultat;
        }

        BigDecimal totalArrondi = total.setScale(decimales, RoundingMode.HALF_UP);
        BigDecimal poidsTotalBd = BigDecimal.valueOf(totalPoids);

        // Part théorique (non arrondie) et part plancher (arrondie vers le bas) de chaque ligne
        BigDecimal[] exactes = new BigDecimal[poids.length];
        BigDecimal sommePlanchers = BigDecimal.ZERO;
        for (int i = 0; i < poids.length; i++) {
            exactes[i] = totalArrondi
                    .multiply(BigDecimal.valueOf(poids[i]))
                    .divide(poidsTotalBd, decimales + 6, RoundingMode.HALF_UP);
            resultat[i] = exactes[i].setScale(decimales, RoundingMode.FLOOR);
            sommePlanchers = sommePlanchers.add(resultat[i]);
        }

        // Reste à distribuer (en nombre d'incréments de la plus petite unité)
        BigDecimal unite = BigDecimal.ONE.movePointLeft(decimales); // ex: 0.01 pour 2 décimales
        BigDecimal restant = totalArrondi.subtract(sommePlanchers);
        int incrementsRestants = restant.divide(unite, 0, RoundingMode.HALF_UP).intValueExact();

        // Attribuer les unités restantes aux lignes ayant le plus fort reste fractionnaire
        Integer[] ordre = new Integer[poids.length];
        for (int i = 0; i < ordre.length; i++) ordre[i] = i;
        BigDecimal[] restes = new BigDecimal[poids.length];
        for (int i = 0; i < poids.length; i++) {
            restes[i] = exactes[i].subtract(resultat[i]);
        }
        java.util.Arrays.sort(ordre, (a, b) -> {
            int cmp = restes[b].compareTo(restes[a]);   // plus fort reste d'abord
            if (cmp != 0) return cmp;
            return Long.compare(poids[b], poids[a]);     // puis plus grand poids
        });

        for (int k = 0; k < incrementsRestants; k++) {
            int idx = ordre[k % ordre.length];
            resultat[idx] = resultat[idx].add(unite);
        }
        return resultat;
    }

    public static void main(String[] args) {
        // --- Tableau du BAS : les 3 périodes ---
        List<LignePeriode> lignes = new ArrayList<>();
        lignes.add(new LignePeriode("01/06/2025", "01/06/2025")); // 1 jour
        lignes.add(new LignePeriode("04/06/2025", "05/06/2025")); // 2 jours
        lignes.add(new LignePeriode("10/06/2025", "12/06/2025")); // 3 jours

        // --- Tableau du HAUT : une seule saisie pour la période 01/06 -> 30/06 ---
        SaisieHaut saisie = new SaisieHaut(
                "30/06/2025",                 // date de paye (recopiée)
                new BigDecimal("6"),          // quantité (ex: nb de jours saisis)
                new BigDecimal("23882"),      // paye
                new BigDecimal("1200"),       // icccp
                new BigDecimal("800"),        // ifm
                new BigDecimal("300")         // primes
        );

        // Ventilation au prorata des jours, montants à 2 décimales
        ventiler(lignes, saisie, 2);

        // --- Affichage du résultat ---
        long totalJours = lignes.stream().mapToLong(l -> l.nbJours).sum();
        System.out.println("Nombre total de jours sur les " + lignes.size() + " lignes: " + totalJours);
        System.out.println();
        System.out.printf("%-24s %-6s %-10s %-12s %-10s %-9s %-9s%n",
                "Période", "Jours", "DatePaye", "Paye", "ICCCP", "IFM", "Primes");

        BigDecimal sPaye = BigDecimal.ZERO, sIcccp = BigDecimal.ZERO,
                   sIfm = BigDecimal.ZERO, sPrimes = BigDecimal.ZERO, sQte = BigDecimal.ZERO;
        for (LignePeriode l : lignes) {
            System.out.printf("%-10s -> %-10s %-6d %-10s %-12s %-10s %-9s %-9s%n",
                    l.dateDebut.format(FMT), l.dateFin.format(FMT), l.nbJours,
                    l.datePaye, l.paye, l.icccp, l.ifm, l.primes);
            sQte = sQte.add(l.quantite);
            sPaye = sPaye.add(l.paye);
            sIcccp = sIcccp.add(l.icccp);
            sIfm = sIfm.add(l.ifm);
            sPrimes = sPrimes.add(l.primes);
        }

        System.out.println();
        System.out.println("Contrôle des totaux (doivent égaler la saisie du haut):");
        System.out.println("  Quantité : " + sQte    + " (saisi: " + saisie.quantite + ")");
        System.out.println("  Paye     : " + sPaye   + " (saisi: " + saisie.paye + ")");
        System.out.println("  ICCCP    : " + sIcccp  + " (saisi: " + saisie.icccp + ")");
        System.out.println("  IFM      : " + sIfm    + " (saisi: " + saisie.ifm + ")");
        System.out.println("  Primes   : " + sPrimes + " (saisi: " + saisie.primes + ")");
    }
}
