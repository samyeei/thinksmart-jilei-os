package local.jile.v5;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

final class OtaUpdate {
    static final String RESULT = "local.jile.v5.OTA_RESULT";
    private static final int MANUAL = 87660;
    private static final int PERIODIC = 87661;
    private static final String FEED = "https://raw.githubusercontent.com/samyeei/thinksmart-jilei-os/refs/heads/ota/release.json";
    private static final long MAX_APK = 160L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    private final Context context;
    private final SharedPreferences prefs;

    OtaUpdate(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("ota", Context.MODE_PRIVATE);
    }

    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
    }

    JSONObject status() throws Exception {
        synchronized (LOCK) {
            PackageInfo current = installed();
            reconcile(current.versionCode);
            return new JSONObject().put("version", current.versionName)
                    .put("versionCode", current.versionCode)
                    .put("available", new KioskPolicy(context).available())
                    .put("automatic", automaticEnabled())
                    .put("message", prefs.getString("message", "尚未检查更新"));
        }
    }

    void configure(JSONObject data) throws Exception {
        synchronized (LOCK) {
            if (!new KioskPolicy(context).available()) throw new IllegalArgumentException("设备未启用设备管理");
            if (data.length() == 1 && data.opt("automatic") instanceof Boolean) {
                if (!prefs.edit().putBoolean("automatic", data.getBoolean("automatic")).commit()) {
                    throw new IOException("无法保存自动更新设置");
                }
                scheduleAutomaticUpdates();
                return;
            }
            reconcile(installed().versionCode);
            if (prefs.getInt("target", 0) != 0) throw new IllegalArgumentException("正在安装更新，请稍等");
            if (!data.optBoolean("check", false) || data.length() != 1) {
                throw new IllegalArgumentException("更新设置格式错误");
            }
            if (CHECKING.get() || jobs().getPendingJob(MANUAL) != null) return;
            JobInfo job = new JobInfo.Builder(MANUAL, component())
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(TimeUnit.SECONDS.toMillis(5)).build();
            if (jobs().schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IOException("Cannot schedule update");
            message("已安排检查，请稍候");
        }
    }

    private boolean automaticEnabled() {
        return prefs.getBoolean("automatic", true);
    }

    void scheduleAutomaticUpdates() {
        synchronized (LOCK) {
            if (!automaticEnabled() || !new KioskPolicy(context).available()) {
                jobs().cancel(PERIODIC);
            } else if (jobs().getPendingJob(PERIODIC) == null) {
                jobs().schedule(new JobInfo.Builder(PERIODIC, component())
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setPeriodic(TimeUnit.HOURS.toMillis(6))
                        .setPersisted(true).build());
            }
        }
    }

    private JobScheduler jobs() { return context.getSystemService(JobScheduler.class); }
    private ComponentName component() { return new ComponentName(context, OtaJob.class); }

    boolean check(int jobId) {
        if (jobId != MANUAL && jobId != PERIODIC) return true;
        boolean automatic = jobId == PERIODIC;
        if (!CHECKING.compareAndSet(false, true)) return false;
        try {
            synchronized (LOCK) {
                if ((automatic && !automaticEnabled()) || !new KioskPolicy(context).available()) return true;
                reconcile(installed().versionCode);
                if (prefs.getInt("target", 0) != 0) return true;
                message("正在检查更新");
            }
            URI feed = OtaChannel.feed(FEED);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            transfer(feed, bytes, 16384, -1, null);
            JSONObject release = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            int version = release.getInt("versionCode");
            PackageInfo current = installed();
            if (version <= current.versionCode) {
                message("已是最新版本");
                return true;
            }
            long length = release.getLong("size");
            String sha256 = release.getString("sha256");
            if (length < 1 || length > MAX_APK || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("更新清单中的文件大小或校验值无效");
            }
            URI apk = OtaChannel.apk(feed, release.getString("apk"));
            File file = new File(context.getCacheDir(), "ota-download.apk");
            try {
                message("正在下载版本 " + version);
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                try (OutputStream out = new FileOutputStream(file)) {
                    transfer(apk, out, MAX_APK, length, sha);
                }
                if (!sha256.equals(hex(sha.digest()))) throw new IllegalArgumentException("下载校验失败，已取消更新");
                PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(
                        file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                if (candidate == null || !current.packageName.equals(candidate.packageName)
                        || candidate.versionCode != version || candidate.signatures == null
                        || !Arrays.equals(current.signatures, candidate.signatures)) {
                    throw new IllegalArgumentException("APK 的应用名称、版本或签名不匹配，已取消更新");
                }
                synchronized (LOCK) {
                    if (Thread.currentThread().isInterrupted() || (automatic && !automaticEnabled())
                            || !new KioskPolicy(context).available() || version <= installed().versionCode) return true;
                    install(file, version);
                }
            } finally { file.delete(); }
        } catch (IllegalArgumentException rejected) {
            message(rejected.getMessage());
        } catch (Exception unavailable) {
            message("更新未完成，请检查网络、存储空间或更新包后重试");
        } finally { CHECKING.set(false); }
        return true;
    }

    private static void transfer(URI uri, OutputStream out, long limit, long expected,
                                 MessageDigest sha) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        long until = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(10);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Update server unavailable");
            long reported = connection.getContentLengthLong();
            if (reported > limit || (expected >= 0 && reported >= 0 && reported != expected)) throw new IOException("Invalid size");
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buffer)) != -1;) {
                    total += n;
                    if (total > limit || (expected >= 0 && total > expected)
                            || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > until) {
                        throw new IOException("Update interrupted");
                    }
                    if (sha != null) sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
                if (expected >= 0 && total != expected) throw new IOException("Truncated update");
            }
        } finally { connection.disconnect(); }
    }

    private void install(File file, int version) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(file.length());
        // The private Android 8.1 build is testOnly so ADB can remove its device owner during recovery.
        // Oreo exposes this flag to the framework; only the already verified self-update uses it.
        if (Build.VERSION.SDK_INT == 27) {
            java.lang.reflect.Field flags = PackageInstaller.SessionParams.class.getField("installFlags");
            flags.setInt(params, flags.getInt(params) | 0x00000004);
        }
        int id = installer.createSession(params);
        boolean submitted = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(file); OutputStream out = session.openWrite("base.apk", 0, file.length())) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Install interrupted");
                    out.write(buffer, 0, n);
                }
                session.fsync(out);
            }
            Intent result = new Intent(context, OtaReceiver.class).setAction(RESULT);
            PendingIntent callback = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT);
            if (!prefs.edit().putInt("session", id).putInt("target", version).putInt("resumeVersion", version)
                    .putString("message", "正在安装版本 " + version).commit()) throw new IOException("Cannot save update state");
            session.commit(callback.getIntentSender());
            submitted = true;
        } finally {
            if (!submitted) {
                try { installer.abandonSession(id); }
                finally { clearPending("无法提交更新，请重试", false); }
            }
        }
    }

    boolean replaced() {
        synchronized (LOCK) {
            try {
                int current = installed().versionCode;
                reconcile(current);
                int resume = prefs.getInt("resumeVersion", 0);
                if (resume == 0 || current < resume) return false;
                prefs.edit().remove("resumeVersion").commit();
                return true;
            }
            catch (PackageManager.NameNotFoundException unavailable) { return false; }
        }
    }

    void result(int id, int status) {
        synchronized (LOCK) {
            if (prefs.getInt("session", -1) != id) return;
            if (status == PackageInstaller.STATUS_SUCCESS) return;
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                try { context.getPackageManager().getPackageInstaller().abandonSession(id); }
                catch (RuntimeException ignored) {}
            }
            clearPending(status == PackageInstaller.STATUS_PENDING_USER_ACTION
                    ? "设备需要在本机确认安装，远程更新已取消" : "Android 安装失败（" + status + "）", false);
        }
    }

    private boolean reconcile(int current) {
        int target = prefs.getInt("target", 0);
        if (target == 0) return false;
        if (current >= target) {
            clearPending("更新成功，当前版本 " + current, true);
            return true;
        }
        if (context.getPackageManager().getPackageInstaller().getSessionInfo(prefs.getInt("session", -1)) == null) {
            clearPending("安装未完成，请重新检查更新", false);
        }
        return false;
    }

    private void clearPending(String value, boolean keepResume) {
        SharedPreferences.Editor edit = prefs.edit().remove("session").remove("target").putString("message", value);
        if (!keepResume) edit.remove("resumeVersion");
        edit.commit();
    }

    private void message(String value) { prefs.edit().putString("message", value).apply(); }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
        return result.toString();
    }
}
