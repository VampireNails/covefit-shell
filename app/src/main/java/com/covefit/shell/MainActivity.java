package com.covefit.shell;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;

import java.io.IOException;
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

    /** 测试钩子：adb shell am broadcast -a com.covefit.shell.LOAD_URL --es url "http://<pc-tailscale-ip>:8788/" */
    private final BroadcastReceiver urlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.covefit.shell.LOAD_URL".equals(intent.getAction())) {
                String url = intent.getStringExtra("url");
                if (url != null && webView != null) {
                    Log.i(TAG, "LOAD_URL broadcast -> " + url);
                    webView.loadUrl(url);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 真机 UI 自动化调试：开放 Chrome DevTools Protocol，便于 adb/DevTools 注入令牌与点击。
        // 仅 debug 构建生效（CI 发布 app-debug.apk），release 构建不会开启，避免生产面暴露。
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        // 1) 壳内本地转发代理（域名分流）
        try {
            localProxy = new LocalProxy(LOCAL_PROXY_PORT);
            localProxy.start();
        } catch (IOException e) {
            Log.e(TAG, "LocalProxy start failed", e);
            localProxy = null;
        }

        // 2) 仅本 App 的 WebView 走本地代理（androidx.webkit.ProxyController 进程级，不影响其它 App）
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

        // 注册测试钩子（仅响应特定 action，生产无副作用）：用于真机验证路径A等直连地址
        registerReceiver(urlReceiver, new IntentFilter("com.covefit.shell.LOAD_URL"));
    }

    private void setAppProxy(int port) {
        // 不同 WebView 实现接受的 proxy rule 格式不同：
        //  - AOSP / 标准 WebView： "PROXY 127.0.0.1:8899"
        //  - 华为 HwWebview 等 OEM： "127.0.0.1:8899"（带 "PROXY " 前缀会被拒）
        // setProxyOverride 在格式不被接受时会同步抛 IllegalArgumentException，这里逐一重试。
        String[] candidates = {
                "PROXY 127.0.0.1:" + port,
                "127.0.0.1:" + port
        };
        for (String rule : candidates) {
            try {
                ProxyConfig config = new ProxyConfig.Builder()
                        .addProxyRule(rule)
                        .build();
                Executor executor = Executors.newSingleThreadExecutor();
                ProxyController.getInstance().setProxyOverride(config, executor,
                        () -> Log.i(TAG, "app proxy override set -> " + rule));
                Log.i(TAG, "setAppProxy OK with rule: " + rule);
                return;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "setAppProxy rejected rule '" + rule + "': " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "setAppProxy failed (WebView proxy unsupported on this device?)", e);
                return;
            }
        }
        Log.e(TAG, "setAppProxy: no acceptable proxy rule format for this WebView");
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
        try { unregisterReceiver(urlReceiver); } catch (Exception ignore) {}
        super.onDestroy();
    }
}
