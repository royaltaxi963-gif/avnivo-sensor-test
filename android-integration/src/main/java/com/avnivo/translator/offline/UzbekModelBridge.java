package com.avnivo.translator.offline;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-private, integrity-checked Uzbek model delivery for the future Android APK.
 * IMPORTANT: supply a real Sherpa-ONNX runtime adapter; never report "active" until
 * the adapter successfully loads the verified model. No browser-only fake activation.
 */
public final class UzbekModelBridge {
    public interface RuntimeAdapter {
        boolean load(File model, File tokens) throws Exception;
        void unload();
    }

    private static final String ID = "uzbek-stt";
    private static final String[] NAMES = {"model.int8.onnx", "tokens.txt"};
    private final Context context;
    private final WebView webView;
    private final RuntimeAdapter runtime;
    private final String ticketEndpoint;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String state = "not_installed";
    private volatile String message = "";
    private volatile int percent = 0;

    public UzbekModelBridge(Context context, WebView webView, RuntimeAdapter runtime, String trustedApiBase) {
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.runtime = runtime;
        if (!trustedApiBase.startsWith("https://")) throw new IllegalArgumentException("HTTPS required");
        this.ticketEndpoint = trustedApiBase.replaceAll("/+$", "") + "/api/offline-models/uzbek-stt/download-ticket";
        worker.execute(this::restore);
    }

    private File directory() { return new File(context.getFilesDir(), "offline-models/uzbek-stt/v1"); }
    private File verified(String name) { return new File(directory(), name); }
    private synchronized void update(String next, int progress, String detail) {
        state = next; percent = progress; message = detail;
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new Event('avnivo-offline-module-status'))", null));
    }

    @JavascriptInterface public String status(String id) {
        JSONObject json = new JSONObject();
        try {
            json.put("state", state);
            json.put("percent", percent);
            json.put("active", "active".equals(state));
            json.put("message", message);
        } catch (Exception ignored) {}
        return json.toString();
    }

    @JavascriptInterface public void download(String id) {
        if (!ID.equals(id) || "downloading".equals(state) || "activating".equals(state)) return;
        worker.execute(this::install);
    }

    @JavascriptInterface public void remove(String id) {
        if (!ID.equals(id)) return;
        worker.execute(() -> {
            runtime.unload();
            for (String name : NAMES) verified(name).delete();
            update("not_installed", 0, "");
        });
    }

    private void restore() {
        if (!verified(NAMES[0]).isFile() || !verified(NAMES[1]).isFile()) return;
        try {
            update("activating", 100, "");
            if (runtime.load(verified(NAMES[0]), verified(NAMES[1]))) update("active", 100, "");
            else update("error", 100, "Model dvigateli ishga tushmadi");
        } catch (Exception e) { update("error", 100, e.getMessage()); }
    }

    private void install() {
        try {
            update("downloading", 0, "");
            JSONObject ticket = getJson(ticketEndpoint);
            JSONArray files = ticket.getJSONArray("files");
            if (files.length() != NAMES.length) throw new IOException("Unexpected model file count");
            if (!directory().exists() && !directory().mkdirs()) throw new IOException("Cannot create model directory");
            long total = 0;
            for (int i = 0; i < files.length(); i++) total += files.getJSONObject(i).getLong("bytes");
            long completed = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String name = file.getString("name");
                if (!name.equals(NAMES[0]) && !name.equals(NAMES[1])) throw new IOException("Unexpected file");
                long expected = file.getLong("bytes");
                File tmp = new File(directory(), name + ".part");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                HttpURLConnection connection = (HttpURLConnection) new URL(file.getString("url")).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                try {
                    if (connection.getResponseCode() != 200) throw new IOException("Download HTTP " + connection.getResponseCode());
                    long count = 0;
                    try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                            count += n;
                            update("downloading", (int)Math.min(99, (completed + count) * 100 / total), "");
                        }
                        out.getFD().sync();
                    }
                    StringBuilder hash = new StringBuilder();
                    for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                    if (count != expected || !hash.toString().equalsIgnoreCase(file.getString("sha256")))
                        throw new IOException("Model integrity check failed: " + name);
                    if (!tmp.renameTo(verified(name))) throw new IOException("Cannot commit verified model: " + name);
                    completed += count;
                } finally { connection.disconnect(); if (tmp.exists()) tmp.delete(); }
            }
            restore();
        } catch (Exception e) {
            update("error", percent, e.getMessage() == null ? "Yuklash xatosi" : e.getMessage());
        }
    }

    private static JSONObject getJson(String endpoint) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("Ticket HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { c.disconnect(); }
    }
}
