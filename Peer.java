import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

class TorrentInfo {
    String fileName, infoHash;
    long length;
    int pieceLength, pieceCount;
    List<String> pieces = new ArrayList<>();

    static TorrentInfo load(Path path) throws IOException {
        String s = Files.readString(path);
        TorrentInfo t = new TorrentInfo();
        t.fileName = match(s, "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        t.infoHash = match(s, "\\\"infoHash\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        t.length = Long.parseLong(match(s, "\\\"length\\\"\\s*:\\s*(\\d+)"));
        t.pieceLength = Integer.parseInt(match(s, "\\\"pieceLength\\\"\\s*:\\s*(\\d+)"));
        t.pieceCount = Integer.parseInt(match(s, "\\\"pieceCount\\\"\\s*:\\s*(\\d+)"));
        String pieces = match(s, "\\\"pieces\\\"\\s*:\\s*\\[(.*?)\\]", true);
        var m = java.util.regex.Pattern.compile("\\\"([0-9a-fA-F]{40})\\\"").matcher(pieces);
        while (m.find()) t.pieces.add(m.group(1));
        return t;
    }

    private static String match(String s, String regex) { return match(s, regex, false); }
    private static String match(String s, String regex, boolean dotall) {
        var p = java.util.regex.Pattern.compile(regex, dotall ? java.util.regex.Pattern.DOTALL : 0);
        var m = p.matcher(s);
        if (!m.find()) throw new IllegalArgumentException("Campo no encontrado: " + regex);
        return m.group(1);
    }
}

class Protocol {
    static String sha1(byte[] data) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

public class Peer {
    static class RemotePeer {
        String id, ip; int port;
        Set<Integer> pieces = new HashSet<>();
        RemotePeer(String id, String ip, int port, String pieceList) {
            this.id=id; this.ip=ip; this.port=port;
            if(pieceList != null && !pieceList.isBlank())
                for(String x: pieceList.split(",")) if(!x.isBlank()) pieces.add(Integer.parseInt(x));
        }
    }

    private final String id, trackerHost;
    private final int port, trackerPort;
    private final Path sharedDir, downloadsDir;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = true;
    private final Scanner console = new Scanner(System.in);

    public Peer(String id, int port, String trackerHost, int trackerPort) {
        this.id=id; this.port=port; this.trackerHost=trackerHost; this.trackerPort=trackerPort;
        sharedDir=Paths.get("peer_"+id, "shared");
        downloadsDir=Paths.get("peer_"+id, "downloads");
    }

    public void start() throws Exception {
        Files.createDirectories(sharedDir);
        Files.createDirectories(downloadsDir);
        server = new ServerSocket(port);
        pool.submit(this::acceptLoop);
        String ip = InetAddress.getLocalHost().getHostAddress();
        tracker("REGISTER|" + id + "|" + ip + "|" + port);
        autoShare();
        System.out.println("\n==============================================");
        System.out.println("          PEER BITTORRENT - " + id);
        System.out.println("==============================================");
        System.out.println("Direccion: " + ip + ":" + port);
        System.out.println("Tracker  : " + trackerHost + ":" + trackerPort);
        menuLoop();
        shutdown();
    }

    private void menuLoop() {
        while (running) {
            printMenu();
            String op = readOption("Seleccione una opcion: ");
            try {
                switch (op) {
                    case "1" -> createTorrentMenu();
                    case "2" -> shareTorrentMenu();
                    case "3" -> downloadTorrentMenu();
                    case "4" -> showConnectedNodes();
                    case "5" -> showMyFiles();
                    case "6" -> showDownloads();
                    case "7" -> showAvailableTorrents();
                    case "8" -> refreshAutoShare();
                    case "9" -> { running = false; }
                    default -> System.out.println("Opcion no valida.");
                }
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getMessage());
            }
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("+------------------------------------------------+");
        System.out.println("|              MENU PRINCIPAL - PEER             |");
        System.out.println("+------------------------------------------------+");
        System.out.println("| 1. Crear torrent de un archivo                 |");
        System.out.println("| 2. Agregar torrent a compartir                |");
        System.out.println("| 3. Descargar / continuar torrent              |");
        System.out.println("| 4. Ver nodos conectados y sus roles            |");
        System.out.println("| 5. Ver mis archivos compartidos                |");
        System.out.println("| 6. Ver mis descargas / archivos en consumo     |");
        System.out.println("| 7. Ver torrents disponibles                    |");
        System.out.println("| 8. Actualizar archivos compartidos             |");
        System.out.println("| 9. Salir                                        |");
        System.out.println("+------------------------------------------------+");
    }

