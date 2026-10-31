package com.dafeiyu.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    /* ================= 日志 =================
       全写进 /sdcard/Android/data/com.dafeiyu.chat/files/fish_app.log
       超过 512KB 自动滚成 fish_app.prev.log，不会撑爆存储。
       崩了的堆栈也写这里——出现 "CRASH" 就是真崩了。 */
    private static volatile java.io.File LOG_FILE;

    private void initLog() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "fish_app.log");
            if (f.exists() && f.length() > 512 * 1024) {
                java.io.File old = new java.io.File(dir, "fish_app.prev.log");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            LOG_FILE = f;
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void writeLog(String tag, String msg) {
        java.io.File f = LOG_FILE;
        if (f == null) return;
        try {
            String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date());
            String line = ts + " [" + tag + "] " + msg + "\n";
            java.io.FileOutputStream os = new java.io.FileOutputStream(f, true);
            try {
                os.write(line.getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                writeLog("CRASH", "线程 " + t.getName() + " 挂了: " + e);
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                writeLog("CRASH", sw.toString());
            } catch (Throwable ignored) {
            }
            if (def != null) def.uncaughtException(t, e);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initLog();
        installCrashHandler();
        writeLog("BOOT", "起来了 pid=" + android.os.Process.myPid()
                + " 缓存=" + (new java.io.File(getFilesDir(), "remote_index.html").exists() ? "有" : "无"));

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

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                writeLog("WEB", "页面加载完 " + url);
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                writeLog("WEB", "主页面加载失败 "
                        + (req.getUrl() == null ? "?" : req.getUrl().toString())
                        + " :: " + (err == null ? "?" : err.getDescription()));
            }
        });
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

    @Override
    protected void onResume() {
        super.onResume();
        writeLog("LIFE", "回到前台");
    }

    @Override
    protected void onPause() {
        super.onPause();
        writeLog("LIFE", "退到后台");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        writeLog("LIFE", "内存吃紧 level=" + level);
    }

    @Override
    protected void onDestroy() {
        writeLog("LIFE", "被销毁");
        super.onDestroy();
    }

    /* 远程页面：改 index.html 推到仓库就生效，不用重编 APK */
    private static final String[] REMOTE_INDEX = {
            "http://127.0.0.1:6186/app/index.html",
            "http://127.0.0.1:6188/index.html",
            "https://ghproxy.net/https://raw.githubusercontent.com/sq7274/fishchat/main/index.html",
            "https://gh-proxy.com/https://raw.githubusercontent.com/sq7274/fishchat/main/index.html",
            "https://cdn.jsdelivr.net/gh/sq7274/fishchat@main/index.html",
            "https://raw.githubusercontent.com/sq7274/fishchat/main/index.html"
    };
    private static final String CACHE_FILE = "remote_index.html";
    /* APK 自带的页面版本，改了这个值就会作废旧缓存 */
    private static final String ASSET_TAG = "v18b";

    private void loadLocal() {
        String tag = getSharedPreferences("fish", 0).getString("assetTag", "");
        if (!ASSET_TAG.equals(tag)) {
            new java.io.File(getFilesDir(), CACHE_FILE).delete();
            getSharedPreferences("fish", 0).edit().putString("assetTag", ASSET_TAG).apply();
            writeLog("WEB", "版本标签变 " + ASSET_TAG + "，清掉旧页面缓存");
        }
        String cached = readCache();
        if (cached != null) {
            writeLog("WEB", "先用缓存的页面，长度 " + cached.length());
            web.loadDataWithBaseURL("file:///android_asset/", cached, "text/html", "UTF-8", null);
        } else {
            writeLog("WEB", "用 APK 自带的 index.html");
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
            int code = c.getResponseCode();
            if (code != 200) {
                writeLog("NET", "拉页面 " + remote + " 返回 " + code);
                return false;
            }
            final String html = readAllBytes(c.getInputStream());
            if (!looksLikeApp(html)) {
                writeLog("NET", "拉回来的不像页面（长度 " + html.length() + "）" + remote);
                return false;
            }
            if (html.equals(readCache())) {
                writeLog("NET", "远程页面没更新，继续用缓存 " + remote);
                return true;
            }
            try (java.io.FileOutputStream fo =
                         new java.io.FileOutputStream(new java.io.File(getFilesDir(), CACHE_FILE))) {
                fo.write(html.getBytes("UTF-8"));
            }
            writeLog("NET", "远程页面更新了，换成新版，" + html.length() + " 字节 " + remote);
            runOnUiThread(() -> {
                if (web != null) {
                    web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
                }
            });
            return true;
        } catch (Exception e) {
            writeLog("NET", "拉页面失败 " + remote + " :: " + e);
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
        writeLog("PICK", "选图回来了 req=" + req + " res=" + res + " 有数据=" + (data != null));
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
            writeLog("PICK", "没选图，放弃");
            emitImage(id, false, "没选", "");
            return;
        }
        final Uri uri = data.getData();
        writeLog("PICK", "开始上传 " + uri);
        new Thread(() -> uploadImage(id, url, key, uri)).start();
    }

    /* 把相册原图先压到长边 1080 再传。
       以前直接把原图流式往外扔，两个毛病：
       ① chunked 上传，服务器要 Content-Length，回 411；
       ② 几兆的原图整个过内存，App 被系统杀掉（选完图直接闪退）。 */
    private byte[] shrinkToJpeg(Uri uri) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            InputStream p1 = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(p1, null, probe);
            if (p1 != null) p1.close();
            int scale = 1;
            int maxSide = Math.max(probe.outWidth, probe.outHeight);
            while (maxSide / scale > 1080) scale *= 2;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = scale;
            InputStream p2 = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(p2, null, opt);
            if (p2 != null) p2.close();
            if (bm == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 85, bo);
            bm.recycle();
            return bo.toByteArray();
        } catch (Throwable t) {
            writeLog("IMG", "压缩失败 " + t);
            return null;
        }
    }

    private void uploadImage(String id, String url, String key, Uri uri) {
        HttpURLConnection c = null;
        try {
            byte[] raw = shrinkToJpeg(uri);
            if (raw == null || raw.length == 0) {
                emitImage(id, false, "图片读不出来", "");
                return;
            }
            String name = "img" + System.currentTimeMillis() + ".jpg";
            String boundary = "----fish" + System.currentTimeMillis();
            byte[] hb = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                    + "Content-Type: image/jpeg\r\n\r\n").getBytes("UTF-8");
            byte[] tb = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setDoOutput(true);
            /* 关键：给出确定的长度，别再 chunked */
            c.setFixedLengthStreamingMode(hb.length + raw.length + tb.length);
            writeLog("IMG", "上传 " + (raw.length / 1024) + "KB -> " + url);

            try (OutputStream os = c.getOutputStream()) {
                os.write(hb);
                os.write(raw);
                os.write(tb);
            }

            int code = c.getResponseCode();
            String body = readAllBytes(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) {
                writeLog("IMG", "上传失败 HTTP " + code + " :: " + body);
                emitImage(id, false, "HTTP " + code + " " + body, "");
                return;
            }
            JSONObject o = new JSONObject(body);
            JSONObject d = o.optJSONObject("data");
            String aid = d != null ? d.optString("attachment_id", "") : "";
            if (aid.isEmpty()) {
                writeLog("IMG", "返回里没 attachment_id :: " + body);
                emitImage(id, false, body, "");
                return;
            }
            String shown = d != null ? d.optString("filename", name) : name;
            writeLog("IMG", "上传成功 " + aid);
            emitImage(id, true, aid, shown);
        } catch (Exception e) {
            writeLog("IMG", "上传异常 " + e);
            emitImage(id, false, e.toString(), "");
        } finally {
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

    private void emitUploaded(String id, int code, String body) {
        js("window.__uploaded&&window.__uploaded(" + id + "," + code + ","
                + JSONObject.quote(body == null ? "" : body) + ")");
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

        /** 前端把内部错误、关键事件丢进来，一起记进日志。 */
        @JavascriptInterface
        public void jsLog(String msg) {
            writeLog("JS", msg);
        }

        /** 读日志尾巴（最后 80KB）给前端显示。 */
        @JavascriptInterface
        public String readLog() {
            try {
                java.io.File f = LOG_FILE;
                if (f == null || !f.exists()) return "（还没有日志）";
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
                try {
                    long len = raf.length();
                    long from = len > 80000 ? len - 80000 : 0;
                    raf.seek(from);
                    byte[] b = new byte[(int) (len - from)];
                    raf.readFully(b);
                    return new String(b, "UTF-8");
                } finally {
                    raf.close();
                }
            } catch (Throwable t) {
                return "读日志失败: " + t;
            }
        }

        @JavascriptInterface
        public String logPath() {
            java.io.File f = LOG_FILE;
            return f == null ? "" : f.getAbsolutePath();
        }

        /** 清空日志。 */
        @JavascriptInterface
        public void clearLog() {
            try {
                java.io.File f = LOG_FILE;
                if (f != null && f.exists()) f.delete();
                writeLog("JS", "日志被清空了");
            } catch (Throwable ignored) {
            }
        }

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
                        writeLog("CHAT", "对话接口返回 " + code);
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
                    writeLog("CHAT", "对话结束 ok");
                    emitDone(id, 200, "");
                } catch (Exception e) {
                    writeLog("CHAT", "对话异常 " + e);
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

        /**
         * 网页压缩后的 base64 直接上传（不经过系统选图），
         * 结果回调 window.__uploaded(id, code, body)。
         */
        @JavascriptInterface
        public void upload(final String id, final String url, final String key,
                           final String b64, final String name) {
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                    String fname = (name == null || name.isEmpty())
                            ? ("img" + System.currentTimeMillis() + ".jpg") : name;
                    String low = fname.toLowerCase();
                    String type = low.endsWith(".png") ? "image/png"
                            : low.endsWith(".webp") ? "image/webp"
                            : low.endsWith(".gif") ? "image/gif" : "image/jpeg";
                    String boundary = "----fish" + System.currentTimeMillis();
                    byte[] hb = ("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fname + "\"\r\n"
                            + "Content-Type: " + type + "\r\n\r\n").getBytes("UTF-8");
                    byte[] tb = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(120000);
                    c.setRequestProperty("Authorization", "Bearer " + key);
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(hb.length + raw.length + tb.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(hb);
                        os.write(raw);
                        os.write(tb);
                    }
                    int code = c.getResponseCode();
                    String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                    writeLog("IMG", "上传 " + raw.length + "B -> " + code);
                    emitUploaded(id, code, body);
                } catch (Exception e) {
                    writeLog("IMG", "上传异常 " + e);
                    emitUploaded(id, 0, e.toString());
                } finally {
                    if (c != null) c.disconnect();
                }
            }).start();
        }

        /** 普通 POST（JSON 体），回 window.__done(id, code, body) */
        @JavascriptInterface
        public void post(final String id, final String url, final String key, final String body) {
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(40000);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    if (key != null && !key.isEmpty()) {
                        c.setRequestProperty("Authorization", "Bearer " + key);
                    }
                    c.setDoOutput(true);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write((body == null ? "" : body).getBytes("UTF-8"));
                    }
                    int code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    emitDone(id, code, readAll(in));
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
                    writeLog("GET", "请求失败 " + url + " :: " + e);
                    emitDone(id, 0, e.toString());
                } finally {
                    if (c != null) c.disconnect();
                }
            }).start();
        }
    }
}

