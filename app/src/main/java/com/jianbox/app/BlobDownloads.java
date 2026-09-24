package com.jianbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Transfers renderer-owned blobs to app storage; the HTTP service never sees blob URLs. */
final class BlobDownloads {
    private static final String INTERFACE = "JianBlobDownloads";
    private static final Map<WebView, Bridge> BRIDGES = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static String script;

    static boolean isBlob(String url) { return url != null && url.regionMatches(true, 0, "blob:", 0, 5); }

    static void install(WebView web) {
        if (BRIDGES.containsKey(web)) return;
        try {
            String source = script(web.getContext());
            Bridge bridge = new Bridge(web);
            BRIDGES.put(web, bridge);
            web.addJavascriptInterface(bridge, INTERFACE);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, source, Collections.singleton("*"));
            }
        } catch (Exception error) {
            Toast.makeText(web.getContext(), "网页文件下载支持初始化失败", Toast.LENGTH_SHORT).show();
        }
    }

    static void onPageFinished(WebView web) {
        try { web.evaluateJavascript(script(web.getContext()), null); } catch (Exception ignored) {}
    }

    static void onPageStarted(WebView web, String url) {
        Bridge bridge = BRIDGES.get(web);
        if (bridge != null && !isBlob(url)) bridge.cancelAll("页面已切换，请回原网页重新下载");
    }

    static void release(WebView web) {
        Bridge bridge = BRIDGES.remove(web);
        if (bridge != null) bridge.cancelAll("页面已关闭，请回原网页重新下载");
    }

    static void request(Context context, WebView web, String url, String userAgent,
                        String disposition, String mime, FrameLayout host) {
        String pageUrl = web.getUrl();
        Runnable start = () -> {
            if (!java.util.Objects.equals(pageUrl, web.getUrl()) || !BRIDGES.containsKey(web)) {
                Toast.makeText(context, "原页面已不可用，请重新点击网页下载按钮", Toast.LENGTH_LONG).show();
                return;
            }
            String id = BRIDGES.get(web).start(url, pageUrl, userAgent, disposition, mime);
            if (id != null && context instanceof Activity) {
                BrowserDownloads.showProgress((Activity) context, host, id.hashCode() & 0x7fffffffL, "网页文件");
            }
        };
        if (context instanceof Activity) {
            new AlertDialog.Builder(context).setTitle("下载网页生成的文件")
                    .setMessage("文件将保存到简盒下载目录。下载期间请保留当前网页。")
                    .setNegativeButton("取消", null).setPositiveButton("开始下载", (dialog, which) -> start.run()).show();
        } else start.run();
    }

    private static String script(Context context) throws Exception {
        if (script == null) {
            try (InputStream input = context.getAssets().open("blob-downloads.js");
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                script = output.toString("UTF-8");
            }
        }
        return script;
    }

    private static final class Pending {
        final DownloadStore.Record record;
        final String token, disposition;
        BlobTransfer transfer;
        Runnable timeout;
        long lastUpdate;
        Pending(DownloadStore.Record record, String disposition) {
            this.record = record;
            this.disposition = disposition == null ? "" : disposition;
            token = UUID.randomUUID().toString();
        }
    }

    // This bridge exposes no file paths or general native API to pages. Every write requires a
    // short-lived random capability created only for a WebView DownloadListener request.
    public static final class Bridge {
        private final Context context;
        private final WeakReference<WebView> web;
        private final Map<String, Pending> pending = new HashMap<>();

        Bridge(WebView web) { context = web.getContext().getApplicationContext(); this.web = new WeakReference<>(web); }

        synchronized String start(String url, String pageUrl, String userAgent, String disposition, String mime) {
            WebView view = web.get();
            if (view == null || pending.size() >= 2) { toast("请等待当前网页文件下载完成"); return null; }
            DownloadStore.Record record = new DownloadStore.Record();
            record.url = url;
            record.pageUrl = pageUrl == null ? "" : pageUrl;
            record.userAgent = userAgent == null ? "" : userAgent;
            record.mime = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            record.name = URLUtil.guessFileName("https://download.invalid/file", disposition, record.mime);
            Pending job = new Pending(record, disposition);
            pending.put(job.token, job);
            DownloadStore.put(context, record);
            job.timeout = () -> timeout(job.token);
            touch(job);
            try {
                String command = new JSONObject().put("token", job.token).put("url", url).toString();
                view.evaluateJavascript(script(context) + "\n;window.__jianBlobDownloads&&window.__jianBlobDownloads.start(" + command + ");", null);
            } catch (Exception error) { failJob(job, "无法读取网页临时文件，请重新点击下载"); }
            return record.id;
        }

        @JavascriptInterface public synchronized boolean begin(String token, String url, String name, String mime, long size) {
            Pending job = pending.get(token);
            if (job == null || job.transfer != null || !job.record.url.equals(url)) return false;
            try {
                if (DownloadStore.get(context, job.record.id) == null) { failJob(job, "下载已取消"); return false; }
                if (mime != null && mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) job.record.mime = mime;
                String filename = !job.disposition.isEmpty() ? URLUtil.guessFileName("https://download.invalid/file", job.disposition, job.record.mime)
                        : name != null && !name.trim().isEmpty() ? name : URLUtil.guessFileName("https://download.invalid/file", null, job.record.mime);
                job.record.name = BlobTransfer.safeName(filename);
                job.transfer = new BlobTransfer(new File(BrowserDownloads.destinationHint(context)), job.record.name, size);
                job.record.path = job.transfer.temporaryFile().getAbsolutePath();
                job.record.total = size;
                job.record.status = DownloadStore.RUNNING;
                if (!DownloadStore.updateExisting(context, job.record)) { failJob(job, "下载已取消"); return false; }
                touch(job);
                return true;
            } catch (Exception error) { failJob(job, error.getMessage()); return false; }
        }

        @JavascriptInterface public synchronized boolean write(String token, int sequence, String data) {
            Pending job = pending.get(token);
            if (job == null || job.transfer == null) return false;
            try {
                job.transfer.write(sequence, data);
                job.record.done = job.transfer.written();
                long now = System.currentTimeMillis();
                if (now - job.lastUpdate >= 500) {
                    job.record.updatedAt = now;
                    if (!DownloadStore.updateExisting(context, job.record)) { failJob(job, "下载已取消"); return false; }
                    job.lastUpdate = now;
                }
                touch(job);
                return true;
            } catch (Exception error) { failJob(job, error.getMessage()); return false; }
        }

        @JavascriptInterface public synchronized boolean finish(String token) {
            Pending job = pending.get(token);
            if (job == null || job.transfer == null) return false;
            try {
                if (DownloadStore.get(context, job.record.id) == null) { failJob(job, "下载已取消"); return false; }
                File file = job.transfer.finish();
                job.record.path = file.getAbsolutePath();
                job.record.name = file.getName();
                job.record.status = DownloadStore.COMPLETE;
                job.record.updatedAt = System.currentTimeMillis();
                if (!DownloadStore.updateExisting(context, job.record)) { java.nio.file.Files.deleteIfExists(file.toPath()); }
                else toast("下载完成：" + file.getName());
                MAIN.removeCallbacks(job.timeout);
                pending.remove(token);
                return true;
            } catch (Exception error) { failJob(job, error.getMessage()); return false; }
        }

        @JavascriptInterface public synchronized void fail(String token) {
            Pending job = pending.get(token);
            if (job != null) failJob(job, "网页文件读取中断，请回原页面重新下载");
        }

        private synchronized void timeout(String token) {
            Pending job = pending.get(token);
            if (job != null) failJob(job, "网页临时链接已失效或读取超时，请重新点击下载");
        }

        synchronized void cancelAll(String reason) { for (Pending job : new ArrayList<>(pending.values())) failJob(job, reason); }

        private void touch(Pending job) { MAIN.removeCallbacks(job.timeout); MAIN.postDelayed(job.timeout, 30000); }

        private void failJob(Pending job, String reason) {
            if (job.transfer != null) job.transfer.abort();
            job.record.path = "";
            job.record.status = DownloadStore.FAILED;
            job.record.updatedAt = System.currentTimeMillis();
            job.record.error = reason == null ? "网页文件下载失败，请重新下载" : reason;
            if (DownloadStore.updateExisting(context, job.record)) toast(job.record.error);
            MAIN.removeCallbacks(job.timeout);
            pending.remove(job.token);
        }

        private void toast(String message) { MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show()); }
    }

    private BlobDownloads() {}
}
