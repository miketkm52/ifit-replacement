package uk.eba.ifitrecon;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * iFit Recon v0.1 — finds out how this console exposes live data and control,
 * so the real replacement app can be built on the right foundation.
 * Report is shown on screen, saved to /sdcard/Download/ifit-recon.txt and
 * served at http://<console-ip>:8080 so it can be opened from a phone.
 */
public class MainActivity extends Activity {

    private static final Pattern IFIT = Pattern.compile("(?i)ifit|glassos|wolf|valinor|eru|icon");
    private static final int PORT = 8080;

    private TextView out;
    private ScrollView scroll;
    private final StringBuilder report = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean tailing = false;
    private List<File> logFiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));
        root.setPadding(24, 24, 24, 24);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(button("1. Scan", v -> scan()));
        bar.addView(button("2. Live log (60s)", v -> startTail()));
        bar.addView(button("Stop", v -> tailing = false));
        bar.addView(button("Storage access", v -> askStorage()));
        root.addView(bar);

        out = new TextView(this);
        out.setTextColor(Color.parseColor("#c9d1d9"));
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(13);
        out.setTextIsSelectable(true);
        scroll = new ScrollView(this);
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        askStorage();
        startServer();
        log("iFit Recon 0.1 — open http://" + ip() + ":" + PORT + " on your phone to copy this report.");
        log("Tap 1. Scan, then start a MANUAL workout, tap 2. Live log and keep changing speed/incline/resistance.\n");
    }

    private Button button(String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(18);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(8, 0, 8, 16);
        btn.setLayoutParams(p);
        return btn;
    }

    // ---------- permissions ----------
    private void askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                    catch (Exception e2) { log("Could not open all-files permission screen: " + e2); }
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    // ---------- scan ----------
    private void scan() {
        new Thread(() -> {
            log("===== SCAN " + now() + " =====");
            log("== Device");
            log("Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            log("Model " + Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.DEVICE + " / " + Build.DISPLAY);
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            log("Screen " + dm.widthPixels + "x" + dm.heightPixels + " @" + dm.densityDpi + "dpi");
            log("IP " + ip());
            log("Storage access: " + storageStatus());

            log("\n== iFit-related packages");
            try {
                for (PackageInfo pi : getPackageManager().getInstalledPackages(0)) {
                    if (IFIT.matcher(pi.packageName).find())
                        log(pi.packageName + "  v" + pi.versionName + " (" + pi.versionCode + ")");
                }
            } catch (Exception e) { log("package list failed: " + e); }

            log("\n== Candidate log/data files (newest first)");
            List<File> found = new ArrayList<>();
            File sd = Environment.getExternalStorageDirectory();
            walk(sd, 0, found);
            walk(new File(sd, "Android/data"), 0, found);
            Collections.sort(found, (a, c) -> Long.compare(c.lastModified(), a.lastModified()));
            logFiles = found;
            if (found.isEmpty()) log("(none found — check Storage access above)");
            for (int i = 0; i < Math.min(found.size(), 25); i++) {
                File f = found.get(i);
                log(stamp(f.lastModified()) + "  " + f.length() + "B  " + f.getAbsolutePath());
            }

            log("\n== Listening TCP ports");
            listening("/proc/net/tcp");
            listening("/proc/net/tcp6");

            log("\n== Shell checks");
            log(exec("id"));
            log(exec("getprop ro.debuggable"));
            log(exec("getprop persist.sys.usb.config"));
            log(exec("getprop service.adb.tcp.port"));
            log(exec("ls -la /sdcard/.wolflogs"));
            log("===== SCAN DONE =====\n");
            save();
        }).start();
    }

    private void walk(File dir, int depth, List<File> found) {
        if (dir == null || depth > 6) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String p = k.getAbsolutePath();
            if (k.isDirectory()) {
                walk(k, depth + 1, found);
            } else if (IFIT.matcher(p).find() && !found.contains(k)
                    && (p.toLowerCase(Locale.UK).contains("log") || p.endsWith(".txt") || p.endsWith(".json"))) {
                found.add(k);
            }
        }
    }

    private void listening(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line; r.readLine();
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 7 && f[3].equals("0A")) {
                    String[] local = f[1].split(":");
                    int port = Integer.parseInt(local[local.length - 1], 16);
                    log((path.endsWith("6") ? "tcp6 " : "tcp  ") + "port " + port + "  uid " + f[7]
                            + "  " + uidName(Integer.parseInt(f[7])));
                }
            }
        } catch (Exception e) { log(path + ": " + e.getMessage()); }
    }

    private String uidName(int uid) {
        String n = getPackageManager().getNameForUid(uid);
        return n == null ? "" : n;
    }

    // ---------- live log ----------
    private void startTail() {
        if (logFiles.isEmpty()) { log("Run 1. Scan first."); return; }
        tailing = true;
        new Thread(() -> {
            // follow the 3 most recently written files at once
            int n = Math.min(3, logFiles.size());
            File[] files = new File[n];
            long[] pos = new long[n];
            for (int i = 0; i < n; i++) { files[i] = logFiles.get(i); pos[i] = files[i].length(); }
            log("===== LIVE LOG " + now() + " — change speed/incline/resistance now =====");
            for (File f : files) log("following " + f.getAbsolutePath());
            long end = System.currentTimeMillis() + 60_000;
            while (tailing && System.currentTimeMillis() < end) {
                for (int i = 0; i < n; i++) {
                    try (RandomAccessFile raf = new RandomAccessFile(files[i], "r")) {
                        long len = raf.length();
                        if (len < pos[i]) pos[i] = 0;
                        if (len > pos[i]) {
                            raf.seek(pos[i]);
                            byte[] buf = new byte[(int) Math.min(len - pos[i], 64_000)];
                            raf.readFully(buf);
                            pos[i] += buf.length;
                            for (String l : new String(buf, StandardCharsets.UTF_8).split("\n"))
                                if (!l.trim().isEmpty()) log("[" + files[i].getName() + "] " + l);
                        }
                    } catch (Exception e) { /* keep going */ }
                }
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            }
            tailing = false;
            log("===== LIVE LOG DONE =====\n");
            save();
        }).start();
    }

    // ---------- output ----------
    private synchronized void log(String s) {
        report.append(s).append('\n');
        ui.post(() -> {
            out.setText(report);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void save() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dl.mkdirs();
            File f = new File(dl, "ifit-recon.txt");
            try (FileWriter w = new FileWriter(f)) { w.write(report.toString()); }
            log("(saved " + f.getAbsolutePath() + ")");
        } catch (Exception e) { log("(save failed: " + e.getMessage() + ")"); }
    }

    private void startServer() {
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                while (true) {
                    try (Socket s = ss.accept()) {
                        new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
                        byte[] body = report.toString().getBytes(StandardCharsets.UTF_8);
                        OutputStream o = s.getOutputStream();
                        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        o.write(body);
                        o.flush();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { log("web server failed: " + e.getMessage()); }
        }).start();
    }

    // ---------- helpers ----------
    private String exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd + " 2>&1"});
            StringBuilder sb = new StringBuilder("$ " + cmd + "\n");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l; int c = 0;
                while ((l = r.readLine()) != null && c++ < 40) sb.append(l).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) { return "$ " + cmd + " -> " + e.getMessage(); }
    }

    private String storageStatus() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager() ? "all files" : "NOT GRANTED";
        if (Build.VERSION.SDK_INT >= 23)
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    ? "granted" : "NOT GRANTED";
        return "granted";
    }

    @SuppressWarnings("deprecation")
    private String ip() {
        try {
            int a = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo().getIpAddress();
            return (a & 255) + "." + (a >> 8 & 255) + "." + (a >> 16 & 255) + "." + (a >> 24 & 255);
        } catch (Exception e) { return "?"; }
    }

    private static String now() { return stamp(System.currentTimeMillis()); }
    private static String stamp(long t) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(new Date(t)); }
}
