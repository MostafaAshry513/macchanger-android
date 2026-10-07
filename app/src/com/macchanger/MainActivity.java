package com.macchanger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lightweight, persistent WiFi MAC changer for rooted phones.
 *
 * Locates the vendor NVRAM file that holds the current WiFi MAC, backs it up,
 * replaces the MAC bytes, then restarts WiFi so the driver re-reads it.
 * Falls back to a runtime ip-link change when no NVRAM file matches.
 */
public class MainActivity extends Activity {

    static final String IFACE = "wlan0";

    // modern neutral dark surfaces; single accent
    static final int C_BG1  = 0xFF0B0B0E;
    static final int C_BG2  = 0xFF111116;
    static final int C_CARD = 0xFF17171C;
    static final int C_LINE = 0xFF232329;
    static final int C_TEXT = 0xFFF1F1F4;
    static final int C_DIM  = 0xFF9A9AA5;
    static final int C_OK   = 0xFF5DD87E;
    static final int C_WARN = 0xFFF5B94D;
    static final int C_BAD  = 0xFFFF6B6B;

    static final int B_SET_T = 0xFFFFFFFF;   // accent button text
    static final int B_RND1 = 0xFF26262D, B_RND_T = 0xFFEDEDF2;
    static final int B_RES1 = 0xFF26262D, B_RES_T = 0xFFFF8A8A;

    TextView vRuntime, vFactory;
    TextView vModel, vSoc, vAndroid;
    TextView vNetwork, vPrivacy;
    TextView tvRoot, vLog;
    EditText etMac;
    ScrollView scv;

