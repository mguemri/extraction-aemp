import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Ventilation (proratisation) d'une saisie unique sur plusieurs périodes.
 *
 * Idée générale (simple) :
 *  1. On a UNE ligne en HAUT : une période (du / au) + des valeurs saisies une seule fois.
 *  2. On a PLUSIEURS lignes en BAS : chacune avec sa propre période (du / au).
 *  3. On part de la période du HAUT pour retrouver les lignes du BAS concernées
 *     (celles qui tombent à l'intérieur de la période du haut).
 *  4. On répartit (ventile) chaque valeur saisie sur ces lignes,
 *     au prorata du nombre de jours de chaque période :
 *
 *        valeurLigne = valeurSaisie * (joursDeLaLigne / joursTotalDesLignes)
 *
 *     Le nombre de jours inclut les deux bornes (du 01/06 au 04/06 = 4 jours).
 *     La date de paye, elle, est simplement recopiée sur chaque ligne.
 */
public class Ventilation {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ---------------------------------------------------------------------
    // 1) Les structures de données (volontairement simples)
    // ---------------------------------------------------------------------

    /** Une période avec une date de début et une date de fin. */
    static class Periode {
        final LocalDate debut;
        final LocalDate fin;

        Periode(String debut, String fin) {
            this.debut = LocalDate.parse(debut, FMT);
            this.fin = LocalDate.parse(fin, FMT);
            if (this.fin.isBefore(this.debut)) {
                throw new IllegalArgumentException("Période invalide : " + debut + " -> " + fin);
            }
        }

        /** Nombre de jours, bornes incluses (01/06 -> 04/06 = 4). */
        long jours() {
            return ChronoUnit.DAYS.between(debut, fin) + 1;
        }

        /** Vrai si cette période est entièrement contenue dans {@code autre}. */
        boolean estDans(Periode autre) {
            return !debut.isBefore(autre.debut) && !fin.isAfter(autre.fin);
        }

        @Override
        public String toString() {
            return debut.format(FMT) + " -> " + fin.format(FMT);
        }
    }

    /** La saisie unique du tableau du HAUT. */
    static class LigneHaut {
        final Periode periode;
        final String datePaye;     // recopiée telle quelle sur chaque ligne du bas
        final BigDecimal quantite;
        final BigDecimal paye;
        final BigDecimal icccp;
        final BigDecimal ifm;
        final BigDecimal primes;

        LigneHaut(Periode periode, String datePaye, double quantite, double paye,
                  double icccp, double ifm, double primes) {
            this.periode = periode;
            this.datePaye = datePaye;
            this.quantite = BigDecimal.valueOf(quantite);
            this.paye = BigDecimal.valueOf(paye);
            this.icccp = BigDecimal.valueOf(icccp);
            this.ifm = BigDecimal.valueOf(ifm);
            this.primes = BigDecimal.valueOf(primes);
        }
    }

    /** Une ligne du tableau du BAS : une période + les valeurs ventilées. */
    static class LigneBas {
        final Periode periode;

        // Remplies par la ventilation
        String datePaye = "";
        BigDecimal quantite = BigDecimal.ZERO;
        BigDecimal paye = BigDecimal.ZERO;
        BigDecimal icccp = BigDecimal.ZERO;
        BigDecimal ifm = BigDecimal.ZERO;
        BigDecimal primes = BigDecimal.ZERO;

        LigneBas(Periode periode) {
            this.periode = periode;
        }
    }

    // ---------------------------------------------------------------------
    // 2) Le coeur de l'algorithme
    // ---------------------------------------------------------------------

    /**
     * Ventile la saisie du haut sur les lignes du bas concernées.
     *
     * @param haut       la saisie unique (période + valeurs)
     * @param toutesBas  toutes les lignes du bas disponibles
     * @param decimales  nombre de décimales des montants ventilés (ex: 2)
     * @return           uniquement les lignes du bas concernées, avec leurs valeurs ventilées
     */
    static List<LigneBas> ventiler(LigneHaut haut, List<LigneBas> toutesBas, int decimales) {
        // Etape 1 : partir de la période du haut pour sélectionner les lignes du bas.
        List<LigneBas> concernees = new ArrayList<>();
        for (LigneBas ligne : toutesBas) {
            if (ligne.periode.estDans(haut.periode)) {
                concernees.add(ligne);
            }
        }
        if (concernees.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aucune ligne du bas ne tombe dans la période " + haut.periode);
        }

        // Etape 2 : calculer le nombre de jours par ligne et le total.
        long[] jours = new long[concernees.size()];
        long joursTotal = 0;
        for (int i = 0; i < concernees.size(); i++) {
            jours[i] = concernees.get(i).periode.jours();
            joursTotal += jours[i];
        }

        // Etape 3 : ventiler chaque champ au prorata des jours.
        BigDecimal[] quantites = repartir(haut.quantite, jours, joursTotal, decimales);
        BigDecimal[] payes     = repartir(haut.paye,     jours, joursTotal, decimales);
        BigDecimal[] icccps    = repartir(haut.icccp,    jours, joursTotal, decimales);
        BigDecimal[] ifms      = repartir(haut.ifm,      jours, joursTotal, decimales);
        BigDecimal[] primess   = repartir(haut.primes,   jours, joursTotal, decimales);

        // Etape 4 : recopier les résultats (et la date de paye) sur chaque ligne.
        for (int i = 0; i < concernees.size(); i++) {
            LigneBas ligne = concernees.get(i);
            ligne.datePaye = haut.datePaye;
            ligne.quantite = quantites[i];
            ligne.paye = payes[i];
            ligne.icccp = icccps[i];
            ligne.ifm = ifms[i];
            ligne.primes = primess[i];
        }
        return concernees;
    }

    /**
     * Répartit un montant total selon des poids (le nombre de jours), en garantissant
     * que la somme des parts est EXACTEMENT égale au total (méthode du plus fort reste,
     * pour éviter toute perte d'arrondi).
     */
    static BigDecimal[] repartir(BigDecimal total, long[] jours, long joursTotal, int decimales) {
        BigDecimal[] parts = new BigDecimal[jours.length];
        BigDecimal totalArrondi = total.setScale(decimales, RoundingMode.HALF_UP);
        BigDecimal joursTotalBd = BigDecimal.valueOf(joursTotal);
        BigDecimal unite = BigDecimal.ONE.movePointLeft(decimales); // ex : 0.01

        // 3a) Part de chaque ligne, arrondie vers le bas, + mémorisation du reste.
        BigDecimal[] restes = new BigDecimal[jours.length];
        BigDecimal sommeParts = BigDecimal.ZERO;
        for (int i = 0; i < jours.length; i++) {
            BigDecimal exact = totalArrondi
                    .multiply(BigDecimal.valueOf(jours[i]))
                    .divide(joursTotalBd, decimales + 6, RoundingMode.HALF_UP);
            parts[i] = exact.setScale(decimales, RoundingMode.FLOOR);
            restes[i] = exact.subtract(parts[i]);
            sommeParts = sommeParts.add(parts[i]);
        }

        // 3b) Distribuer les unités manquantes aux lignes ayant le plus fort reste.
        int manquantes = totalArrondi.subtract(sommeParts)
                .divide(unite, 0, RoundingMode.HALF_UP).intValueExact();
        for (int n = 0; n < manquantes; n++) {
            int meilleur = 0;
            for (int i = 1; i < restes.length; i++) {
                if (restes[i].compareTo(restes[meilleur]) > 0) {
                    meilleur = i;
                }
            }
            parts[meilleur] = parts[meilleur].add(unite);
            restes[meilleur] = BigDecimal.valueOf(-1); // déjà servie
        }
        return parts;
    }

    // ---------------------------------------------------------------------
    // 3) Exemple d'utilisation
    // ---------------------------------------------------------------------

    public static void main(String[] args) {
        // Tableau du BAS : toutes les périodes disponibles.
        List<LigneBas> toutesBas = new ArrayList<>();
        toutesBas.add(new LigneBas(new Periode("01/06/2025", "01/06/2025"))); // 1 jour
        toutesBas.add(new LigneBas(new Periode("04/06/2025", "05/06/2025"))); // 2 jours
        toutesBas.add(new LigneBas(new Periode("10/06/2025", "12/06/2025"))); // 3 jours
        toutesBas.add(new LigneBas(new Periode("05/07/2025", "06/07/2025"))); // hors période (ignorée)

        // Tableau du HAUT : une seule saisie pour la période 01/06 -> 30/06.
        LigneHaut haut = new LigneHaut(
                new Periode("01/06/2025", "30/06/2025"),
                "30/06/2025", // date de paye (recopiée)
                6,            // quantité
                23882,        // paye
                1200,         // icccp
                800,          // ifm
                300           // primes
        );

        // Ventilation : on part de la période du haut pour retrouver les lignes du bas.
        List<LigneBas> resultat = ventiler(haut, toutesBas, 2);

        // Affichage.
        long joursTotal = resultat.stream().mapToLong(l -> l.periode.jours()).sum();
        System.out.println("Période du haut : " + haut.periode);
        System.out.println("Lignes du bas concernées : " + resultat.size()
                + " (total " + joursTotal + " jours)\n");

        System.out.printf("%-24s %-6s %-11s %-11s %-9s %-8s %-8s%n",
                "Période", "Jours", "DatePaye", "Paye", "ICCCP", "IFM", "Primes");
        for (LigneBas l : resultat) {
            System.out.printf("%-24s %-6d %-11s %-11s %-9s %-8s %-8s%n",
                    l.periode, l.periode.jours(), l.datePaye, l.paye, l.icccp, l.ifm, l.primes);
        }

        // Contrôle : la somme des lignes doit égaler la saisie du haut.
        System.out.println("\nContrôle des totaux (doivent égaler la saisie) :");
        System.out.println("  Paye   : " + somme(resultat, l -> l.paye)   + " / " + haut.paye);
        System.out.println("  ICCCP  : " + somme(resultat, l -> l.icccp)  + " / " + haut.icccp);
        System.out.println("  IFM    : " + somme(resultat, l -> l.ifm)    + " / " + haut.ifm);
        System.out.println("  Primes : " + somme(resultat, l -> l.primes) + " / " + haut.primes);
        System.out.println("  Qté    : " + somme(resultat, l -> l.quantite) + " / " + haut.quantite);
    }

    /** Petite aide pour additionner un champ de toutes les lignes. */
    interface Champ { BigDecimal de(LigneBas l); }

    static BigDecimal somme(List<LigneBas> lignes, Champ champ) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneBas l : lignes) {
            total = total.add(champ.de(l));
        }
        return total;
    }
}
