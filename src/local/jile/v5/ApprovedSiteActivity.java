package local.jile.v5;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.BasicSelectionActionDelegate;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;

/** Gecko reader for one parent-approved origin, with no browser chrome or external handlers. */
public final class ApprovedSiteActivity extends Activity {
    private static final String EXTENSION = "approved-sites@jile-v5.local";
    private static final String NATIVE = "jile.approvedSites";
    private static final String LOGIN_NATIVE = "jile.siteLogin";
    private static GeckoRuntime runtime;
    private static WebExtension guardExtension;
    private static ApprovedSiteActivity active;
    private static boolean guardReady;
    private FamilyExtras extras;
    private SharedPreferences preferences;
    private String entry;
    private String origin;
    private String currentUrl;
    private GeckoSession session;
    private boolean entryLoaded;
    private GeckoView browser;
    private TextView notice;
    private Button account;
    private SiteLoginStore logins;
    private WebExtension.Port loginPort;
    private boolean canGoBack;
    private SiteImageChooser images;
    private GeckoSession.PermissionDelegate.Callback androidPermission;
    private boolean mediaGranted;
    private String openedSiteConfig;
    private boolean parentSite;
    private static final int MEDIA_PERMISSIONS = 64;

    private final SharedPreferences.OnSharedPreferenceChangeListener approvalChanged =
            (prefs, key) -> {
                if (!FamilyExtras.SITES.equals(key)) return;
                JSONObject site = extras.site(entry);
                if (site == null || !site.toString().equals(openedSiteConfig)) finish();
            };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        extras = new FamilyExtras(this);
        logins = new SiteLoginStore(this);
        images = new SiteImageChooser(this, this::sitePermission);
        parentSite = getIntent().getBooleanExtra("parentSite", false);
        entry = parentSite ? FamilyExtras.AUTHOR : getIntent().getStringExtra("url");
        origin = FamilyExtras.origin(entry);
        if (!approved()) {
            finish();
            return;
        }
        if (!parentSite) {
            openedSiteConfig = extras.site(entry).toString();
            preferences = getSharedPreferences("family", MODE_PRIVATE);
            preferences.registerOnSharedPreferenceChangeListener(approvalChanged);
        }
        notice = new TextView(this);
        notice.setGravity(Gravity.CENTER);
        notice.setTextSize(16);
        notice.setText("正在打开学习网站…");
        setContentView(notice);
        notice.postDelayed(() -> {
            if (!entryLoaded) notice.setText("学习浏览器暂未就绪，请返回桌面重试。");
        }, 15000);
        try {
            if (runtime == null) startEngine();
        } catch (IOException | RuntimeException failure) {
            notice.setText("学习浏览器暂不可用，请返回桌面重试。");
        }
    }

    private void startEngine() throws IOException {
        File config = new File(getFilesDir(), "approved-site-gecko.yaml");
        try (InputStream source = getAssets().open("approved-sites/gecko.yaml");
             FileOutputStream target = new FileOutputStream(config)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) target.write(buffer, 0, count);
        }
        runtime = GeckoRuntime.create(getApplicationContext(), new GeckoRuntimeSettings.Builder()
                .configFilePath(config.getAbsolutePath())
                .remoteDebuggingEnabled(false)
                .consoleOutput(false)
                .extensionsProcessEnabled(false)
                .build());
        runtime.getWebExtensionController().ensureBuiltIn(
                "resource://android/assets/approved-sites/", EXTENSION).accept(
                extension -> {
                    if (extension == null) return;
                    guardExtension = extension;
                    extension.setMessageDelegate(new WebExtension.MessageDelegate() {
                        @Override public GeckoResult<Object> onMessage(String name, Object value,
                                WebExtension.MessageSender sender) {
                            if (!NATIVE.equals(name) || !EXTENSION.equals(sender.webExtension.id)
                                    || sender.session != null || !(value instanceof JSONObject)) {
                                return GeckoResult.fromValue(false);
                            }
                            JSONObject message = (JSONObject) value;
                            if ("ready".equals(message.optString("type"))) {
                                guardReady = true;
                                if (active != null) active.openWhenReady();
                                return GeckoResult.fromValue(true);
                            }
                            boolean allowed = "check".equals(message.optString("type"))
                                    && guardReady && active != null && active.entryLoaded
                                    && active.allowsRequest(message.optString("url"), message.optString("resourceType"),
                                            message.optString("document"));
                            return GeckoResult.fromValue(allowed);
                        }
                    }, NATIVE);
                }, failure -> android.util.Log.e("ApprovedSites", "Browser policy unavailable", failure));
    }

    private boolean approved() {
        return entry != null && !origin.isEmpty() && (parentSite || extras.site(entry) != null);
    }

    private boolean allows(String url) {
        if (parentSite) return origin.equals(FamilyExtras.origin(url));
        JSONObject site = entry == null ? null : extras.site(entry);
        if (site == null || origin.isEmpty()) return false;
        String candidate = FamilyExtras.origin(url);
        if (candidate.isEmpty()) return false;
        URI base = URI.create(origin);
        URI target = URI.create(candidate);
        boolean transport = base.getScheme().equals(target.getScheme()) && base.getPort() == target.getPort();
        if (!transport) return false;
        String host = base.getHost();
        return host.equals(target.getHost()) || (site.optBoolean("includeSubdomains")
                && host.contains(".") && !host.matches("[0-9.]+")
                && target.getHost().endsWith("." + host));
    }

    private boolean allowsRequest(String url, String type, String document) {
        if ("sub_frame".equals(type) && !sameFrameOrigin(url)) return false;
        if (allows(url)) return true;
        if (!allows(document) || !isBaiduPage(document)) return false;
        String resource = FamilyExtras.origin(url);
        // Baidu serves its mobile site's code and styles from these two exact hosts.
        if ("https://psstatic.cdn.bcebos.com:443".equals(resource)) return "script".equals(type);
        return "https://ms.bdstatic.com:443".equals(resource)
                && ("script".equals(type) || "stylesheet".equals(type)
                    || "image".equals(type) || "font".equals(type));
    }

    private boolean sameFrameOrigin(String url) {
        if (!hasSitePermissions()) return true;
        return FamilyExtras.origin(currentUrl == null ? entry : currentUrl).equals(FamilyExtras.origin(url));
    }

    private boolean hasSitePermissions() {
        JSONObject site = extras.site(entry);
        JSONObject rules = site == null ? null : site.optJSONObject("permissions");
        return rules != null && (rules.optBoolean("camera") || rules.optBoolean("microphone")
                || rules.optBoolean("photos"));
    }

    private boolean sitePermission(String name) {
        if (parentSite) return false;
        JSONObject site = extras.site(entry);
        JSONObject rules = site == null ? null : site.optJSONObject("permissions");
        return !isFinishing() && origin.startsWith("https://") && origin.equals(FamilyExtras.origin(currentUrl))
                && rules != null && rules.optBoolean(name);
    }

    private static boolean isBaiduPage(String url) {
        String document = FamilyExtras.origin(url);
        return "https://www.baidu.com:443".equals(document)
                || "https://m.baidu.com:443".equals(document);
    }

    private void openWhenReady() {
        if (active != this || isFinishing() || !approved()) return;
        if (session == null) openBlankSession();
        if (!guardReady || entryLoaded) return;
        entryLoaded = true;
        browser = new GeckoView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        account = new Button(this);
        account.setText("账号");
        account.setVisibility(View.GONE);
        account.setOnClickListener(view -> SiteLoginDialog.show(this, logins, this::loginOrigin, clear -> {
            if (clear) clearPendingLoginFill();
            else sendSavedLogin();
        }));
        LinearLayout.LayoutParams accountLayout = new LinearLayout.LayoutParams(-2, -2);
        accountLayout.gravity = Gravity.END;
        content.addView(account, accountLayout);
        content.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
        browser.setSession(session);
        session.getWebExtensionController().setMessageDelegate(guardExtension,
                new WebExtension.MessageDelegate() {
                    @Override public void onConnect(WebExtension.Port port) {
                        WebExtension.MessageSender sender = port.sender;
                        if (!LOGIN_NATIVE.equals(port.name) || sender.session != session || !sender.isTopLevel()
                                || !EXTENSION.equals(sender.webExtension.id) || !allows(sender.url)
                                || SiteLoginStore.origin(sender.url) == null) {
                            port.disconnect();
                            return;
                        }
                        loginPort = port;
                        port.setDelegate(new WebExtension.PortDelegate() {
                            @Override public void onPortMessage(Object message, WebExtension.Port source) {
                                if (source == loginPort && "ready".equals(message)) sendSavedLogin();
                            }

                            @Override public void onDisconnect(WebExtension.Port source) {
                                if (source == loginPort) loginPort = null;
                            }
                        });
                    }
                }, LOGIN_NATIVE);
        session.loadUri(entry);
    }

    private String loginOrigin() {
        return !parentSite && active == this && allows(currentUrl) ? SiteLoginStore.origin(currentUrl) : null;
    }

    private void sendSavedLogin() {
        String origin = loginOrigin();
        if (origin == null || loginPort == null
                || !origin.equals(SiteLoginStore.origin(loginPort.sender.url))) return;
        try {
            JSONObject saved = logins.load(origin);
            if (saved != null) loginPort.postMessage(saved.put("origin", origin));
        } catch (Exception ignored) {
            message("已保存账号暂不可用，请在「账号」中重新保存。");
        }
    }

    private void clearPendingLoginFill() {
        if (loginPort == null) return;
        try { loginPort.postMessage(new JSONObject().put("clear", true)); }
        catch (Exception ignored) {}
    }

    private void openBlankSession() {
        // Gecko needs a window to start an already-installed extension on a cold boot.
        // Open the empty session first and deny every network request until the guard is ready.
        session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession current,
                    LoadRequest request) {
                boolean allowed = guardReady && entryLoaded
                        && request.target != TARGET_WINDOW_NEW && allows(request.uri);
                if (!allowed) message("此地址未开放");
                return GeckoResult.fromValue(allowed ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
            }
            @Override public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession current,
                    LoadRequest request) {
                return GeckoResult.fromValue(guardReady && entryLoaded && allows(request.uri) && sameFrameOrigin(request.uri)
                        ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
            }
            @Override public void onLocationChange(GeckoSession current, String url,
                    java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                    Boolean userGesture) {
                if (url == null || "about:blank".equals(url)) return;
                if (guardReady && entryLoaded && allows(url)) {
                    if (currentUrl != null && !FamilyExtras.origin(currentUrl).equals(FamilyExtras.origin(url))) {
                        images.cancel();
                        if (mediaGranted) { finish(); return; }
                    }
                    currentUrl = url;
                    if (account != null) account.setVisibility(loginOrigin() == null ? View.GONE : View.VISIBLE);
                } else {
                    current.stop();
                    if (browser != null) browser.setVisibility(android.view.View.INVISIBLE);
                    message("此地址未开放");
                    finish();
                }
            }
            @Override public void onCanGoBack(GeckoSession current, boolean available) {
                canGoBack = available;
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(GeckoSession current, WebResponse response) {
                if (response.body != null) {
                    try { response.body.close(); } catch (IOException ignored) {}
                }
                message("此入口不允许下载文件");
            }
            @Override public void onContextMenu(GeckoSession current, int x, int y, ContextElement item) {}
            @Override public void onCrash(GeckoSession current) {
                message("网站已停止，请从桌面重新打开。");
                finish();
            }
            @Override public void onKill(GeckoSession current) {
                message("网站已停止，请从桌面重新打开。");
                finish();
            }
        });
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public void onAndroidPermissionsRequest(GeckoSession current,
                    String[] permissions, Callback callback) {
                if (active != ApprovedSiteActivity.this || permissions == null || permissions.length == 0
                        || androidPermission != null) { callback.reject(); return; }
                for (String permission : permissions) {
                    if ((!Manifest.permission.CAMERA.equals(permission) || !sitePermission("camera"))
                            && (!Manifest.permission.RECORD_AUDIO.equals(permission) || !sitePermission("microphone"))) {
                        callback.reject();
                        return;
                    }
                }
                androidPermission = callback;
                requestPermissions(permissions, MEDIA_PERMISSIONS);
            }
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession current,
                    ContentPermission permission) {
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
            @Override public void onMediaPermissionRequest(GeckoSession current, String uri,
                    MediaSource[] video, MediaSource[] audio, MediaCallback callback) {
                MediaSource camera = firstSource(video, MediaSource.SOURCE_CAMERA);
                MediaSource microphone = firstSource(audio, MediaSource.SOURCE_MICROPHONE);
                boolean wantsCamera = video != null && video.length > 0;
                boolean wantsMicrophone = audio != null && audio.length > 0;
                if (active != ApprovedSiteActivity.this || !origin.equals(FamilyExtras.origin(uri))
                        || (!wantsCamera && !wantsMicrophone)
                        || (wantsCamera && (camera == null || !canCapture("camera", Manifest.permission.CAMERA)))
                        || (wantsMicrophone && (microphone == null
                            || !canCapture("microphone", Manifest.permission.RECORD_AUDIO)))) {
                    callback.reject();
                    return;
                }
                String requested = wantsCamera && wantsMicrophone ? "摄像头和麦克风"
                        : wantsCamera ? "摄像头" : "麦克风";
                new AlertDialog.Builder(ApprovedSiteActivity.this).setTitle("使用" + requested)
                        .setMessage(origin + "\n仅在当前网页打开时可用。")
                        .setPositiveButton("允许本次", (dialog, which) -> {
                            if (active != ApprovedSiteActivity.this
                                    || (wantsCamera && !canCapture("camera", Manifest.permission.CAMERA))
                                    || (wantsMicrophone && !canCapture("microphone", Manifest.permission.RECORD_AUDIO))) {
                                callback.reject();
                                return;
                            }
                            mediaGranted = true;
                            callback.grant(camera, microphone);
                        })
                        .setNegativeButton("拒绝", (dialog, which) -> callback.reject())
                        .setOnCancelListener(dialog -> callback.reject()).show();
            }
        });
        session.setPromptDelegate(new GeckoSession.PromptDelegate() {
            @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession current, FilePrompt prompt) {
                return active == ApprovedSiteActivity.this ? images.show(prompt)
                        : GeckoResult.fromValue(prompt.dismiss());
            }
        });
        BasicSelectionActionDelegate selection = new BasicSelectionActionDelegate(this);
        selection.enableExternalActions(false);
        session.setSelectionActionDelegate(selection);
        session.open(runtime);
    }

    private boolean canCapture(String name, String permission) {
        return sitePermission(name) && checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static GeckoSession.PermissionDelegate.MediaSource firstSource(
            GeckoSession.PermissionDelegate.MediaSource[] sources, int kind) {
        if (sources != null) {
            for (GeckoSession.PermissionDelegate.MediaSource source : sources) {
                if (source.source == kind) return source;
            }
        }
        return null;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == SiteImageChooser.CAMERA_PERMISSION) {
            images.cameraPermissionResult(grants);
        } else if (request == MEDIA_PERMISSIONS && androidPermission != null) {
            GeckoSession.PermissionDelegate.Callback callback = androidPermission;
            androidPermission = null;
            if (grants.length == 0) { callback.reject(); return; }
            for (int grant : grants) {
                if (grant != PackageManager.PERMISSION_GRANTED) { callback.reject(); return; }
            }
            callback.grant();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SiteImageChooser.PICK_IMAGE || request == SiteImageChooser.TAKE_PHOTO) {
            images.result(request, result, data);
        }
    }

    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() {
        super.onResume();
        if (!approved()) {
            finish();
            return;
        }
        active = this;
        HomeKeyService.foreground(getPackageName(), ApprovedSiteActivity.class.getName());
        if (session != null) session.setActive(true);
        if (runtime != null) openWhenReady();
        if (account != null) account.setVisibility(loginOrigin() == null ? View.GONE : View.VISIBLE);
    }

    @Override protected void onPause() {
        if (active == this) active = null;
        if (session != null) session.setActive(false);
        if (mediaGranted) finish();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (canGoBack && session != null) session.goBack();
        else finish();
    }

    @Override protected void onDestroy() {
        if (active == this) active = null;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(approvalChanged);
        if (images != null) images.close();
        if (androidPermission != null) androidPermission.reject();
        if (session != null) {
            if (loginPort != null) loginPort.disconnect();
            if (browser != null) browser.releaseSession();
            session.close();
        }
        super.onDestroy();
    }
}