    private String readOption(String prompt) {
        System.out.print(prompt);
        return console.nextLine().trim();
    }

    private void createTorrentMenu() throws Exception {
        List<Path> files = listRegularFiles(Paths.get("archivos"));
        if (files.isEmpty()) {
            System.out.println("No hay archivos en la carpeta 'archivos'.");
            return;
        }
        System.out.println("\nARCHIVOS DISPONIBLES PARA CREAR TORRENT:");
        printIndexed(files);
        int n = readIndex(files.size());
        Path selected = files.get(n);
        String response = tracker("PING");
        if (response == null) throw new IOException("No se pudo contactar al Tracker.");
        createTorrentFile(selected);
    }

    private void createTorrentFile(Path input) throws Exception {
        Files.createDirectories(Paths.get("torrents"));
        String ip = trackerHost;
        int tp = trackerPort;
        Path output = Torrent.createTorrent(input, Paths.get("torrents"), ip, tp);
        System.out.println("\nTorrent creado correctamente:");
        System.out.println("  Archivo : " + input.getFileName());
        System.out.println("  Torrent : " + output.toAbsolutePath());
    }

    private void shareTorrentMenu() throws Exception {
        List<Path> torrents = listTorrents();
        if (torrents.isEmpty()) {
            System.out.println("No hay archivos .torrent disponibles.");
            return;
        }
        System.out.println("\nTORRENTS DISPONIBLES:");
        printTorrentIndexed(torrents);
        int n = readIndex(torrents.size());
        share(torrents.get(n));
    }

    private void downloadTorrentMenu() throws Exception {
        List<Path> torrents = listTorrents();
        if (torrents.isEmpty()) {
            System.out.println("No hay archivos .torrent disponibles.");
            return;
        }
        System.out.println("\nTORRENTS PARA DESCARGAR / CONTINUAR:");
        printTorrentIndexed(torrents);
        int n = readIndex(torrents.size());
        download(torrents.get(n));
    }

    private void showConnectedNodes() throws Exception {
        String response = tracker("NODES");
        System.out.println("\n================ NODOS CONECTADOS ================");
        String[] p = response.split("\\|", -1);
        if (p.length < 2) { System.out.println("Sin informacion."); return; }
        if (p.length == 2) { System.out.println("No hay nodos registrados."); return; }
        for (int i=1;i<p.length;i++) {
            String[] x=p[i].split("~",-1);
            if(x.length<4) continue;
            System.out.printf("%d. %-10s %s:%s%n", i, x[0], x[1], x[2]);
            System.out.println("   " + (x[3].isBlank()?"Sin archivos registrados":x[3].replace(";", " | ")));
        }
        System.out.println("===================================================");
    }

    private void showMyFiles() throws Exception {
        List<Path> shared = listRegularFiles(sharedDir);
        System.out.println("\n================ MIS ARCHIVOS COMPARTIDOS ================");
        if(shared.isEmpty()) System.out.println("No hay archivos compartidos localmente.");
        else for(int i=0;i<shared.size();i++) System.out.printf("%d. %s (%s)%n", i+1, shared.get(i).getFileName(), humanSize(Files.size(shared.get(i))));
        System.out.println("===========================================================");
    }

    private void showDownloads() throws Exception {
        List<Path> torrents=listTorrents();
        System.out.println("\n================ MIS DESCARGAS / CONSUMO ================");
        boolean found=false;
        for(Path tp:torrents){
            TorrentInfo t;
            try { t=TorrentInfo.load(tp); } catch(Exception e){ continue; }
            Path f=downloadsDir.resolve(t.fileName);
            Path state=downloadsDir.resolve(t.fileName+".pieces");
            if(Files.exists(f) || Files.exists(state)){
                found=true;
                Set<Integer> done=Files.exists(state)?readPieceState(state):new HashSet<>();
                if(done.size()==t.pieceCount || Files.exists(sharedDir.resolve(t.fileName)))
                    System.out.printf("- %-30s SEEDER / COMPLETO%n", t.fileName);
                else
                    System.out.printf("- %-30s LEECHER %.1f%% (%d/%d piezas)%n", t.fileName,100.0*done.size()/t.pieceCount,done.size(),t.pieceCount);
            }
        }
        if(!found) System.out.println("No hay descargas iniciadas.");
        System.out.println("===========================================================");
    }

