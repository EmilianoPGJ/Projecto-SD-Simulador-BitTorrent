import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Tracker {
    static class PeerInfo {
        String id, ip; int port;
        PeerInfo(String id,String ip,int port){this.id=id;this.ip=ip;this.port=port;}
    }
    static class FileInfo {
        String hash,name; int count;
        Map<String,Set<Integer>> pieces=new ConcurrentHashMap<>();
        FileInfo(String hash,String name,int count){this.hash=hash;this.name=name;this.count=count;}
    }

    final Map<String,PeerInfo> peers=new ConcurrentHashMap<>();
    final Map<String,FileInfo> files=new ConcurrentHashMap<>();
    final Map<String,Set<String>> activeDownloads=new ConcurrentHashMap<>();
    final ExecutorService pool=Executors.newCachedThreadPool();
    volatile boolean running=true;
    ServerSocket serverSocket;

    void start(int port) throws IOException {
        serverSocket=new ServerSocket(port);
        System.out.println("\n==============================================");
        System.out.println("       TRACKER BITTORRENT - ETAPA 6");
        System.out.println("==============================================");
        System.out.println("Puerto: "+port);
        System.out.println("Tracker listo. Los peers pueden conectarse.");
        System.out.println("Escribe 'menu' para consultar el estado desde esta consola.\n");

        pool.submit(this::acceptLoop);
        trackerMenu();
    }

    private void acceptLoop(){
        while(running){
            try { Socket socket=serverSocket.accept(); pool.submit(()->handle(socket)); }
            catch(IOException e){ if(running) System.err.println("[ERROR SERVIDOR] "+e.getMessage()); }
        }
    }

    void handle(Socket socket){
        try(socket;
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out=new PrintWriter(socket.getOutputStream(),true)){
            String line;
            while((line=in.readLine())!=null){
                String[] p=line.split("\\|",-1);
                switch(p[0]){
                    case "PING" -> out.println("PONG");
                    case "REGISTER" -> {
                        String id=p[1],ip=p[2];int port=Integer.parseInt(p[3]);
                        boolean nuevo=!peers.containsKey(id);
                        peers.put(id,new PeerInfo(id,ip,port));
                        if(nuevo) System.out.printf("[REGISTRO] Peer %s conectado desde %s:%d%n",id,ip,port);
                        else System.out.printf("[ACTUALIZACION] Peer %s reconecto su conexion%n",id);
                        mostrarResumen(); out.println("REGISTER_OK");
                    }
                    case "SHARE" -> {
                        FileInfo f=files.computeIfAbsent(p[1],h->new FileInfo(h,p[3],Integer.parseInt(p[4])));
                        Set<Integer> set=parsePieces(p.length>5?p[5]:"");
                        f.pieces.put(p[2],set); activeDownloads.computeIfAbsent(p[2],k->ConcurrentHashMap.newKeySet()).remove(f.hash);
                        System.out.printf("[SHARE] Peer %s comparte %s (%d/%d piezas)%n",p[2],p[3],set.size(),f.count);
                        out.println("SHARE_OK");
                    }
                    case "DOWNLOAD_START" -> {
                        activeDownloads.computeIfAbsent(p[1],k->ConcurrentHashMap.newKeySet()).add(p[2]);
                        System.out.printf("[DOWNLOAD] Peer %s comenzo/continua %s%n",p[1],p.length>3?p[3]:p[2]);
                        out.println("DOWNLOAD_OK");
                    }
                    case "DOWNLOAD_COMPLETE" -> {
                        Set<String> set=activeDownloads.get(p[1]); if(set!=null)set.remove(p[2]);
                        out.println("DOWNLOAD_OK");
                    }
                    case "UPDATE" -> {
                        FileInfo f=files.get(p[1]);
                        if(f==null){out.println("ERROR|UNKNOWN_FILE");break;}
                        Set<Integer> set=parsePieces(p.length>3?p[3]:""); f.pieces.put(p[2],set);
                        if(set.size()>0 && set.size()<f.count) activeDownloads.computeIfAbsent(p[2],k->ConcurrentHashMap.newKeySet()).add(f.hash);
                        double pct=100.0*set.size()/f.count;
                        System.out.printf("[UPDATE] Peer %s -> %s: %d/%d piezas (%.1f%%)%n",p[2],f.name,set.size(),f.count,pct);
                        out.println("UPDATE_OK");
                    }
                    case "GET_PEERS" -> out.println(buildPeersResponse(p[1]));
                    case "NODES" -> out.println(buildNodesResponse());
                    case "FILES" -> out.println(buildFilesResponse());
                    case "NODE" -> out.println(buildNodeResponse(p[1]));
                    case "LIST" -> {mostrarEstadoCompleto();out.println("LIST_OK");}
                    default -> out.println("ERROR|UNKNOWN");
                }
            }
        }catch(Exception e){System.err.println("[ERROR TRACKER] "+e.getMessage());}
    }

    private Set<Integer> parsePieces(String text){
        Set<Integer> set=ConcurrentHashMap.newKeySet();
        if(text!=null&&!text.isBlank()) for(String x:text.split(",")) if(!x.isBlank())set.add(Integer.parseInt(x));
        return set;
    }

    private String buildPeersResponse(String hash){
        FileInfo f=files.get(hash); StringBuilder r=new StringBuilder("PEERS|").append(hash);
        if(f!=null) for(var e:f.pieces.entrySet()){
            PeerInfo x=peers.get(e.getKey()); if(x==null)continue;
            StringBuilder pieces=new StringBuilder(); for(Integer p:e.getValue())pieces.append(p).append(',');
            r.append('|').append(x.id).append(',').append(x.ip).append(',').append(x.port).append(',').append(pieces);
        }
        return r.toString();
    }

    private String buildNodesResponse(){
        StringBuilder r=new StringBuilder("NODES");
        List<PeerInfo> list=new ArrayList<>(peers.values());list.sort(Comparator.comparing(x->x.id));
        for(PeerInfo p:list){
            List<String> descriptions=new ArrayList<>();
            for(FileInfo f:files.values()){
                Set<Integer> set=f.pieces.get(p.id); if(set==null)continue;
                descriptions.add(f.name+"="+role(set.size(),f.count)+"("+set.size()+"/"+f.count+")");
            }
            r.append('|').append(p.id).append('~').append(p.ip).append('~').append(p.port).append('~').append(String.join(";",descriptions));
        }
        return r.toString();
    }

    private String buildFilesResponse(){
        StringBuilder r=new StringBuilder("FILES");
        List<FileInfo> list=new ArrayList<>(files.values());list.sort(Comparator.comparing(x->x.name.toLowerCase()));
        for(FileInfo f:list) r.append('|').append(f.hash).append('~').append(f.name).append('~').append(f.count).append('~').append(fileSummary(f));
        return r.toString();
    }

    private String buildNodeResponse(String id){
        PeerInfo p=peers.get(id); if(p==null)return "NODE|NOT_FOUND";
        StringBuilder r=new StringBuilder("NODE|").append(p.id).append('~').append(p.ip).append('~').append(p.port);
        for(FileInfo f:files.values()){
            Set<Integer> set=f.pieces.get(id); if(set!=null)r.append('|').append(f.name).append('~').append(role(set.size(),f.count)).append('~').append(set.size()).append('~').append(f.count);
        }
        return r.toString();
    }

    private String role(int pieces,int total){
        if(pieces>=total&&total>0)return "SEEDER";
        if(pieces>0)return "LEECHER + UPLOADER";
        return "REGISTRADO";
    }

    private String fileSummary(FileInfo f){
        List<String> names=new ArrayList<>();
        for(var e:f.pieces.entrySet()) if(peers.containsKey(e.getKey())) names.add(e.getKey()+":"+role(e.getValue().size(),f.count));
        return String.join(",",names);
    }

    private void trackerMenu(){
        Scanner sc=new Scanner(System.in);
        while(running){
            System.out.println("\n+------------------------------------------------+");
            System.out.println("|              MENU DEL TRACKER                 |");
            System.out.println("+------------------------------------------------+");
            System.out.println("| 1. Ver nodos conectados y roles               |");
            System.out.println("| 2. Ver archivos y fuentes                     |");
            System.out.println("| 3. Ver detalle de un nodo (por indice)        |");
            System.out.println("| 4. Actualizar pantalla                        |");
            System.out.println("| 5. Salir del Tracker                          |");
            System.out.println("+------------------------------------------------+");
            System.out.print("Seleccione una opcion: ");
            String op=sc.nextLine().trim();
            try{
                switch(op){
                    case "1" -> mostrarNodosDetallado();
                    case "2" -> mostrarArchivosDetallado();
                    case "3" -> detalleNodoMenu(sc);
                    case "4" -> mostrarEstadoCompleto();
                    case "5" -> {running=false;try{serverSocket.close();}catch(IOException ignored){}pool.shutdownNow();System.out.println("Tracker detenido.");}
                    default -> System.out.println("Opcion no valida.");
                }
            }catch(Exception e){System.out.println("Error: "+e.getMessage());}
        }
    }

    synchronized void mostrarNodosDetallado(){
        System.out.println("\n================ NODOS CONECTADOS ================");
        List<PeerInfo> list=new ArrayList<>(peers.values());list.sort(Comparator.comparing(x->x.id));
        if(list.isEmpty()){System.out.println("No hay peers registrados.");return;}
        for(int i=0;i<list.size();i++){
            PeerInfo p=list.get(i);System.out.printf("\n%d. Peer %s  %s:%d%n",i+1,p.id,p.ip,p.port);
            boolean any=false;
            for(FileInfo f:files.values()){
                Set<Integer> set=f.pieces.get(p.id);if(set==null)continue;any=true;
                System.out.printf("   %-28s %-20s %d/%d piezas%n",f.name,role(set.size(),f.count),set.size(),f.count);
            }
            if(!any)System.out.println("   Sin archivos registrados.");
        }
        System.out.println("===================================================");
    }

    synchronized void mostrarArchivosDetallado(){
        System.out.println("\n================ ARCHIVOS DEL TRACKER ================");
        List<FileInfo> list=new ArrayList<>(files.values());list.sort(Comparator.comparing(x->x.name.toLowerCase()));
        if(list.isEmpty()){System.out.println("No hay archivos conocidos.");return;}
        for(int i=0;i<list.size();i++){
            FileInfo f=list.get(i);System.out.printf("\n%d. %s | %d piezas%n",i+1,f.name,f.count);System.out.println("   InfoHash: "+f.hash);
            for(var e:f.pieces.entrySet())System.out.printf("   - Peer %-8s %-20s %d/%d%n",e.getKey(),role(e.getValue().size(),f.count),e.getValue().size(),f.count);
        }
        System.out.println("=======================================================");
    }

    private void detalleNodoMenu(Scanner sc){
        List<PeerInfo> list=new ArrayList<>(peers.values());list.sort(Comparator.comparing(x->x.id));
        if(list.isEmpty()){System.out.println("No hay peers.");return;}
        for(int i=0;i<list.size();i++)System.out.printf("%d. %s (%s:%d)%n",i+1,list.get(i).id,list.get(i).ip,list.get(i).port);
        System.out.print("Seleccione el numero: ");
        try{int n=Integer.parseInt(sc.nextLine().trim())-1;if(n<0||n>=list.size()){System.out.println("Indice invalido.");return;}mostrarDetalleNodo(list.get(n));}catch(NumberFormatException e){System.out.println("Indice invalido.");}
    }

    private void mostrarDetalleNodo(PeerInfo p){
        System.out.println("\n=============== DETALLE DEL PEER ===============");
        System.out.println("ID       : "+p.id);System.out.println("Direccion: "+p.ip+":"+p.port);
        boolean any=false;
        for(FileInfo f:files.values()){
            Set<Integer> set=f.pieces.get(p.id);if(set==null)continue;any=true;
            System.out.printf("Archivo: %s%n",f.name);System.out.printf("  Rol: %s%n",role(set.size(),f.count));System.out.printf("  Piezas: %d/%d (%.1f%%)%n",set.size(),f.count,100.0*set.size()/f.count);
        }
        Set<String> active=activeDownloads.getOrDefault(p.id,Collections.emptySet());
        if(!active.isEmpty())System.out.println("Descargas activas: "+active.size());
        if(!any)System.out.println("Sin archivos registrados.");
        System.out.println("==================================================");
    }

    synchronized void mostrarEstadoCompleto(){mostrarNodosDetallado();mostrarArchivosDetallado();}
    synchronized void mostrarResumen(){System.out.println("[TRACKER] Peers conectados: "+peers.size()+" | Archivos conocidos: "+files.size());}

    public static void main(String[] args)throws Exception{int port=args.length>0?Integer.parseInt(args[0]):5000;new Tracker().start(port);}
}
