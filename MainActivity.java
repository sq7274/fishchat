package com.dafeiyu.chat;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void js(String code) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    private void emitChunk(String id, String line) {
        js("window.__chunk(" + id + "," + JSONObject.quote(line) + ")");
    }

    private void emitDone(String id, int status, String err) {
        js("window.__done(" + id + "," + status + "," + JSONObject.quote(err == null ? "" : err) + ")");
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStreamReader r = new InputStreamReader(in, "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAllBytes(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public class Bridge {

        /**
         * 流式对话：读 SSE，逐行回调 window.__chunk。
         */
        @JavascriptInterface
        public void chat(final String id, final String url, final String key, final String body) {
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(300000);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setRequestProperty("Accept", "text/event-stream");
                    c.setRequestProperty("Authorization", "Bearer " + key);
                    c.setDoOutput(true);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body.getBytes("UTF-8"));
                    }

                    int code = c.getResponseCode();
                    if (code != 200) {
                        emitDone(id, code, readAll(c.getErrorStream()));
                        return;
                    }

                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            emitChunk(id, line);
                        }
                    }
                    emitDone(id, 200, "");
                } catch (Exception e) {
                    emitDone(id, 0, e.toString());
                } finally {
                    if (c != null) c.disconnect();
                }
            }).start();
        }

        /** 普通 GET，用于自检。 */
        @JavascriptInterface
        public void get(final String id, final String url, final String key) {
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("Authorization", "Bearer " + key);
                    int code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    emitDone(id, code, readAllBytes(in));
                } catch (Exception e) {
                    emitDone(id, 0, e.toString());
                } finally {
                    if (c != null) c.disconnect();
                }
            }).start();
        }
    }
}