    private void showAvailableTorrents() throws Exception {
        List<Path> torrents=listTorrents();
        System.out.println("\n================ TORRENTS DISPONIBLES ================");
        if(torrents.isEmpty()) System.out.println("No hay torrents.");
        else printTorrentIndexed(torrents);
        System.out.println("=======================================================");
    }

    private void refreshAutoShare() throws Exception { autoShare(); System.out.println("Lista de archivos compartidos actualizada."); }

    private void autoShare() throws Exception {
        Path dir=Paths.get("torrents");
        if(!Files.exists(dir)) return;
        try(var st=Files.list(dir)) {
            st.filter(x->x.toString().endsWith(".torrent")).forEach(x->{
                try {
                    TorrentInfo t=TorrentInfo.load(x);
                    Path f=sharedDir.resolve(t.fileName);
                    if(Files.exists(f)) share(x);
                } catch(Exception ignored) {}
            });
        }
    }

    private void share(Path torrentPath) throws Exception {
        TorrentInfo t=TorrentInfo.load(torrentPath);
        Path f=sharedDir.resolve(t.fileName);
        if(!Files.exists(f)) {
            Path original=Paths.get("archivos",t.fileName);
            if(Files.exists(original)) {
                Files.createDirectories(sharedDir);
                Files.copy(original,f,StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Archivo localizado automaticamente en 'archivos' y agregado a shared.");
            } else {
                System.out.println("No se encontro el archivo asociado al torrent.");
                System.out.println("Debe existir en 'archivos' o en la carpeta shared del peer.");
                return;
            }
        }
        StringBuilder pieces=new StringBuilder();
        for(int i=0;i<t.pieceCount;i++) pieces.append(i).append(",");
        String response=tracker("SHARE|"+t.infoHash+"|"+id+"|"+t.fileName+"|"+t.pieceCount+"|"+pieces);
        System.out.println("SHARE_OK".equals(response)?"Archivo agregado correctamente a compartir.":"Respuesta Tracker: "+response);
    }

    private void acceptLoop() {
        while(running) try {
            Socket s=server.accept();
            pool.submit(()->handleUpload(s));
        } catch(IOException e) { if(running) System.err.println("Servidor P2P: "+e.getMessage()); }
    }

    private void handleUpload(Socket s) {
        try(s;
            DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {
            String cmd=in.readUTF();
            if(!cmd.startsWith("GET|")) return;
            String[] p=cmd.split("\\|",-1);
            String file=p[1]; int piece=Integer.parseInt(p[2]); int pieceLen=Integer.parseInt(p[3]);
            System.out.printf("[UPLOAD] %s -> pieza %d de %s%n",s.getInetAddress().getHostAddress(),piece,file);
            Path source=sharedDir.resolve(file);
            if(!Files.exists(source)) source=findPartialFile(file,piece);
            if(source==null || !Files.exists(source)){out.writeInt(-1);out.flush();return;}
            long offset=(long)piece*pieceLen;
            if(offset>=Files.size(source)){out.writeInt(-1);out.flush();return;}
            int size=(int)Math.min(pieceLen,Files.size(source)-offset); byte[] data=new byte[size];
            try(RandomAccessFile raf=new RandomAccessFile(source.toFile(),"r")){raf.seek(offset);raf.readFully(data);}
            out.writeInt(data.length);out.write(data);out.flush();
        } catch(Exception e){System.err.println("Upload: "+e.getMessage());}
    }

    private Path findPartialFile(String file,int piece) throws IOException {
        Path partial=downloadsDir.resolve(file), state=downloadsDir.resolve(file+".pieces");
        if(!Files.exists(partial)||!Files.exists(state)) return null;
        return readPieceState(state).contains(piece)?partial:null;
    }

    private Set<Integer> readPieceState(Path state) throws IOException {
        Set<Integer> set=new HashSet<>(); String text=Files.readString(state).trim();
        if(!text.isBlank()) for(String x:text.split(",")) if(!x.isBlank()) set.add(Integer.parseInt(x));
        return set;
    }

    private void download(Path torrentPath) throws Exception {
        TorrentInfo t=TorrentInfo.load(torrentPath);
        System.out.println("\nBuscando fuentes para: "+t.fileName);
        List<RemotePeer> peers=parsePeers(tracker("GET_PEERS|"+t.infoHash));
        if(peers.isEmpty()) throw new IOException("No hay peers para este archivo.");
        tracker("DOWNLOAD_START|"+id+"|"+t.infoHash+"|"+t.fileName);
        Path outFile=downloadsDir.resolve(t.fileName), stateFile=downloadsDir.resolve(t.fileName+".pieces");
        Files.createDirectories(outFile.getParent());
        Set<Integer> completed=Files.exists(stateFile)?readPieceState(stateFile):ConcurrentHashMap.newKeySet();
        try(RandomAccessFile raf=new RandomAccessFile(outFile.toFile(),"rw")){if(raf.length()!=t.length)raf.setLength(t.length);}
        updatePieces(t,completed);
        System.out.printf("Continuando desde %.1f%% (%d/%d piezas).%n",100.0*completed.size()/t.pieceCount,completed.size(),t.pieceCount);
        ExecutorService downloads=Executors.newFixedThreadPool(Math.min(8,Math.max(1,peers.size()*2)));
        List<Future<?>> jobs=new ArrayList<>();
        for(int i=0;i<t.pieceCount;i++){
            if(completed.contains(i)) continue; final int piece=i;
            jobs.add(downloads.submit(()->{
                boolean ok=false;
                List<RemotePeer> candidates=new ArrayList<>();
                for(RemotePeer rp:peers) if(rp.pieces.isEmpty()||rp.pieces.contains(piece))candidates.add(rp);
                for(RemotePeer rp:candidates)try{
                    byte[] data=getPiece(rp,t.fileName,piece,t.pieceLength);
                    if(!Protocol.sha1(data).equalsIgnoreCase(t.pieces.get(piece)))throw new IOException("SHA-1 incorrecto");
                    synchronized(outFile.toString().intern()){
                        try(RandomAccessFile raf=new RandomAccessFile(outFile.toFile(),"rw")){raf.seek((long)piece*t.pieceLength);raf.write(data);}
                        completed.add(piece);savePieceState(stateFile,completed);
                    }
                    double progress=100.0*completed.size()/t.pieceCount;
                    if(progress>20.0) updatePieces(t,completed);
                    System.out.printf("Pieza %d/%d OK (%.1f%%)%n",piece+1,t.pieceCount,progress); ok=true;break;
                }catch(Exception e){System.out.println("Pieza "+piece+" fallo en "+rp.id+": "+e.getMessage());}
                if(!ok)throw new CompletionException(new IOException("No se pudo obtener la pieza "+piece));
            }));
        }
        try{for(Future<?> f:jobs)f.get();}finally{downloads.shutdownNow();}
        updatePieces(t,completed);
        if(completed.size()==t.pieceCount){
            Path seederFile=sharedDir.resolve(t.fileName);Files.createDirectories(seederFile.getParent());Files.move(outFile,seederFile,StandardCopyOption.REPLACE_EXISTING);Files.deleteIfExists(stateFile);
            StringBuilder all=new StringBuilder();for(int i=0;i<t.pieceCount;i++){if(all.length()>0)all.append(',');all.append(i);}
            tracker("SHARE|"+t.infoHash+"|"+id+"|"+t.fileName+"|"+t.pieceCount+"|"+all);
            tracker("DOWNLOAD_COMPLETE|"+id+"|"+t.infoHash);
            System.out.println("DESCARGA COMPLETA - ahora eres SEEDER.");
        }else System.out.printf("Descarga parcial: %.1f%%%n",100.0*completed.size()/t.pieceCount);
    }

    private void updatePieces(TorrentInfo t,Set<Integer> completed)throws IOException{
        List<Integer> list=new ArrayList<>(completed);Collections.sort(list);StringBuilder pieces=new StringBuilder();
        for(Integer p:list){if(pieces.length()>0)pieces.append(',');pieces.append(p);}
        String response=tracker("UPDATE|"+t.infoHash+"|"+id+"|"+pieces);
        if(response==null||(!response.equals("UPDATE_OK")&&!response.startsWith("UPDATE_OK")))throw new IOException("Tracker rechazo UPDATE: "+response);
        System.out.printf("[%s] Tracker actualizado: %d/%d piezas (%.1f%%)%n",id,completed.size(),t.pieceCount,100.0*completed.size()/t.pieceCount);
    }

    private void savePieceState(Path state,Set<Integer> pieces)throws IOException{
        List<Integer> list=new ArrayList<>(pieces);Collections.sort(list);StringBuilder sb=new StringBuilder();for(int x:list)sb.append(x).append(',');Files.writeString(state,sb.toString());
    }

    private byte[] getPiece(RemotePeer rp,String file,int piece,int pieceLen)throws Exception{
        try(Socket s=new Socket(rp.ip,rp.port);DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))){
            out.writeUTF("GET|"+file+"|"+piece+"|"+pieceLen);out.flush();int n=in.readInt();if(n<0)throw new IOException("archivo no disponible");byte[] d=new byte[n];in.readFully(d);return d;
        }
    }

