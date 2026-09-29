import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class Torrent {

    private static final int PIECE_LENGTH = 512 * 1024;

    private static final String INPUT_DIR  = "archivos";
    private static final String OUTPUT_DIR = "torrents";

    public static void main(String[] args) {
        if (args.length == 0) {
            try {
                interactiveMenu();
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            return;
        }

        if (args.length != 3) {
            System.err.println("Uso: java Torrent <ip-tracker> <puerto-tracker> <nombre-del-archivo>");
            System.err.println("O simplemente: java Torrent");
            System.exit(1);
            return;
        }

        String trackerIp   = args[0];
        String trackerPort = args[1];
        String fileName    = args[2];

        try {

            int port = Integer.parseInt(trackerPort);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("Puerto fuera de rango");
            }

            Path inputPath = Paths.get(INPUT_DIR, fileName);
            if (!Files.exists(inputPath) || !Files.isRegularFile(inputPath)) {
                System.err.println("Error: no se encontro el archivo '" + fileName +
                        "' dentro de la carpeta '" + INPUT_DIR + "'.");
                System.exit(1);
                return;
            }

            Path outputDir = Paths.get(OUTPUT_DIR);
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }

            createTorrent(inputPath, outputDir, trackerIp, port);

        } catch (NumberFormatException e) {
            System.err.println("Error: el puerto debe ser un numero entero valido entre 1 y 65535.");
            System.exit(1);
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Error: no se encontro el algoritmo de hash SHA-1 en este sistema.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error de E/S: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void interactiveMenu() throws Exception {
        java.util.Scanner sc = new java.util.Scanner(System.in);
        System.out.println("\n==============================================");
        System.out.println("       GENERADOR DE TORRENTS - MENU");
        System.out.println("==============================================");
        java.nio.file.Path dir = java.nio.file.Paths.get(INPUT_DIR);
        if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectories(dir);
        java.util.List<Path> files;
        try (var st = Files.list(dir)) {
            files = st.filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparing(x -> x.getFileName().toString().toLowerCase()))
                    .toList();
        }
        if (files.isEmpty()) { System.out.println("No hay archivos en 'archivos'."); return; }
        for (int i=0;i<files.size();i++)
            System.out.printf("%d. %s%n", i+1, files.get(i).getFileName());
        int n=-1;
        while(n<0 || n>=files.size()) {
            System.out.print("Seleccione el numero del archivo: ");
            try { n=Integer.parseInt(sc.nextLine().trim())-1; } catch(Exception e){ n=-1; }
        }
        System.out.print("IP del Tracker [127.0.0.1]: ");
        String ip=sc.nextLine().trim(); if(ip.isBlank()) ip="127.0.0.1";
        System.out.print("Puerto del Tracker [5000]: ");
        String ps=sc.nextLine().trim(); int port=ps.isBlank()?5000:Integer.parseInt(ps);
        createTorrent(files.get(n), Paths.get(OUTPUT_DIR), ip, port);
    }

    public static Path createTorrent(Path inputPath, Path outputDir, String trackerIp, int trackerPort)
            throws IOException, NoSuchAlgorithmException {

        String fileName = inputPath.getFileName().toString();
        long fileSize = Files.size(inputPath);

        List<String> pieceHashesHex = new ArrayList<>();
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        MessageDigest infoDigest = MessageDigest.getInstance("SHA-1"); // hash acumulado -> infoHash

        byte[] buffer = new byte[PIECE_LENGTH];

        try (InputStream in = Files.newInputStream(inputPath)) {
            int bytesLeidos;
            while ((bytesLeidos = readFully(in, buffer)) > 0) {
                byte[] pieceData = trim(buffer, bytesLeidos);
                byte[] pieceHash = sha1.digest(pieceData);
                pieceHashesHex.add(bytesToHex(pieceHash));
                infoDigest.update(pieceHash); // el infoHash se calcula sobre la concatenacion de hashes
            }
        }

        byte[] infoHashBytes = infoDigest.digest();
        String infoHashHex = bytesToHex(infoHashBytes);
        int pieceCount = pieceHashesHex.size();

 
        String baseName = quitarExtension(fileName);
        String outputFileName = baseName + ".torrent";
        Path outputPath = outputDir.resolve(outputFileName);

        String json = construirJson(fileName, fileSize, trackerIp, trackerPort,
                PIECE_LENGTH, pieceCount, pieceHashesHex, infoHashHex);

        Files.write(outputPath, json.getBytes(StandardCharsets.UTF_8));

        System.out.println("Torrent generado exitosamente:");
        System.out.println("  Archivo origen : " + inputPath.toAbsolutePath());
        System.out.println("  Tamanio        : " + fileSize + " bytes");
        System.out.println("  Piezas         : " + pieceCount + " (de " + PIECE_LENGTH + " bytes c/u)");
        System.out.println("  Tracker        : " + trackerIp + ":" + trackerPort);
        System.out.println("  Info hash      : " + infoHashHex);
        System.out.println("  Salida         : " + outputPath.toAbsolutePath());
        return outputPath;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        int leidos;
        while (total < buffer.length && (leidos = in.read(buffer, total, buffer.length - total)) != -1) {
            total += leidos;
        }
        return total;
    }
    
    private static byte[] trim(byte[] array, int length) {
        if (length == array.length) return array.clone();
        byte[] result = new byte[length];
        System.arraycopy(array, 0, result, 0, length);
        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String quitarExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return (idx == -1) ? fileName : fileName.substring(0, idx);
    }

    private static String escapeJson(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String construirJson(String fileName, long fileSize, String trackerIp, int trackerPort,
                                         int pieceLength, int pieceCount, List<String> pieces, String infoHash) {
        StringBuilder sb = new StringBuilder();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        sb.append("{\n");
        sb.append("  \"announce\": \"").append(escapeJson(trackerIp)).append(":").append(trackerPort).append("\",\n");
        sb.append("  \"createdBy\": \"TorrentSimulator/1.0\",\n");
        sb.append("  \"creationDate\": \"").append(timestamp).append("\",\n");
        sb.append("  \"infoHash\": \"").append(infoHash).append("\",\n");
        sb.append("  \"info\": {\n");
        sb.append("    \"name\": \"").append(escapeJson(fileName)).append("\",\n");
        sb.append("    \"length\": ").append(fileSize).append(",\n");
        sb.append("    \"pieceLength\": ").append(pieceLength).append(",\n");
        sb.append("    \"pieceCount\": ").append(pieceCount).append(",\n");
        sb.append("    \"pieces\": [\n");
        for (int i = 0; i < pieces.size(); i++) {
            sb.append("      \"").append(pieces.get(i)).append("\"");
            sb.append(i < pieces.size() - 1 ? ",\n" : "\n");
        }
        sb.append("    ]\n");
        sb.append("  }\n");
        sb.append("}\n");

        return sb.toString();
    }
}