package com.dafeiyu.chat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
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
                if (webFileCb != null) { webFileCb.onReceiveValue(null); }
                webFileCb = cb;
                Intent it;
                try {
                    it = params != null ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT);
                } catch (Exception e) {
                    it = new Intent(Intent.ACTION_GET_CONTENT);
                }
                it.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(Intent.createChooser(it, "选张图"), REQ_WEB_PICK);
                } catch (Exception e) {
                    webFileCb = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        setContentView(web);
        if (web != null) loadLocal();
        new Thread(this::tryLoadRemote).start();
    }

    /* 远程页面：改 index.html 推到仓库就生效，不用重编 APK */
    private static final String[] REMOTE_INDEX = {
            "https://raw.githubusercontent.com/sq7274/fishchat/main/index.html",
            "https://raw.githubusercontent.com/sq7274/fishchat/main/assets/index.html"
    };
    private static final String CACHE_FILE = "remote_index.html";

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
        for (String remote : REMOTE_INDEX) {
            if (tryOneRemote(remote)) return;
        }
    }

    private boolean tryOneRemote(String remote) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(remote).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return false;
            final String html = readAllBytes(c.getInputStream());
            if (!looksLikeApp(html)) return false;
            if (html.equals(readCache())) return true;
            try (java.io.FileOutputStream fo =
                         new java.io.FileOutputStream(new java.io.File(getFilesDir(), CACHE_FILE))) {
                fo.write(html.getBytes("UTF-8"));
            }
            runOnUiThread(() -> {
                if (web != null) {
                    web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
                }
            });
            return true;
        } catch (Exception ignored) {
            /* 网络不行就继续用本地的 */
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------- 选图上传 ---------- */

    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_WEB_PICK = 1002;
    private ValueCallback<Uri[]> webFileCb;

    private String imgId, imgUrl, imgKey;

    private void startPickImage(String id, String url, String key) {
        imgId = id;
        imgUrl = url;
        imgKey = key;
        try {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(it, "选张图"), REQ_PICK_IMAGE);
        } catch (Exception e) {
            emitImage(id, false, "打不开选图", "");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_WEB_PICK) {
            ValueCallback<Uri[]> cb = webFileCb;
            webFileCb = null;
            if (cb == null) return;
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    out = new Uri[]{ data.getData() };
                } else if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            cb.onReceiveValue(out);
            return;
        }
        if (req != REQ_PICK_IMAGE) return;
        final String id = imgId, url = imgUrl, key = imgKey;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            emitImage(id, false, "没选", "");
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> uploadImage(id, url, key, uri)).start();
    }

    private void uploadImage(String id, String url, String key, Uri uri) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            String type = getContentResolver().getType(uri);
            if (type == null || !type.startsWith("image")) type = "image/jpeg";
            String ext = type.contains("png") ? ".png" : type.contains("gif") ? ".gif"
                    : type.contains("webp") ? ".webp" : ".jpg";
            String name = "img" + System.currentTimeMillis() + ext;

            String boundary = "----fish" + System.currentTimeMillis();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                    + "Content-Type: " + type + "\r\n\r\n";
            String tail = "\r\n--" + boundary + "--\r\n";

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setDoOutput(true);
            c.setChunkedStreamingMode(8192);

            in = getContentResolver().openInputStream(uri);
            try (OutputStream os = c.getOutputStream()) {
                os.write(head.getBytes("UTF-8"));
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.write(tail.getBytes("UTF-8"));
            }

            int code = c.getResponseCode();
            String body = readAllBytes(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) {
                emitImage(id, false, "HTTP " + code + " " + body, "");
                return;
            }
            JSONObject o = new JSONObject(body);
            JSONObject d = o.optJSONObject("data");
            String aid = d != null ? d.optString("attachment_id", "") : "";
            if (aid.isEmpty()) {
                emitImage(id, false, body, "");
                return;
            }
            String shown = d.optString("filename", name);
            emitImage(id, true, aid, shown);
        } catch (Exception e) {
            emitImage(id, false, e.toString(), "");
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (c != null) c.disconnect();
        }
    }

    private void emitImage(String id, boolean ok, String a, String b) {
        StringBuilder sb = new StringBuilder("window.__imageResult&&window.__imageResult(");
        sb.append(JSONObject.quote(id == null ? "" : id)).append(',');
        sb.append(ok);
        sb.append(',').append(JSONObject.quote(a == null ? "" : a));
        sb.append(',').append(JSONObject.quote(b == null ? "" : b));
        sb.append(");");
        js(sb.toString());
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

        /** 选图并上传，结果回调 window.__imageResult(id, ok, attachmentId, name)。 */
        @JavascriptInterface
        public void pickImage(final String id, final String url, final String key) {
            runOnUiThread(() -> startPickImage(id, url, key));
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
