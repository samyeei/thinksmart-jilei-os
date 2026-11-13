package local.jile.v5;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded LAN HTTP server owned by the Android foreground service. */
public final class ParentHttpServer implements AutoCloseable {
    public interface Backend {
        JSONObject status();
        JSONObject apps() throws Exception;
        boolean approve(String id, boolean allow);
        JSONObject settings(String path) throws Exception;
        boolean updateSettings(String path, JSONObject data) throws Exception;
        byte[] screenshot() throws Exception;
        boolean verifyPassword(String value);
        boolean changePassword(String current,String next);
        OtaUpdate ota();
    }
    private final Object browserControlLock=new Object();
    interface AssetLoader { InputStream open(String name) throws IOException; }
    private final AssetLoader assets;
    private final Backend backend;
    private final SecureRandom random = new SecureRandom();
    private final Map<String,Session> sessions = new LinkedHashMap<>();
    private final ParentWebSecurity.LoginLimiter loginLimiter = new ParentWebSecurity.LoginLimiter();
    private final Set<Socket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), task -> { Thread thread=new Thread(task,"parent-http-client"); thread.setDaemon(true); return thread; });
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread=new Thread(task,"parent-http-timeout"); thread.setDaemon(true); return thread;
    });
    private ServerSocket listener;
    private volatile boolean closed;
    private long lastScreenAt = -2000;
    private static final long SESSION_MS = 8L * 60 * 60 * 1000;
    private static final class Session {
        final String csrf;
        final long expiresAt;
        Session(String csrf,long expiresAt) { this.csrf=csrf; this.expiresAt=expiresAt; }
    }
    public ParentHttpServer(Context context, Backend backend) {
        this(backend,name -> context.getApplicationContext().getAssets().open("parent/"+name));
    }
    ParentHttpServer(Backend backend,AssetLoader assets){this.backend=backend;this.assets=assets;}
    public synchronized void start() throws IOException {
        startAt(InetAddress.getByName("0.0.0.0"),8766);
    }
    synchronized int startAt(InetAddress address,int port) throws IOException {
        if (closed) throw new IOException("Server closed");
        if (listener!=null) return listener.getLocalPort();
        ServerSocket socket=new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(address,port),8);
        } catch(IOException error) { socket.close(); throw error; }
        listener=socket;
        Thread accept=new Thread(() -> accept(socket),"parent-http-accept"); accept.setDaemon(true); accept.start();
        return socket.getLocalPort();
    }
    private void accept(ServerSocket server) {
        while(!closed) {
            try {
                Socket client=server.accept();
                if (!ParentWebSecurity.allowedClient(client.getInetAddress().getHostAddress())) { client.close(); continue; }
                client.setSoTimeout(4000);
                clients.add(client);
                try {
                    // Absolute deadline also bounds slow-drip requests and time spent in the queue.
                    ScheduledFuture<?> deadline=deadlines.schedule(() -> closeSocket(client),15,TimeUnit.SECONDS);
                    try { workers.execute(() -> { try { serve(client); } finally { deadline.cancel(false); clients.remove(client); closeSocket(client); } }); }
                    catch (java.util.concurrent.RejectedExecutionException busy) { deadline.cancel(false); clients.remove(client); closeSocket(client); }
                } catch(java.util.concurrent.RejectedExecutionException stopped) { clients.remove(client); closeSocket(client); }
            } catch(IOException error) { if(!closed) close(); }
        }
    }
    private void serve(Socket client) {
        try {
            ParentWebSecurity.Request request;
            try { request=ParentWebSecurity.read(client.getInputStream()); }
            catch(IOException malformed) { send(client,400,"text/plain; charset=utf-8",bytes("请求格式不受支持"),null); return; }
            String host=request.headers.get("host");
            JSONObject state=backend.status();
            // Only the Wi-Fi local address may accept traffic, even if another private interface exists.
            if(!client.getLocalAddress().getHostAddress().equals(state.optString("local_ip",""))) {
                jsonError(client,403,"仅允许通过家庭 Wi-Fi 访问"); return;
            }
            if (!ParentWebSecurity.allowedHost(host,state.optString("local_ip",""))) {
                jsonError(client,403,"仅允许从本设备的局域网地址访问"); return;
            }
            boolean crossSite = "cross-site".equals(request.headers.get("sec-fetch-site"));
            if (!ParentWebSecurity.sameOrigin(request.headers.get("origin"),host) || crossSite) {
                if (crossSite && publicLoginNavigation(request)) {
                    if (request.path.equals("/login")) asset(client,"login.html","text/html; charset=utf-8");
                    else redirect(client,"/login",null);
                    return;
                }
                jsonError(client,403,"仅允许从本设备的局域网地址访问"); return;
            }
            if (!request.method.equals("GET") && !request.method.equals("POST")) { jsonError(client,405,"不支持此请求方法"); return; }
            if (request.path.equals("/login")) {
                if(request.query!=null) { jsonError(client,400,"登录地址不接受参数"); return; }
                if(request.method.equals("GET")) asset(client,"login.html","text/html; charset=utf-8");
                else login(client,request,client.getInetAddress().getHostAddress());
                return;
            }
            if (request.method.equals("GET") && request.query==null && request.path.equals("/brand.png")) {
                asset(client,"brand.png","image/png"); return;
            }
            if (request.method.equals("GET") && request.query==null && request.path.equals("/style.css")) { asset(client,"style.css","text/css; charset=utf-8"); return; }
            if (request.method.equals("GET") && request.query==null && request.path.equals("/app.js")) { asset(client,"app.js","text/javascript; charset=utf-8"); return; }
            if (request.method.equals("GET") && request.query==null && request.path.equals("/site-icons.js")) {
                asset(client,"site-icons.js","text/javascript; charset=utf-8"); return;
            }
            if (request.method.equals("GET") && request.query==null
                    && request.path.matches("/wallpapers/(original|aurora|cosmos|dune)\\.webp")) {
                asset(client,request.path.substring(1),"image/webp"); return;
            }
            Session session=session(request.headers.get("cookie"));
            if(session==null) {
                if(request.path.equals("/") || request.path.equals("/index.html")) redirect(client,"/login",null);
                else jsonError(client,401,"登录已失效，请重新登录");
                return;
            }
            if(request.method.equals("POST") && !constantEquals(session.csrf,request.headers.get("x-csrf-token"))) {
                jsonError(client,403,"页面验证已失效，请刷新后重试"); return;
            }
            if(request.method.equals("GET")) {
                switch(request.path) {
                    case "/": case "/index.html":
                        if(request.query!=null) { jsonError(client,400,"地址参数不受支持"); return; }
                        asset(client,"index.html","text/html; charset=utf-8"); return;
                    case "/api/status":
                        if(request.query!=null) { jsonError(client,400,"地址参数不受支持"); return; }
                        sendJson(client,200,new JSONObject(state.toString()).put("csrf_token",session.csrf)); return;
                    case "/api/sites": case "/api/desktop": case "/api/usage":
                        if(request.query!=null) { jsonError(client,400,"地址参数不受支持"); return; }
                        sendJson(client,200,backend.settings(request.path)); return;
                    case "/api/apps": sendJson(client,200,backend.apps()); return;
                    case "/api/update":
                        if (request.query != null) { jsonError(client,400,"地址参数不受支持"); return; }
                        sendJson(client,200,backend.ota().status()); return;
                    default: jsonError(client,404,"页面不存在"); return;
                }
            }
            // Serialize authenticated mutations with credential replacement and login issuance.
            synchronized(browserControlLock){
            if(session(request.headers.get("cookie"))!=session){jsonError(client,401,"登录已失效，请重新登录");return;}
            if(request.path.equals("/api/password")){
                changePassword(client,request,client.getInetAddress().getHostAddress());return;
            }
            if (request.path.equals("/api/update") && request.query == null) {
                String type = request.headers.get("content-type");
                if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                    jsonError(client,415,"请求格式不受支持"); return;
                }
                try {
                    backend.ota().configure(new JSONObject(new String(request.body, StandardCharsets.UTF_8)));
                    sendJson(client,200,backend.ota().status());
                } catch (IllegalArgumentException invalid) {
                    jsonError(client,400,invalid.getMessage());
                }
                return;
            }
            if((request.path.equals("/api/sites") || request.path.equals("/api/desktop")) && request.query==null){
                String type=request.headers.get("content-type");
                if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json")){
                    jsonError(client,415,"请求格式不受支持"); return;
                }
                JSONObject update=new JSONObject(new String(request.body,StandardCharsets.UTF_8));
                if(!backend.updateSettings(request.path,update)){
                    jsonError(client,400,"请检查网址、图标或桌面设置的格式"); return;
                }
                sendJson(client,200,backend.settings(request.path)); return;
            }
            if(request.path.equals("/api/apps") && request.query==null){
                String type=request.headers.get("content-type");
                if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json")){
                    jsonError(client,415,"请求格式不受支持"); return;
                }
                JSONObject update=new JSONObject(new String(request.body,StandardCharsets.UTF_8));
                Object allow=update.opt("allowed");
                if(!(allow instanceof Boolean) || !backend.approve(update.optString("id",""),(Boolean)allow)){
                    jsonError(client,409,"无法保存，请刷新应用列表"); return;
                }
                sendJson(client,200,backend.apps()); return;
            }
            if(request.query!=null || request.body.length!=0) { jsonError(client,400,"此操作不接受请求内容"); return; }
            switch(request.path) {
                case "/api/screen":
                    synchronized(this) {
                        long now=now();
                        if(now-lastScreenAt<2000) { jsonError(client,429,"请至少间隔 2 秒再查看屏幕"); return; }
                        lastScreenAt=now;
                    }
                    try {
                        byte[] png=backend.screenshot();
                        if(png==null || png.length<8 || png.length>8*1024*1024
                                || png[0]!=(byte)137 || png[1]!=80 || png[2]!=78 || png[3]!=71) throw new IOException("Invalid screenshot");
                        send(client,200,"image/png",png,null);
                    } catch(Exception error) {
                        if(error instanceof IllegalStateException && error.getMessage()!=null && error.getMessage().contains("授权"))
                            jsonError(client,409,"需要在设备上授权屏幕查看");
                        else jsonError(client,503,"本次屏幕查看失败，请确认设备在线后重试");
                    }
                    return;
                case "/api/logout":
                    synchronized(browserControlLock) {
                        if(session(request.headers.get("cookie"))!=session || !constantEquals(session.csrf,request.headers.get("x-csrf-token"))) {jsonError(client,403,"页面验证已失效，请重新登录");return;}
                        removeSession(request.headers.get("cookie"));
                        send(client,200,"application/json; charset=utf-8",bytes("{\"acknowledged\":true}"),"Set-Cookie: ts_parent=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict\r\n");return;
                    }
                default: jsonError(client,405,"不支持此操作");
            }
            }
        } catch(IllegalArgumentException invalid) {
            try { jsonError(client,400,"日期或请求参数无效"); } catch(IOException ignored) {}
        } catch(Exception failure) {
            // Never log URLs, passwords, cookies, bodies, captures or exception details.
            try { jsonError(client,503,"设备暂时无法提供数据，请稍后重试"); } catch(IOException ignored) {}
        }
    }
    private static boolean publicLoginNavigation(ParentWebSecurity.Request request) {
        return request.method.equals("GET") && request.query == null
                && (request.path.equals("/") || request.path.equals("/login"))
                && "navigate".equals(request.headers.get("sec-fetch-mode"))
                && "document".equals(request.headers.get("sec-fetch-dest"));
    }
    private void login(Socket client,ParentWebSecurity.Request request,String peer) throws Exception {
        synchronized(browserControlLock){loginLocked(client,request,peer);}
    }
    private void loginLocked(Socket client,ParentWebSecurity.Request request,String peer) throws Exception {
        String type=request.headers.get("content-type");
        if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
            jsonError(client,415,"请通过登录表单提交"); return;
        }
        String body=new String(request.body,StandardCharsets.UTF_8);
        if(!body.startsWith("password=") || body.indexOf('&')>=0) { jsonError(client,400,"登录表单无效"); return; }
        String password;
        try { password=URLDecoder.decode(body.substring(9),"UTF-8"); }
        catch(IllegalArgumentException invalid) { jsonError(client,400,"登录表单无效"); return; }
        if(password.length()>1024) { jsonError(client,400,"密码过长"); return; }
        boolean valid;
        synchronized(loginLimiter) {
            long at=now();
            if(!loginLimiter.allowed(peer,at)) { loginPageError(client,429,"尝试次数过多，请等待一分钟再登录。"); return; }
            valid=backend.verifyPassword(password);
            if(!valid) loginLimiter.failed(peer,at);
        }
        if(!valid) { loginPageError(client,401,"密码错误，请重试。"); return; }
        String token=randomToken();
        synchronized(sessions) {
            expireSessions();
            if(sessions.size()>=64) sessions.remove(sessions.keySet().iterator().next());
            sessions.put(token,new Session(randomToken(),now()+SESSION_MS));
        }
        redirect(client,"/","Set-Cookie: ts_parent="+token+"; Path=/; Max-Age=28800; HttpOnly; SameSite=Strict\r\n");
    }
    private static final String EXPIRE_COOKIE="Set-Cookie: ts_parent=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict\r\n";
    private void changePassword(Socket client,ParentWebSecurity.Request request,String peer)throws Exception{
        if(request.query!=null){jsonError(client,400,"修改密码地址不接受参数");return;}
        PasswordChangeForm form;
        try{form=PasswordChangeForm.parse(request.body,request.headers.get("content-type"));}
        catch(IllegalArgumentException invalid){jsonError(client,400,invalid.getMessage());return;}
        synchronized(loginLimiter){
            long at=now();
            if(!loginLimiter.allowed(peer,at)){jsonError(client,429,"尝试次数过多，请等待一分钟再修改");return;}
            boolean changed;
            try{changed=backend.changePassword(form.current,form.next);}
            catch(RuntimeException failure){
                synchronized(sessions){sessions.clear();}
                send(client,503,"application/json; charset=utf-8",bytes("{\"error\":\"设备未能确认修改结果，请重新登录后检查\"}"),EXPIRE_COOKIE);return;
            }
            if(!changed){loginLimiter.failed(peer,at);jsonError(client,403,"当前密码不正确");return;}
        }
        synchronized(sessions){sessions.clear();}
        send(client,200,"application/json; charset=utf-8",bytes("{\"password_changed\":true,\"reauthentication_required\":true}"),EXPIRE_COOKIE);
    }
    boolean changeLocalPassword(String current, String next) {
        synchronized(browserControlLock) {
            synchronized(loginLimiter) {
                long at=now();
                String local="device-pin";
                if(!loginLimiter.allowed(local,at)) return false;
                if(!backend.changePassword(current,next)) {
                    loginLimiter.failed(local,at);
                    return false;
                }
                synchronized(sessions){sessions.clear();}
                return true;
            }
        }
    }
    private void loginPageError(Socket client,int code,String message) throws IOException {
        String html="<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>登录 · 鸡肋OS V5 家长端</title><link rel=\"stylesheet\" href=\"/style.css\"><main class=\"login-card\"><p class=\"eyebrow\">鸡肋OS V5</p><h1>登录未完成</h1><p role=\"alert\">"+message+"</p><a class=\"button\" href=\"/login\">返回登录</a></main></html>";
        send(client,code,"text/html; charset=utf-8",bytes(html),null);
    }
    private Session session(String cookie) {
        String token=cookieToken(cookie);
        if(token==null) return null;
        synchronized(sessions) { expireSessions(); return sessions.get(token); }
    }
    private void removeSession(String cookie) { synchronized(sessions) { sessions.remove(cookieToken(cookie)); } }
    private String cookieToken(String cookie) {
        if(cookie==null) return null;
        String token=null;
        for(String part:cookie.split(";")) {
            String value=part.trim();
            if(value.startsWith("ts_parent=")) { if(token!=null) return null; token=value.substring(10); }
        }
        return token!=null && token.matches("[0-9a-f]{64}") ? token : null;
    }
    private void expireSessions() {
        Iterator<Session> entries=sessions.values().iterator(); long at=now();
        while(entries.hasNext()) if(entries.next().expiresAt<=at) entries.remove();
    }
    private String randomToken() {
        byte[] value=new byte[32]; random.nextBytes(value); StringBuilder hex=new StringBuilder(64);
        for(byte b:value) { hex.append(Character.forDigit((b>>>4)&15,16)); hex.append(Character.forDigit(b&15,16)); }
        return hex.toString();
    }
    private static boolean constantEquals(String expected,String supplied) {
        return supplied!=null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII));
    }
    private void asset(Socket socket,String name,String mime) throws IOException {
        try(InputStream input=assets.open(name)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int size;
            while((size=input.read(buffer))!=-1) { if(out.size()+size>256*1024) throw new IOException("Asset too large"); out.write(buffer,0,size); }
            send(socket,200,mime,out.toByteArray(),null);
        }
    }
    private void sendJson(Socket socket,int code,JSONObject value) throws IOException { send(socket,code,"application/json; charset=utf-8",bytes(value.toString()),null); }
    private void jsonError(Socket socket,int code,String message) throws IOException {
        try { sendJson(socket,code,new JSONObject().put("error",message)); }
        catch(org.json.JSONException impossible) { throw new IOException("Error response failed"); }
    }
    private void redirect(Socket socket,String location,String headers) throws IOException {
        send(socket,303,"text/plain; charset=utf-8",new byte[0],"Location: "+location+"\r\n"+(headers==null?"":headers));
    }
    private void send(Socket socket,int code,String mime,byte[] body,String extra) throws IOException {
        String reason;
        switch(code) {
            case 200: reason="OK"; break; case 303: reason="See Other"; break;
            case 400: reason="Bad Request"; break; case 401: reason="Unauthorized"; break;
            case 403: reason="Forbidden"; break; case 404: reason="Not Found"; break;
            case 405: reason="Method Not Allowed"; break; case 409: reason="Conflict"; break;
            case 415: reason="Unsupported Media Type"; break; case 429: reason="Too Many Requests"; break;
            default: reason="Service Unavailable";
        }
        String headers="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+mime+"\r\nContent-Length: "+body.length
                +"\r\nConnection: close\r\nCache-Control: no-store\r\nPragma: no-cache\r\nX-Content-Type-Options: nosniff"
                +"\r\nX-Frame-Options: DENY\r\nReferrer-Policy: same-origin\r\nPermissions-Policy: camera=(), microphone=(), geolocation=()"
                +"\r\nContent-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
                +"\r\n"+(extra==null?"":extra)+"\r\n";
        OutputStream output=socket.getOutputStream(); output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static long now() { return System.nanoTime()/1_000_000L; }
    private static void closeSocket(Socket socket) { try { socket.close(); } catch(IOException ignored) {} }
    @Override public synchronized void close() {
        closed=true;
        if(listener!=null) try { listener.close(); } catch(IOException ignored) {}
        for(Socket client:clients) closeSocket(client);
        clients.clear(); workers.shutdownNow(); deadlines.shutdownNow();
        synchronized(sessions) { sessions.clear(); }
    }
}
