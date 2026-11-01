package br.com.caio.pttbluetooth;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.net.http.SslError;

public class HubActivity extends Activity {
    private static final String HOST = "nthnkr.com";
    private WebView webView;
    private TextView offlineNotice;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 73;

    private static final String[][] MODULES = {
        {"Portal", "https://nthnkr.com/"},
        {"Finanças", "https://nthnkr.com/financas"},
        {"TV", "https://nthnkr.com/tv"},
        {"Terminal", "https://nthnkr.com/terminal"},
        {"Terceira Margem", "https://nthnkr.com/tm/"},
        {"Notas", "https://nthnkr.com/notas"},
        {"Caderno", "https://nthnkr.com/caderno"},
        {"Status", "https://nthnkr.com/status"}
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildShell();
        configureWebView();
        if (state == null) webView.loadUrl(MODULES[0][1]);
        else webView.restoreState(state);
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12, 18, 26));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(6, 5, 6, 5);
        for (String[] module : MODULES) {
            TextView item = new TextView(this);
            item.setText(module[0]);
            item.setTextColor(Color.WHITE);
            item.setTextSize(13);
            item.setGravity(Gravity.CENTER);
            item.setPadding(14, 12, 14, 12);
            item.setBackgroundColor(Color.rgb(31, 48, 65));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(4, 2, 4, 2);
            nav.addView(item, params);
            item.setOnClickListener(v -> webView.loadUrl(module[1]));
        }
        navScroll.addView(nav);
        root.addView(navScroll, new LinearLayout.LayoutParams(-1, -2));

        offlineNotice = new TextView(this);
        offlineNotice.setText("Conecte-se à internet para acessar os módulos online do Hub. O PTT Bluetooth continua local.");
        offlineNotice.setTextColor(Color.WHITE);
        offlineNotice.setTextSize(12);
        offlineNotice.setPadding(12, 8, 12, 8);
        offlineNotice.setBackgroundColor(Color.rgb(112, 72, 24));
        offlineNotice.setVisibility(View.GONE);
        root.addView(offlineNotice, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setSafeBrowsingEnabled(true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    Uri origin = request.getOrigin();
                    boolean trustedOrigin = origin != null
                            && "https".equalsIgnoreCase(origin.getScheme())
                            && HOST.equalsIgnoreCase(origin.getHost());
                    String[] requested = request.getResources();
                    boolean audioOnly = requested.length > 0;
                    for (String resource : requested) {
                        if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audioOnly = false;
                    }
                    if (trustedOrigin && audioOnly
                            && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(Uri.parse(url));
            }
            private boolean routeUrl(Uri uri) {
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if ("https".equalsIgnoreCase(scheme) && HOST.equalsIgnoreCase(host)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Toast.makeText(HubActivity.this, "Não há aplicativo para abrir este link.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                offlineNotice.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) offlineNotice.setVisibility(View.VISIBLE);
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(HubActivity.this, "Conexão segura recusada. Verifique a rede ou o certificado.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] result = resultCode == RESULT_OK && data != null && data.getData() != null
                    ? new Uri[]{data.getData()} : null;
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