    final Handler ui = new Handler(Looper.getMainLooper());
    final SecureRandom rnd = new SecureRandom();
    final List<String> logLines = new ArrayList<String>();
    final List<Integer> logColors = new ArrayList<Integer>();
    volatile boolean busy = false;
    volatile boolean lastRoot = false;
    volatile boolean rootChecking = false;
    boolean logExpanded = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        scv.post(new Runnable() { public void run() { scv.fullScroll(View.FOCUS_UP); } });
        checkRoot(true);
    }

    // ------------------------------------------------------------- styling

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    int darken(int c) {
        int a = (c >>> 24) & 0xFF, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (a << 24) | ((r * 8 / 10) << 16) | ((g * 8 / 10) << 8) | (b * 8 / 10);
    }

    GradientDrawable round(int fill, int stroke, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    LinearLayout card(String title, int titleColor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(C_CARD, 0, 18));
        int p = dp(14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(titleColor);
            t.setTextSize(11);
            t.setLetterSpacing(0.08f);
            t.setPadding(0, 0, 0, dp(6));
            c.addView(t);
        }
        return c;
    }

    TextView addRow(LinearLayout parent, String label, String value, boolean mono) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(C_DIM);
        l.setTextSize(13);
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(84), -2));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(C_TEXT);
        v.setTextSize(13);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(l);
        r.addView(v);
        parent.addView(r);
        return v;
    }

    TextView addRow(LinearLayout parent, String label, String value) {
        return addRow(parent, label, value, false);
    }

    Button mkBtn(String text, int fill, int border, int fg, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_pressed}, round(darken(fill), border, 14));
        sd.addState(new int[]{}, round(fill, border, 14));
        b.setBackground(sd);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return b;
    }

    LinearLayout.LayoutParams half() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = dp(5);
        return lp;
    }

    // ---------------------------------------------------------------- UI

    View buildUi() {
        int p = dp(18);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_BG1, C_BG2}));

        // --- sticky header: title | refresh | root ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(p, dp(20), p, dp(10));

        TextView title = new TextView(this);
        title.setText("Mac Changer");
        title.setTextColor(C_TEXT);
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(title);

        Button refreshBtn = mkBtn("Refresh", 0xFF1E1E24, 0, 0xFFC9C9D1,
                new Runnable() { public void run() { refresh(); log("info refreshed", C_DIM); } });
        refreshBtn.setTextSize(12);
        refreshBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(-2, -2);
        rblp.rightMargin = dp(8);
        refreshBtn.setLayoutParams(rblp);
        header.addView(refreshBtn);

        tvRoot = new TextView(this);
        tvRoot.setTextSize(12);
        tvRoot.setPadding(dp(10), dp(5), dp(10), dp(5));
        tvRoot.setClickable(true);
        tvRoot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { checkRoot(false); }
        });
        header.addView(tvRoot);
        outer.addView(header);
        setRoot(false, true);

        // --- scrollable cards ---
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, 0, p, dp(6));
        sc.addView(root, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout cMac = card("MAC ADDRESS", 0xFF7FB4FF);
        vRuntime = addRow(cMac, "runtime", "...", true);
        vRuntime.setTextSize(15);
        vFactory = addRow(cMac, "factory", "...", true);
        vFactory.setTextColor(C_DIM);
        root.addView(cMac);

        LinearLayout cDev = card("DEVICE", 0xFFB9A8FF);
        vModel   = addRow(cDev, "model",   "...");
        vSoc     = addRow(cDev, "soc",     "...");
        vAndroid = addRow(cDev, "android", "...");
        root.addView(cDev);

        LinearLayout cWifi = card("NETWORK", 0xFF6FD6C4);
        vNetwork = addRow(cWifi, "wifi", "...");
        vPrivacy = addRow(cWifi, "privacy", "...");
        vPrivacy.setClickable(true);
        vPrivacy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); }
                catch (Throwable ignored) { }
            }
        });
        root.addView(cWifi);

        LinearLayout cSet = card("SET MAC", 0xFFFF9FB0);
        etMac = new EditText(this);
        etMac.setHint("AA:BB:CC:DD:EE:FF");
        etMac.setHintTextColor(0x44FFFFFF);
        etMac.setTextColor(C_TEXT);
        etMac.setTextSize(15);
        etMac.setTypeface(Typeface.MONOSPACE);
        etMac.setInputType(InputType.TYPE_CLASS_TEXT);
        etMac.setSingleLine(true);
        etMac.setBackground(round(0xFF111116, C_LINE, 14));
        etMac.setPadding(dp(12), dp(11), dp(12), dp(11));
        cSet.addView(etMac);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(mkBtn("Set MAC", 0xFF6C63FF, 0, B_SET_T,
                new Runnable() { public void run() { setMac(etMac.getText().toString()); } }), half());
        row.addView(mkBtn("Random", B_RND1, 0, B_RND_T,
                new Runnable() { public void run() { randomMac(); } }), half());
        cSet.addView(row);

        Button restore = mkBtn("Restore factory", B_RES1, 0, B_RES_T,
                new Runnable() { public void run() { restore(); } });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(8);
        restore.setLayoutParams(rlp);
        cSet.addView(restore);
        root.addView(cSet);

        outer.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        scv = sc;

        // --- fixed bottom status / log bar (tap to expand) ---
        vLog = new TextView(this);
        vLog.setTextSize(12);
        vLog.setTextColor(C_DIM);
        vLog.setTypeface(Typeface.MONOSPACE);
        vLog.setMaxLines(1);
        vLog.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vLog.setBackground(round(C_CARD, C_LINE, 14));
        vLog.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.setMargins(p, dp(4), p, dp(12));
        vLog.setLayoutParams(llp);
        vLog.setClickable(true);
        vLog.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                logExpanded = !logExpanded;
                vLog.setMaxLines(logExpanded ? 6 : 1);
                vLog.setEllipsize(logExpanded ? null : android.text.TextUtils.TruncateAt.END);
                renderLog();
            }
        });
        outer.addView(vLog);

        root.setFocusableInTouchMode(true);
        root.requestFocus();
        return outer;
    }

    void toast(final String s) {
        ui.post(new Runnable() { public void run() {
            Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
        } });
    }

    void setRoot(boolean granted, boolean checking) {
        if (checking) {
            tvRoot.setText("checking\u2026");
            tvRoot.setTextColor(C_DIM);
            tvRoot.setBackground(round(0x00000000, C_LINE, 20));
        } else if (granted) {
            tvRoot.setText("\u25CF granted");
            tvRoot.setTextColor(C_OK);
            tvRoot.setBackground(round(0x00000000, C_LINE, 20));
        } else {
            tvRoot.setText("\u25CF not granted");
            tvRoot.setTextColor(C_BAD);
            tvRoot.setBackground(round(0x00000000, C_LINE, 20));
        }
    }

    /** Append one result to the log (newest first, max 8) and refresh the bar. */
    void log(final String msg, final int color) {
        ui.post(new Runnable() { public void run() {
            String sym = color == C_OK ? "\u2713 "
                       : (color == C_BAD || color == C_WARN) ? "\u2717 " : "\u2022 ";
            logLines.add(0, sym + msg);
            logColors.add(0, color);
            while (logLines.size() > 8) {
                logLines.remove(logLines.size() - 1);
                logColors.remove(logColors.size() - 1);
            }
            renderLog();
        } });
    }

    void renderLog() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < logLines.size(); i++) {
            int start = sb.length();
            sb.append(logLines.get(i));
            if (i < logLines.size() - 1) sb.append('\n');
            sb.setSpan(new ForegroundColorSpan(logColors.get(i)), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        vLog.setText(sb);
    }

    // ------------------------------------------------------------- shell

    String run(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            return new String(bo.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    String getprop(String name) {
        try {
            Process p = new ProcessBuilder("getprop", name).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            return new String(bo.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    byte[] readFile(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    boolean writeFile(File f, byte[] d) {
        try {
            FileOutputStream o = new FileOutputStream(f);
            o.write(d);
            o.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    byte[] readRoot(String path) {
        try {
            Process p = new ProcessBuilder("su", "-c", "cat '" + path + "'").start();
            final InputStream err = p.getErrorStream();
            new Thread(new Runnable() { public void run() {
                try { byte[] b = new byte[1024]; while (err.read(b) > 0) { } }
                catch (Throwable ignored) { }
            } }).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            byte[] d = bo.toByteArray();
            return d.length == 0 ? null : d;
        } catch (Throwable t) {
            return null;
        }
    }

    boolean writeRoot(String path, byte[] data) {
        File tmp = new File(getFilesDir(), "write.tmp");
        if (!writeFile(tmp, data)) return false;
        run("cat '" + tmp.getAbsolutePath() + "' > '" + path + "'");
        tmp.delete();
        byte[] back = readRoot(path);
        return back != null && Arrays.equals(back, data);
    }

    void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ----------------------------------------------------------- helpers

    String getRuntimeMac() {
        return run("cat /sys/class/net/" + IFACE + "/address").toLowerCase().trim();
    }

    String getPref(String k) { return getSharedPreferences("mc", 0).getString(k, null); }
    void setPref(String k, String v) {
        getSharedPreferences("mc", 0).edit().putString(k, v).apply();
    }

    String socName() {
        String id = firstNonEmpty(
                getprop("ro.chipname"),
                getprop("ro.soc.model"),
                getprop("ro.board.platform"),
                getprop("ro.hardware"),
                Build.HARDWARE);
        String all = (id + " " + getprop("ro.board.platform") + " " + getprop("ro.hardware")
                + " " + Build.BOARD + " " + Build.MANUFACTURER).toLowerCase();
        String vendor;
        if (all.contains("mediatek") || all.matches(".*\\bmt\\d+.*")) vendor = "MediaTek";
        else if (all.contains("qcom") || all.contains("msm") || all.contains("sdm")
                || all.contains("sm8") || all.contains("sm7") || all.contains("snapdragon")
                || all.contains("qualcomm")) vendor = "Qualcomm";
        else if (all.contains("exynos") || all.contains("samsung") || all.contains("universal"))
            vendor = "Samsung";
        else if (all.contains("spreadtrum") || all.contains("unisoc") || all.contains("ums")
                || all.contains("sc9") || all.contains("sc7")) vendor = "Unisoc";
        else vendor = "Unknown";
        if (id.isEmpty()) return vendor;
        String mid = id.toUpperCase();
        if (vendor.equals("Unknown")) return mid;
        return vendor + " " + mid;
    }

    int socColor(String s) {
        if (s.startsWith("MediaTek")) return 0xFFFFA94D;
        if (s.startsWith("Qualcomm")) return 0xFFFF7A7A;
        if (s.startsWith("Samsung"))  return 0xFF6EA8FF;
        if (s.startsWith("Unisoc"))   return 0xFFB98BFF;
        return C_TEXT;
    }

    String firstNonEmpty(String... a) {
        for (String s : a) if (s != null && !s.isEmpty()) return s;
        return "";
    }

    String pathsCsv() {
        StringBuilder sb = new StringBuilder();
        for (String p : nvramPaths()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('\'').append(p).append('\'');
        }
        return sb.toString();
    }

    List<String> nvramPaths() {
        List<String> l = new ArrayList<String>();
        l.add("/mnt/vendor/nvdata/APCFG/APRDEB/WIFI");   // MediaTek (modern)
        l.add("/data/nvram/APCFG/APRDEB/WIFI");          // MediaTek (older)
        l.add("/mnt/vendor/persist/wifi/wlan_mac.bin");  // Qualcomm
        l.add("/persist/wifi/wlan_mac.bin");             // Qualcomm
        l.add("/data/vendor/wifi/wlan_mac.bin");         // Qualcomm
        l.add("/efs/wifi/.mac.info");                    // Samsung
        l.add("/efs/wifi/mac.info");                     // Samsung
        l.add("/productinfo/wifi_mac");                  // Unisoc
        l.add("/mnt/vendor/productinfo/wifi_mac");       // Unisoc
        return l;
    }

    List<String> existingNvram() {
        List<String> out = new ArrayList<String>();
        String r = run("for p in " + pathsCsv() + "; do [ -e \"$p\" ] && echo \"$p\"; done");
        for (String line : r.split("\n")) {
            line = line.trim();
            if (line.startsWith("/")) out.add(line);
        }
        return out;
    }

    byte[] macToBytes(String mac) {
        try {
            String[] p = mac.trim().toLowerCase().split(":");
            if (p.length != 6) return null;
            byte[] b = new byte[6];
            for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    String bytesToMac(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) s.append(':');
            s.append(String.format("%02x", b[i] & 0xff));
        }
        return s.toString();
    }

    String hexPlain(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) s.append(String.format("%02x", b[i] & 0xff));
        return s.toString();
    }

    byte[] reverse(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }

    int replaceAll(byte[] data, byte[] pat, byte[] rep) {
        int count = 0;
        for (int i = 0; i + pat.length <= data.length; ) {
            boolean hit = true;
            for (int j = 0; j < pat.length; j++)
                if (data[i + j] != pat[j]) { hit = false; break; }
            if (hit) {
                System.arraycopy(rep, 0, data, i, rep.length);
                i += pat.length;
                count++;
            } else i++;
        }
        return count;
    }

    int patch(byte[] data, byte[] oldB, byte[] newB) {
        int t = 0;
        t += replaceAll(data, oldB, newB);
        t += replaceAll(data, reverse(oldB), reverse(newB));
        t += replaceAll(data, bytesToMac(oldB).getBytes(), bytesToMac(newB).getBytes());
        t += replaceAll(data, hexPlain(oldB).getBytes(), hexPlain(newB).getBytes());
        return t;
    }

    boolean isMac(String s) {
        return s != null && s.matches("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$");
    }

    File backupDir() {
        File d = new File(getFilesDir(), "nvram_backup");
        d.mkdirs();
        return d;
    }

    File backupFor(String path) {
        String name = path.replace('/', '_');
        File f = new File(backupDir(), name);
        File tag = new File(backupDir(), name + ".path");
        if (!tag.exists()) writeFile(tag, path.getBytes());
        return f;
    }

    String connectedSsid() {
        String s = run("cmd wifi status 2>/dev/null | head -3");
        int a = s.indexOf('"'), b = s.lastIndexOf('"');
        if (s.contains("connected to") && a >= 0 && b > a) return s.substring(a + 1, b);
        return "";
    }

    String configStore() {
        String x = run("cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null");
        if (x.isEmpty()) x = run("cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null");
        return x;
    }

    /** true if the currently-connected network is overriding the hardware MAC. */
    boolean isRandomized() {
        String pv = privacyValue(configStore(), connectedSsid());
        return pv.equals("1") || pv.equals("2");
    }

    /** Reads the per-network MacRandomizationSetting from the config store. 0=device MAC. */
    String privacyValue(String xml, String ssid) {
        if (xml == null || xml.isEmpty() || ssid == null || ssid.equals("\u2014")) return "?";
        String[] blocks = xml.split("<Network>");
        for (String blk : blocks) {
            if (!blk.contains(ssid)) continue;
            int i = blk.indexOf("MacRandomizationSetting");
            if (i < 0) return "1";
            int v = blk.indexOf("value=\"", i);
            if (v < 0) return "1";
            int e = blk.indexOf('"', v + 7);
            if (e <= v) return "1";
            return blk.substring(v + 7, e);
        }
        return "?";
    }

    // ----------------------------------------------------------- actions

    void checkRoot() { checkRoot(true); }

    void checkRoot(final boolean thenRefresh) {
        if (rootChecking) return;
        rootChecking = true;
        setRoot(false, true);
        new Thread(new Runnable() { public void run() {
            final boolean granted = MainActivity.this.run("id").contains("uid=0");
            rootChecking = false;
            lastRoot = granted;
            ui.post(new Runnable() { public void run() { setRoot(granted, false); } });
            log(granted ? "root granted" : "root not granted", granted ? C_OK : C_BAD);
            if (granted && thenRefresh) refresh();
        } }).start();
    }

    void refresh() {
        new Thread(new Runnable() { public void run() {
            String cmd = "echo RT=$(cat /sys/class/net/" + IFACE + "/address 2>/dev/null); "
                       + "echo WF=$(cmd wifi status 2>/dev/null | sed -n '1,3p' | tr '\\n' '|'); "
                       + "echo __CFG__; "
                       + "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null "
                       + "|| cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null; "
                       + "echo __END__";
            String out = MainActivity.this.run(cmd);
            String rt = "", wifi = "", cfg = "";
            int c0 = out.indexOf("__CFG__"), c1 = out.indexOf("__END__");
            if (c0 >= 0 && c1 > c0) cfg = out.substring(c0 + 7, c1).trim();
            for (String line : out.split("\n")) {
                if (line.startsWith("RT=")) rt = line.substring(3).trim();
                else if (line.startsWith("WF=")) wifi = line.substring(3).trim();
            }
            final String frt = rt;
            final String fwifi = wifi;
            final String fcfg = cfg;
            final String fac = getPref("factory");
            final String soc = socName();

            ui.post(new Runnable() { public void run() {
                vRuntime.setText(frt.isEmpty() ? "\u2014" : frt);
                if (fac == null) vRuntime.setTextColor(C_TEXT);
                else vRuntime.setTextColor(frt.equalsIgnoreCase(fac) ? C_OK : C_WARN);
                vFactory.setText(fac == null ? "(not saved)" : fac);
                vModel.setText(Build.MANUFACTURER + " " + Build.MODEL);
                vSoc.setText(soc);
                vSoc.setTextColor(socColor(soc));
                vAndroid.setText(Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
                String ssid = "\u2014";
                int a = fwifi.indexOf('"'), b = fwifi.lastIndexOf('"');
                if (fwifi.contains("connected to")) ssid = (a >= 0 && b > a)
                        ? fwifi.substring(a + 1, b) : "connected";
                String state = fwifi.contains("is enabled") ? "on" : "off";
                vNetwork.setText(ssid + "  \u00B7  " + state);
                vNetwork.setTextColor(fwifi.contains("connected to") ? C_OK : C_DIM);

                String pv = privacyValue(fcfg, ssid);
                if (pv.equals("0")) {
                    vPrivacy.setText("device MAC");
                    vPrivacy.setTextColor(C_OK);
                } else if (pv.equals("1") || pv.equals("2")) {
                    vPrivacy.setText("randomized \u00B7 tap to fix");
                    vPrivacy.setTextColor(C_WARN);
                } else {
                    vPrivacy.setText("unknown");
                    vPrivacy.setTextColor(C_DIM);
                }
            } });
        } }).start();
    }

    void setMac(final String macRaw) {
        if (busy) { toast("busy\u2026"); return; }
        final String mac = macRaw == null ? "" : macRaw.toLowerCase().trim();
        if (!isMac(mac)) { toast("Invalid MAC"); log("invalid MAC: " + macRaw, C_BAD); return; }
        busy = true;
        log("setting " + mac + " \u2026", C_DIM);
        new Thread(new Runnable() { public void run() {
            try {
                String cur = getRuntimeMac();
                if (!isMac(getPref("factory")) && isMac(cur)) setPref("factory", cur);
                byte[] ob = macToBytes(cur);
                byte[] nb = macToBytes(mac);
                if (ob == null || nb == null) { toast("cannot parse MAC"); log("cannot parse MAC", C_BAD); return; }

                boolean any = false;
                int hitsTotal = 0;
                for (String path : existingNvram()) {
                    byte[] data = readRoot(path);
                    if (data == null) continue;
                    byte[] orig = data.clone();       // keep the true original for backup
                    int hits = patch(data, ob, nb);
                    if (hits > 0) {
                        File bk = backupFor(path);
                        if (!bk.exists()) writeFile(bk, orig);
                        any = writeRoot(path, data) || any;
                        hitsTotal += hits;
                    }
                }
                if (any) log("nvram patched (" + hitsTotal + " hit)", C_OK);
                else log("no nvram match \u00B7 runtime only", C_WARN);

                reinitWifi(mac);

                String now = getRuntimeMac();
                if (now.equalsIgnoreCase(mac)) {
                    log("runtime = " + now + "  ok", C_OK);
                    toast("MAC set: " + now);
                } else if (isRandomized()) {
                    log("ignored: this network randomizes the MAC", C_WARN);
                    log("set its Privacy to 'Use device MAC'", C_DIM);
                    toast("network randomizes MAC");
                } else {
                    log("runtime = " + now + " (driver kept it)", C_WARN);
                    toast("driver gave " + now);
                }
            } finally {
                busy = false;
                refresh();
            }
        } }).start();
    }

    void randomMac() {
        byte[] b = new byte[6];
        rnd.nextBytes(b);
        b[0] = (byte) ((b[0] & 0xFC) | 0x02); // locally administered, unicast
        setMac(bytesToMac(b));
    }

    void restore() {
        if (busy) { toast("busy\u2026"); return; }
        final String factory = getPref("factory");
        if (!isMac(factory)) { toast("No factory MAC saved yet"); log("no factory saved", C_BAD); return; }
        busy = true;
        log("restoring " + factory + " \u2026", C_DIM);
        new Thread(new Runnable() { public void run() {
            try {
                int restored = 0;
                String curRt = getRuntimeMac();
                byte[] from = macToBytes(curRt);
                byte[] to = macToBytes(factory);
                // 1) patch the live MAC -> factory directly in the NVRAM
                for (String path : existingNvram()) {
                    byte[] data = readRoot(path);
                    if (data == null) continue;
                    int hits = (from != null && to != null) ? patch(data, from, to) : 0;
                    if (hits > 0 && writeRoot(path, data)) restored++;
                }
                // 2) fall back to the saved original backups
                if (restored == 0) {
                    File[] fs = backupDir().listFiles();
                    if (fs != null) for (File f : fs) {
                        if (f.getName().endsWith(".path")) continue;
                        File pf = new File(backupDir(), f.getName() + ".path");
                        if (!pf.exists()) continue;
                        String path;
                        try { path = new String(readFile(pf), "UTF-8"); }
                        catch (Throwable t) { continue; }
                        byte[] data = readFile(f);
                        if (data == null) continue;
                        if (writeRoot(path, data)) restored++;
                    }
                }
                if (restored > 0) log("nvram restored (" + restored + ")", C_OK);
                else log("no nvram match \u00B7 runtime only", C_WARN);

                reinitWifi(factory);

                String now = getRuntimeMac();
                if (now.equalsIgnoreCase(factory)) {
                    log("runtime = " + now + "  ok", C_OK);
                    toast("Restored: " + now);
                } else if (isRandomized()) {
                    log("ignored: this network randomizes the MAC", C_WARN);
                    log("set its Privacy to 'Use device MAC'", C_DIM);
                    toast("network randomizes MAC");
                } else {
                    log("runtime = " + now + " (unexpected)", C_WARN);
                    toast("driver gave " + now);
                }
            } finally {
                busy = false;
                refresh();
            }
        } }).start();
    }

    void reinitWifi(String targetMac) {
        run("svc wifi disable");
        sleep(2500);
        run("svc wifi enable");
        sleep(7000);
        String now = getRuntimeMac();
        if (targetMac != null && !now.equalsIgnoreCase(targetMac)) {
            run("svc wifi disable");
            sleep(1500);
            run("ip link set " + IFACE + " down; "
                    + "ip link set dev " + IFACE + " address " + targetMac + "; "
                    + "ip link set " + IFACE + " up");
            run("svc wifi enable");
            sleep(5000);
        }
    }
}
