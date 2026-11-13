package com.samet.windowstik;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

final class AppUpdate {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static boolean downloading;

    static void download(Context context, String url, String digest) {
        if (downloading) return;
        downloading = true;
        Context app = context.getApplicationContext();
        Toast.makeText(app, "Güncelleme indiriliyor…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File file = null;
            try {
                file = File.createTempFile("update-", ".apk", app.getCacheDir());
                fetch(url, digest, file);
                install(app, file);
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(app, "Güncelleme indirilemedi veya doğrulanamadı.", Toast.LENGTH_LONG).show());
            } finally {
                if (file != null) file.delete();
                new Handler(Looper.getMainLooper()).post(() -> downloading = false);
            }
        }, "apk-update").start();
    }

    private static void fetch(String url, String expected, File file) throws Exception {
        if (!expected.matches("sha256:[0-9a-f]{64}")) throw new IOException("Missing digest");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        URL target = new URL(url);
        for (int redirects = 0; redirects < 5; redirects++) {
            if (!"https".equals(target.getProtocol())) throw new IOException("Insecure redirect");
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    target = new URL(target, location);
                    continue;
                }
                if (status != 200) throw new IOException("HTTP " + status);
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("APK too large");
                        hash.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                    }
                }
                StringBuilder actual = new StringBuilder("sha256:");
                for (byte value : hash.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
                if (!expected.contentEquals(actual)) throw new IOException("Digest mismatch");
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static void install(Context app, File file) throws Exception {
        PackageInfo candidate = app.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        PackageInfo current = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        if (candidate == null || !app.getPackageName().equals(candidate.packageName)
                || candidate.getLongVersionCode() <= current.getLongVersionCode()) throw new IOException("Invalid update");
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("base.apk", 0, file.length());
                 InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                session.fsync(out);
            }
            Intent callback = new Intent(app, UpdateResult.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            session.commit(PendingIntent.getBroadcast(app, id, callback, flags).getIntentSender());
            committed = true;
        } finally {
            if (!committed) installer.abandonSession(id);
        }
    }
}
