package local.jile.v5;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.util.Collections;

public final class SiteActivity extends Activity {
    private WebView page;
    private FamilyExtras extras;
    private String entry, origin;
    private boolean parentSite;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        extras = new FamilyExtras(this);
        parentSite = getIntent().getBooleanExtra("parentSite", false);
        entry = parentSite ? FamilyExtras.AUTHOR : getIntent().getStringExtra("url");
        if (entry == null || (!parentSite && extras.site(entry) == null)) { finish(); return; }
        origin = FamilyExtras.origin(entry);
        if (origin.isEmpty()) { finish(); return; }
        page = new WebView(this);
        page.getSettings().setAllowFileAccess(false);
        page.getSettings().setAllowContentAccess(false);
        page.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Keep pages read-only: scripts could otherwise create requests outside WebView's HTTP callbacks.
        page.getSettings().setJavaScriptEnabled(parentSite);
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) { return denied(); }
        });
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                boolean block = !allowed(request.getUrl().toString());
                if (block && request.isForMainFrame()) Toast.makeText(SiteActivity.this, "此地址未开放", Toast.LENGTH_SHORT).show();
                return block;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!allowed(url)) return denied();
                return null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (!allowed(url)) {
                    view.stopLoading();
                    view.setVisibility(android.view.View.INVISIBLE);
                    Toast.makeText(SiteActivity.this, "已拦截外部页面", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        });
        setContentView(page);
        page.loadUrl(entry);
    }

    @Override public void onBackPressed() {
        if (page != null && page.canGoBack()) page.goBack();
        else finish();
    }

    private boolean allowed(String url) { return origin.equals(FamilyExtras.origin(url)) && (parentSite || extras.site(entry) != null); }
    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
    @Override protected void onResume() {
        super.onResume();
        if (entry != null && !parentSite && extras.site(entry) == null) finish();
    }
    @Override protected void onDestroy() {
        if (page != null) {
            ((android.view.ViewGroup) page.getParent()).removeView(page);
            page.destroy();
        }
        super.onDestroy();
    }
}
