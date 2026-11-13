package local.jile.v5;

import android.content.Context;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Hosts the same parent forms as the LAN portal, without exposing a LAN session or PIN. */
final class LocalParentPanel extends WebView {
    private static final String HOST = "v5-parent.local";
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' blob: data:; base-uri 'none'; form-action 'none'; frame-src 'none'";
    private volatile boolean active = true;

    LocalParentPanel(Context context, String panel, Runnable leaveParent) {
        super(context);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(false);
        getSettings().setAllowContentAccess(false);
        getSettings().setAllowFileAccessFromFileURLs(false);
        getSettings().setAllowUniversalAccessFromFileURLs(false);
        getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        addJavascriptInterface(new Bridge(leaveParent), "V5LocalParent");
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return localAsset(request);
            }
        });
        // Keep the page heading visible instead of scrolling to the section anchor.
        loadUrl("https://" + HOST + "/index.html#local-" + panel);
    }

    private WebResourceResponse localAsset(WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!active || !"GET".equals(request.getMethod()) || !"https".equals(uri.getScheme())
                || !HOST.equals(uri.getHost()) || uri.getPort() != -1 || uri.getQuery() != null) return blocked();
        String path = uri.getPath();
        if (path == null) return blocked();
        String mime;
        switch (path) {
            case "/index.html": mime = "text/html"; break;
            case "/style.css": mime = "text/css"; break;
            case "/app.js": case "/site-icons.js": mime = "text/javascript"; break;
            case "/brand.png": mime = "image/png"; break;
            default:
                if (!path.matches("/wallpapers/(original|aurora|cosmos|dune)\\.webp")) return blocked();
                mime = "image/webp";
        }
        Map<String,String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", CSP);
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Cache-Control", "no-store");
        try {
            return new WebResourceResponse(mime, mime.startsWith("image/") ? null : "UTF-8",
                    200, "OK", headers, getContext().getAssets().open("parent" + path));
        } catch (IOException failure) { return blocked(); }
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    void closePanel() {
        active = false;
        removeJavascriptInterface("V5LocalParent");
        stopLoading();
        destroy();
    }

    private final class Bridge {
        private final Runnable leaveParent;
        Bridge(Runnable leaveParent) { this.leaveParent = leaveParent; }

        @JavascriptInterface public String read(String path) {
            FamilyService service = FamilyService.running;
            if (!active || service == null) return error("家长服务尚未就绪");
            if ("/api/update".equals(path)) {
                try { return service.ota().status().toString(); }
                catch (Exception failure) { return error("暂时无法读取更新状态"); }
            }
            if (!"/api/sites".equals(path) && !"/api/desktop".equals(path) && !"/api/usage".equals(path)) {
                return error("此页面不支持该操作");
            }
            try { return service.settings(path).toString(); }
            catch (Exception failure) { return error("暂时无法读取设置"); }
        }

        @JavascriptInterface public String write(String path, String body) {
            FamilyService service = FamilyService.running;
            if (!active || service == null) return error("家长服务尚未就绪");
            if (body == null || body.length() > 420000) return error("设置内容过大");
            try {
                JSONObject data = new JSONObject(body);
                if ("/api/update".equals(path)) {
                    service.ota().configure(data);
                    return service.ota().status().toString();
                }
                if ("/api/password".equals(path)) {
                    String next = data.optString("new_password");
                    if (!next.matches("[0-9]{6}") || !next.equals(data.optString("confirm_password"))) {
                        return error("请两次输入相同的六位数字");
                    }
                    if (!service.changeLocalPassword(data.optString("current_password"), next)) {
                        return error("无法修改，请检查当前 PIN 或稍后再试");
                    }
                    post(() -> { if (active) leaveParent.run(); });
                    return "{}";
                }
                if (!"/api/sites".equals(path) && !"/api/desktop".equals(path)) return error("此页面不支持该操作");
                if (!service.updateSettings(path, data)) return error("请检查网址、图标或桌面设置的格式");
                return service.settings(path).toString();
            } catch (IllegalArgumentException invalid) { return error(invalid.getMessage()); }
            catch (Exception failure) { return error("保存失败，请重试"); }
        }

        private String error(String message) {
            return "{\"error\":" + JSONObject.quote(message) + "}";
        }
    }
}
