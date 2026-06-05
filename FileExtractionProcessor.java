import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileExtractionProcessor {

    static class FieldConfig {
        String champ;
        int position;
        int longueur;

        FieldConfig(String champ, int position, int longueur) {
            this.champ = champ;
            this.position = position;
            this.longueur = longueur;
        }
    }

    public static void main(String[] args) {
        try {
            // Chemins des fichiers
            String configFile = "config.csv";           // Fichier de configuration (3 colonnes)
            String zipFile = "data.zip";                // Fichier ZIP contenant le TXT
            String txtFileName = "data.txt";            // Nom du fichier TXT dans le ZIP
            String outputFile = "extraction_result.csv"; // Fichier de résultat

            // Lire la configuration
            List<FieldConfig> configs = readConfiguration(configFile);
            System.out.println("✓ Configuration chargée: " + configs.size() + " champs");

            // Extraire et lire le fichier TXT du ZIP
            List<String> lines = readTxtFromZip(zipFile, txtFileName);
            System.out.println("✓ Fichier TXT extrait du ZIP: " + lines.size() + " lignes");

            // Extraire les données
            extractAndSave(configs, lines, outputFile);
            System.out.println("✓ Fichier de résultat généré: " + outputFile);

        } catch (Exception e) {
            System.err.println("❌ Erreur: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Lit le fichier de configuration CSV (3 colonnes: champ;position;longueur)
     */
    static List<FieldConfig> readConfiguration(String configFile) throws IOException {
        List<FieldConfig> configs = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(configFile), StandardCharsets.UTF_8);

        // Ignorer la première ligne (header)
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(";");
            if (parts.length >= 3) {
                try {
                    String champ = parts[0].trim();
                    int position = Integer.parseInt(parts[1].trim());
                    int longueur = Integer.parseInt(parts[2].trim());

                    // Convertir position base 1 en index base 0
                    int indexBase0 = position - 1;

                    configs.add(new FieldConfig(champ, indexBase0, longueur));
                } catch (NumberFormatException e) {
                    System.err.println("⚠ Ligne invalide: " + line);
                }
            }
        }

        return configs;
    }

    /**
     * Extrait le fichier TXT du ZIP et retourne les lignes
     */
    static List<String> readTxtFromZip(String zipFilePath, String txtFileName) throws IOException {
        List<String> lines = new ArrayList<>();

        try (ZipFile zip = new ZipFile(zipFilePath)) {
            ZipEntry entry = zip.getEntry(txtFileName);

            if (entry == null) {
                throw new FileNotFoundException("Fichier '" + txtFileName + "' non trouvé dans le ZIP");
            }

            // Lire le contenu du fichier TXT depuis le ZIP
            try (InputStream inputStream = zip.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        }

        return lines;
    }

    /**
     * Extrait les données et génère le fichier CSV résultat
     */
    static void extractAndSave(List<FieldConfig> configs, List<String> lines, String outputFile) throws IOException {
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {

            // Écrire le header
            StringBuilder header = new StringBuilder("Champ");
            for (int i = 1; i <= lines.size(); i++) {
                header.append(";Valeur_").append(i);
            }
            writer.println(header.toString());

            // Extraire et écrire chaque champ
            for (FieldConfig config : configs) {
                StringBuilder row = new StringBuilder(config.champ);

                for (String line : lines) {
                    // Vérifier que la ligne a suffisamment de caractères
                    String value = "";
                    if (config.position < line.length()) {
                        int endIndex = Math.min(config.position + config.longueur, line.length());
                        value = line.substring(config.position, endIndex);
                    }

                    row.append(";").append(value);
                }

                writer.println(row.toString());
            }
        }
    }
}

class OutputStreamWriter extends java.io.OutputStreamWriter {
    public OutputStreamWriter(OutputStream out, java.nio.charset.Charset cs) {
        super(out, cs);
    }
}