    private List<RemotePeer> parsePeers(String response){
        List<RemotePeer> list=new ArrayList<>();String[] p=response.split("\\|",-1);
        for(int i=2;i<p.length;i++){String[] x=p[i].split(",",4);if(x.length>=4)list.add(new RemotePeer(x[0],x[1],Integer.parseInt(x[2]),x[3]));}
        return list;
    }

    private String tracker(String msg)throws IOException{
        try(Socket s=new Socket(trackerHost,trackerPort);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));PrintWriter out=new PrintWriter(s.getOutputStream(),true)){out.println(msg);return in.readLine();}
    }

    private List<Path> listTorrents() throws IOException { return listFilesWithExtension(Paths.get("torrents"),".torrent"); }
    private List<Path> listRegularFiles(Path dir)throws IOException{
        if(!Files.exists(dir))return new ArrayList<>();try(var st=Files.list(dir)){return st.filter(Files::isRegularFile).sorted(Comparator.comparing(x->x.getFileName().toString().toLowerCase())).toList();}
    }
    private List<Path> listFilesWithExtension(Path dir,String ext)throws IOException{
        if(!Files.exists(dir))return new ArrayList<>();try(var st=Files.list(dir)){return st.filter(Files::isRegularFile).filter(x->x.toString().toLowerCase().endsWith(ext)).sorted(Comparator.comparing(x->x.getFileName().toString().toLowerCase())).toList();}
    }
    private void printIndexed(List<Path> list){for(int i=0;i<list.size();i++)System.out.printf("%d. %s%n",i+1,list.get(i).getFileName());}
    private void printTorrentIndexed(List<Path> list){for(int i=0;i<list.size();i++){try{TorrentInfo t=TorrentInfo.load(list.get(i));System.out.printf("%d. %-30s %d piezas%n",i+1,t.fileName,t.pieceCount);}catch(Exception e){System.out.printf("%d. %s%n",i+1,list.get(i).getFileName());}}}
    private int readIndex(int size){while(true){try{int n=Integer.parseInt(readOption("Seleccione el numero: "))-1;if(n>=0&&n<size)return n;}catch(NumberFormatException ignored){}System.out.println("Seleccione un indice valido.");}}
    private String humanSize(long n){if(n<1024)return n+" B";if(n<1024*1024)return String.format("%.1f KB",n/1024.0);if(n<1024L*1024*1024)return String.format("%.1f MB",n/1024.0/1024.0);return String.format("%.2f GB",n/1024.0/1024.0/1024.0);}
    private void shutdown(){try{running=false;if(server!=null)server.close();}catch(IOException ignored){}pool.shutdownNow();console.close();}

    public static void main(String[] args)throws Exception{
        if(args.length!=4){System.out.println("Uso: java Peer <id> <puerto-peer> <ip-tracker> <puerto-tracker>");return;}
        new Peer(args[0],Integer.parseInt(args[1]),args[2],Integer.parseInt(args[3])).start();
    }
}
