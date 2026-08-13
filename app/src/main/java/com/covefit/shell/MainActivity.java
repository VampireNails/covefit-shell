package com.covefit.shell;

import android.app.Activity;
import android.net.ProxyConfig;
import android.net.ProxyController;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * CoveFit WebView 壳。
 *  - 启动壳内本地转发代理（按域名分流）
 *  - 用 ProxyController 把「本 App」的流量只导向该本地代理（不触碰系统代理设置）
 *  - 加载 CoveFit 移动控制面 PWA
 * 效果：仅 *.workers.dev 经 v2rayNG 出网；其它流量及其它 App 完全不受影响；随 App 启动。
 */
public class MainActivity extends Activity {
    private static final String TAG = "CoveFit.Main";
    private static final String PWA_URL = "https://covefit-tri-cloud.g-vampirenails.workers.dev/";
    private static final int LOCAL_PROXY_PORT = 8899;

    private WebView webView;
    private LocalProxy localProxy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1) 壳内本地转发代理（域名分流）
        try {
            localProxy = new LocalProxy(LOCAL_PROXY_PORT);
            localProxy.start();
        } catch (IOException e) {
            Log.e(TAG, "LocalProxy start failed", e);
            localProxy = null;
        }

        // 2) 仅本 App 走本地代理（ProxyController 为进程级，不影响其它 App）
        if (localProxy != null) {
            setAppProxy(LOCAL_PROXY_PORT);
        }

        // 3) WebView 加载 PWA
        webView = findViewById(R.id.webview);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl(PWA_URL);
    }

    private void setAppProxy(int port) {
        try {
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:" + port)
                    .build();
            Executor executor = Executors.newSingleThreadExecutor();
            ProxyController.getInstance().setProxyOverride(config, executor,
                    () -> Log.i(TAG, "app proxy override set -> 127.0.0.1:" + port));
        } catch (Exception e) {
            Log.e(TAG, "setAppProxy failed", e);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (localProxy != null) localProxy.stopProxy();
        super.onDestroy();
    }
}
