package com.dafeiyu.chat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
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
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    startActivityForResult(Intent.createChooser(i, "选张图"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        setContentView(web);
        if (web != null) loadLocal();
        new Thread(this::tryLoadRemote).start();
    }

    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_FILE = 1001;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] out = null;
            if (res == Activity.RESULT_OK && data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(out);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    /* 远程页面：改 index.html 推到仓库就生效，不用重编 APK */
    private static final String REMOTE_INDEX =
            "https://raw.githubusercontent.com/sq7274/fishchat/main/index.html";
    private static final String CACHE_FILE = "remote_index_v2.html";

    private void loadLocal() {
        String cached = readCache();
        if (cached != null) {
            web.loadDataWithBaseURL("file:///android_asset/", cached, "text/html", "UTF-8", null);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    private static boolean looksLikeApp(String s) {
        return s != null && s.length() > 2000 && s.contains("window.__chunk") && s.contains("</html>");
    }

    private String readCache() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), CACHE_FILE);
            if (!f.exists()) return null;
            String s = readAllBytes(new java.io.FileInputStream(f));
            return looksLikeApp(s) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void tryLoadRemote() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(REMOTE_INDEX).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return;
            final String html = readAllBytes(c.getInputStream());
            if (!looksLikeApp(html)) return;
            if (html.equals(readCache())) return;
            try (java.io.FileOutputStream fo =
                         new java.io.FileOutputStream(new java.io.File(getFilesDir(), CACHE_FILE))) {
                fo.write(html.getBytes("UTF-8"));
            }
            runOnUiThread(() -> {
                if (web != null) {
                    web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
                }
            });
        } catch (Exception ignored) {
            /* 网络不行就继续用本地的 */
        } finally {
            if (c != null) c.disconnect();
        }
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

    private void emitUploaded(String id, int code, String resp) {
        js("window.__uploaded(" + id + "," + code + ","
                + JSONObject.quote(resp == null ? "" : resp) + ")");
    }

    /* 聊天记录落盘，重启不丢 */
    private java.io.File histFile(String session) {
        String name = session == null || session.isEmpty() ? "default"
                : session.replaceAll("[^A-Za-z0-9_-]", "_");
        return new java.io.File(getFilesDir(), "hist_" + name + ".json");
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

        /**
         * 上传图片：base64 进来，multipart 发到 /api/v1/files，回 attachment_id。
         */
        @JavascriptInterface
        public void upload(final String id, final String url, final String key,
                           final String b64, final String filename) {
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                    String boundary = "----fish" + System.currentTimeMillis();
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(180000);
                    c.setRequestProperty("Authorization", "Bearer " + key);
                    c.setRequestProperty("Content-Type",
                            "multipart/form-data; boundary=" + boundary);
                    c.setDoOutput(true);

                    String safe = filename == null ? "image.jpg" : filename.replace("\"", "");
                    String ctype = safe.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(("--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                                + safe + "\"\r\n"
                                + "Content-Type: " + ctype + "\r\n\r\n").getBytes("UTF-8"));
                        os.write(data);
                        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
                    }

                    int code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    emitUploaded(id, code, readAllBytes(in));
                } catch (Exception e) {
                    emitUploaded(id, 0, e.toString());
                } finally {
                    if (c != null) c.disconnect();
                }
            }).start();
        }

        /** 聊天记录存手机文件：window.saveHist(session, json) */
        @JavascriptInterface
        public void saveHist(final String session, final String json) {
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(histFile(session))) {
                fo.write((json == null || json.isEmpty() ? "[]" : json).getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
        }

        /** 读回聊天记录，回调 window.__histLoaded(session, json) */
        @JavascriptInterface
        public void loadHist(final String session) {
            new Thread(() -> {
                String s = "";
                try {
                    java.io.File f = histFile(session);
                    if (f.exists()) s = readAllBytes(new java.io.FileInputStream(f));
                } catch (Exception ignored) {
                }
                js("window.__histLoaded && window.__histLoaded("
                        + JSONObject.quote(session == null ? "" : session) + ","
                        + JSONObject.quote(s) + ")");
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
