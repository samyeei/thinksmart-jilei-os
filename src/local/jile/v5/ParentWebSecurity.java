package local.jile.v5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Small, deliberately strict HTTP/1.1 boundary; no proxy headers or URL decoding. */
final class ParentWebSecurity {
    static boolean allowedClient(String ip) {
        if (ip == null || !ip.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = ip.split("\\.");
        int[] n = new int[4];
        for (int i=0;i<4;i++) { n[i] = Integer.parseInt(parts[i]); if(n[i]>255) return false; }
        return n[0]==10 || n[0]==127 || (n[0]==192 && n[1]==168) || (n[0]==172 && n[1]>=16 && n[1]<=31);
    }
    static boolean allowedHost(String host, String ip) {
        return host != null && (host.equals("localhost:8766") || host.equals("127.0.0.1:8766")
                || (allowedClient(ip) && host.equals(ip + ":8766")));
    }
    static boolean sameOrigin(String origin, String host) { return origin == null || origin.equals("http://" + host); }
    static String date(String query) {
        if (query == null || !query.matches("date=[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Invalid date");
        String date = query.substring(5);
        try { if (!LocalDate.parse(date).toString().equals(date)) throw new IllegalArgumentException("Invalid date"); }
        catch (java.time.DateTimeException error) { throw new IllegalArgumentException("Invalid date"); }
        return date;
    }
    static final class Request { String method,path,query; Map<String,String> headers; byte[] body; }
    static Request read(InputStream input) throws IOException {
        int[] bytes = {0};
        String line = line(input, bytes);
        String[] first = line.split(" ", -1);
        if (first.length!=3 || !first[0].matches("[A-Z]{1,12}") || !first[2].equals("HTTP/1.1")) throw new IOException("Invalid request line");
        String target = first[1];
        int queryAt = target.indexOf('?');
        String path = queryAt<0 ? target : target.substring(0,queryAt);
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") || !path.matches("/[A-Za-z0-9/_\\-.]*")
                || target.indexOf('#')>=0 || target.indexOf('\\')>=0) throw new IOException("Invalid path");
        Request request = new Request(); request.method=first[0]; request.path=path;
        request.query=queryAt<0 ? null : target.substring(queryAt+1);
        request.headers=new HashMap<>();
        int count=0;
        while (!(line=line(input,bytes)).isEmpty()) {
            if (++count>100) throw new IOException("Too many headers");
            int colon=line.indexOf(':');
            if (colon<1 || !line.substring(0,colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new IOException("Invalid header");
            String key=line.substring(0,colon).toLowerCase(Locale.ROOT);
            String value=line.substring(colon+1).trim();
            if (request.headers.put(key,value)!=null) throw new IOException("Duplicate header");
        }
        if (!request.headers.containsKey("host") || request.headers.containsKey("transfer-encoding")
                || request.headers.containsKey("expect")) throw new IOException("Unsupported framing");
        String length=request.headers.get("content-length");
        int size=0;
        if (length!=null) {
            boolean desktopUpload = request.method.equals("POST") && request.path.equals("/api/desktop") && request.query == null;
            int maxSize = desktopUpload ? 420000 : 4096;
            if (!length.matches("[0-9]{1,6}")) throw new IOException("Invalid body length");
            size=Integer.parseInt(length);
            if (size>maxSize) throw new IOException("Body too large");
        }
        if (!request.method.equals("POST") && size!=0) throw new IOException("Unexpected body");
        request.body=new byte[size];
        for (int offset=0;offset<size;) {
            int read=input.read(request.body,offset,size-offset);
            if(read<0) throw new IOException("Incomplete body");
            offset+=read;
        }
        return request;
    }
    private static String line(InputStream input, int[] total) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while (true) {
            int value=input.read();
            if (value<0 || ++total[0]>16384) throw new IOException("Incomplete or oversized headers");
            if (value=='\r') {
                if(input.read()!='\n' || ++total[0]>16384) throw new IOException("Invalid line ending");
                return out.toString(StandardCharsets.US_ASCII.name());
            }
            if(value<32 || value>126) throw new IOException("Invalid header character");
            out.write(value);
        }
    }
    static final class LoginLimiter {
        private final ArrayDeque<Long> global = new ArrayDeque<>();
        private final Map<String,ArrayDeque<Long>> peers = new HashMap<>();
        synchronized boolean allowed(String peer,long now) {
            clean(now);
            ArrayDeque<Long> attempts=peers.get(peer);
            return global.size()<5 && (attempts==null || attempts.size()<5);
        }
        synchronized void failed(String peer,long now) {
            clean(now); global.addLast(now);
            if (peers.size()<256 || peers.containsKey(peer)) peers.computeIfAbsent(peer,key -> new ArrayDeque<>()).addLast(now);
        }
        private void clean(long now) {
            prune(global,now);
            Iterator<ArrayDeque<Long>> entries=peers.values().iterator();
            while(entries.hasNext()) { ArrayDeque<Long> entry=entries.next(); prune(entry,now); if(entry.isEmpty()) entries.remove(); }
        }
        private void prune(ArrayDeque<Long> entries,long now) { while(!entries.isEmpty() && now-entries.peekFirst()>=60000) entries.removeFirst(); }
    }
    private ParentWebSecurity() {}
}
