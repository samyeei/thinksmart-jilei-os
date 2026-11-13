package local.jile.v5;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import java.io.ByteArrayOutputStream;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class FamilyService extends Service implements ParentHttpServer.Backend {
    static volatile FamilyService running;
    private ParentHttpServer server;
    ScreenCapture screen;
    boolean screenRestoreAttempted;
    private ParentPin pin;
    private FamilyApps apps;
    private FamilyExtras extras;
    private OtaUpdate ota;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("family", "家长管理",
                NotificationManager.IMPORTANCE_LOW));
        startForeground(8766, new Notification.Builder(this, "family")
                .setContentTitle("鸡肋OS V5 · 家长管理")
                .setContentText("家庭 Wi-Fi 家长端")
                .setSmallIcon(android.R.drawable.ic_lock_lock).build());
        screen = new ScreenCapture(this);
        apps = new FamilyApps(this);
        extras = new FamilyExtras(this);
        pin = new ParentPin(getSharedPreferences("family", MODE_PRIVATE));
        ota = new OtaUpdate(this);
        ota.scheduleAutomaticUpdates();
        server = new ParentHttpServer(this, this);
        try {
            server.start();
            running = this;
        } catch (java.io.IOException exception) {
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_STICKY; }
    @Override public OtaUpdate ota() { return ota; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (running == this) running = null;
        if (server != null) server.close();
        if (screen != null) screen.close();
        super.onDestroy();
    }

    static String wifiAddress(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return "";
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties links = manager.getLinkProperties(network);
            if (links == null) continue;
            for (LinkAddress address : links.getLinkAddresses()) {
                String ip = address.getAddress().getHostAddress();
                if (ParentWebSecurity.allowedClient(ip) && !ip.startsWith("127.")) return ip;
            }
        }
        return "";
    }

    @Override public JSONObject status() {
        JSONObject result = new JSONObject();
        try {
            result.put("local_ip", wifiAddress(this));
            result.put("locked", new KioskPolicy(this).locked());
            result.put("screen_ready", screen.ready());
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return result;
    }

    @Override public JSONObject apps() throws Exception {
        Set<String> approved = apps.approved();
        JSONArray list = new JSONArray();
        for (FamilyApps.Entry entry : apps.installed()) {
            list.put(new JSONObject().put("id", entry.id()).put("label", entry.label)
                    .put("allowed", approved.contains(entry.id()))
                    .put("icon", appIcon(entry)));
        }
        return new JSONObject().put("apps", list);
    }

    private String appIcon(FamilyApps.Entry entry) {
        Drawable drawable = entry.info.loadIcon(getPackageManager());
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream encoded = new ByteArrayOutputStream()) {
            drawable.setBounds(0, 0, 48, 48);
            drawable.draw(new Canvas(bitmap));
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded);
            return "data:image/png;base64," + Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP);
        } catch (java.io.IOException failure) {
            return "";
        } finally {
            bitmap.recycle();
        }
    }

    @Override public boolean approve(String id, boolean allowed) {
        return apps.change(id, allowed);
    }

    @Override public JSONObject settings(String path) throws Exception {
        switch (path) {
            case "/api/sites": return new JSONObject().put("sites", extras.sites());
            case "/api/desktop": return extras.desktopSettings();
            case "/api/usage": return extras.usage();
            default: throw new IllegalArgumentException(path);
        }
    }

    @Override public boolean updateSettings(String path, JSONObject data) throws Exception {
        switch (path) {
            case "/api/sites":
                Object url = data.opt("url"), label = data.opt("label"), remove = data.opt("remove");
                return url instanceof String && label instanceof String && remove instanceof Boolean
                        && (!data.has("icon") || data.opt("icon") instanceof String)
                        && (!data.has("includeSubdomains") || data.opt("includeSubdomains") instanceof Boolean)
                        && (!data.has("permissions") || data.opt("permissions") instanceof JSONObject)
                        && extras.changeSite((String) label, (String) url, (Boolean) remove,
                                data.has("icon") ? data.getString("icon") : null,
                                (Boolean) data.opt("includeSubdomains"), data.optJSONObject("permissions"));
            case "/api/desktop":
                return extras.updateDesktop(data);
            default: return false;
        }
    }

    @Override public byte[] screenshot() throws Exception { return screen.capture(); }

    @Override public boolean verifyPassword(String value) {
        try { return pin.verify(value); }
        catch (Exception ignored) { return false; }
    }

    boolean changeLocalPassword(String current, String next) {
        return server != null && server.changeLocalPassword(current, next);
    }

    @Override public boolean changePassword(String current, String next) {
        try { return pin.change(current, next); }
        catch (Exception ignored) { return false; }
    }
}
