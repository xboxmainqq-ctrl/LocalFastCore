package ru.nexora.ai.server;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class DashboardActivity extends Activity {
    private DashboardBridge bridge;
    private GitHubBridge gitHubBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        bridge = new DashboardBridge(this, webView);
        webView.addJavascriptInterface(bridge, "NexoraHost");
        gitHubBridge = new GitHubBridge(this, webView);
        webView.addJavascriptInterface(gitHubBridge, "NexoraGitHub");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !"file".equals(uri.getScheme())
                        || !uri.toString().startsWith("file:///android_asset/web/");
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/web/index.html");
        setContentView(webView);
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.shutdown();
            bridge = null;
        }
        if (gitHubBridge != null) {
            gitHubBridge.shutdown();
            gitHubBridge = null;
        }
        super.onDestroy();
    }
}
