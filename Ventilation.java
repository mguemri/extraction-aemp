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
 *  1. En HAUT : une ligne avec une période (du / au) + des valeurs saisies une seule fois.
 *  2. En BAS  : plusieurs lignes, chacune avec sa propre période (du / au).
 *  3. On part de la période du HAUT pour retrouver les lignes du BAS concernées.
 *  4. Pour CHAQUE champ, on vérifie d'abord si l'opérateur a déjà saisi une valeur
 *     directement en bas :
 *        - si OUI  -> on garde la valeur saisie en bas (on n'écrase rien) ;
 *        - si NON  -> on la remplit par ventilation depuis le haut, au prorata
 *                     du nombre de jours (bornes incluses : 01/06 -> 04/06 = 4 jours).
 *
 *     La ventilation ne répartit la valeur du haut que sur les lignes restées vides
 *     pour ce champ. Si aucune valeur n'est saisie en haut, on laisse le bas tel quel.
 *
 * Convention : une valeur {@code null} signifie « non saisie ».
 */
public class Ventilation {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ---------------------------------------------------------------------
    // 1) Structures de données (simples)
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

    /**
     * La saisie du tableau du HAUT. Chaque champ est optionnel :
     * {@code null} = l'opérateur n'a rien saisi en haut pour ce champ.
     */
    static class LigneHaut {
        final Periode periode;
        final String datePaye;   // null si non saisie
        final BigDecimal quantite;
        final BigDecimal paye;
        final BigDecimal icccp;
        final BigDecimal ifm;
        final BigDecimal primes;

        LigneHaut(Periode periode, String datePaye, Double quantite, Double paye,
                  Double icccp, Double ifm, Double primes) {
            this.periode = periode;
            this.datePaye = datePaye;
            this.quantite = toBd(quantite);
            this.paye = toBd(paye);
            this.icccp = toBd(icccp);
            this.ifm = toBd(ifm);
            this.primes = toBd(primes);
        }
    }

    /**
     * Une ligne du tableau du BAS. Chaque champ peut être saisi directement par
     * l'opérateur ({@code null} = non saisi, sera rempli par ventilation).
     */
    static class LigneBas {
        final Periode periode;

        String datePaye;       // null = non saisie
        BigDecimal quantite;   // null = non saisie
        BigDecimal paye;
        BigDecimal icccp;
        BigDecimal ifm;
        BigDecimal primes;

        LigneBas(Periode periode) {
            this.periode = periode;
        }
    }

    // ---------------------------------------------------------------------
    // 2) Coeur de l'algorithme
    // ---------------------------------------------------------------------

    /**
     * Complète les lignes du bas concernées par la période du haut, en respectant
     * les valeurs déjà saisies directement en bas.
     *
     * @return les lignes du bas concernées, complétées.
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

        // Etape 2 : la date de paye -> on garde celle du bas si saisie, sinon on copie celle du haut.
        for (LigneBas ligne : concernees) {
            if (estVide(ligne.datePaye) && !estVide(haut.datePaye)) {
                ligne.datePaye = haut.datePaye;
            }
        }

        // Etape 3 : chaque champ montant/quantité -> on ventile uniquement sur les lignes vides.
        ventilerChamp(haut.quantite, concernees, l -> l.quantite, (l, v) -> l.quantite = v, decimales);
        ventilerChamp(haut.paye,     concernees, l -> l.paye,     (l, v) -> l.paye = v,     decimales);
        ventilerChamp(haut.icccp,    concernees, l -> l.icccp,    (l, v) -> l.icccp = v,    decimales);
        ventilerChamp(haut.ifm,      concernees, l -> l.ifm,      (l, v) -> l.ifm = v,      decimales);
        ventilerChamp(haut.primes,   concernees, l -> l.primes,   (l, v) -> l.primes = v,   decimales);

        return concernees;
    }

    /**
     * Ventile la valeur du haut d'un champ sur les lignes du bas restées vides pour ce champ.
     * Les lignes déjà saisies en bas ne sont pas touchées.
     */
    static void ventilerChamp(BigDecimal valeurHaut, List<LigneBas> lignes,
                              Getter getter, Setter setter, int decimales) {
        // Rien à saisir en haut : on laisse le bas tel quel.
        if (valeurHaut == null) {
            return;
        }

        // On ne ventile que sur les lignes vides pour ce champ.
        List<LigneBas> vides = new ArrayList<>();
        for (LigneBas ligne : lignes) {
            if (getter.get(ligne) == null) {
                vides.add(ligne);
            }
        }
        if (vides.isEmpty()) {
            return; // toutes les lignes ont déjà une valeur saisie en bas
        }

        // Poids = nombre de jours de chaque ligne vide.
        long[] jours = new long[vides.size()];
        long joursTotal = 0;
        for (int i = 0; i < vides.size(); i++) {
            jours[i] = vides.get(i).periode.jours();
            joursTotal += jours[i];
        }

        BigDecimal[] parts = repartir(valeurHaut, jours, joursTotal, decimales);
        for (int i = 0; i < vides.size(); i++) {
            setter.set(vides.get(i), parts[i]);
        }
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

        // Part de chaque ligne, arrondie vers le bas, + mémorisation du reste.
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

        // Distribuer les unités manquantes aux lignes ayant le plus fort reste.
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
    // Petits utilitaires
    // ---------------------------------------------------------------------

    interface Getter { BigDecimal get(LigneBas l); }
    interface Setter { void set(LigneBas l, BigDecimal v); }

    static boolean estVide(String s) {
        return s == null || s.trim().isEmpty();
    }

    static BigDecimal toBd(Double d) {
        return d == null ? null : BigDecimal.valueOf(d);
    }

    static String affiche(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    // ---------------------------------------------------------------------
    // 3) Exemple d'utilisation
    // ---------------------------------------------------------------------

    public static void main(String[] args) {
        // Tableau du BAS : toutes les périodes disponibles.
        List<LigneBas> toutesBas = new ArrayList<>();
        LigneBas l1 = new LigneBas(new Periode("01/06/2025", "01/06/2025")); // 1 jour
        LigneBas l2 = new LigneBas(new Periode("04/06/2025", "05/06/2025")); // 2 jours
        LigneBas l3 = new LigneBas(new Periode("10/06/2025", "12/06/2025")); // 3 jours

        // L'opérateur a saisi DIRECTEMENT en bas certaines valeurs :
        l2.paye = new BigDecimal("5000");   // paye saisie à la main -> on ne l'écrase pas
        l1.datePaye = "01/06/2025";          // date de paye saisie à la main sur la 1re ligne

        toutesBas.add(l1);
        toutesBas.add(l2);
        toutesBas.add(l3);
        toutesBas.add(new LigneBas(new Periode("05/07/2025", "06/07/2025"))); // hors période (ignorée)

        // Tableau du HAUT : une seule saisie (certains champs peuvent être null = non saisis).
        LigneHaut haut = new LigneHaut(
                new Periode("01/06/2025", "30/06/2025"),
                "30/06/2025", // date de paye
                6.0,          // quantité
                23882.0,      // paye
                1200.0,       // icccp
                800.0,        // ifm
                null          // primes : non saisi en haut -> rien à ventiler
        );

        List<LigneBas> resultat = ventiler(haut, toutesBas, 2);

        // Affichage.
        System.out.println("Période du haut : " + haut.periode);
        System.out.println("Lignes du bas concernées : " + resultat.size() + "\n");
        System.out.printf("%-24s %-6s %-11s %-11s %-9s %-8s %-8s%n",
                "Période", "Jours", "DatePaye", "Paye", "ICCCP", "IFM", "Primes");
        for (LigneBas l : resultat) {
            System.out.printf("%-24s %-6d %-11s %-11s %-9s %-8s %-8s%n",
                    l.periode, l.periode.jours(),
                    estVide(l.datePaye) ? "-" : l.datePaye,
                    affiche(l.paye), affiche(l.icccp), affiche(l.ifm), affiche(l.primes));
        }
    }
}
