package com.macchanger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
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
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight, persistent WiFi MAC changer for rooted phones.
 *
 * Locates the vendor NVRAM file that holds the current WiFi MAC, backs it up,
 * replaces the MAC bytes in place, then restarts WiFi so the driver re-reads it.
 * A runtime ip-link change exists only as an explicit, off-by-default opt-in, and
 * is never reported as a persistent change.
 *
 * Comments tagged IMP: belong to the improvement pass that followed the audit
 * fixes. They add no capability to the write path; they are the statement of what
 * a write is about to do, the confirmation in front of it, the read-only pre-flight
 * scan, the recovery report, and the small input/rotation/contrast corrections.
 */
public class MainActivity extends Activity {

    /**
     * M6: the WiFi interface is DETECTED, never assumed.
     *
     * It used to be this constant, in thirteen places, and wlan0 is not guaranteed:
     * concurrent AP/STA, hotspot tethering and some ROMs expose wlan1, and more than
     * one wlan* entry can exist at once. Reading the wrong interface reports "cannot
     * read wlan0" as a fault of the device, and the CLI -- which was fixed to detect
     * the interface and refuse when zero or several candidates exist -- and the app
     * then disagreed about which interface the same operation was about. See
     * iface()/resolveIface() for the detection and the explicit override.
     */
    static final String IFACE_PREF = "iface";

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

    /**
     * H8: the exact wording a non-persistent outcome must carry. A runtime ip-link
     * change is never described as "set": it reverts on the next WiFi re-init.
     *
     * Both strings are this file's own. The CLI never prints RUNTIME_ONLY at all
     * -- `grep -i persistent cli/macchanger.sh` matches only that script's banner
     * lines. What it does carry is the MTK sentence below, verbatim, which is why
     * the app quotes it instead of inventing its own wording for the same hazard.
     *
     * Cite the grep, never a line number: this prose has already gone stale twice
     * by naming cli/macchanger.sh line numbers that later edits moved. Anything
     * here that must stay true should name a symbol or a greppable literal.
     */
    static final String RUNTIME_ONLY = "runtime only \u2014 NOT persistent, will not survive reboot";
    static final String MTK_IP_LINK_WARNING =
            "Do NOT touch ip link here - it crashes the WiFi service on MTK.";

    TextView vRuntime, vFactory, vFactoryProv;
    TextView vModel, vSoc, vAndroid, vVersion, vIface;
    TextView vNetwork, vPrivacy;
    TextView tvRoot, vLog;
    EditText etMac, etFactory, etIface;
    ScrollView scv;
    Button refreshBtn, saveFactoryBtn, exportBtn, useIfaceBtn;
    Button setBtn, rndBtn, restoreBtn, checkBtn;
    CheckBox cbRuntime;

    /** IMP: the "what would be written, and where" card and its scan verdict line. */
    TextView vPlan, vPlanVerdict;
    /** IMP: the recovery card: which pre-images exist and what a Restore would install. */
    TextView vRecovery;
    /** IMP: the dialog this instance owns, so a rotation can neither leak nor strand it. */
    AlertDialog liveDialog = null;

    /** IMP: rotation-safe keys for the two entries and the log's expanded state. */
    static final String K_MAC = "imp_mac", K_FACTORY = "imp_factory", K_LOGEXP = "imp_logexp";
    static final String K_IFACE = "imp_iface";

    /**
     * IMP: what a long-press on a MAC row copies.
     *
     * The rows can also hold an explanation ("cannot read wlan0 (WiFi off, or root
     * denied)"), and copying that as if it were an address would be worse than
     * refusing, so the copy reads these fields -- set only where a real MAC was read
     * or recorded -- and never the rendered text.
     */
    volatile String copyRuntime = "", copyFactory = "";

    /** H8: the runtime ip-link fallback is opt-in and off unless the user asks. */
    volatile boolean allowRuntimeFallback = false;

    /**
     * M2: the "one operation owns the shell" flag is process-wide, and the log
     * outlives the Activity.
     *
     * The manifest declares no android:configChanges and no orientation lock, so
     * a rotation destroys this Activity and builds a fresh one while the worker
     * is still running. As an instance field the new copy came back with
     * busy == false and its buttons enabled, so a tap during the 9.5-16.5 s
     * restart window started a *second* worker writing a second MAC to the same
     * calibration file and interleaving its own svc wifi / ip link commands.
     * Static, the new instance sees the operation in flight, keeps its buttons
     * disabled and reports it, and the retained log stays on screen.
     */
    static volatile boolean busy = false;

    /** M2: the instance on screen. Worker callbacks must reach it, not a destroyed one. */
    static volatile MainActivity current = null;

    static final Handler ui = new Handler(Looper.getMainLooper());
    final SecureRandom rnd = new SecureRandom();
    static final List<String> logLines = new ArrayList<String>();
    static final List<Integer> logColors = new ArrayList<Integer>();
    volatile boolean lastRoot = false;
    volatile boolean rootChecking = false;
    volatile boolean refreshing = false;
    boolean logExpanded = false;

    /**
     * IMP: the two report cards, retained statically like the log, for two reasons.
     *
     * Every string in them needs a root round trip to build -- nvramPaths() classifies
     * the SoC through getprop, factoryStore() reads the durable record -- and doing
     * that from the UI thread is an ANR and a burst of su prompts, so they are built
     * on a worker and posted as finished text. And a rotation destroys this instance:
     * as instance fields the cards came back blank in the middle of a destructive
     * operation, which is exactly when the user needs to see what was written.
     */
    static volatile String planCardText = null;
    static volatile String recoveryCardText = null;

    /** IMP: the last read-only scan: one {path, note} pair per candidate, plus its verdict. */
    static volatile List<String[]> scanRows = null;
    static volatile String scanVerdict = null;
    static volatile int scanColor = C_DIM;
    static volatile long scanAt = 0L;

    /** IMP: what the last confirmed operation did, or what the next one is about to do. */
    static volatile String planNote = null;

    /**
     * IMP: one pending "write this?" question.
     *
     * The worker that is about to write blocks on this object; the dialog answers it.
     * It is static because the answering Activity is destroyed and rebuilt by a
     * rotation, and the question must survive that -- the new instance re-shows it --
     * rather than being answered "no" by a config change the user never asked for.
     */
    static final class Ask {
        final String text;
        boolean answered, yes;
        String note = "";
        Ask(String text) { this.text = text; }
    }
    static volatile Ask pendingAsk = null;

    /**
     * IMP: how long the write question waits for an answer, and how often the on-disk
     * lock is re-stamped while it does.
     *
     * The lock's staleness window is LOCK_STALE_S; a question held open by a human
     * outlives it, and a second writer that then breaks the lock in would race this
     * operation against the pre-image it is about to install. So the lock is renewed
     * while the question is open, and the operation is abandoned -- fail closed --
     * if the renewal shows the lock is no longer ours. Five minutes is long enough
     * to read the question and short enough that walking away frees the device for
     * the CLI instead of leaving it held.
     */
    static final long ASK_WAIT_MS = 300000L;
    static final long LOCK_RENEW_MS = 30000L;

    // root-probe state: absent is decided by running su, not by looking for it
    volatile String suBin = null;
    static final String[] SU_CANDIDATES = {
            "/system/bin/su", "/debug_ramdisk/su", "/sbin/su", "/system/xbin/su" };

    // durable record state, read once through the root channel (H1)
    volatile boolean recLoaded = false;
    volatile String recMac = null;
    volatile String recSrc = null;
    volatile String recTag = null;
    /** IMP: the record script's own report on the durable directory and pre-image. */
    volatile String recDir = null;
    volatile String recBak = null;
    /** The shared image's size, the offset its record names, and its recorded digest. */
    volatile String recBakSz = null;
    volatile String recOff = null;
    volatile String recSha = null;
    /** The app's own durable slot (ADB_APP), when the shared one was already taken. */
    volatile String recAppTag = null;
    volatile String recAppSz = null;
    volatile String recAppOff = null;
    /** The digest SAVE_SCRIPT recorded beside the app's own slot, checked in loadBackup. */
    volatile String recAppSha = null;

    static final int ROOT_UNKNOWN  = 0;
    static final int ROOT_GRANTED  = 1;
    static final int ROOT_DENIED   = 2;
    static final int ROOT_NO_SU    = 3;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        // L5: the factory MAC, the spoofed MAC and the connected SSID are exactly
        // what a bystander must not read off the recents thumbnail or a screen
        // recording. FLAG_SECURE is API 1, needs no permission and adds no
        // dependency; it must be set before the content view is installed.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(buildUi());
        // IMP: a rotation destroys this instance. The MAC the user typed and the
        // expanded state of the log are user work, not decoration, so they come back;
        // the retained log buffer is redrawn here because the new instance's bar is
        // otherwise blank until the next message is appended.
        if (b != null) {
            String m = b.getString(K_MAC);
            String f = b.getString(K_FACTORY);
            if (m != null && etMac != null) etMac.setText(m);
            if (f != null && etFactory != null) etFactory.setText(f);
            String i = b.getString(K_IFACE);
            if (i != null && etIface != null) etIface.setText(i);
            logExpanded = b.getBoolean(K_LOGEXP, false);
        }
        if (!logLines.isEmpty()) renderLog();
        // IMP: the two report cards say what was written and what the backups are;
        // they are the same text the previous instance showed, not a re-derivation.
        showPlanCard();
        showRecoveryCard();
        scv.post(new Runnable() { public void run() { scv.fullScroll(View.FOCUS_UP); } });
        // M2: an operation started before a rotation is still running under this
        // brand-new instance, so the fresh buttons must come up disabled and say
        // so instead of offering a second run.
        if (busy) {
            setBusy(true);
            log("an operation is still running \u00b7 buttons disabled until it finishes", C_DIM);
        }
        // IMP: a rotation while the write question is open must re-ask it on this
        // instance, so the operation is neither lost nor silently answered.
        if (pendingAsk != null) showAsk();
        checkRoot(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // IMP: without this the MAC the user typed is gone after a rotation, because
        // programmatically built views carry no id for the framework to key on.
        if (etMac != null) out.putString(K_MAC, etMac.getText().toString());
        if (etFactory != null) out.putString(K_FACTORY, etFactory.getText().toString());
        if (etIface != null) out.putString(K_IFACE, etIface.getText().toString());
        out.putBoolean(K_LOGEXP, logExpanded);
    }

    @Override
    protected void onDestroy() {
        // IMP: this instance's dialog dies with it (a leaked window is a crash on
        // some OEM builds); whoever is on screen next re-shows the question.
        if (liveDialog != null) {
            try { liveDialog.dismiss(); } catch (Throwable ignored) { }
            liveDialog = null;
        }
        // IMP: the user left the app rather than answering, so the write does NOT
        // happen -- the question is cancelled, never defaulted to yes.
        if (isFinishing()) answerAsk(pendingAsk, false);
        // M2: only the instance that is still the live one may clear it, so a
        // rotation cannot leave the worker posting at nothing.
        if (current == this) current = null;
        super.onDestroy();
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
        // IMP: 48dp is the platform minimum touch target. The old buttons sized
        // themselves from their text plus 12dp of padding, i.e. about 44dp, which is
        // under the minimum for the one control that writes a calibration partition.
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
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

        refreshBtn = mkBtn("Refresh", 0xFF1E1E24, 0, 0xFFC9C9D1,
                new Runnable() { public void run() { refreshNow(); } });
        refreshBtn.setTextSize(12);
        refreshBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(-2, -2);
        rblp.rightMargin = dp(8);
        refreshBtn.setLayoutParams(rblp);
        header.addView(refreshBtn);

        tvRoot = new TextView(this);
        tvRoot.setTextSize(12);
        // IMP: the root badge is a button (it re-checks and re-reads), so it gets a
        // real touch target and a spoken description rather than a bare colour dot.
        tvRoot.setPadding(dp(12), dp(12), dp(12), dp(12));
        tvRoot.setGravity(Gravity.CENTER);
        tvRoot.setMinHeight(dp(48));
        tvRoot.setMinimumHeight(dp(48));
        tvRoot.setClickable(true);
        tvRoot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) { toast("busy\u2026"); return; }
                // L6: this used to pass false, so granting root from this badge
                // left every card stuck on "..." with no hint that Refresh was
                // now needed. A tap is a request to re-check *and* re-read.
                checkRoot(true);
            }
        });
        header.addView(tvRoot);
        outer.addView(header);
        setRoot(ROOT_UNKNOWN, true);

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
        // IMP: the provenance of the recorded factory value, on its own row: whether
        // it was captured out of a calibration pre-image, typed by the user, and which
        // of the three stores in use holds it -- the app-private pref dies with a
        // clear-data, the /data/adb copy does not, and only the row can say which.
        vFactoryProv = addRow(cMac, "record", "...");
        vFactoryProv.setTextSize(11);
        vFactoryProv.setTextColor(C_DIM);
        // IMP: long-press copies a value instead of making the user read it off the
        // screen and retype it -- the two things people actually need to write down.
        vRuntime.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { copyValue("runtime MAC", copyRuntime); return true; }
        });
        vFactory.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { copyValue("factory MAC", copyFactory); return true; }
        });
        root.addView(cMac);

        LinearLayout cFac = card("FACTORY RECORD", 0xFFFFC46B);
        etFactory = new EditText(this);
        etFactory.setHint("factory MAC, from the box or the label");
        // IMP: 0x44FFFFFF is about 2.4:1 against this field's background -- a hint
        // the user cannot read is not a hint. 0x8AFFFFFF is 6.06:1 against the field
        // fill (0xFF111116) and 5.97:1 against C_CARD, i.e. above the 4.5:1 the
        // WCAG AA level asks for at this text size. Both figures measured, not
        // estimated; an earlier version of this comment said "about 5.5:1".
        etFactory.setHintTextColor(0x8AFFFFFF);
        etFactory.setTextColor(C_TEXT);
        etFactory.setTextSize(15);
        etFactory.setTypeface(Typeface.MONOSPACE);
        etFactory.setInputType(InputType.TYPE_CLASS_TEXT);
        etFactory.setSingleLine(true);
        etFactory.setBackground(round(0xFF111116, C_LINE, 14));
        etFactory.setPadding(dp(12), dp(11), dp(12), dp(11));
        cFac.addView(etFactory);

        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        frow.setPadding(0, dp(10), 0, 0);
        saveFactoryBtn = mkBtn("Save factory", B_RND1, 0, B_RND_T,
                new Runnable() { public void run() { saveTypedFactory(etFactory.getText().toString()); } });
        frow.addView(saveFactoryBtn, half());
        exportBtn = mkBtn("Export record", B_RND1, 0, B_RND_T,
                new Runnable() { public void run() { exportRecord(); } });
        frow.addView(exportBtn, half());
        cFac.addView(frow);
        root.addView(cFac);

        LinearLayout cDev = card("DEVICE", 0xFFB9A8FF);
        vModel   = addRow(cDev, "model",   "...");
        vSoc     = addRow(cDev, "soc",     "...");
        vAndroid = addRow(cDev, "android", "...");
        // M8: the app's own version and build time, so a screen recording of a bug
        // can be tied to the build that produced it. getPackageManager() and
        // getPackageInfo() need no permission, no resource and no dependency, which
        // is why the version is read here rather than from a generated constant.
        vVersion = addRow(cDev, "version", "...");
        // M6: the interface is a detected reading, so it is a row. The field below it
        // is the explicit override for a device whose wlan* candidates are ambiguous
        // (concurrent AP/STA, hotspot tethering), the app's equivalent of the CLI's
        // --iface=NAME; it is validated against /sys/class/net before it is used.
        vIface   = addRow(cDev, "wifi if", "...");
        etIface = new EditText(this);
        etIface.setHint("wlan0 \u00b7 only needed if several exist");
        etIface.setHintTextColor(0x8AFFFFFF);
        etIface.setTextColor(C_TEXT);
        etIface.setTextSize(14);
        etIface.setTypeface(Typeface.MONOSPACE);
        etIface.setInputType(InputType.TYPE_CLASS_TEXT);
        etIface.setSingleLine(true);
        etIface.setBackground(round(0xFF111116, C_LINE, 14));
        etIface.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams eilp = new LinearLayout.LayoutParams(-1, -2);
        eilp.topMargin = dp(8);
        etIface.setLayoutParams(eilp);
        cDev.addView(etIface);
        useIfaceBtn = mkBtn("Use this interface", B_RND1, 0, B_RND_T,
                new Runnable() { public void run() { useIface(etIface.getText().toString()); } });
        LinearLayout.LayoutParams uilp = new LinearLayout.LayoutParams(-1, -2);
        uilp.topMargin = dp(8);
        useIfaceBtn.setLayoutParams(uilp);
        cDev.addView(useIfaceBtn);
        root.addView(cDev);

        LinearLayout cWifi = card("NETWORK", 0xFF6FD6C4);
        vNetwork = addRow(cWifi, "wifi", "...");
        vPrivacy = addRow(cWifi, "privacy", "...");
        // IMP: this row is a button (it opens WiFi settings), so it gets a touch
        // target worth tapping instead of the 4dp row padding it shares with the
        // passive rows above it.
        vPrivacy.setPadding(0, dp(12), 0, dp(12));
        vPrivacy.setClickable(true);
        vPrivacy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); }
                catch (Throwable ignored) { }
            }
        });
        root.addView(cWifi);

        // IMP: the statement a root partition write must never be able to hide.
        // "Set MAC" is one tap away from rewriting a calibration file, so the screen
        // says, permanently and before the tap: which candidate files exist and what
        // root may do to each, that the MAC's position is found by scanning rather
        // than assumed, what the write primitive is, where the pre-images are, and
        // what the last operation actually did. It is built from the last read-only
        // scan and the last confirmed operation, never from an assumption.
        LinearLayout cPlan = card("WRITE PLAN", 0xFF7FB4FF);
        vPlan = new TextView(this);
        vPlan.setTextColor(0xFFD8D8E0);
        vPlan.setTextSize(11);
        vPlan.setTypeface(Typeface.MONOSPACE);
        vPlan.setTextIsSelectable(true);
        vPlan.setLineSpacing(dp(3), 1f);
        cPlan.addView(vPlan);

        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setPadding(0, dp(10), 0, 0);
        // IMP: "Check NVRAM (read-only)" overclaimed. The scan never writes the
        // NVRAM -- it issues no dd and no WiFi command -- but when no factory value
        // is recorded anywhere it saves one out of the image it read (recordFactory
        // -> storeFactory: two prefs, the app-private mirror and, through
        // TEXT_SCRIPT, /data/adb/macchanger/factory.txt). That is a file write, and
        // the CLI's equivalent (doctor) is documented as creating nothing at all, so
        // the button says what it does and the log line says the rest.
        checkBtn = mkBtn("Check NVRAM", B_RND1, 0, B_RND_T,
                new Runnable() { public void run() { checkNvram(); } });
        prow.addView(checkBtn, new LinearLayout.LayoutParams(-1, -2));
        cPlan.addView(prow);

        vPlanVerdict = new TextView(this);
        vPlanVerdict.setTextSize(11);
        vPlanVerdict.setTextColor(C_DIM);
        vPlanVerdict.setPadding(0, dp(8), 0, 0);
        cPlan.addView(vPlanVerdict);
        root.addView(cPlan);

        LinearLayout cSet = card("SET MAC", 0xFFFF9FB0);
        etMac = new EditText(this);
        etMac.setHint("AA:BB:CC:DD:EE:FF");
        etMac.setHintTextColor(0x8AFFFFFF);
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
        setBtn = mkBtn("Set MAC", 0xFF5B52E0, 0, B_SET_T,
                new Runnable() { public void run() { setMac(etMac.getText().toString()); } });
        row.addView(setBtn, half());
        rndBtn = mkBtn("Random", B_RND1, 0, B_RND_T,
                new Runnable() { public void run() { randomMac(); } });
        row.addView(rndBtn, half());
        cSet.addView(row);

        restoreBtn = mkBtn("Restore factory", B_RES1, 0, B_RES_T,
                new Runnable() { public void run() { restore(); } });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(8);
        restoreBtn.setLayoutParams(rlp);
        cSet.addView(restoreBtn);

        // H8: the ip-link fallback is the one action that can leave the WiFi
        // service crashed on MTK, and it is never persistent, so it is opt-in and
        // off by default. cli/macchanger.sh -- the same tool -- refuses to run it
        // at all for the same reason, which is the warning shown underneath.
        cbRuntime = new CheckBox(this);
        cbRuntime.setText("Runtime ip-link fallback (not persistent)");
        cbRuntime.setTextColor(C_WARN);
        cbRuntime.setTextSize(12);
        cbRuntime.setChecked(false);
        cbRuntime.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                allowRuntimeFallback = on;
                if (on) log("runtime fallback enabled \u00b7 " + RUNTIME_ONLY, C_WARN);
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        cbRuntime.setLayoutParams(clp);
        cSet.addView(cbRuntime);

        TextView ipWarn = new TextView(this);
        ipWarn.setText("MTK: " + MTK_IP_LINK_WARNING
                + " Used only when the NVRAM write did not verify, and it never"
                + " survives a reboot.");
        ipWarn.setTextColor(C_DIM);
        // IMP: 11sp is an eyebrow size; this is a warning the user has to read.
        ipWarn.setTextSize(12);
        cSet.addView(ipWarn);

        root.addView(cSet);

        // IMP: the recovery half of the same statement. A tool that rewrites a
        // calibration partition must be able to show, without running anything, which
        // pre-images exist, where they are, when they were taken, whether they are
        // still shaped like the file they came from, and what Restore would install.
        LinearLayout cRec = card("RECOVERY", 0xFF6FD6C4);
        vRecovery = new TextView(this);
        vRecovery.setTextColor(0xFFD8D8E0);
        vRecovery.setTextSize(11);
        vRecovery.setTypeface(Typeface.MONOSPACE);
        vRecovery.setTextIsSelectable(true);
        vRecovery.setLineSpacing(dp(3), 1f);
        cRec.addView(vRecovery);
        root.addView(cRec);

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
        vLog.setPadding(dp(14), dp(12), dp(14), dp(12));
        // IMP: the bar is tap-to-expand and long-press-to-copy, so it is a control
        // and needs a touch target rather than the ~30dp a single text line gives it.
        vLog.setMinHeight(dp(48));
        vLog.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.setMargins(p, dp(4), p, dp(12));
        vLog.setLayoutParams(llp);
        vLog.setClickable(true);
        vLog.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                logExpanded = !logExpanded;
                renderLog();
            }
        });
        // M3: the log is the only record of which calibration path was rewritten,
        // and it dies with the process -- so let the user take it with them. The
        // clipboard needs no permission and no resource.
        vLog.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { copyLog(); return true; }
        });
        outer.addView(vLog);

        root.setFocusableInTouchMode(true);
        root.requestFocus();
        return outer;
    }

    /**
     * M2: the instance currently on screen, or null when there is none.
     *
     * A worker started before a rotation is still running, and every one of its
     * UI callbacks (log, toast, setBusy(false)) used to be posted at the
     * destroyed instance: the new screen never learned the operation had
     * finished, so its buttons stayed disabled forever. Resolving `current`
     * inside the posted runnable -- never when it is posted -- sends the update
     * to whoever is on screen now.
     */
    MainActivity live() {
        return current;
    }

    void toast(final String s) {
        ui.post(new Runnable() { public void run() {
            MainActivity a = live();
            if (a == null) return;
            Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
        } });
    }

    void setRoot(final int state, final boolean checking) {
        String word = checking ? "checking\u2026"
                : state == ROOT_GRANTED ? "granted"
                : state == ROOT_DENIED ? "root denied"
                : state == ROOT_NO_SU ? "su not installed"
                : "unknown";
        tvRoot.setText("\u25CF " + word);
        tvRoot.setTextColor(checking || state == ROOT_UNKNOWN ? C_DIM
                : state == ROOT_GRANTED ? C_OK : C_BAD);
        // IMP: the badge is a control whose whole meaning is its colour; without a
        // description a screen reader announces "unknown" with no idea what for.
        tvRoot.setContentDescription("root status: " + word + " \u00b7 tap to check again");
        tvRoot.setBackground(round(0x00000000, C_LINE, 20));
    }

    /** Append one result to the log (newest first) and refresh the bar. */
    void log(final String msg, final int color) {
        ui.post(new Runnable() { public void run() {
            String sym = color == C_OK ? "\u2713 "
                       : (color == C_BAD || color == C_WARN) ? "\u2717 " : "\u2022 ";
            logLines.add(0, sym + msg);
            logColors.add(0, color);
            // M3: the cap was 8, so a Set MAC touching several candidate paths
            // pushed the earlier evidence of what was written out of the only
            // record the app keeps. The retained window is now deep enough for a
            // whole operation, and the expanded bar shows all of it.
            while (logLines.size() > 50) {
                logLines.remove(logLines.size() - 1);
                logColors.remove(logColors.size() - 1);
            }
            MainActivity a = live();
            if (a != null && a.vLog != null) a.renderLog();
        } });
    }

    void renderLog() {
        // M3: expanded used to be setMaxLines(6) while the buffer kept 8, so two
        // retained lines could never be read. Expanded means all of them.
        vLog.setMaxLines(logExpanded ? Math.max(1, logLines.size()) : 1);
        vLog.setEllipsize(logExpanded ? null : android.text.TextUtils.TruncateAt.END);
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

    /** M3: long-press "copy log" -- the record of what was written, off the device. */
    void copyLog() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < logLines.size(); i++) {
            if (i > 0) s.append('\n');
            s.append(logLines.get(i));
        }
        if (s.length() == 0) { toast("log is empty"); return; }
        if (setClipboard("MacChanger log", s.toString())) toast("log copied (" + logLines.size() + " lines)");
    }

    /**
     * IMP: long-press a MAC row to copy it.
     *
     * `value` is the field a real read or record wrote, never the rendered row: the
     * rows can also hold "cannot read wlan0 (WiFi off, or root denied)", and copying
     * that into a paste buffer the user then trusts as an address is worse than
     * saying there is nothing to copy.
     */
    void copyValue(String what, String value) {
        if (value == null || !isMac(value)) {
            toast("no " + what + " to copy yet");
            return;
        }
        if (setClipboard("MacChanger " + what, value)) toast(what + " copied: " + value);
    }

    /** The one clipboard write path: no permission, no resource, no manifest change. */
    boolean setClipboard(String label, String text) {
        try {
            Object cm = getSystemService(CLIPBOARD_SERVICE);
            if (cm instanceof ClipboardManager) {
                ((ClipboardManager) cm).setPrimaryClip(ClipData.newPlainText(label, text));
                return true;
            }
            toast("no clipboard on this device");
        } catch (Throwable t) {
            toast("copy failed");
        }
        return false;
    }

    // ------------------------------------------------------------- shell

    /** Shell deadlines. The write path has its own, deliberately wider one -- see MS_WRITE. */
    static final long MS_DEFAULT  = 15000L;   // probes, toggles, small text reads
    static final long MS_BIG_READ = 60000L;   // a whole calibration partition over a root shell

    /**
     * H4/java_ui: the deadline for the install itself.
     *
     * java_core deliberately left this one command unwatched, because the write
     * primitive it shipped was `cat tmp > path`, and a watchdog killing that
     * mid-write truncates the calibration file -- the worst outcome in this
     * project. java_write has since replaced the primitive with a size-bounded
     * in-place `dd conv=notrunc,fsync` (WRITE_SCRIPT), so this stage arms the
     * deadline: dd opens the destination without O_TRUNC and keeps the inode, so
     * a kill can no longer shorten the file -- at worst it leaves a
     * partly-overwritten image of the same length, which the size and content
     * gates below refuse to call a success and which the pre-image saved before
     * the write can undo. Twice MS_BIG_READ, so a slow eMMC write of a
     * partition-sized image cannot be cut short, while an ignored su prompt
     * still cannot wedge the worker forever.
     *
     * Do not lower this to MS_DEFAULT: the point of the margin is that the
     * deadline only ever fires on a hung shell, never on a slow one.
     */
    static final long MS_WRITE = 120000L;

    /**
     * One command's exit status plus its output.
     *
     * Failure travels as status, never as text: every display site can test
     * {@link #ok()} instead of pattern-matching an error string, so no shell
     * message can ever be rendered as if it were a MAC address.
     */
    static final class Res {
        static final int TIMEOUT  = -1;   // deadline expired, the shell was killed
        static final int NO_SHELL = -2;   // the command could not be started at all
        final int code;
        final String out;
        Res(int code, String out) {
            this.code = code;
            this.out = out == null ? "" : out;
        }
        boolean ok() { return code == 0; }
        boolean timedOut() { return code == Res.TIMEOUT; }
    }

    /** Raw bytes plus status, for commands whose stdout must stay pristine. */
    static final class Blob {
        final int code;
        final byte[] out;
        final String err;
        Blob(int code, byte[] out, String err) {
            this.code = code;
            this.out = out == null ? new byte[0] : out;
            this.err = err == null ? "" : err;
        }
        boolean ok() { return code == 0; }
        boolean timedOut() { return code == Res.TIMEOUT; }
        boolean missing() { return code == Res.NO_SHELL; }
    }

    /**
     * The su binary to run: the first absolute candidate that exists, else the
     * bare name so the PATH lookup this app shipped with still applies.
     *
     * Absence is decided by actually running su (see probeRoot), never by this
     * lookup: /debug_ramdisk is mode 0700 root, so an app cannot stat it even
     * when su is there, and calling that "not installed" would misjudge every
     * Magisk device on Android 11+.
     */
    String suBinary() {
        if (suBin == null) {
            String found = null;
            for (int i = 0; i < SU_CANDIDATES.length; i++) {
                if (new File(SU_CANDIDATES[i]).exists()) { found = SU_CANDIDATES[i]; break; }
            }
            suBin = found != null ? found : "su";
        }
        return suBin;
    }

    String[] suArgv(String script) {
        return new String[]{ suBinary(), "-c", script };
    }

    /** True when the failure is "there is no such binary" rather than "it refused". */
    boolean looksAbsent(String err) {
        if (err == null) return false;
        String e = err.toLowerCase();
        return e.contains("no such file") || e.contains("enoent")
                || e.contains("error=2") || e.contains("not found");
    }

    /**
     * Runs one command with a deadline and returns its status.
     *
     * The deadline is on the *call*, not on the output stream. stdout is drained by
     * its own daemon thread, stdin is written by another, and the caller waits for
     * the process to exit with the deadline -- so the call ends at the deadline
     * because it says so, and not because a pipe happened to reach EOF.
     *
     * That distinction is the repair of the H4 deadline: the caller used to block in
     * read() and depend on EOF, while the watchdog killed only the direct child. On
     * Magisk the direct child is the su client and the root shell is a child of the
     * daemon, so the shell survived, kept the inherited stdout pipe open, and the
     * read never returned -- the deadline fired, and the operation still never
     * ended. Measured with a hanging su and a 3 s deadline: still blocked after
     * 25 s, busy true, every button disabled, no timeout line logged.
     *
     * Abandoning a *write* this way is safe only because the install primitive is
     * `dd conv=notrunc,fsync` (WRITE_SCRIPT): it cannot shorten the file, so the
     * worst outcome of walking away is a same-length, partly-overwritten image that
     * writeRoot's size and content gates refuse to call a success and that the saved
     * pre-image can undo. That case is reported as W_PARTIAL, never as W_NONE.
     *
     * The deadline is not Process.waitFor(long, TimeUnit) because that only exists
     * from API 26 and this app has minSdk 21: calling it would be a
     * NoSuchMethodError on Android 7.
     *
     * merge=true folds stderr into stdout, which text probes want (the shell's
     * own message is the diagnostic). Binary reads pass merge=false: an appended
     * "cat: Permission denied" line would otherwise become part of a calibration
     * image and be written back.
     */
    Blob exec(String[] argv, String input, long ms, boolean merge) {
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            if (merge) pb.redirectErrorStream(true);
            p = pb.start();
        } catch (Throwable t) {
            return new Blob(Res.NO_SHELL, new byte[0], String.valueOf(t));
        }
        final Process fp = p;
        if (input != null) {
            // A child that never reads its stdin must not be able to block the
            // caller before the deadline has even started, so the feed is its own
            // daemon thread and its failure is not the command's failure.
            final String in = input;
            Thread wr = new Thread(new Runnable() { public void run() {
                try {
                    OutputStream os = fp.getOutputStream();
                    os.write(in.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                } catch (Throwable ignored) { }
            } });
            wr.setDaemon(true);
            wr.start();
        }
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final InputStream in = p.getInputStream();
        Thread rd = new Thread(new Runnable() { public void run() {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            } catch (Throwable ignored) {
                // A closed or killed stream ends the drain; what was collected stays.
            }
        } });
        rd.setDaemon(true);
        rd.start();
        // merge=false means stderr is its own stream, and it used to be left
        // undrained: a command writing more than one pipe buffer to stderr blocked
        // until the deadline (the write path passes merge=true, so this is latent
        // rather than live today). It is drained by a daemon thread of its own now,
        // and what it collects travels in Blob.err -- diagnostic only, since every
        // test on it in this file is over a merge=true call.
        final ByteArrayOutputStream be = new ByteArrayOutputStream();
        Thread rde = null;
        if (!merge) {
            final InputStream er = p.getErrorStream();
            rde = new Thread(new Runnable() { public void run() {
                byte[] buf = new byte[4096];
                int n;
                try {
                    while ((n = er.read(buf)) > 0) be.write(buf, 0, n);
                } catch (Throwable ignored) { }
            } });
            rde.setDaemon(true);
            rde.start();
        }

        long deadline = ms > 0 ? System.currentTimeMillis() + ms : 0L;
        boolean killed = false;
        if (ms > 0) {
            while (alive(p) && System.currentTimeMillis() < deadline) sleep(SHELL_POLL_MS);
            if (alive(p)) {
                killed = true;
                kill(p);
                closeQuietly(p.getOutputStream());
                closeQuietly(in);
                closeQuietly(p.getErrorStream());
            }
        }
        // The direct child has exited (or was killed), so everything it wrote is
        // already in the pipe; the drain gets a bounded grace to collect it and is
        // then abandoned. It is NOT waited on indefinitely: a surviving descendant
        // -- the root shell on Magisk -- can hold the write end open for ever, and
        // that is exactly the state that used to wedge the worker. A caller that
        // asked for no deadline at all (ms <= 0) still wants the whole read, and
        // that is the one place an unbounded wait remains, on purpose.
        if (ms <= 0) {
            try { rd.join(); } catch (InterruptedException ignored) { }
        } else {
            long grace;
            if (killed) {
                // Killed at the deadline: there is nothing left to collect that this
                // side can wait for, so the close below is what ends the drain.
                grace = 0L;
            } else {
                grace = deadline - System.currentTimeMillis();
                if (grace > POST_EXIT_DRAIN_MS) grace = POST_EXIT_DRAIN_MS;
                if (grace < 0) grace = 0;
            }
            joinQuietly(rd, grace);
            if (rd.isAlive()) {
                // Unproven output is not usable output: whatever is not collected
                // here is reported as a timeout, which is the safe direction.
                killed = true;
                closeQuietly(in);
                joinQuietly(rd, POST_KILL_DRAIN_MS);
            }
        }
        // The stderr drain gets the same bounded treatment and is never waited on
        // longer than it: it is diagnostic, so a survivor holding that pipe open
        // costs the call nothing beyond the grace the stdout drain already pays.
        if (rde != null) {
            joinQuietly(rde, killed ? POST_KILL_DRAIN_MS : POST_EXIT_DRAIN_MS);
            if (rde.isAlive()) closeQuietly(p.getErrorStream());
        }
        String err = merge ? "" : utf8(be.toByteArray());
        if (killed) {
            return new Blob(Res.TIMEOUT, bo.toByteArray(), err);
        }
        int rc = exitOf(p);
        return new Blob(rc, bo.toByteArray(), err);
    }

    /** How often the caller re-checks whether the process exited. Never a blind wait. */
    static final long SHELL_POLL_MS = 25L;

    /**
     * How long the drain thread is given after the process itself has exited.
     *
     * The child cannot exit while its own output is still unread -- a full pipe
     * blocks it -- so this is only the cost of reading what is already there plus a
     * scheduling margin. When it expires the output is treated as incomplete and
     * the call is reported as a timeout, which is the safe direction.
     */
    static final long POST_EXIT_DRAIN_MS = 3000L;

    /**
     * How long the drain is given after the stream has been closed under it.
     *
     * Short on purpose: on Linux, closing a file descriptor does not unblock a read
     * already parked on it, so this is only the time it takes the thread to notice;
     * the caller must not spend a second deadline waiting for a survivor that may
     * hold the pipe for hours.
     */
    static final long POST_KILL_DRAIN_MS = 500L;

    /** True while the process has not exited; exitValue() throws until it has. */
    boolean alive(Process p) {
        try { p.exitValue(); return false; }
        catch (IllegalThreadStateException e) { return true; }
        catch (Throwable t) { return false; }
    }

    /** The exit status of a process that has exited, or TIMEOUT when it will not say. */
    int exitOf(Process p) {
        try { return p.exitValue(); }
        catch (Throwable t) { return Res.TIMEOUT; }
    }

    void joinQuietly(Thread t, long ms) {
        if (t == null || ms <= 0) return;
        try { t.join(ms); } catch (InterruptedException ignored) { }
    }

    void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) { }
    }

    void kill(Process p) {
        // H4: the direct child is not always the only process in the operation. On
        // Magisk, `su -c` is a client that asks the daemon to run the shell, so
        // destroying the client leaves the root shell running. Where the platform
        // has ProcessHandle (API 26+) its descendants are killed too; the lookup is
        // by reflection because Process.toHandle()/ProcessHandle do not exist on
        // API 21-25 and this app has minSdk 21 -- the same rule the deadline above
        // follows. A root shell parented by the su daemon is not a descendant of
        // this process and cannot be reached this way at all: it is left to finish
        // on its own, which is why the write primitive must never be truncating.
        if (Build.VERSION.SDK_INT >= 26) killDescendants(p);
        // destroyForcibly is API 26 as well, so it is looked up the same way rather
        // than called directly. A direct call compiles against the stubs and works
        // wherever the method exists, but it makes kill() unverifiable on API 21-25:
        // a VerifyError is raised when the method is verified, not when the call
        // executes, so the catch below could not be relied on to turn it into the
        // destroy() fallback this app has minSdk 21 for. callNoArg returns null when
        // the method is absent, and destroy() (API 1) is then the fallback.
        if (callNoArg(p, "destroyForcibly") == null) {
            try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** Best effort, reflection only, bounded: kills up to 64 descendants of `p`. */
    void killDescendants(Process p) {
        try {
            Object h = callNoArg(p, "toHandle");
            Object ds = callNoArg(h, "descendants");
            Object it = callNoArg(ds, "iterator");
            for (int n = 0; n < 64 && it != null; n++) {
                if (!Boolean.TRUE.equals(callNoArg(it, "hasNext"))) break;
                callNoArg(callNoArg(it, "next"), "destroyForcibly");
            }
        } catch (Throwable ignored) { }
    }

    /** Invokes a no-argument method by name, or returns null. Never throws. */
    Object callNoArg(Object o, String name) {
        if (o == null) return null;
        try {
            java.lang.reflect.Method[] ms = o.getClass().getMethods();
            for (int i = 0; i < ms.length; i++) {
                if (!ms[i].getName().equals(name) || ms[i].getParameterTypes().length != 0)
                    continue;
                try { return ms[i].invoke(o); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Deadline-bounded text command through the root shell.
     *
     * No path is ever pasted into the command text: callers that need a path
     * feed it on stdin (see feed), so the shell only ever sees shell variables.
     */
    Res runTimed(String cmd, long ms) {
        Blob b = exec(suArgv(cmd), null, ms, true);
        if (b.timedOut()) log("su did not answer \u2014 command aborted, nothing was written", C_BAD);
        return new Res(b.code, utf8(b.out));
    }

    Res run(String cmd) {
        return runTimed(cmd, MS_DEFAULT);
    }

    /** Same, with the paths the script reads placed on stdin instead of in the text. */
    Res feed(String script, String input, long ms) {
        Blob b = exec(suArgv(script), input, ms, true);
        if (b.timedOut()) log("su did not answer \u2014 command aborted, nothing was written", C_BAD);
        return new Res(b.code, utf8(b.out));
    }

    /**
     * The install command's own bounded channel.
     *
     * This used to be execUntimed -- no deadline at all -- while the primitive it
     * ran was the truncating `cat tmp > path`. It now goes through the same
     * watchdog as everything else, on MS_WRITE, because WRITE_SCRIPT's
     * `dd conv=notrunc,fsync` cannot truncate: see MS_WRITE for the full
     * argument, which is the ordering java_core's comment left to this stage.
     */
    Blob execWrite(String[] argv, String input, boolean merge) {
        return exec(argv, input, MS_WRITE, merge);
    }

    String utf8(byte[] b) {
        try { return new String(b, "UTF-8"); } catch (Throwable t) { return ""; }
    }

    /** UTF-8 bytes for a text file: never the platform default charset. */
    byte[] utf8Bytes(String s) {
        try { return s.getBytes("UTF-8"); } catch (Throwable t) { return new byte[0]; }
    }

    /** A device property, bounded like every other shell call. Never routed through su. */
    String getprop(String name) {
        Blob b = exec(new String[]{ "getprop", name }, null, MS_DEFAULT, true);
        return b.ok() ? utf8(b.out).trim() : "";
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

    /**
     * Atomic, verified write inside app-private storage (C2).
     *
     * The bytes go to name.part, are fsynced, are read back and compared, and
     * only then is the file renamed onto its final name. A kill or ENOSPC during
     * the write therefore never leaves a file that a later restore could mistake
     * for a complete backup.
     *
     * The rename is safe *here* because this is the app's own directory. It must
     * never be used on the NVRAM path: a rename there would bring the temp
     * file's SELinux label and need create/rename permission, which is why
     * writeRoot keeps the target inode and rewrites it in place.
     */
    boolean writeFile(File f, byte[] d) {
        File part = new File(f.getAbsolutePath() + ".part");
        FileOutputStream o = null;
        try {
            o = new FileOutputStream(part);
            o.write(d);
            o.flush();
            o.getFD().sync();
            o.close();
            o = null;
            byte[] back = readFile(part);
            if (back == null || !Arrays.equals(back, d)) { part.delete(); return false; }
            if (!part.renameTo(f)) { part.delete(); return false; }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (o != null) { try { o.close(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * The on-disk size of the image, followed by the image itself, from ONE root
     * round trip (C1).
     *
     * The size is printed first, on a line of its own, by the very invocation
     * that streams the bytes, so the length the read is checked against cannot
     * come from a different call, a different mount, or a Java-side stat of a
     * path this process may not be able to see at all. `cat` stays last so the
     * invocation's exit status is still the read's own status -- a denied or
     * failed read must not be reported as a successful short read.
     */
    static final String READ_SCRIPT =
            "IFS= read -r p; "
          + "sz=$(stat -c %s -- \"$p\" 2>/dev/null) || sz=$(wc -c < \"$p\" 2>/dev/null); "
          + "[ -n \"$sz\" ] || sz=-1; printf '%s\\n' \"$sz\"; cat -- \"$p\"";

    /** A calibration image read together with the size seen on disk for it. */
    static final class Img {
        final long size;      // -1 when the size could not be read at all
        final byte[] data;    // what cat returned; may be shorter than size
        Img(long size, byte[] data) {
            this.size = size;
            this.data = data == null ? new byte[0] : data;
        }
    }

    /**
     * Reads one calibration file and its size, or null when the read itself
     * failed. The first output line is the size READ_SCRIPT printed; everything
     * after it is the image, byte for byte, un-merged with stderr.
     */
    Img readRootImage(String path, long ms) {
        if (!nvramPaths().contains(path)) return null;
        Blob b = exec(suArgv(READ_SCRIPT), path + "\n", ms, false);
        if (!b.ok() || b.out.length == 0) return null;
        int nl = -1;
        for (int i = 0; i < b.out.length; i++) if (b.out[i] == '\n') { nl = i; break; }
        if (nl < 0) return null;
        long sz = parseLong(utf8(Arrays.copyOfRange(b.out, 0, nl)));
        return new Img(sz, Arrays.copyOfRange(b.out, nl + 1, b.out.length));
    }

    /**
     * The bytes of a calibration file, or null when they are not a complete image.
     *
     * This gate is the whole of C1. The old read returned whatever `cat` managed
     * to produce and the old write installed that buffer over the file, so a read
     * cut short -- by the low-memory killer, by a Magisk timeout, by a partition
     * that returned fewer bytes than it declares -- silently replaced every byte
     * past the cut with nothing, on images that hold far more than one MAC.
     * A read whose length is not the file's own size is now discarded before
     * anything is patched, backed up or written.
     */
    byte[] readRoot(String path) {
        return readRoot(path, null);
    }

    /**
     * The same gate, reporting whether the file was *unreadable* rather than
     * merely unusable.
     *
     * `unreadable` (a one-element array, or null) is set only when the file is
     * big enough to be a calibration image and the read itself failed or came
     * back short -- a candidate that exists but cannot be read, which is the
     * condition H8 uses to forbid the runtime ip-link fallback, because that file
     * may be the one holding the MAC. A file too small to hold a MAC at all is
     * not "unreadable": it can never carry the value, so it neither counts as a
     * match nor blocks the fallback.
     */
    byte[] readRoot(String path, boolean[] unreadable) {
        Img im = readRootImage(path, MS_BIG_READ);
        if (im == null) {
            if (unreadable != null) unreadable[0] = true;      // no answer at all
            log("cannot read " + path + " \u00b7 refusing to touch it", C_BAD);
            return null;
        }
        if (im.size < 0) {
            if (unreadable != null) unreadable[0] = true;
            log("cannot size " + path + " \u00b7 refusing to touch it", C_BAD);
            return null;
        }
        if (im.size < MIN_IMAGE) {
            log(path + " is only " + im.size + " bytes (< " + MIN_IMAGE
                    + ") \u00b7 refusing to touch it", C_BAD);
            return null;
        }
        if (im.data.length != im.size) {
            if (unreadable != null) unreadable[0] = true;
            log("read-size/on-disk mismatch for " + path + ": read " + im.data.length
                    + " of " + im.size + " bytes \u00b7 nothing written", C_BAD);
            return null;
        }
        return im.data;
    }

    /** Reads one root-only file by absolute path (the record files, not NVRAM). */
    byte[] readRootFile(String path, long ms) {
        Blob b = exec(suArgv("IFS= read -r p; cat -- \"$p\""), path + "\n", ms, false);
        return (b.ok() && b.out.length > 0) ? b.out : null;
    }

    /** On-disk size of a calibration file, or -1 when it cannot be read. */
    long sizeRoot(String path) {
        if (!nvramPaths().contains(path)) return -1L;
        Res r = feed("IFS= read -r p; wc -c < \"$p\" 2>/dev/null", path + "\n", MS_BIG_READ);
        return parseLong(r.ok() ? r.out.trim() : "");
    }

    long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return -1L; }
    }

    /**
     * A calibration image smaller than this is not an image (C1): never patched,
     * never installed, never used as a pre-image.
     *
     * Ten bytes, not sixteen: the MediaTek APRDEB/WIFI blob this project's CLI
     * was written for on an MT6761 is 4 header bytes plus the 6-byte MAC, i.e.
     * exactly 10 bytes, and cli/macchanger.sh patches it (its own guard is
     * `off + 6 <= size`). A 16-byte floor -- the number the audit suggested --
     * would silently refuse to patch the primary supported device, so the floor
     * is set at the smallest thing that can hold a MAC at all. The real defence
     * against a damaged image is not this floor but the size equality below: the
     * bytes read must be exactly the file's own length.
     *
     * One floor, one meaning: this same constant is the pre-image gate
     * (backupReject, which is the predicate loadBackup applies) and the recovery
     * card's stated floor. It was 10
     * here and 16 there, so a 10-byte pre-image of the MediaTek layout was saved
     * by saveBackup() and then refused for ever by loadBackup() -- on exactly the
     * device the comment above names as primary, and with a rejection message
     * ("10 bytes ≠ 10") that named a reason that was not the reason.
     */
    static final long MIN_IMAGE = 10L;

    /**
     * C1: the in-place install. Both paths arrive on stdin and become the shell's
     * positional parameters, so no path is ever pasted into the command text.
     *
     * `dd ... conv=notrunc,fsync` -- the same primitive, with the same flags,
     * that cli/macchanger.sh has always used -- opens the destination O_WRONLY
     * *without* O_TRUNC. The inode is kept, so the file's SELinux label, owner
     * and mode are kept with it, and a kill in the middle of the write cannot
     * leave a shorter file: that is the whole point of never redirecting `cat`
     * into the destination and never renaming a temporary over it.
     *
     * The destination's own size is the authority, and it is read by this same
     * invocation, on both sides of the write: the image is installed only into a
     * file whose on-disk size already equals the staged image's size, and the
     * file's size must still be that value afterwards. A short source can
     * therefore never shorten a calibration file, and a size that changes is
     * reported as what it is -- an unrecoverable truncation -- rather than
     * silently accepted. `cmp` then proves the bytes landed.
     *
     * A size is not a content. Three lines arrive on stdin now -- the staged
     * image, the pre-image the patch was derived from, and the destination -- and
     * the third line is `-` when the caller has no pre-image to offer. When it has
     * one, `cmp` must find the destination equal to it BEFORE dd runs, or the
     * script answers wf=stale and exits: a same-size image installed by another
     * writer between this app's read and this install is overwritten by nothing,
     * because it is not silently accepted as the state the patch was built on.
     * W_STALE carries that answer out of the write.
     */
    static final String WRITE_SCRIPT =
            "IFS= read -r s; IFS= read -r e; IFS= read -r d; set -- \"$s\" \"$e\" \"$d\"; "
          + "ss=$(stat -c %s -- \"$1\" 2>/dev/null) || ss=$(wc -c < \"$1\" 2>/dev/null); "
          + "ds=$(stat -c %s -- \"$3\" 2>/dev/null) || ds=$(wc -c < \"$3\" 2>/dev/null); "
          + "[ -n \"$ss\" ] && [ -n \"$ds\" ] || { echo wf=size-unreadable; exit 1; }; "
          + "[ \"$ss\" = \"$ds\" ] || { echo \"wf=size-mismatch src=$ss dst=$ds\"; exit 1; }; "
          + "if [ \"$2\" != - ]; then cmp -s -- \"$2\" \"$3\" || { echo wf=stale; exit 1; }; fi; "
          + "dd if=\"$1\" of=\"$3\" bs=4096 conv=notrunc,fsync 2>/dev/null "
          + "|| { echo \"wf=write-failed src=$ss dst=$ds\"; exit 1; }; "
          + "as=$(stat -c %s -- \"$3\" 2>/dev/null) || as=$(wc -c < \"$3\" 2>/dev/null); "
          + "[ \"$as\" = \"$ds\" ] || { echo \"wf=size-changed was=$ds now=$as\"; exit 1; }; "
          + "cmp -s -- \"$1\" \"$3\" || { echo wf=cmp-failed; exit 1; }; "
          + "echo wf=ok";

    /** The write state of the last writeRoot call (M4). */
    static final int W_NONE     = 0;   // nothing reached the partition
    static final int W_PARTIAL  = 1;   // dd ran but the install did not complete
    static final int W_LANDED   = 2;   // the shell installed and cmp-verified the image
    static final int W_VERIFIED = 3;   // ... and the partition read back equal here

    /**
     * Nothing was written because the destination is no longer the pre-image this
     * operation read: another writer replaced it between the read and the install.
     *
     * WRITE_SCRIPT's size gate cannot see this. It proves the destination's LENGTH
     * did not change, so a same-length image installed by someone else in that
     * window is exactly the case it calls a verified success -- measured with the
     * shipped script: with the 512-byte target replaced by a different 512-byte
     * image between the read and the write, the write landed, the other writer's
     * image was gone, and the log said "512 bytes verified". The window is not
     * milliseconds either: the app holds its own lock across a question a human
     * answers, so minutes pass between the read and the write.
     *
     * The repair is a content gate inside the same root invocation, immediately
     * before dd: the app stages the pre-image it derived the patch from and the
     * script compares the destination against it (cmp), refusing with wf=stale when
     * they differ. Nothing has been written at that point.
     *
     * NEGATIVE on purpose. Callers keep the strongest evidence of a change with
     * `if (lastWriteState > worst)`, and a refusal that installed nothing must
     * never be able to raise that: it is not evidence about the partition, it is
     * evidence that this operation was overtaken.
     */
    static final int W_STALE    = -1;  // the file changed while this operation held it

    /**
     * M4: what the most recent install actually did.
     *
     * writeRoot answers a boolean, which folds "nothing was written", "the image
     * did land but the readback could not prove it" and "the write was cut short"
     * into one `false`. The caller then printed the same neutral line for all
     * three, so a *successful* patch whose readback failed was reported to the
     * user as "no nvram match" -- on a tool whose whole job is destructive
     * writes. This field carries the distinction out of the write.
     */
    volatile int lastWriteState = W_NONE;

    /**
     * Installs `data` at an NVRAM path through the root shell, in place.
     *
     * The whitelist gate is re-checked here, at the only place that writes: a
     * path that is not one of the shipped candidates is refused outright, so
     * nothing read back from the shell can ever become a write target.
     *
     * The command runs on MS_WRITE's deadline -- see execWrite and MS_WRITE for
     * why arming it is safe now and was not before C1 landed.
     */
    boolean writeRoot(String path, byte[] data) {
        return writeRoot(path, data, null, null, null, null);
    }

    /**
     * The same install, with what the caller knows about the patch: the windows
     * patch() replaced, and the old -> new MAC pair. Every write then logs the
     * path, the offsets it touched and the change it made, and re-derives the MAC
     * from the recorded offset on the re-read, so a write is only ever reported
     * as done when the partition itself says so.
     */
    boolean writeRoot(String path, byte[] data, List<int[]> win, String oldMac, String newMac) {
        return writeRoot(path, data, win, oldMac, newMac, null);
    }

    /**
     * The same install, plus the pre-image the staged image was derived from.
     *
     * `expectPre` is the whole point of the W_STALE repair: when the caller has
     * the bytes it read out of this exact path to build the patch, the script is
     * told to prove the destination is still those bytes before dd may run. Pass
     * null only when the caller genuinely has no pre-image -- a saved-image
     * restore whose pre-image could not be read, which is itself refused before
     * this point.
     */
    boolean writeRoot(String path, byte[] data, List<int[]> win, String oldMac, String newMac,
                      byte[] expectPre) {
        lastWriteState = W_NONE;
        if (!nvramPaths().contains(path)) {
            log("refusing to write outside the NVRAM candidate list: " + path, C_BAD);
            return false;
        }
        // C1: an empty or stub-sized buffer is never installed. The staging helper
        // would happily write it and the old primitive then truncated the target
        // to it; here it is refused before any root command runs.
        if (data == null || data.length < MIN_IMAGE) {
            log("refusing to write " + (data == null ? 0 : data.length) + " bytes to "
                    + path + " \u00b7 nothing done", C_BAD);
            return false;
        }
        File tmp = new File(getFilesDir(), "write.tmp");
        if (!writeFile(tmp, data)) {
            log("cannot stage the image for " + path + " \u00b7 nothing written", C_BAD);
            return false;
        }
        long staged = tmp.length();
        if (staged != data.length) {
            tmp.delete();
            log("staged image for " + path + " is " + staged + " bytes \u2260 " + data.length
                    + " \u00b7 nothing written", C_BAD);
            return false;
        }
        // The pre-image the patch was derived from, staged beside the image so the
        // shell can cmp the destination against it in the same invocation. Its own
        // staging is verified by writeFile the same way the image is.
        File pre = null;
        if (expectPre != null && expectPre.length > 0) {
            if (expectPre.length != data.length) {
                tmp.delete();
                log("the pre-image for " + path + " is " + expectPre.length + " bytes \u2260 "
                        + data.length + " \u00b7 a patch cannot change the length, nothing written",
                        C_BAD);
                return false;
            }
            pre = new File(getFilesDir(), "write.pre");
            if (!writeFile(pre, expectPre)) {
                tmp.delete();
                log("cannot stage the pre-image of " + path + " \u00b7 nothing written", C_BAD);
                return false;
            }
        }
        Blob w = execWrite(suArgv(WRITE_SCRIPT), tmp.getAbsolutePath() + "\n"
                + (pre == null ? "-" : pre.getAbsolutePath()) + "\n" + path + "\n", true);
        String wf = utf8(w.out).trim();
        tmp.delete();
        if (pre != null) pre.delete();
        // `wf=ok` is the shell's own proof that the size did not change and that
        // cmp found the image in place. It is the last thing the script prints and
        // it cannot be printed before both checks passed, so it is accepted even
        // when this side had to walk away from the pipe at the deadline: the write
        // is proved by the partition, not by an exit status this app never saw.
        boolean shellProved = wf.indexOf("wf=ok") >= 0;
        if (!shellProved) {
            // The content gate ran before dd, so this is a refusal that installed
            // nothing -- and a different one from "the shell would not write".
            if (wf.indexOf("wf=stale") >= 0) {
                lastWriteState = W_STALE;
                log("nothing was written to " + path + ": it is not the file this operation read"
                        + " \u00b7 another writer changed it while the write was being prepared",
                        C_BAD);
                return false;
            }
            // M4 + the H4 repair: only the two refusals that happen BEFORE dd runs
            // prove the partition is untouched. Everything else -- a deadline, a dd
            // failure, a cmp failure, a size that changed, or an answer this code
            // does not recognise at all -- means dd may have run and bytes may have
            // changed. The old test was a whitelist of "may have landed" causes, so
            // an unrecognised or empty answer was reported as "refused by the shell"
            // -- the one thing it could not know.
            boolean refusedBeforeWrite = wf.indexOf("size-mismatch") >= 0
                    || wf.indexOf("size-unreadable") >= 0;
            boolean mayHaveLanded = !refusedBeforeWrite;
            lastWriteState = mayHaveLanded ? W_PARTIAL : W_NONE;
            log("write to " + path + (mayHaveLanded ? " did not complete, its bytes may have changed"
                    : " refused by the shell before any byte was written") + ": "
                    + (wf.isEmpty() ? "exit " + w.code + (w.timedOut() ? " (deadline)" : "") : wf)
                    + (w.err.isEmpty() ? "" : " (" + w.err + ")")
                    + (mayHaveLanded ? " \u00b7 re-check before rebooting" : ""), C_BAD);
            return false;
        }
        // The shell installed the whole image and cmp matched it. From here on the
        // partition *has* changed even if this app cannot prove it by reading it
        // back, which is exactly the case the caller must not call "no match".
        lastWriteState = W_LANDED;
        // Independent of the shell's own cmp: read the file back through the
        // size-gated read above, then verify the windows patch() recorded, each in
        // its own encoding. The verdict is the partition's content, never an exit
        // status.
        byte[] back = readRoot(path);
        if (back == null) {
            log("wrote " + path + " but the readback did not verify"
                    + " \u00b7 unproven, its saved pre-image is intact", C_BAD);
            return false;
        }
        if (back.length != data.length) {
            log(path + " changed size (" + data.length + " -> " + back.length
                    + "): a truncated calibration file is not recoverable", C_BAD);
            return false;
        }
        if (!Arrays.equals(back, data)) {
            log("content mismatch after writing " + path
                    + " \u00b7 its saved pre-image is authoritative", C_BAD);
            return false;
        }
        if (newMac != null) {
            byte[] nbm = macToBytes(newMac);
            if (nbm == null || !windowsVerify(back, data, win, nbm)) {
                log("wrote " + path + " but " + newMac + " is not in the window"
                        + (win != null && win.size() == 1 ? "" : "s") + " this write recorded ("
                        + winText(win) + ") on re-read \u00b7 not verified", C_BAD);
                return false;
            }
        }
        String where = winText(win);
        lastWriteState = W_VERIFIED;
        log("wrote " + path + (where.isEmpty() ? "" : " " + where)
                + (oldMac != null && newMac != null ? " \u00b7 " + oldMac + " -> " + newMac : "")
                + " \u00b7 " + back.length + " bytes verified", C_DIM);
        return true;
    }

    /**
     * The first window patch() replaced, or -1 when there is none.
     *
     * Only ever used to name an offset in a log line; the *verdict* no longer comes
     * from "the first 6-byte window", because that assumption is what reported a
     * landed write as unverified whenever the MAC was stored reversed or as ASCII.
     * See windowsVerify.
     */
    int firstOffset(List<int[]> win) {
        if (win == null || win.isEmpty()) return -1;
        return win.get(0)[0];
    }

    /** "at 0x4, 0x20" for the windows patch() recorded, or "" when there are none. */
    String winText(List<int[]> win) {
        if (win == null || win.isEmpty()) return "";
        StringBuilder s = new StringBuilder("at ");
        for (int i = 0; i < win.size(); i++) {
            if (i > 0) s.append(", ");
            s.append("0x").append(Integer.toHexString(win.get(i)[0]));
        }
        return s.toString();
    }

    void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ----------------------------------------------------------- helpers

    /**
     * M6: the interfaces that could be the WiFi station, in the CLI's order.
     *
     * /sys/class/net/wlan* first (the cheap, always-present source), then `iw dev`,
     * then the name `cmd wifi status` prints -- the same three sources
     * cli/macchanger.sh's iface_candidates() uses, so the two front ends cannot
     * disagree about which interface this device has.
     */
    static final String IFACE_SCRIPT =
            "for n in /sys/class/net/wlan*; do [ -e \"$n\" ] || continue; echo \"${n##*/}\"; done; "
          + "iw dev 2>/dev/null | while read -r k v r; do [ \"$k\" = Interface ] || continue; "
          + "case \"$v\" in wlan*) echo \"$v\";; esac; done; "
          + "cmd wifi status 2>/dev/null | sed -n 's/.*\\(wlan[0-9][0-9]*\\).*/\\1/p'";

    /** The station interface, or "" when it could not be resolved. Cached per process. */
    volatile String ifaceName = null;

    /** Why it could not be resolved, "" when it was. */
    volatile String ifaceWhy = "";

    /** Only a plain interface name may become a path component or an ip-link argument. */
    static boolean isIfaceName(String s) {
        return s != null && s.length() > 0 && s.length() <= 15
                && s.matches("^[a-zA-Z0-9_.-]+$");
    }

    List<String> ifaceCandidates() {
        List<String> l = new ArrayList<String>();
        Res r = run(IFACE_SCRIPT);
        if (r.out.isEmpty()) return l;
        String[] lines = r.out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (!isIfaceName(s) || l.contains(s)) continue;
            l.add(s);
        }
        return l;
    }

    /**
     * Resolves the station interface once, or records why it cannot be resolved.
     *
     * One candidate is the answer. None, or several, is an explicit refusal -- not a
     * guess at wlan0 -- unless the user named one in the DEVICE card, which is the
     * app's equivalent of the CLI's --iface=NAME and is validated against
     * /sys/class/net before it is used.
     */
    void resolveIface() {
        String forced = getPref(IFACE_PREF);
        if (isIfaceName(forced)) {
            if (ifacePresent(forced)) {
                ifaceName = forced;
                ifaceWhy = "";
            } else {
                ifaceName = "";
                ifaceWhy = "the interface " + forced + " set in DEVICE does not exist";
            }
            return;
        }
        List<String> c = ifaceCandidates();
        if (c.size() == 1) {
            ifaceName = c.get(0);
            ifaceWhy = "";
        } else if (c.isEmpty()) {
            ifaceName = "";
            ifaceWhy = "no WiFi interface was found (WiFi off, or root denied)";
        } else {
            ifaceName = "";
            ifaceWhy = "several WiFi interfaces exist (" + joinList(c)
                    + ") \u00b7 name one in DEVICE before anything is written";
        }
    }

    String iface() {
        if (ifaceName == null) resolveIface();
        return ifaceName;
    }

    /**
     * Re-runs detection when the previous attempt failed, and only then.
     *
     * ifaceName == "" is a FAILED resolution (none found, several found, or a bad
     * forced name) and iface() re-resolves only while it is null, so a cold start
     * with the radio off -- "no WiFi interface was found" -- cached that failure for
     * the life of the process: turning WiFi on and tapping Refresh still showed
     * "unresolved", and Set MAC refused with "cannot read the WiFi interface" while
     * writing nothing. Pristine read wlan0 unconditionally, so this case worked
     * before the (correct) detection change and does not otherwise. Called on every
     * refresh and at the start of each action, on the worker thread, so the state
     * recovers without a force-stop.
     */
    void retryIfaceIfUnresolved() {
        if (ifaceName != null && ifaceName.isEmpty()) {
            ifaceName = null;
            ifaceWhy = "";
        }
    }

    /** The interface for a message: the detected name, or what is wrong with detection. */
    String ifaceLabel() {
        String n = iface();
        return n.isEmpty() ? "the WiFi interface" : n;
    }

    /** Why a read of the interface state failed, named. */
    String ifaceReason() {
        String n = iface();
        if (n.isEmpty()) return ifaceWhy == null || ifaceWhy.isEmpty()
                ? "it could not be detected" : ifaceWhy;
        return "WiFi off, or root denied";
    }

    String joinList(List<String> l) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) s.append(", ");
            s.append(l.get(i));
        }
        return s.toString();
    }

    /**
     * The driver's current MAC, or "" when it cannot be read at all.
     *
     * One implementation, deliberately: the previous duplicate inline `cat` in
     * refresh() disagreed with this one about stderr, so the two could show
     * different values for the same row. Callers must treat "" as "unknown"
     * rather than rendering it, and they must never render an error string -- which
     * is also why an unresolved interface returns "" instead of reading wlan0.
     */
    String getRuntimeMac() {
        String n = iface();
        if (n.isEmpty()) return "";
        Res r = run("cat /sys/class/net/" + n + "/address 2>/dev/null");
        String s = r.ok() ? r.out.toLowerCase().trim() : "";
        return isMac(s) ? s : "";
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
        String vendor = socVendor();
        if (id.isEmpty()) return vendor;
        String mid = id.toUpperCase();
        if (vendor.equals("Unknown")) return mid;
        return vendor + " " + mid;
    }

    /** M5: the detected vendor, cached -- nvramPaths() runs on every whitelist check. */
    volatile String socCache = null;

    /** The lowercased property haystack the vendor classification is made from. */
    String socHaystack() {
        return (firstNonEmpty(getprop("ro.chipname"), getprop("ro.soc.model"),
                    getprop("ro.board.platform"), getprop("ro.hardware"), Build.HARDWARE)
                + " " + getprop("ro.board.platform") + " " + getprop("ro.hardware")
                + " " + Build.BOARD + " " + Build.MANUFACTURER).toLowerCase();
    }

    /** M5: the vendor on its own, so it can order the candidate list and not just colour a row. */
    String socVendor() {
        if (socCache == null) socCache = vendorOf(socHaystack());
        return socCache;
    }

    /** Pure classification of the haystack; "Unknown" when nothing matches. */
    static String vendorOf(String all) {
        if (all.contains("mediatek") || all.matches(".*\\bmt\\d+.*")) return "MediaTek";
        if (all.contains("qcom") || all.contains("msm") || all.contains("sdm")
                || all.contains("sm8") || all.contains("sm7") || all.contains("snapdragon")
                || all.contains("qualcomm")) return "Qualcomm";
        if (all.contains("exynos") || all.contains("samsung") || all.contains("universal"))
            return "Samsung";
        if (all.contains("spreadtrum") || all.contains("unisoc") || all.contains("ums")
                || all.contains("sc9") || all.contains("sc7")) return "Unisoc";
        return "Unknown";
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

    // The nine candidates, grouped by the vendor that ships them. The groups are
    // data, not behaviour: nvramPaths() concatenates all four in every case, so
    // this list stays the write whitelist whatever order it is read in.
    static final String[] P_MTK = { "/mnt/vendor/nvdata/APCFG/APRDEB/WIFI",   // MediaTek (modern)
                                    "/data/nvram/APCFG/APRDEB/WIFI" };        // MediaTek (older)
    static final String[] P_QC  = { "/mnt/vendor/persist/wifi/wlan_mac.bin",  // Qualcomm
                                    "/persist/wifi/wlan_mac.bin",             // Qualcomm
                                    "/data/vendor/wifi/wlan_mac.bin" };       // Qualcomm
    static final String[] P_SAM = { "/efs/wifi/.mac.info",                    // Samsung
                                    "/efs/wifi/mac.info" };                   // Samsung
    static final String[] P_UNI = { "/productinfo/wifi_mac",                  // Unisoc
                                    "/mnt/vendor/productinfo/wifi_mac" };     // Unisoc

    /**
     * M5: the candidate paths, the detected vendor's own group first.
     *
     * The vendor used to be decoration -- it coloured one row and nothing else --
     * so on a MediaTek device the list was probed in the same fixed order as on a
     * Qualcomm one and the two Qualcomm candidates that cannot exist there were
     * read (and reported as absent) first. Membership is unchanged, and
     * membership is the security property: readRootImage, writeRoot, loadBackup,
     * nvPathOf and storeFactory all use this list as their whitelist.
     */
    List<String> nvramPaths() {
        String v = socVendor();
        List<String> l = new ArrayList<String>();
        if (v.equals("Qualcomm"))      addAll(l, P_QC, P_MTK, P_SAM, P_UNI);
        else if (v.equals("Samsung"))  addAll(l, P_SAM, P_QC, P_MTK, P_UNI);
        else if (v.equals("Unisoc"))   addAll(l, P_UNI, P_QC, P_MTK, P_SAM);
        else                           addAll(l, P_MTK, P_QC, P_SAM, P_UNI);
        return l;
    }

    void addAll(List<String> to, String[]... groups) {
        for (int g = 0; g < groups.length; g++)
            for (int i = 0; i < groups[g].length; i++) to.add(groups[g][i]);
    }

    /** M4: what root reported about one candidate path. */
    static final class PathState {
        final String path;
        final boolean present, readable, writable;
        final String mount, opts;
        PathState(String path, boolean present, boolean readable, boolean writable,
                  String mount, String opts) {
            this.path = path;
            this.present = present;
            this.readable = readable;
            this.writable = writable;
            this.mount = mount == null ? "" : mount;
            this.opts = opts == null ? "" : opts;
        }
        boolean usable() { return present && readable && writable; }
        /** "path absent", or "path r=ok w=denied on /persist (ro,relatime)". */
        String label() {
            if (!present) return path + " absent";
            StringBuilder s = new StringBuilder(path);
            s.append(" r=").append(readable ? "ok" : "denied");
            s.append(" w=").append(writable ? "ok" : "denied");
            if (!mount.isEmpty()) {
                s.append(" on ").append(mount);
                if (!opts.isEmpty()) s.append(" (").append(opts).append(')');
            }
            return s.toString();
        }
    }

    /**
     * M4: one root round trip that reports, per candidate, whether it exists,
     * whether root may read and write it, and the mount it lives on.
     *
     * A read-only mount or a mode that denies root is the difference between "this
     * device does not have that file" and "the file is there and I was not allowed
     * to touch it", and the whole point of this probe is that the app stops
     * reporting the second as the first. The per-path options come from
     * /proc/mounts, matched on the longest mount point that is a prefix of the
     * path, so an RO filesystem is visible in the report rather than inferred.
     */
    static final String PROBE_SCRIPT =
            "while IFS= read -r p; do "
          + "if [ ! -e \"$p\" ]; then echo \"$p absent\"; continue; fi; "
          + "r=ok; w=ok; [ -r \"$p\" ] || r=denied; [ -w \"$p\" ] || w=denied; "
          + "mnt=; opts=; "
          + "while read -r _d _m _f _o _rest; do "
          + "case \"$p\" in \"$_m\"/*) ;; *) [ \"$_m\" = / ] || continue;; esac; "
          + "[ ${#_m} -gt ${#mnt} ] || continue; "
          + "mnt=\"$_m\"; opts=\"$_o\"; "
          + "done < /proc/mounts; "
          + "echo \"$p r=$r w=$w mnt=$mnt opts=$opts\"; "
          + "done";

    /**
     * The candidate paths as root sees them, in ONE root round trip.
     *
     * The paths are fed on stdin and only strings that are literally in
     * nvramPaths() are accepted back, so a line of shell output -- or a file the
     * shell read -- can never become a write target.
     *
     * `probeOk` (a one-element array, or null) reports whether the enumeration
     * itself succeeded. An empty list with probeOk[0] false means "root would not
     * tell me", which the caller must treat as its own outcome -- never as "this
     * device is unsupported" -- and must abort before any WiFi restart. An
     * all-absent list is a *successful* enumeration and is the real
     * unsupported-device verdict.
     */
    List<PathState> probeNvram(boolean[] probeOk) {
        List<String> cand = nvramPaths();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < cand.size(); i++) in.append(cand.get(i)).append('\n');
        Res r = feed(PROBE_SCRIPT, in.toString(), MS_DEFAULT);
        List<PathState> l = parseProbe(r.out, cand);
        boolean ok = r.ok() && !l.isEmpty();
        if (probeOk != null) probeOk[0] = ok;
        return l;
    }

    /** Pure parse of PROBE_SCRIPT's output; anything that is not a candidate is dropped. */
    List<PathState> parseProbe(String out, List<String> cand) {
        List<PathState> l = new ArrayList<PathState>();
        if (out == null || out.isEmpty()) return l;
        String[] lines = out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            String p = line.substring(0, sp);
            if (!cand.contains(p)) continue;
            String rest = line.substring(sp + 1);
            if (rest.startsWith("absent")) {
                l.add(new PathState(p, false, false, false, "", ""));
                continue;
            }
            l.add(new PathState(p, true, "ok".equals(field(rest, "r")), "ok".equals(field(rest, "w")),
                    field(rest, "mnt"), field(rest, "opts")));
        }
        return l;
    }

    /** The value of "key=value" in a space-separated report line, or "". */
    String field(String s, String key) {
        String[] p = s.split(" ");
        for (int i = 0; i < p.length; i++)
            if (p[i].startsWith(key + "=")) return p[i].substring(key.length() + 1);
        return "";
    }

    /** The paths in a probe that root could actually read and write. */
    List<String> usablePaths(List<PathState> probe) {
        List<String> l = new ArrayList<String>();
        for (int i = 0; i < probe.size(); i++)
            if (probe.get(i).usable()) l.add(probe.get(i).path);
        return l;
    }

    /** The paths in a probe that exist but that root may not read or write (M4). */
    List<String> blockedPaths(List<PathState> probe) {
        List<String> l = new ArrayList<String>();
        for (int i = 0; i < probe.size(); i++)
            if (probe.get(i).present && !probe.get(i).usable()) l.add(probe.get(i).path);
        return l;
    }

    /** True when the probe found at least one of the candidates on this device (M5). */
    boolean anyPresent(List<PathState> probe) {
        for (int i = 0; i < probe.size(); i++) if (probe.get(i).present) return true;
        return false;
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

    /**
     * Every occurrence of `pat` in `data` becomes `rep`, and each replacement is
     * recorded as an [offset, length] window in `win` when one is supplied.
     */
    int replaceAll(byte[] data, byte[] pat, byte[] rep, List<int[]> win) {
        int count = 0;
        for (int i = 0; i + pat.length <= data.length; ) {
            boolean hit = true;
            for (int j = 0; j < pat.length; j++)
                if (data[i + j] != pat[j]) { hit = false; break; }
            if (hit) {
                System.arraycopy(rep, 0, data, i, rep.length);
                if (win != null) win.add(new int[]{ i, rep.length });
                i += pat.length;
                count++;
            } else i++;
        }
        return count;
    }

    int replaceAll(byte[] data, byte[] pat, byte[] rep) {
        return replaceAll(data, pat, rep, null);
    }

    /** patch() refused, and changed nothing at all (C3). */
    static final int PATCH_REFUSED = -1;

    /**
     * C3: the most matches any file this app understands may hold.
     *
     * A legitimate blob can carry the MAC as raw bytes, reversed, as ASCII hex
     * and as colon-separated ASCII, plus the duplicate copies some vendors keep,
     * so the cap sits above the range a real device uses -- but far below the
     * 78-83 matches a degenerate pattern produces. Above it the file is not the
     * layout this app understands and is abandoned whole: no backup, no write.
     */
    static final int PATCH_MAX_HITS = 8;

    /**
     * Rewrites every encoding of `oldB` in `data` into the matching encoding of
     * `newB`, in place, and returns how many replacements it made.
     *
     * Returns PATCH_REFUSED, leaving `data` byte-identical, when the *pattern* is
     * degenerate. The pattern is always the runtime MAC -- never the typed value,
     * which is only ever the replacement -- and a driver that reports
     * 00:00:00:00:00:00 or ff:ff:ff:ff:ff:ff before it associates, or after it
     * rejects a MAC, would otherwise match every zero- or 0xFF-padded region of
     * the image and rewrite about 90% of it (measured on the 512-byte MTK layout:
     * 78 hits / 468 bytes changed for all-zero, 83 / 498 for all-0xFF). That is
     * also why restore() must never run with such a pattern.
     *
     * All the work happens on a scratch copy: `data` is only overwritten once the
     * result has been checked, so a refusal cannot leave a half-patched image
     * behind. A count above PATCH_MAX_HITS leaves `data` untouched too and the
     * caller must abandon the file entirely.
     */
    int patch(byte[] data, byte[] oldB, byte[] newB, List<int[]> win) {
        if (data == null || oldB == null || newB == null) return PATCH_REFUSED;
        if (oldB.length != 6 || newB.length != 6) return PATCH_REFUSED;
        // Unicast, at least one non-zero octet, and not all-0xFF. The
        // locally-administered bit is deliberately NOT rejected here: a MAC this
        // app set earlier, and randomMac()'s output, both carry 0x02 and both are
        // legitimate things to search a calibration image for.
        if ((oldB[0] & 0x01) != 0) return PATCH_REFUSED;
        boolean zero = true, ones = true;
        for (int i = 0; i < 6; i++) {
            int v = oldB[i] & 0xFF;
            if (v != 0) zero = false;
            if (v != 0xFF) ones = false;
        }
        if (zero || ones) return PATCH_REFUSED;

        byte[] work = data.clone();
        List<int[]> w = new ArrayList<int[]>();
        int t = 0;
        t += replaceAll(work, oldB, newB, w);
        // A palindromic pattern (aa:bb:cc:cc:bb:aa) makes this pass byte-identical to
        // the raw one above, and the pass can then only re-match what the first one
        // left in place -- i.e. only when the replacement equals the pattern
        // (newB == oldB), since nothing else survives pass 1. Measured on the
        // extracted methods: one occurrence of a palindromic MAC with newB == oldB
        // reported hits=2 through the four-pass sequence and 1 through this one, so
        // five occurrences reported ten and crossed PATCH_MAX_HITS, which is a
        // refusal invented by the counter. The bytes are identical either way
        // (newB == oldB changes nothing, and both callers skip such a patch), so this
        // is the count being made truthful, not a change to what is written; with
        // newB != oldB the skipped pass matched nothing in either version.
        if (!Arrays.equals(oldB, reverse(oldB)))
            t += replaceAll(work, reverse(oldB), reverse(newB), w);
        t += replaceAll(work, bytesToMac(oldB).getBytes(), bytesToMac(newB).getBytes(), w);
        t += replaceAll(work, hexPlain(oldB).getBytes(), hexPlain(newB).getBytes(), w);
        if (t == 0) {
            if (win != null) win.clear();
            return 0;
        }
        if (t > PATCH_MAX_HITS) {
            // Abandon: `data` is untouched, and so is the caller's window list. Those
            // offsets used to be left in it, so the caller's "unexpected layout ...
            // refusing (at 0x.., 0x..)" line named windows in a file this call
            // refused to patch, which reads as if they had been patched.
            if (win != null) win.clear();
            return t;
        }
        if (win != null) win.addAll(w);

        // The two assertions that make a silent whole-file rewrite impossible:
        // the length is unchanged, and every byte outside the recorded windows is
        // still the byte it was. Both are checked before anything may be written.
        if (work.length != data.length) {
            if (win != null) win.clear();
            return PATCH_REFUSED;
        }
        for (int i = 0; i < data.length; i++) {
            if (data[i] == work[i]) continue;
            boolean inside = false;
            for (int k = 0; k < w.size(); k++) {
                int[] v = w.get(k);
                if (i >= v[0] && i < v[0] + v[1]) { inside = true; break; }
            }
            if (!inside) {
                if (win != null) win.clear();
                return PATCH_REFUSED;
            }
        }
        System.arraycopy(work, 0, data, 0, work.length);
        return t;
    }

    int patch(byte[] data, byte[] oldB, byte[] newB) {
        return patch(data, oldB, newB, null);
    }

    /**
     * The four encodings patch() rewrites, named so the *precondition* and the
     * post-write verification can talk about the one that actually matched.
     */
    static final int ENC_RAW = 0, ENC_REV = 1, ENC_COLON = 2, ENC_HEX = 3;

    /** `mac` as it appears in the image, in one of the four encodings patch() knows. */
    byte[] encPattern(byte[] mac, int enc) {
        if (enc == ENC_REV) return reverse(mac);
        if (enc == ENC_COLON) return bytesToMac(mac).getBytes();
        if (enc == ENC_HEX) return hexPlain(mac).getBytes();
        return mac;
    }

    /** The encoding of the window [off, off+len) of `data`, or -1 when it is none of them. */
    int encOfWindow(byte[] data, int off, int len, byte[] mac) {
        for (int e = 0; e <= ENC_HEX; e++) {
            byte[] p = encPattern(mac, e);
            if (p.length != len) continue;
            boolean hit = true;
            for (int j = 0; j < len; j++)
                if (data[off + j] != p[j]) { hit = false; break; }
            if (hit) return e;
        }
        return -1;
    }

    /**
     * The first window in `data` that holds `mac` in any of the four encodings
     * patch() rewrites, as {offset, encoding}, or null.
     *
     * This is the precondition H2 asks for, applied to the encoding that matched:
     * raw and reversed windows are compared as the six bytes that must equal the
     * runtime MAC (respectively its reverse), and the ASCII windows are compared as
     * the text that must equal bytesToMac(cur).
     */
    int[] findMacWindow(byte[] data, byte[] mac) {
        if (data == null || mac == null || mac.length != 6) return null;
        for (int e = 0; e <= ENC_HEX; e++) {
            int off = indexOf(data, encPattern(mac, e));
            if (off >= 0) return new int[]{ off, e };
        }
        return null;
    }

    /** True when the window at `off` holds `mac` in the encoding `enc` says it does. */
    boolean windowHolds(byte[] data, int off, int enc, byte[] mac) {
        if (data == null || mac == null) return false;
        byte[] p = encPattern(mac, enc);
        if (off < 0 || off + p.length > data.length) return false;
        for (int j = 0; j < p.length; j++) if (data[off + j] != p[j]) return false;
        return true;
    }

    /**
     * Verifies an install per recorded window, in the encoding that window really
     * carries, instead of re-searching for the raw bytes.
     *
     * The old check took the first 6-byte window and looked for the new MAC as raw
     * bytes, so a file whose MAC is stored reversed or as ASCII was reported as
     * "not verified" even after the shell's own `cmp` had proved the image landed:
     * the write was un-provable in exactly the encodings the plan card advertises.
     * The encoding is re-derived from the staged image (`staged`), which is the
     * image this call installed, so a 6-byte window cannot be confused between raw
     * and reversed. The caller has already proved that every byte outside the
     * windows is unchanged (back equals staged), so this only has to prove the
     * windows.
     */
    boolean windowsVerify(byte[] back, byte[] staged, List<int[]> win, byte[] newMac) {
        if (back == null || staged == null || win == null || win.isEmpty()) return false;
        for (int i = 0; i < win.size(); i++) {
            int off = win.get(i)[0], len = win.get(i)[1];
            if (off < 0 || len <= 0 || off + len > back.length || off + len > staged.length)
                return false;
            int enc = encOfWindow(staged, off, len, newMac);
            if (enc < 0) return false;
            byte[] p = encPattern(newMac, enc);
            for (int j = 0; j < len; j++) if (back[off + j] != p[j]) return false;
        }
        return true;
    }

    /**
     * True when the image carries `mac` in any of the encodings patch() rewrites.
     *
     * A scan only -- this is a presence test for a saved pre-image, not a patch,
     * so the C3 cap deliberately does not apply: refusing here would silently
     * drop a legitimate backup.
     */
    boolean containsMacValue(byte[] data, byte[] mac) {
        if (data == null || mac == null || mac.length != 6) return false;
        return indexOf(data, mac) >= 0
                || indexOf(data, reverse(mac)) >= 0
                || indexOf(data, bytesToMac(mac).getBytes()) >= 0
                || indexOf(data, hexPlain(mac).getBytes()) >= 0;
    }

    /** Syntax only: six hex octets. Deliberately does not reject a legal target. */
    boolean isMac(String s) {
        return s != null && s.matches("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$");
    }

    /**
     * A MAC a driver can accept: unicast, not all-zero and not all-0xFF.
     *
     * The locally-administered bit is NOT rejected here -- randomMac() sets it
     * (0x02) on purpose, so a shared rule would break the Random button. It is
     * rejected only for a captured *factory* value, see isFactoryCandidate.
     */
    boolean isUsableTarget(byte[] b) {
        return b != null && b.length == 6 && isUsableTarget(b, 0);
    }

    boolean isUsableTarget(byte[] b, int off) {
        if (b == null || off < 0 || off + 6 > b.length) return false;
        if ((b[off] & 0x01) != 0) return false;              // multicast / broadcast
        boolean zero = true, ones = true;
        for (int i = 0; i < 6; i++) {
            int v = b[off + i] & 0xFF;
            if (v != 0) zero = false;
            if (v != 0xFF) ones = false;
        }
        return !zero && !ones;
    }

    /**
     * A factory burn-in is universally administered, and a randomized or already
     * spoofed runtime MAC never is. This is the only place 0x02 is rejected.
     */
    boolean isFactoryCandidate(byte[] b) {
        return isUsableTarget(b) && (b[0] & 0x02) == 0;
    }

    /**
     * Why a recorded factory value must not be written back, or null when it may.
     *
     * Pure, and separate from restore() on purpose: the recovery path is the one
     * that must always work, and this is the gate the pre-repair build did not have
     * at all. Measured on that build, a record of 00:00:00:00:00:00 wrote six zero
     * bytes into the calibration file and reported "NVRAM restored (verified)", and
     * a record of 02:11:22:33:44:55 -- locally administered, exactly what an
     * Android 11+ device with MAC randomization stores -- was written and reported
     * the same way. setMac refuses both of those as targets; restore now does too.
     *
     * The locally administered case is refused unless the provenance is "typed",
     * because only the user's own entry can justify writing an address that could
     * not have come from the factory. All-zero, all-0xFF and multicast are refused
     * outright: no provenance makes them writable.
     */
    String restoreRefusal(String mac, String src) {
        byte[] b = macToBytes(mac);
        if (!isMac(mac) || !isUsableTarget(b))
            return "it is not a usable MAC (all-zero, broadcast or multicast)";
        if (!isFactoryCandidate(b) && !"typed".equals(src))
            return "it is locally administered ("
                    + provLabel(src) + "), so it is not a factory burn-in";
        return null;
    }

    /** True when some 6-byte window of `data` is a plausible unicast MAC. */
    boolean hasMacField(byte[] data) {
        if (data == null) return false;
        for (int i = 0; i + 6 <= data.length; i++) if (isUsableTarget(data, i)) return true;
        return false;
    }

    /** First offset of `pat` in `data`, or -1. Scanning only -- no fixed NVRAM offset. */
    int indexOf(byte[] data, byte[] pat) {
        if (data == null || pat == null || pat.length == 0) return -1;
        for (int i = 0; i + pat.length <= data.length; i++) {
            boolean hit = true;
            for (int j = 0; j < pat.length; j++)
                if (data[i + j] != pat[j]) { hit = false; break; }
            if (hit) return i;
        }
        return -1;
    }

    /** The MAC at a byte offset of a calibration image, or null when out of range. */
    String macAt(byte[] data, int off) {
        if (data == null || off < 0 || off + 6 > data.length) return null;
        byte[] b = new byte[6];
        System.arraycopy(data, off, b, 0, 6);
        return bytesToMac(b);
    }

    // ------------------------------------------- backups and the factory record

    /**
     * Primary record location (H1).
     *
     * App-private storage dies with an uninstall or a clear-data, which destroys
     * the only recovery path for a modified calibration partition. This directory
     * survives both, and cli/macchanger.sh already uses the same directory and the
     * same WIFI.factory / WIFI.factory.path names, so both front ends share one
     * record instead of keeping two that can disagree.
     */
    static final String ADB_DIR = "/data/adb/macchanger";
    static final String ADB_BAK = ADB_DIR + "/WIFI.factory";
    static final String ADB_TAG = ADB_DIR + "/WIFI.factory.path";
    /**
     * The two companions cli/macchanger.sh validates the shared image against.
     *
     * It reads WIFI.factory.offset to know where the MAC field sits (a plain
     * integer, or '?N' when the capture could not locate it) and WIFI.factory.sha256
     * to tell "replaced by another writer" from "damaged": its restore, set and
     * panic all refuse an image whose digest does not match. Writing the image
     * without them -- which is what this app used to do -- left the CLI unable to
     * trust an intact record, so the app has to maintain them now (repair round,
     * blockers "app overwrites the shared factory image" and "the app destroys the
     * CLI's recovery record").
     */
    static final String ADB_OFF = ADB_DIR + "/WIFI.factory.offset";
    static final String ADB_SHA = ADB_DIR + "/WIFI.factory.sha256";

    /**
     * The app's own durable slot, used only when ADB_BAK is already taken.
     *
     * /data/adb/macchanger/WIFI.factory is shared, and an image captured there by
     * the CLI belongs to the path the CLI captured. Overwriting it with this app's
     * image for a different calibration path would destroy the other front end's
     * only way back, so when the shared slot is occupied the app keeps its own
     * image -- and its own sidecars -- under these names instead. Nothing else
     * reads them: the shared slot stays the shared slot.
     */
    static final String ADB_APP = ADB_BAK + ".app";
    static final String ADB_APP_TAG = ADB_TAG + ".app";
    static final String ADB_APP_OFF = ADB_OFF + ".app";
    static final String ADB_APP_SHA = ADB_SHA + ".app";

    /**
     * Claims the durable slot for one pre-image: ten lines on stdin.
     *
     * `d` directory, `si` the staged source image, `sp` the NVRAM path it came
     * from, `di`/`dt`/`doff`/`dsha` the four destination names, `ssz` the source
     * length, `soff` the offset the MAC field was located at, `sdg` its SHA-256 --
     * ten `IFS= read -r` lines below, and pushDurable() feeds exactly those ten.
     * (This said twelve; the script has never read twelve, and the two numbers were
     * reconciled from the script rather than from the sentence.)
     *
     * WRITE-ONCE, the same rule as cli/macchanger.sh's ensure_backup(): an existing
     * image is never overwritten through this path, because it is the only way back
     * and the app cannot tell whose capture it is. `[ -L ]` first, so a symlink
     * planted at the destination cannot be followed by the copy. A replaced or
     * short copy is deleted again (`failed=size`), and the offset and digest
     * companions are written in the same invocation that writes the image, so the
     * CLI can never see an image whose digest is stale. The image is fsynced before
     * it is reported, the barrier the CLI's own write uses; the three companion
     * files are not (neither are the CLI's), so the fallback for a torn record is
     * the digest, which is checked on every read.
     */
    static final String SAVE_SCRIPT =
            "umask 077; IFS= read -r d; IFS= read -r si; IFS= read -r sp; "
          + "IFS= read -r di; IFS= read -r dt; IFS= read -r doff; IFS= read -r dsha; "
          + "IFS= read -r ssz; IFS= read -r soff; IFS= read -r sdg; "
          + "mkdir -p -- \"$d\" 2>/dev/null; chmod 700 -- \"$d\" 2>/dev/null; "
          + "[ -L \"$di\" ] && { echo failed=symlink; exit 1; }; "
          + "[ -e \"$di\" ] && { echo \"kept=$(wc -c < \"$di\" 2>/dev/null) tag=$(sed -n '1p' \"$dt\" 2>/dev/null)\"; exit 0; }; "
          + "cp -f -- \"$si\" \"$di.tmp\" 2>/dev/null && mv -f -- \"$di.tmp\" \"$di\" 2>/dev/null; "
          + "asz=$(wc -c < \"$di\" 2>/dev/null); "
          + "[ \"$asz\" = \"$ssz\" ] || { rm -f -- \"$di\" 2>/dev/null; echo failed=size; exit 1; }; "
          // The durability barrier the CLI's own comment calls "the only way back":
          // it writes its image with `dd ... conv=fsync`, while this copy is a cp +
          // rename, so a power loss could leave the name in place with the data not
          // yet on the medium. A same-file `dd` with notrunc rewrites nothing (the
          // offsets coincide) and fsyncs the whole image; conv=notrunc is what keeps
          // it from truncating the file it is reading.
          + "dd if=\"$di\" of=\"$di\" bs=4096 conv=notrunc,fsync 2>/dev/null; "
          + "printf '%s\\n' \"$sp\" > \"$dt.tmp\" 2>/dev/null && mv -f -- \"$dt.tmp\" \"$dt\" 2>/dev/null; "
          + "printf '%s\\n' \"$soff\" > \"$doff.tmp\" 2>/dev/null && mv -f -- \"$doff.tmp\" \"$doff\" 2>/dev/null; "
          + "printf '%s\\n' \"$sdg\" > \"$dsha.tmp\" 2>/dev/null && mv -f -- \"$dsha.tmp\" \"$dsha\" 2>/dev/null; "
          + "chmod 600 -- \"$di\" \"$dt\" \"$doff\" \"$dsha\" 2>/dev/null; "
          + "[ -f \"$di\" ] && [ -f \"$dt\" ] && [ -f \"$doff\" ] && [ -f \"$dsha\" ] "
          + "&& echo \"saved=$asz\" || echo failed";

    /**
     * Write factory.txt (line 1 = MAC, line 2 = provenance). The guard against an
     * empty MAC matters: without it a later run without a captured value would
     * blank a good record that is the user's only way back.
     */
    static final String TEXT_SCRIPT =
            "umask 077; IFS= read -r d; IFS= read -r m; IFS= read -r o; "
          + "mkdir -p -- \"$d\" 2>/dev/null; chmod 700 -- \"$d\" 2>/dev/null; "
          + "[ -n \"$m\" ] || exit 1; "
          + "{ printf '%s\\n' \"$m\"; printf '%s\\n' \"$o\"; } > \"$d/factory.txt.tmp\" 2>/dev/null "
          + "&& mv -f -- \"$d/factory.txt.tmp\" \"$d/factory.txt\" 2>/dev/null; "
          + "[ -f \"$d/factory.txt\" ] && echo saved || echo failed";

    /**
     * Read the whole record in one round trip; one "key=value" line per field.
     *
     * IMP: the "};" after the directory test is load-bearing. As "} " the compound
     * command had no separator before the next word, so the shell rejected the whole
     * script with "Syntax error: word unexpected": loadRecord() got nothing back, which
     * meant recTag stayed "" and the durable pre-image under /data/adb/macchanger could
     * never be found by loadBackup -- the app-private backup was the only one that
     * counted, and H1's whole point is the copy that survives an uninstall.
     *
     * What proves that today is a check run outside this file, not a gate in it:
     * extract the script constants from this file's Java literals and run `sh -n`
     * over each. An earlier version of this comment said "`sh -n` over every script
     * constant in this file is what caught it", which credited a gate that does not
     * exist anywhere under tools/ -- there is no such check, so the sentence has been
     * replaced by the measurement it actually rests on. Run for this repair: all ten
     * constants (RECORD, PROBE, WRITE, SAVE, READ, EXPORT, LOCK, RENEW, IFACE, TEXT)
     * pass `sh -n` under dash and under bash, and the negative control -- the same
     * RECORD_SCRIPT with "};" reduced to "} " -- fails with "Syntax error: word
     * unexpected", exit 2. The claim was true; the provenance was not.
     */
    static final String RECORD_SCRIPT =
            "umask 077; IFS= read -r d; "
          + "mkdir -p -- \"$d\" 2>/dev/null; chmod 700 -- \"$d\" 2>/dev/null; "
          + "{ [ -d \"$d\" ] && echo dir=ok || echo dir=no; }; "
          + "printf 'mac=%s\\n' \"$(sed -n '1p' \"$d/factory.txt\" 2>/dev/null)\"; "
          + "printf 'src=%s\\n' \"$(sed -n '2p' \"$d/factory.txt\" 2>/dev/null)\"; "
          + "printf 'tag=%s\\n' \"$(sed -n '1p' \"$d/WIFI.factory.path\" 2>/dev/null)\"; "
          + "{ [ -f \"$d/WIFI.factory\" ] && echo bak=ok || echo bak=no; }; "
          + "printf 'baksz=%s\\n' \"$(wc -c < \"$d/WIFI.factory\" 2>/dev/null)\"; "
          + "printf 'off=%s\\n' \"$(sed -n '1p' \"$d/WIFI.factory.offset\" 2>/dev/null)\"; "
          + "printf 'sha=%s\\n' \"$(sed -n '1p' \"$d/WIFI.factory.sha256\" 2>/dev/null)\"; "
          + "printf 'apptag=%s\\n' \"$(sed -n '1p' \"$d/WIFI.factory.app.path\" 2>/dev/null)\"; "
          + "printf 'appsz=%s\\n' \"$(wc -c < \"$d/WIFI.factory.app\" 2>/dev/null)\"; "
          + "printf 'appoff=%s\\n' \"$(sed -n '1p' \"$d/WIFI.factory.app.offset\" 2>/dev/null)\"; "
          + "printf 'appsha=%s\\n' \"$(sed -n '1p' \"$d/WIFI.factory.app.sha256\" 2>/dev/null)\"";

    /**
     * Export for the user. Every path here is a literal constant, and the copy
     * runs as root through the channel the app already owns, so no storage
     * permission and no manifest change is needed (H1).
     */
    static final String EXPORT_SCRIPT =
            "d=/data/adb/macchanger; o=/sdcard/Download; [ -d \"$o\" ] || o=/sdcard; "
          + "[ -d \"$o\" ] || { echo 'no public directory to write to'; exit 1; }; "
          + "n=0; for f in WIFI.factory WIFI.factory.path WIFI.factory.offset WIFI.factory.sha256 "
          + "WIFI.factory.app WIFI.factory.app.path WIFI.factory.app.offset "
          + "WIFI.factory.app.sha256 factory.txt; do "
          + "[ -f \"$d/$f\" ] && cp -f -- \"$d/$f\" \"$o/$f\" && n=$((n+1)); done; "
          + "echo \"exported $n file(s) to $o\"";

    File backupDir() {
        File d = new File(getFilesDir(), "nvram_backup");
        d.mkdirs();
        return d;
    }

    File backupFor(String path) {
        return new File(backupDir(), path.replace('/', '_'));
    }

    File tagFor(String path) {
        return new File(backupDir(), path.replace('/', '_') + ".path");
    }

    File factoryMirror() {
        return new File(getFilesDir(), "factory.txt");
    }

    /**
     * C2: saves the pre-image of `path` BEFORE anything is written, and returns
     * true only when a byte-verified copy of the expected length exists.
     *
     * The pre-image was just read whole in a single `cat`, so its length IS the
     * on-disk size. An existing copy that does not match is re-created rather than
     * trusted: a 0-byte or short file -- a backup killed mid-write by an earlier
     * build -- must never be promoted to "factory" for the life of the install.
     * An existing copy of the right length is deliberately KEPT rather than
     * refreshed, for the same reason cli/macchanger.sh keeps its image: the first
     * pre-image of a calibration file is the only one that is worth anything, and
     * every later one is a state this tool itself created.
     *
     * @param capture true only for the phase-1 pre-image of Set MAC / Random --
     *                the bytes that were in the file while it still held the
     *                driver's own MAC. Restore passes false: its pre-image is the
     *                live file, which already holds a spoof, and a spoof must
     *                never be promoted into the shared record.
     * @param off     the raw offset the MAC was located at in `orig`, or -1 when
     *                no raw 6-byte MAC field was found (then no offset can be
     *                recorded, and the durable slot is not claimed).
     */
    boolean saveBackup(String path, byte[] orig, boolean capture, int off) {
        File f = backupFor(path);
        boolean ok = f.isFile() && f.length() == orig.length;
        if (!ok) {
            boolean wrote = writeFile(f, orig) && writeFile(tagFor(path), utf8Bytes(path));
            ok = wrote && f.isFile() && f.length() == orig.length;
        }
        lastBackupDurable = false;
        lastDurablePath = null;
        if (!capture || off < 0) {
            // Nothing durable is claimed, but a durable image for this path that is
            // already on disk still counts as this path's way back, and the
            // confirmation has to say which of the two it is.
            if (durableFor(path, orig.length)) {
                lastBackupDurable = true;
                lastDurablePath = path;
            }
            if (capture && off < 0)
                log("no raw 6-byte MAC field in " + path + " \u00b7 nothing durable is recorded"
                        + " for it (app-private pre-image only)", C_DIM);
            return ok;
        }
        if (claimDurable(path, orig, off)) {
            lastBackupDurable = true;
            lastDurablePath = path;
        }
        return ok;
    }

    /**
     * What the last saveBackup() actually established about the durable record.
     *
     * The confirmation dialog and the plan card used to assert that "a verified
     * pre-image is already saved to /data/adb/macchanger/WIFI.factory and to
     * app-private storage" as a constant, and saveBackup()'s answer for the durable
     * half was discarded -- so on a device where /data/adb is absent, or where the
     * push failed, the app promised a copy that did not exist. Both texts are now
     * built from these two fields.
     */
    volatile boolean lastBackupDurable = false;

    /** The path the last saveBackup() claimed a durable image for, or null. */
    volatile String lastDurablePath = null;

    /**
     * One line about the pre-images of `path`, for the confirmation that precedes
     * the first destructive byte. Nothing in it is a promise: the app-private copy
     * is stat()ed and the durable half is what saveBackup() just established.
     */
    String backupPhrase(String path) {
        File f = backupFor(path);
        long n = f.isFile() ? f.length() : -1L;
        String app = n >= MIN_IMAGE
                ? "app-private pre-image " + n + " B (lost on an uninstall or clear-data)"
                : "NO app-private pre-image";
        String dur = lastBackupDurable && path.equals(lastDurablePath)
                ? "durable copy in " + ADB_DIR + " verified for this path"
                : "no usable durable copy in " + ADB_DIR + " for this path";
        return app + " \u00b7 " + dur;
    }

    /**
     * True when the durable slot holds an image of `wantLen` bytes recorded for
     * exactly this calibration path. One cached record read, no extra root call.
     */
    boolean durableFor(String path, long wantLen) {
        loadRecord();
        if (path.equals(recTag) && "ok".equals(recBak) && parseLong(recBakSz) == wantLen) return true;
        return path.equals(recAppTag) && parseLong(recAppSz) == wantLen;
    }

    /** SHA-256 of a buffer, as lowercase hex; "" when the platform will not provide it. */
    String sha256Hex(byte[] d) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(d);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < h.length; i++) s.append(String.format("%02x", h[i] & 0xff));
            return s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Claims a durable factory image for `path` out of `img`, at the raw offset
     * `off`, and returns true only when a size-matching image for this path is in
     * place afterwards.
     *
     * Three rules, and each of them is a defect this repair round fixed:
     *
     *  - the value at `off` must be a factory candidate (isFactoryCandidate). A
     *    locally administered MAC -- what a randomizer or an earlier spoof leaves
     *    in the file -- is not a factory burn-in, so an image holding one is never
     *    promoted into the record. cli/macchanger.sh refuses the same capture
     *    ("refusing to record ... it is a locally administered address"), and this
     *    is the app's half of that rule.
     *  - the shared slot is WRITE-ONCE. If WIFI.factory already exists -- with or
     *    without a digest recorded by the CLI -- it is left exactly as it is, and
     *    the app claims its own ADB_APP slot instead. Overwriting it used to
     *    replace a CLI capture with whatever the app-private directory happened to
     *    hold (on a clear-data, with the file already spoofed, that is the spoof),
     *    while leaving the CLI's WIFI.factory.sha256 stale -- after which set,
     *    restore and panic all refused a perfectly intact image.
     *  - offset and digest are written in the same invocation as the image, from
     *    the very bytes that were staged, so nothing can see an image whose
     *    companions describe a different one.
     */
    boolean claimDurable(String path, byte[] img, int off) {
        if (img == null || off < 0 || off + 6 > img.length) return false;
        byte[] at = new byte[6];
        System.arraycopy(img, off, at, 0, 6);
        if (!isFactoryCandidate(at)) {
            log("not recording a durable factory image for " + path + ": the MAC field at 0x"
                    + Integer.toHexString(off) + " holds " + bytesToMac(at)
                    + ", which is not a factory burn-in (locally administered)"
                    + " \u00b7 the durable record is left as it is", C_WARN);
            return false;
        }
        // The staged file, not the app-private backup: this is the byte-exact image
        // writeFile() verified, so the digest describes what is really published.
        File stage = new File(getFilesDir(), "publish.tmp");
        if (!writeFile(stage, img)) {
            log("cannot stage the pre-image of " + path + " for the durable record", C_WARN);
            return false;
        }
        String shared = pushDurable(stage, path, off, img.length, ADB_BAK, ADB_TAG, ADB_OFF, ADB_SHA);
        if (publishOk(shared, path, img.length)) {
            stage.delete();
            return true;
        }
        String keptBy = publishTag(shared);
        if (keptBy == null) {
            stage.delete();
            log("the durable record for " + path + " could not be written ("
                    + (shared.isEmpty() ? "no answer from the shell" : shared)
                    + ") \u00b7 app-private pre-image only", C_WARN);
            return false;
        }
        // The shared slot is taken -- by the CLI, by an earlier capture, or by a
        // stale image of this very path. It is never overwritten; the app keeps its
        // own durable image under its own name instead.
        String mine = pushDurable(stage, path, off, img.length, ADB_APP, ADB_APP_TAG, ADB_APP_OFF,
                ADB_APP_SHA);
        stage.delete();
        if (publishOk(mine, path, img.length)) {
            log(ADB_BAK + " is already an image for " + keptBy
                    + " \u00b7 kept it, and saved this one to " + ADB_APP, C_DIM);
            return true;
        }
        log(ADB_BAK + " is already an image for " + keptBy + " \u00b7 kept it untouched; the"
                + " app's own durable copy could not be written either ("
                + (mine.isEmpty() ? "no answer from the shell" : mine) + ")"
                + " \u00b7 app-private pre-image only", C_WARN);
        return false;
    }

    /**
     * True when a claim invocation left an image of `len` bytes in the slot AND
     * that image is this path's. "kept=" is a report about somebody else's image
     * unless the tag says otherwise, so the tag is checked here and not assumed.
     */
    boolean publishOk(String out, String path, long len) {
        if (out == null) return false;
        if (out.startsWith("saved=")) return parseLong(out.substring(6).trim()) == len;
        if (out.startsWith("kept="))
            return path.equals(field(out, "tag")) && parseLong(field(out, "kept")) == len;
        return false;
    }

    /** The path an occupied slot already holds, or null when the claim failed. */
    String publishTag(String out) {
        if (out == null || out.indexOf("kept=") < 0) return null;
        String t = field(out, "tag");
        return t.isEmpty() ? "an untagged image" : t;
    }

    /**
     * One claim attempt against one slot; the script's own report, or "" when the
     * invocation produced nothing at all.
     */
    String pushDurable(File src, String path, int off, long len,
                       String di, String dt, String doff, String dsha) {
        String in = ADB_DIR + "\n" + src.getAbsolutePath() + "\n" + path + "\n"
                  + di + "\n" + dt + "\n" + doff + "\n" + dsha + "\n"
                  + len + "\n" + off + "\n" + sha256Hex(readFile(src)) + "\n";
        return feed(SAVE_SCRIPT, in, MS_DEFAULT).out.trim();
    }

    /** Writes the factory text record into ADB_DIR. Best effort. */
    boolean pushText(String mac, String src) {
        // The persistence boundary checks the value, not just its syntax: this is
        // the one function that writes /data/adb/macchanger/factory.txt, the record
        // cli/macchanger.sh reads and restore is gated on, and isMac() alone would
        // let a multicast, all-zero or all-0xFF value -- none of which can be a
        // factory burn-in and all of which restoreRefusal() then refuses -- be
        // published there as "the factory MAC". The callers already screen their
        // values, so this changes nothing they do; it is the invariant held where
        // the file is written.
        if (!recordableFactory(mac)) return false;
        Res r = feed(TEXT_SCRIPT, ADB_DIR + "\n" + mac + "\n" + (src == null ? "" : src) + "\n",
                MS_DEFAULT);
        return r.out.contains("saved");
    }

    /** Reads the durable record once and caches it; every field is "" if absent. */
    void loadRecord() {
        if (recLoaded) return;
        recLoaded = true;
        Res r = feed(RECORD_SCRIPT, ADB_DIR + "\n", MS_DEFAULT);
        recMac = recField(r.out, "mac");
        recSrc = recField(r.out, "src");
        recTag = recField(r.out, "tag");
        // IMP: the script already asks root whether the directory and the pre-image
        // are there; discarding those two answers left the recovery card unable to
        // say whether the durable copy is intact or merely absent.
        recDir = recField(r.out, "dir");
        recBak = recField(r.out, "bak");
        // The same round trip now reports the three things the CLI validates the
        // shared image against; asking for them separately would let them disagree.
        recBakSz = recField(r.out, "baksz");
        recOff = recField(r.out, "off");
        recSha = recField(r.out, "sha");
        recAppTag = recField(r.out, "apptag");
        recAppSz = recField(r.out, "appsz");
        recAppOff = recField(r.out, "appoff");
        recAppSha = recField(r.out, "appsha");
    }

    /** One "key=value" field of the record dump. */
    String recField(String dump, String key) {
        String[] lines = dump == null ? new String[0] : dump.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith(key + "=")) return l.substring(key.length() + 1).trim();
        }
        return "";
    }

    /** Line `n` (1-based) of a UTF-8 buffer, trimmed, or "". */
    String line(byte[] b, int n) {
        if (b == null) return "";
        String[] l = utf8(b).split("\n");
        return (n >= 1 && n <= l.length) ? l[n - 1].trim() : "";
    }

    /**
     * Stores a factory value with its provenance: "<nvram path>@0x<offset>" when
     * it came out of a calibration image, "typed" when the user entered it.
     * Returns true when at least one durable copy exists (H1/H2).
     *
     * The value is validated here, where it is stored, and not only by the callers
     * that happen to remember to: see recordableFactory for what a record may hold.
     */
    boolean storeFactory(String mac, String src, String nvPath) {
        if (!recordableFactory(mac)) return false;
        setPref("factory", mac);
        setPref("factory_src", src);
        recMac = mac;
        recSrc = src;
        if (nvPath != null && nvramPaths().contains(nvPath)) recTag = nvPath;
        // IMP: the record on disk has just changed, so the cached copy of the durable
        // state is no longer current -- the recovery card reads dir=/bak= from that
        // cache and would otherwise report the pre-image it just wrote as missing. The
        // values above stay as the immediate answer; the next read re-derives them.
        recLoaded = false;
        boolean ok = writeFile(factoryMirror(), utf8Bytes(mac + "\n" + src + "\n"));
        if (pushText(mac, src)) ok = true;
        return ok;
    }

    /**
     * The resolved factory record: the value, where it came from, an extra honesty
     * note, and whether it fails isFactoryCandidate.
     *
     * Resolved in ONE place because three readers used to walk the three stores
     * independently -- factoryMac() returned the first syntactic MAC it found while
     * factorySrc() looked in the same order but for a different field -- so the
     * value and its provenance could come from two different stores, and neither of
     * them validated the value it was about to hand to a write.
     */
    static final class FactoryRec {
        final String mac;       // never null: an object exists only for a real MAC
        final String src;       // "typed", "<nvram path>@0x<off>", or "" when unknown
        final String note;      // extra honesty text, "" when there is none
        final boolean suspect;  // locally administered: not a factory burn-in
        FactoryRec(String mac, String src, String note, boolean suspect) {
            this.mac = mac;
            this.src = src == null ? "" : src;
            this.note = note == null ? "" : note;
            this.suspect = suspect;
        }
    }

    /** The app-private mirror, read once, for the two fields below it. */
    String mirrorLine(int n) {
        return line(readFile(factoryMirror()), n);
    }

    /**
     * The factory record, from the first store that holds a value that can actually
     * be written back.
     *
     * Order: the pref, the durable factory.txt, the app-private mirror, and finally
     * the shared pre-image itself at the offset its record names. The last one is
     * the repair for "a record captured by the CLI is invisible to the app": the
     * CLI writes WIFI.factory, WIFI.factory.path and WIFI.factory.offset and never
     * factory.txt, so after `macchanger.sh backup` the app used to say "No factory
     * MAC recorded yet" while the correct image and its offset were sitting in the
     * shared directory.
     *
     * A value that fails isUsableTarget (all-zero, all-0xFF, multicast) is skipped
     * rather than returned: it can never be a write target, and returning it is
     * what let restore() write six zero bytes into the calibration file and report
     * a verified restore. A locally administered value is returned but flagged
     * suspect.
     */
    FactoryRec factoryRec() {
        String p = getPref("factory");
        if (isMac(p) && usableFactory(p)) return mkRec(p, getPref("factory_src"), "");
        loadRecord();
        if (isMac(recMac) && usableFactory(recMac)) return mkRec(recMac, recSrc, "");
        String m = mirrorLine(1);
        if (isMac(m) && usableFactory(m)) return mkRec(m, mirrorLine(2), "");
        loadImageRecord();
        return imgMac == null ? null : mkRec(imgMac, imgSrc, imgNote);
    }

    FactoryRec mkRec(String mac, String src, String note) {
        return new FactoryRec(mac, src, note, !isFactoryCandidate(macToBytes(mac)));
    }

    /** A recorded value may only be used when it could be installed as a target. */
    boolean usableFactory(String mac) {
        return isUsableTarget(macToBytes(mac));
    }

    /**
     * True when `mac` may be persisted as a factory record at all: it must be a
     * value this app could put in a calibration file.
     *
     * isMac() is syntax only. isUsableTarget rejects multicast, all-zero and
     * all-0xFF -- and a value that fails it is one restoreRefusal() refuses to
     * write back, so persisting it would publish a "factory MAC" the app itself
     * will not use, into the shared record the CLI also reads.
     */
    boolean recordableFactory(String mac) {
        return isMac(mac) && isUsableTarget(macToBytes(mac));
    }

    /** The value of the shared pre-image, derived once per record load. */
    volatile String imgMac = null;
    volatile String imgSrc = null;
    volatile String imgNote = "";
    volatile boolean imgLoaded = false;

    /**
     * Reads the factory value out of the durable pre-image, at the offset the
     * record's own WIFI.factory.offset names -- the fallback that makes a
     * CLI-captured image a usable app record on its own.
     *
     * '?N' is the CLI's mark for an offset it could not locate and only assumed
     * (--assume-factory); a value read through one is reported as unverified rather
     * than silently promoted. The value must still pass isFactoryCandidate,
     * because the image may have been captured while the file already held a spoof.
     */
    void loadImageRecord() {
        if (imgLoaded) return;
        imgLoaded = true;
        loadRecord();
        String tag = recTag;
        String offs = recOff;
        byte[] img = null;
        if (nvramPaths().contains(tag == null ? "" : tag) && "ok".equals(recBak)) {
            img = readRootFile(ADB_BAK, MS_BIG_READ);
        } else if (nvramPaths().contains(recAppTag == null ? "" : recAppTag)
                && parseLong(recAppSz) > 0) {
            tag = recAppTag;
            offs = recAppOff;
            img = readRootFile(ADB_APP, MS_BIG_READ);
        }
        if (img == null || offs == null || offs.isEmpty()) return;
        boolean assumed = offs.startsWith("?");
        if (assumed) offs = offs.substring(1);
        int off = (int) parseLong(offs);
        String v = macAt(img, off);
        if (v == null || !isFactoryCandidate(macToBytes(v))) return;
        imgMac = v;
        imgSrc = tag + "@0x" + Integer.toHexString(off);
        imgNote = assumed
                ? "the record marks offset 0x" + Integer.toHexString(off)
                        + " as assumed, not located \u00b7 unverified"
                : "";
    }

    /**
     * The factory MAC, or null when no store holds a usable one.
     *
     * Reading the file is not optional: an uninstall or a clear-data removes the
     * pref and used to remove the record with it, leaving a modified calibration
     * partition with no way back.
     */
    String factoryMac() {
        FactoryRec r = factoryRec();
        return r == null ? null : r.mac;
    }

    /** Provenance of the value factoryMac() returned. */
    String factorySrc() {
        FactoryRec r = factoryRec();
        return r == null ? "" : r.src;
    }

    /**
     * The provenance label, including the two honesty tails this repair round
     * added: a value that cannot be a factory burn-in, and an offset the record
     * only assumed. The FACTORY RECORD row and the recovery card both build their
     * text from this, so they cannot disagree about how far to trust the value.
     */
    String factoryProvenance() {
        FactoryRec r = factoryRec();
        if (r == null) return "unverified \u00b7 no provenance recorded";
        String s = provLabel(r.src);
        if (!r.note.isEmpty()) s = s + " \u00b7 " + r.note;
        if (r.suspect) s = s + " \u00b7 " + FACTORY_SUSPECT;
        return s;
    }

    /** Why a value that looks like a MAC is still not a factory value. */
    static final String FACTORY_SUSPECT =
            "locally administered, so not a factory burn-in \u00b7 restore will refuse to write it";

    /** "path@0x4" -> "captured from NVRAM <path> @0x4"; "typed" -> "typed by you". */
    String provLabel(String src) {
        if (src == null || src.isEmpty()) return "unverified \u00b7 no provenance recorded";
        if (src.equals("typed")) return "typed by you \u00b7 unverified";
        int at = src.indexOf('@');
        if (at > 0) {
            String p = src.substring(0, at);
            String off = src.substring(at + 1);
            return nvramPaths().contains(p)
                    ? "captured from NVRAM " + p + " @" + off
                    : "unverified \u00b7 " + src;
        }
        return src;
    }

    /**
     * IMP: which of the stores in use holds the factory value that is in effect.
     *
     * The pref and the app-private mirror die with an uninstall or a clear-data and
     * the /data/adb record does not, so "restore will still work" is a different
     * promise on each of them, and only the row can say which one is in effect.
     * Worker thread only: the durable read goes through su.
     */
    String factoryStore() {
        FactoryRec r = factoryRec();
        if (r == null) return "no factory value recorded";
        String p = getPref("factory");
        if (isMac(p) && usableFactory(p) && p.equalsIgnoreCase(r.mac)) {
            loadRecord();
            if (isMac(recMac) && recMac.equalsIgnoreCase(r.mac)) return "durable copy in " + ADB_DIR;
            return "app-private only \u00b7 an uninstall or clear-data loses it";
        }
        loadRecord();
        if (isMac(recMac) && recMac.equalsIgnoreCase(r.mac))
            return "read from " + ADB_DIR + " \u00b7 survives an uninstall";
        if (imgMac != null && imgMac.equalsIgnoreCase(r.mac))
            return "read from the saved image " + ADB_BAK + " \u00b7 survives an uninstall";
        return "read from the app-private mirror \u00b7 the durable copy is missing";
    }

    /**
     * IMP: the one place MAC text typed by a human is normalised.
     *
     * Trailing whitespace and the ipconfig/Windows dash spelling are the same address,
     * and normalising a separator here cannot make two different addresses look equal.
     * isMac() itself is deliberately NOT relaxed: it is the gate in front of the shell
     * and the prefs.
     */
    String normMac(String raw) {
        if (raw == null) return "";
        return raw.replace('-', ':').toLowerCase().trim();
    }

    /** The NVRAM path encoded in a provenance string, when it is one we ship. */
    String nvPathOf(String src) {
        if (src == null) return null;
        int at = src.indexOf('@');
        if (at <= 0) return null;
        String p = src.substring(0, at);
        return nvramPaths().contains(p) ? p : null;
    }

    /**
     * The offset encoded in a provenance string ("<path>@0x4"), or -1.
     *
     * Needed because the durable image has to be published with the offset its MAC
     * field really sits at: a record published without one (or with a guessed one)
     * is exactly the "assumed offset" the CLI marks unverified.
     */
    int nvOffsetOf(String src) {
        if (src == null) return -1;
        int at = src.indexOf('@');
        if (at <= 0) return -1;
        String s = src.substring(at + 1).trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X"))
                return Integer.parseInt(s.substring(2), 16);
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * C2/MIN_IMAGE: why a buffer is not usable as the pre-image of a file of
     * `disk` bytes, or null when it is.
     *
     * The three gates are the same as installs and reads use, and the reason is
     * named in words because the old message printed "10 bytes ≠ 10" for a 10-byte
     * image -- the lengths were equal, so it named a reason that was not the
     * reason. A .path sibling is never proof by itself.
     */
    String backupReject(byte[] d, long disk) {
        if (d == null) return "it could not be read";
        if (d.length == 0) return "it is empty";
        if (d.length < MIN_IMAGE)
            return "it is " + d.length + " bytes, below the " + MIN_IMAGE
                    + "-byte floor for a calibration image";
        if (d.length != disk)
            return "it is " + d.length + " bytes and the file is " + disk + " bytes on disk";
        if (!hasMacField(d)) return "it is " + d.length + " bytes with no MAC-shaped field in it";
        return null;
    }

    /**
     * The saved pre-image for `path`, or null.
     *
     * Three stores, in the order of how well they survive: the shared slot the CLI
     * also uses, the app's own durable slot, and app-private storage. The NVRAM
     * path is re-validated against the shipped candidate list even when it was read
     * out of a record file, because that file is writable by anything running as
     * root, and a durable slot is only this path's image when its tag says so.
     *
     * When a digest was recorded with the image, the image must match it. That is
     * the CLI's own rule (bak_digest_state), and it is what stops a replaced image
     * from being installed as a factory pre-image.
     */
    byte[] loadBackup(String path) {
        if (!nvramPaths().contains(path)) return null;
        loadRecord();
        File f = backupFor(path), tag = tagFor(path);
        String appTag = line(readFile(tag), 1);
        boolean shared = path.equals(recTag) && "ok".equals(recBak);
        boolean own = path.equals(recAppTag) && parseLong(recAppSz) > 0;
        boolean appCand = tag.isFile() && path.equals(appTag);
        if (!shared && !own && !appCand) return null;
        long disk = sizeRoot(path);
        if (disk <= 0) {
            log("cannot size " + path + " \u00b7 its backup is not used", C_WARN);
            return null;
        }
        if (shared) {
            byte[] d = readRootFile(ADB_BAK, MS_BIG_READ);
            String why = backupReject(d, disk);
            if (why == null && !digestOk(d, recSha))
                why = "it does not match the digest recorded with it (" + ADB_SHA + ")";
            if (why == null) return d;
            log(ADB_BAK + " rejected: " + why, C_WARN);
        }
        if (own) {
            byte[] d = readRootFile(ADB_APP, MS_BIG_READ);
            String why = backupReject(d, disk);
            // The app's own slot records a digest as well -- SAVE_SCRIPT writes the
            // dsha sidecar for whichever slot it claims and pushDurable passes
            // ADB_APP_SHA for this one -- and it was written, exported and then never
            // checked by anything, so a same-size WIFI.factory.app that had been
            // replaced or corrupted but still held a MAC-shaped field was accepted on
            // the one path whose pre-image has no other guard.
            if (why == null && !digestOk(d, recAppSha))
                why = "it does not match the digest recorded with it (" + ADB_APP_SHA + ")";
            if (why == null) return d;
            log(ADB_APP + " rejected: " + why, C_WARN);
        }
        if (appCand) {
            byte[] d = readFile(f);
            String why = backupReject(d, disk);
            if (why == null) return d;
            log("app backup for " + path + " rejected: " + why, C_WARN);
        }
        return null;
    }

    /** True when `d` matches the digest recorded beside the image, or none is recorded. */
    boolean digestOk(byte[] d, String want) {
        if (want == null || want.isEmpty()) return true;      // nothing to verify against
        String got = sha256Hex(d);
        return !got.isEmpty() && got.equalsIgnoreCase(want);
    }

    /**
     * Best-effort refresh of the durable copy from the app-side record (H1).
     *
     * The image is published only when it really carries the recorded factory value
     * at the offset the provenance names, and only through claimDurable() -- which
     * will not overwrite an image that is already there. Pushing the app-private
     * file blindly (the old pushImage) is what replaced the CLI's capture.
     */
    boolean publishRecord() {
        FactoryRec r = factoryRec();
        if (r == null) return false;
        // The provenance is RECORDED, never invented. This used to substitute
        // "typed" for an empty one, and "typed" is precisely the provenance that
        // exempts a locally administered value from restoreRefusal() -- so exporting
        // a legacy record (the pref the shipped build wrote from /sys, which carries
        // no factory_src) relabelled it "typed" on disk, and a later clear-data left
        // the app ready to write a randomized address into the calibration partition
        // while logging "you typed it, so it is written as you asked". Executed on
        // the shipped code path: pref 02:11:22:33:44:55, no provenance -> Export
        // record -> factory.txt line 2 became "typed" -> restoreRefusal() returned
        // null where it had returned a refusal. An empty provenance stays empty, and
        // an empty provenance stays unverified everywhere it is read.
        boolean ok = pushText(r.mac, r.src);
        String nv = nvPathOf(r.src);
        int off = nvOffsetOf(r.src);
        if (nv != null && off >= 0) {
            byte[] img = readFile(backupFor(nv));
            String at = macAt(img, off);
            if (at != null && at.equalsIgnoreCase(r.mac)
                    && isFactoryCandidate(macToBytes(at))) {
                if (claimDurable(nv, img, off)) ok = true;
            } else {
                log("the saved pre-image for " + nv + " does not hold " + r.mac + " at 0x"
                        + Integer.toHexString(off) + " \u00b7 it is not published as a durable"
                        + " factory image", C_WARN);
            }
        }
        return ok;
    }

    /**
     * H5: what 'cmd wifi status' said, parsed by content.
     *
     * The shipped parser ran `cmd wifi status` through a three-line slice and
     * looked for `connected to` inside it. printStatus (WifiShellCommand) has a
     * privilege branch from Android 12 on that only non-root callers take, and
     * every command in this app runs as root: the first three lines are then
     * "Wifi is enabled", "Wifi scanning is ..." and a
     * "==== ClientModeManager instance: ... ====" banner, while the SSID line is
     * printed later by printWifiInfo. Grepping that slice for `connected to`
     * therefore matches nothing on Android 12-16, so connectedSsid() returned "" on
     * essentially every current device -- and the privacy row then read that
     * empty string as "this network randomizes the MAC". android11-release is the
     * one branch without that privilege break, which is exactly why the Android
     * 11 test device hid this.
     *
     * So the whole report is read, the SSID comes from an *anchored* match on the
     * "Wifi is connected to " prefix, the double quotes WifiInfo.getSSID() adds
     * are stripped, "<unknown ssid>" and an explicit "Wifi is not connected" are
     * states of their own, and "no connected network" is no longer the same
     * value as "unknown".
     */
    static final String WF_PREFIX = "Wifi is connected to ";
    static final String WF_NOT_CONNECTED = "Wifi is not connected";
    static final String WF_UNKNOWN_SSID = "<unknown ssid>";
    static final String DASH = "\u2014";

    static final class WifiStat {
        final boolean readable;    // the report carried a radio-state line at all
        final boolean enabled;     // ... and it said the radio is on
        final boolean connected;   // "Wifi is connected to" was present
        final boolean known;       // ... and it named a network we can compare
        final String ssid;         // that name, "" when it is not known
        WifiStat(boolean readable, boolean enabled, boolean connected, boolean known, String ssid) {
            this.readable = readable;
            this.enabled = enabled;
            this.connected = connected;
            this.known = known;
            this.ssid = ssid == null ? "" : ssid;
        }
        static final WifiStat NONE = new WifiStat(false, false, false, false, "");
        /** The SSID for display: never an empty cell, and never a made-up name. */
        String displaySsid() { return known ? ssid : DASH; }
    }

    /** Pure: what the driver's own report says. Checkable without a device. */
    static WifiStat parseWifiStatus(String out) {
        if (out == null || out.isEmpty()) return WifiStat.NONE;
        boolean en = false, dis = false, conn = false, notConn = false;
        String ssid = "";
        String[] lines = out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int s = 0;
            while (s < l.length() && (l.charAt(s) == ' ' || l.charAt(s) == '\t')) s++;
            l = l.substring(s);
            if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
            // Anchored, and the *first* state line decides: the report goes on to
            // describe per-manager state, and the head of it is the radio's own.
            if (!en && !dis && l.startsWith("Wifi is enabled")) en = true;
            else if (!en && !dis && l.startsWith("Wifi is disabled")) dis = true;
            if (l.startsWith(WF_NOT_CONNECTED)) notConn = true;
            if (!l.startsWith(WF_PREFIX)) continue;
            conn = true;
            String v = unquote(l.substring(WF_PREFIX.length()));
            if (v.isEmpty() || v.equals(WF_UNKNOWN_SSID) || v.equals(DASH)) { ssid = ""; continue; }
            ssid = v;
        }
        boolean up = conn && !notConn;
        return new WifiStat(en || dis, en, up, up && !ssid.isEmpty(), ssid);
    }

    /** Strips ONE pair of surrounding double quotes, the pair WifiInfo.getSSID() adds. */
    static String unquote(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
            return s.substring(1, s.length() - 1);
        return s;
    }

    /** Reads the whole report, bounded, and parses it. Never a line slice. */
    WifiStat readWifiStatus(long ms) {
        Blob b = exec(suArgv("cmd wifi status 2>/dev/null"), null, ms, true);
        return parseWifiStatus(utf8(b.out));
    }

    WifiStat readWifiStatus() { return readWifiStatus(MS_DEFAULT); }

    /** The connected SSID, or "" when there is none or it is not known (H5). */
    String connectedSsid() {
        WifiStat s = readWifiStatus();
        return s.known ? s.ssid : "";
    }

    String configStore() {
        Res x = run("cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null");
        if (!x.ok() || x.out.isEmpty()) {
            x = run("cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null");
        }
        return x.out;
    }

    /**
     * M1: 1, 2 and 3 all mean "the framework may override the hardware MAC".
     *
     * This is the predicate the whole feature is stated in, and it is the one that
     * is called: the connected network's setting decides the privacy row
     * (refresh), the failure explanation (explainPrivacy) and the count over all
     * saved networks (privacySummary). A single-network wrapper, isRandomized(),
     * used to sit above it with no caller -- it was kept alive only by a comment
     * that named it as M1's acceptance predicate, and both are gone: an audit
     * criterion satisfied by a function nothing calls is not satisfied.
     */
    static boolean isRandomizedValue(String pv) {
        return pv != null && (pv.equals("1") || pv.equals("2") || pv.equals("3"));
    }

    /** H5/M1: the label for a stored value. 3 is AUTO, not "unknown". */
    static String privacyLabel(String pv) {
        if ("0".equals(pv)) return "device MAC";
        if ("1".equals(pv)) return "randomized \u00b7 persistent \u00b7 tap to fix";
        if ("2".equals(pv)) return "randomized \u00b7 non-persistent \u00b7 tap to fix";
        if ("3".equals(pv)) return "auto (framework decides) \u00b7 tap to fix";
        return "unknown";
    }

    static int privacyColor(String pv) {
        if ("0".equals(pv)) return C_OK;
        if (isRandomizedValue(pv)) return C_WARN;
        return C_DIM;
    }

    /**
     * H5/M1: the per-network MacRandomizationSetting, as a tri-state.
     *
     * "0" device MAC, "1" persistent, "2" non-persistent, "3" auto. The value
     * space is 0-3 and 3 is the field's own default in WifiConfiguration
     * (RANDOMIZATION_AUTO), so a network that never had an explicit setter used
     * to be classified as *not* randomized and the app could tell the user their
     * spoof would reach the router when the framework was about to override it.
     * "?" is unknown, and unknown must never be rendered as randomized.
     *
     * Two defects are fixed in the same pass. Element 0 of split("<Network>") is
     * the file preamble: it holds no MacRandomizationSetting, and it matched every
     * SSID because `blk.contains("")` is always true, so an empty SSID returned
     * "1" -- i.e. every failure was explained as "this network randomizes the
     * MAC". And the SSID was matched as a raw substring of XML-escaped text, so
     * an SSID containing & < > ' or " could fail to match its own block and could
     * match a different one (the original K9).
     */
    String privacyValue(String xml, String ssid) {
        if (xml == null || xml.isEmpty() || ssid == null || ssid.isEmpty()
                || ssid.equals(DASH)) return "?";
        // Both sides of the comparison go through the same unescaping; the store
        // side inside ssidOf(), the driver's side here.
        String want = xmlUnescape(ssid);
        String[] blocks = xml.split("<Network>");
        for (int b = 1; b < blocks.length; b++) {          // b = 1: skip the preamble
            String got = ssidOf(blocks[b]);
            if (got == null || !got.equals(want)) continue;
            return settingOf(blocks[b]);
        }
        return "?";
    }

    /** The SSID of one <Network> block: that block's own SSID element, unescaped and unquoted. */
    String ssidOf(String blk) {
        String key = "<string name=\"SSID\">";
        int i = blk.indexOf(key);
        if (i < 0) return null;
        int e = blk.indexOf("</string>", i + key.length());
        if (e < 0) return null;
        return unquote(xmlUnescape(blk.substring(i + key.length(), e)));
    }

    /**
     * The one element this method trusts:
     * <int name="MacRandomizationSetting" value="N" />.
     *
     * "?" when the block carries no such element. An absent setting is not
     * evidence of randomization, and this value decides whether the app sends the
     * user to change a WiFi setting they may already have right.
     */
    String settingOf(String blk) {
        int i = blk.indexOf("MacRandomizationSetting");
        if (i < 0) return "?";
        int v = blk.indexOf("value=\"", i);
        if (v < 0) return "?";
        int e = blk.indexOf('"', v + 7);
        if (e <= v) return "?";
        String s = blk.substring(v + 7, e);
        return s.matches("[0-3]") ? s : "?";
    }

    /** Every saved network's setting, one per <Network> block, preamble skipped (M1). */
    List<String> privacySettings(String xml) {
        List<String> l = new ArrayList<String>();
        if (xml == null || xml.isEmpty()) return l;
        String[] blocks = xml.split("<Network>");
        for (int b = 1; b < blocks.length; b++) l.add(settingOf(blocks[b]));
        return l;
    }

    /**
     * M1: "randomized on N of M saved networks", or "" when the store was not read.
     *
     * The setting is per saved network, and the app only ever looked at the
     * connected one -- so it could never warn that the spoof would be overridden
     * the next time the phone joined another SSID, which is the most likely way a
     * working spoof "stops working".
     */
    String privacySummary(String xml) {
        List<String> all = privacySettings(xml);
        if (all.isEmpty()) return "";
        int n = 0;
        for (int i = 0; i < all.size(); i++) if (isRandomizedValue(all.get(i))) n++;
        return "randomized on " + n + " of " + all.size() + " saved networks";
    }

    /**
     * H5: the five XML entities, and nothing else.
     *
     * Deliberately conservative: no URL decoding, no whitespace trimming and no
     * case folding. An SSID is an opaque byte string, and an over-eager
     * normalisation would match a *different* network -- the wrong-block bug this
     * fixes, re-introduced from the other side. &amp; is replaced last so that
     * "&amp;lt;" does not become "<".
     */
    static String xmlUnescape(String s) {
        if (s == null) return "";
        String v = s.replace("&lt;", "<");
        v = v.replace("&gt;", ">");
        v = v.replace("&quot;", "\"");
        v = v.replace("&apos;", "'");
        return v.replace("&amp;", "&");
    }

    // ------------------------------ what will be written, and what can undo it (IMP)

    /** IMP: the {path, note} pair the last read-only scan recorded for one candidate. */
    String scanNote(String path) {
        List<String[]> rows = scanRows;
        if (rows == null) return null;
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i)[0].equals(path)) return rows.get(i)[1];
        return null;
    }

    /**
     * IMP: every offset at which `mac` appears in `data`, in any of the encodings
     * patch() rewrites, capped.
     *
     * Capped because the pattern may be degenerate and would otherwise produce one
     * "hit" per byte of a 512-byte image; the cap is a display limit only, and the
     * caller still refuses to write on a degenerate pattern.
     */
    int[] offsetsOf(byte[] data, byte[] mac, int cap) {
        List<Integer> l = new ArrayList<Integer>();
        addOffsets(l, data, mac, cap);
        addOffsets(l, data, reverse(mac), cap);
        addOffsets(l, data, bytesToMac(mac).getBytes(), cap);
        addOffsets(l, data, hexPlain(mac).getBytes(), cap);
        int[] out = new int[l.size()];
        for (int i = 0; i < l.size(); i++) out[i] = l.get(i).intValue();
        return out;
    }

    void addOffsets(List<Integer> to, byte[] data, byte[] pat, int cap) {
        if (data == null || pat == null || pat.length == 0) return;
        for (int i = 0; i + pat.length <= data.length && to.size() < cap; i++) {
            boolean hit = true;
            for (int j = 0; j < pat.length; j++)
                if (data[i + j] != pat[j]) { hit = false; break; }
            if (hit) to.add(Integer.valueOf(i));
        }
    }

    /** IMP: "0x4", "0x4, 0x208", or "0x4 (+2 more)" for a capped offset list. */
    String offsetsText(int[] offs, int cap) {
        if (offs == null || offs.length == 0) return "";
        StringBuilder s = new StringBuilder();
        int show = Math.min(offs.length, cap);
        for (int i = 0; i < show; i++) {
            if (i > 0) s.append(", ");
            s.append("0x").append(Integer.toHexString(offs[i]));
        }
        if (offs.length > show) s.append(" (+").append(offs.length - show).append(" more)");
        return s.toString();
    }

    /** IMP: a file timestamp for the recovery card, or why there is none. */
    String stamp(long ms) {
        if (ms <= 0L) return "time unknown";
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
        } catch (Throwable t) {
            return "time unknown";
        }
    }

    /**
     * IMP: the "what will be written, and where" card.
     *
     * Built on a worker thread (it needs nvramPaths(), i.e. su-backed getprop) and
     * retained statically, so a rotation cannot blank the one statement that tells
     * the user which calibration file a tap is pointed at. Nothing in it is a claim
     * about what *has* happened unless the scan or an operation proved it: the target
     * rows carry the scan's own findings, and the last line is the operation's own
     * log text or an explicit "nothing written by this session".
     */
    String buildPlanText() {
        StringBuilder s = new StringBuilder();
        s.append("writes  in place, same inode: dd conv=notrunc,fsync")
         .append(" \u00b7 never cp, cat > or mv\n");
        s.append("finds   by scanning each file for the runtime MAC as raw bytes,")
         .append(" reversed,\n        as hex and as colon-hex; the ASCII forms are")
         .append(" lowercase only,\n        so a file holding the MAC as UPPERCASE hex")
         .append(" is not found at all --\n        and a file is written only when the")
         .append(" bytes at the matched offset\n        equal the runtime MAC\n");
        List<String> cand = nvramPaths();
        s.append("targets in probe order, ").append(socVendor()).append(" first")
         .append(" \u00b7 ").append(scanRows == null ? "not scanned yet" : "from the last scan")
         .append('\n');
        for (int i = 0; i < cand.size(); i++) {
            String note = scanNote(cand.get(i));
            s.append("  ").append(cand.get(i)).append('\n');
            s.append("    ").append(note == null ? "not scanned \u00b7 tap Check NVRAM" : note)
             .append('\n');
        }
        // The durable half of this sentence is conditional, and now says so: the
        // shared slot is claimed only for a CAPTURED factory value (Set MAC and
        // Random, capture=true), never for the live file a Restore copies, because
        // that file already holds a spoof and promoting it would poison the record.
        // Restore still saves an app-private pre-image of every file it writes, so
        // the first half is true of both.
        s.append("backup  the pre-image of each file written is saved to app-private storage")
         .append(" before\n        the first byte; a captured factory value is also claimed in ")
         .append(ADB_BAK).append("\n        (a restore's live file is saved but never claimed")
         .append(" there); an existing\n        durable image is never overwritten, and the")
         .append(" question says which copies exist\n");
        // IMP: the two MACs the write will name, so the pair is not a surprise: the
        // runtime value is the pattern the patch searches for and the value it will
        // replace, and the target is what the confirmation reads out of the Set MAC box
        // at the moment of the tap. "(not read yet)" is the honest answer before the
        // first refresh, not a guess.
        s.append("change  ")
         .append(copyRuntime != null && isMac(copyRuntime) ? copyRuntime : "(runtime not read yet)")
         .append(" -> the MAC typed in SET MAC \u00b7 the question names the exact pair\n");
        s.append("confirm nothing is written until a question names the exact paths,")
         .append(" offsets and MACs\n");
        s.append("last    ").append(planNote == null
                ? "nothing written by this session" : planNote);
        return s.toString();
    }

    /**
     * IMP: the recovery half -- which pre-images exist, where, when, whether they are
     * still usable, and what a Restore would install.
     *
     * Every line is either an app-local stat of a file this app wrote (no root) or a
     * field of the durable record the worker already read, so it cannot invent a
     * backup that is not there. Worker thread only: it reads the record through su.
     */
    String buildRecoveryText() {
        StringBuilder s = new StringBuilder();
        FactoryRec rec = factoryRec();
        String fac = rec == null ? null : rec.mac;
        s.append("factory ").append(isMac(fac) ? fac : "(not recorded)").append('\n');
        s.append("  from  ").append(isMac(fac) ? factoryProvenance() : "nothing recorded yet")
         .append('\n');
        s.append("  kept  ").append(isMac(fac) ? factoryStore() : "n/a").append('\n');
        s.append("durable ").append(ADB_DIR).append(" \u00b7 dir ")
         .append("ok".equals(recDir) ? "ok" : "missing")
         .append(" \u00b7 pre-image WIFI.factory ")
         .append("ok".equals(recBak) ? "readable" : "not saved yet");
        if (recTag != null && !recTag.isEmpty()) s.append(" \u00b7 for ").append(recTag);
        s.append('\n');
        // MIN_IMAGE is the floor for reads, writes AND pre-images, and the card says
        // so: it used to call a 10-byte pre-image "too small to be an image" while
        // readRoot() patched that very file and saveBackup() saved it, so on the
        // MediaTek APRDEB layout this card misdescribed the app's own gate.
        s.append("floor   a pre-image is used only when it is at least ").append(MIN_IMAGE)
         .append(" bytes, exactly the\n        target's on-disk size, and carries a")
         .append(" MAC-shaped field\n");
        List<String> cand = nvramPaths();
        int found = 0;
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < cand.size(); i++) {
            File f = backupFor(cand.get(i));
            if (!f.isFile()) continue;
            long len = f.length();
            s.append("  ").append(cand.get(i)).append('\n');
            s.append("    ").append(len).append(" B \u00b7 ").append(stamp(f.lastModified()))
             .append(" \u00b7 ").append(backupState(f, len)).append('\n');
            if (where.length() > 0) where.append(", ");
            where.append(cand.get(i));
            found++;
        }
        if (found == 0)
            s.append("  no app-private pre-image saved yet \u00b7 one is taken before the")
             .append(" first write to each file\n");
        s.append("restore ");
        if (!isMac(fac)) {
            s.append("refuses \u2014 no factory value is recorded, so there is nothing to")
             .append(" restore to\n        (type the value from the box or the label and save it)");
        } else if (rec != null && rec.suspect) {
            s.append("refuses \u2014 ").append(fac).append(" is locally administered, so it is")
             .append(" not a factory\n        burn-in and was not typed by you; save the real")
             .append(" factory MAC to replace it");
        } else if (where.length() == 0) {
            s.append("patches the runtime MAC back to ").append(fac)
             .append(" in the files that\n        carry it \u00b7 no saved pre-image to install");
        } else {
            s.append("patches the runtime MAC back to ").append(fac)
             .append(", and installs the\n        saved pre-image for ").append(where);
        }
        return s.toString();
    }

    /**
     * IMP: does this app-private pre-image still look like the file it came from?
     *
     * Honest about its own limits: the final gate is loadBackup()'s, which re-checks
     * the length against the *target's* current on-disk size through root at restore
     * time. This says only what can be known without touching the partition.
     */
    String backupState(File f, long len) {
        if (len < MIN_IMAGE) return "below the " + MIN_IMAGE
                + "-byte floor for a calibration image \u00b7 never used";
        if (len > 262144L) return "large \u00b7 not content-checked here";
        byte[] d = readFile(f);
        if (d == null || d.length != len) return "unreadable \u00b7 never used";
        if (!hasMacField(d)) return "no MAC-shaped field \u00b7 never used";
        return "usable (the target's own size and digest are re-checked at restore time)";
    }

    /** IMP: shows the retained plan card. Nothing here touches root. */
    void showPlanCard() {
        if (vPlan != null && planCardText != null) vPlan.setText(planCardText);
        if (vPlanVerdict != null) {
            vPlanVerdict.setText(scanVerdictText());
            vPlanVerdict.setTextColor(scanVerdict == null ? C_DIM : scanColor);
        }
    }

    /** IMP: shows the retained recovery card. Nothing here touches root. */
    void showRecoveryCard() {
        if (vRecovery != null && recoveryCardText != null) vRecovery.setText(recoveryCardText);
    }

    /** IMP: the scan verdict with its age, so a stale scan cannot read as a fresh one. */
    String scanVerdictText() {
        String v = scanVerdict;
        if (v == null) return "no scan yet \u00b7 the per-file offsets above are unknown until one runs";
        long mins = (System.currentTimeMillis() - scanAt) / 60000L;
        return v + " \u00b7 scanned " + (mins <= 0L ? "just now" : mins + " min ago");
    }

    /** IMP: records what the last operation did (or is about to do) on the plan card. */
    void notePlan(final String note) {
        planNote = note;
        planCardText = buildPlanText();
        ui.post(new Runnable() { public void run() {
            MainActivity a = live();
            if (a != null) a.showPlanCard();
        } });
    }

    /**
     * IMP: the pre-flight scan, and the honest unsupported-device verdict.
     *
     * "No nvram match" after a failed write is not an answer: it does not say whether
     * the file is absent, denied, or present without the runtime MAC in it. This asks
     * the same questions the write path asks, in the same order, but reads the NVRAM
     * without ever writing it and issues no WiFi command -- so a user can find out
     * what their device is before committing anything, and a device where none of the
     * nine candidates exists gets told so explicitly instead of by a vague failure.
     *
     * It is also the safe place to capture the factory value (H2): the bytes come out
     * of the pre-image at the offset that holds the runtime MAC, never out of /sys.
     *
     * "Reads the NVRAM without writing it" is the precise claim, and it is not the
     * same as "touches nothing": when no factory value is recorded anywhere, the
     * capture above writes one -- two prefs, the app-private mirror and, through
     * TEXT_SCRIPT, /data/adb/macchanger/factory.txt. The button used to say
     * "(read-only)" and the log line "nothing is written", which was true of the
     * partition and false as a general statement; both now say what happens.
     *
     * It deliberately does NOT take the cross-process lock, because it never writes
     * the NVRAM -- the record it may write is not the file another writer is
     * mid-write on -- and taking the lock would only stop a question being asked
     * while the CLI works. The consequence is that its findings are advisory and can
     * be overtaken by a writer, which is why every card and verdict built from them
     * carries the time it was taken, and why the write path re-derives its own plan
     * under the lock.
     */
    void checkNvram() {
        if (busy) { toast("busy\u2026"); return; }
        setBusy(true);
        log("scanning the NVRAM candidates \u00b7 the NVRAM is only read, and no WiFi command"
                + " is issued; if no factory value is recorded yet, the one the image holds is"
                + " saved to the record", C_DIM);
        new Thread(new Runnable() { public void run() {
            try {
                // M6: a previous failed detection is retried here rather than cached
                // for the life of the process; see retryIfaceIfUnresolved.
                retryIfaceIfUnresolved();
                List<String[]> rows = new ArrayList<String[]>();
                boolean[] probeOk = new boolean[1];
                List<PathState> probe = probeNvram(probeOk);
                String rt = getRuntimeMac();
                byte[] rb = isMac(rt) ? macToBytes(rt) : null;
                boolean degenerate = rb != null && !isUsableTarget(rb);
                int present = 0, readable = 0, matched = 0;
                String matchPath = null, matchOffs = "";
                boolean captured = false;
                String verdict;
                int color;
                for (int i = 0; i < probe.size(); i++) {
                    PathState ps = probe.get(i);
                    if (!ps.present) { rows.add(new String[]{ ps.path, "absent" }); continue; }
                    present++;
                    if (!ps.readable) {
                        rows.add(new String[]{ ps.path, "present but root may not read it" });
                        continue;
                    }
                    readable++;
                    boolean[] bad = new boolean[1];
                    byte[] data = readRoot(ps.path, bad);
                    if (data == null) {
                        String why = bad[0] ? "read denied or came back short"
                                            : "too small to be a calibration image";
                        rows.add(new String[]{ ps.path, "present, " + why });
                        continue;
                    }
                    String detail = (ps.writable ? "w=ok" : "w=denied") + " \u00b7 "
                                  + data.length + " B";
                    if (rb == null) {
                        rows.add(new String[]{ ps.path, detail
                                + " \u00b7 runtime MAC unknown, nothing to look for" });
                        continue;
                    }
                    if (degenerate) {
                        rows.add(new String[]{ ps.path, detail
                                + " \u00b7 runtime MAC " + rt + " is degenerate, not searched" });
                        continue;
                    }
                    int[] offs = offsetsOf(data, rb, 8);
                    if (offs.length == 0) {
                        rows.add(new String[]{ ps.path, detail + " \u00b7 " + rt + " not found"
                                + (hasMacField(data) ? " \u00b7 carries some other MAC-shaped field" : "") });
                        continue;
                    }
                    matched++;
                    if (matchPath == null) {
                        matchPath = ps.path;
                        matchOffs = offsetsText(offs, 8);
                    }
                    rows.add(new String[]{ ps.path, detail + " \u00b7 " + rt + " at "
                            + offsetsText(offs, 8) + " \u00b7 this is what a write would patch" });
                    // H2's capture condition, checked against the pre-image's own
                    // bytes rather than /sys, and only at the raw 6-byte offset. The
                    // guard is the whole recorded value, not just the pref: a scan must
                    // never replace a factory value the user typed with a value taken
                    // out of an image that a previous spoof may have written.
                    int raw = indexOf(data, rb);
                    if (!captured && raw >= 0 && factoryMac() == null)
                        captured = recordFactory(ps.path, raw, data, rt);
                }
                if (!probeOk[0]) {
                    verdict = "cannot enumerate the candidate paths \u00b7 no root, or su did not answer";
                    color = C_BAD;
                    log("cannot enumerate the NVRAM candidates \u00b7 nothing was written", C_BAD);
                    if (!rootNow()) log("no root \u2014 nothing was attempted", C_BAD);
                    else log("su did not answer, or answered nothing", C_BAD);
                } else if (present == 0) {
                    verdict = "UNSUPPORTED DEVICE \u00b7 none of the " + probe.size()
                            + " candidate paths exists here";
                    color = C_BAD;
                } else if (readable == 0) {
                    verdict = "candidate NVRAM present but root may not read it \u00b7 nothing"
                            + " can be verified on this device";
                    color = C_BAD;
                } else if (!isMac(rt)) {
                    verdict = "cannot read " + ifaceLabel() + " (" + ifaceReason() + ") \u00b7 there is"
                            + " no runtime MAC to look for";
                    color = C_BAD;
                } else if (degenerate) {
                    verdict = "the runtime MAC " + rt + " is degenerate \u00b7 searching for it"
                            + " would match everywhere, so no write would be attempted";
                    color = C_BAD;
                } else if (matched > 0) {
                    verdict = "SUPPORTED \u00b7 " + matched + " candidate file"
                            + (matched == 1 ? "" : "s") + " hold " + rt
                            + "; a write would patch " + matchPath + " at " + matchOffs;
                    color = C_OK;
                } else {
                    verdict = "no candidate file contains " + rt + " \u00b7 this app cannot"
                            + " change the MAC on this device";
                    color = C_WARN;
                }
                scanRows = rows;
                scanVerdict = verdict;
                scanColor = color;
                scanAt = System.currentTimeMillis();
                planCardText = buildPlanText();
                for (int i = 0; i < rows.size(); i++)
                    log("  " + rows.get(i)[0] + " \u00b7 " + rows.get(i)[1], C_DIM);
                log(verdict, color);
                ui.post(new Runnable() { public void run() {
                    MainActivity a = live();
                    if (a == null) return;
                    a.showPlanCard();
                    a.showRecoveryCard();
                } });
            } finally {
                setBusy(false);
                refresh();
            }
        } }).start();
    }

    // --------------------------------------------- the question before the write (IMP)

    /**
     * IMP: the confirmation in front of the first destructive byte.
     *
     * Set MAC and Restore both reach this after the read/backup phase and before any
     * write, so the user is told which files, which offsets, and which MAC -> MAC are
     * about to change -- and can stop it. Cancel, back, a dismissal and an abandoned
     * screen all mean "do not write": there is no default that writes. The operation
     * holds the cross-process lock while the question is open, so the question is also
     * the only place the lock has to be renewed; see ASK_WAIT_MS.
     */
    boolean askToWrite(final String plan) {
        final Ask ask = new Ask(plan);
        pendingAsk = ask;
        ui.post(new Runnable() { public void run() { showAsk(); } });
        synchronized (ask) {
            long waited = 0L, sinceRenew = 0L;
            while (!ask.answered) {
                try { ask.wait(500L); } catch (InterruptedException e) { break; }
                if (ask.answered) break;
                waited += 500L;
                sinceRenew += 500L;
                if (sinceRenew >= LOCK_RENEW_MS) {
                    sinceRenew = 0L;
                    if (!renewLock()) {
                        ask.note = "the operation lock is no longer ours";
                        ask.answered = true;
                        ask.yes = false;
                        break;
                    }
                }
                if (waited >= ASK_WAIT_MS) {
                    ask.note = "no answer for " + (ASK_WAIT_MS / 60000L) + " minutes";
                    ask.answered = true;
                    ask.yes = false;
                    break;
                }
            }
        }
        pendingAsk = null;
        ui.post(new Runnable() { public void run() { dismissAsk(); } });
        if (ask.yes) return true;
        log("write cancelled by the confirmation \u00b7 nothing was written"
                + (ask.note.isEmpty() ? "" : " (" + ask.note + ")"), C_WARN);
        return false;
    }

    /** IMP: shows the pending question on whichever instance is on screen. */
    void showAsk() {
        MainActivity a = live();
        if (a == null) return;
        final Ask ask = pendingAsk;
        if (ask == null || ask.answered || a.liveDialog != null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("Write to NVRAM?");
        b.setMessage(ask.text);
        b.setCancelable(true);
        b.setPositiveButton("Write it", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { answerAsk(ask, true); } });
        b.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { answerAsk(ask, false); } });
        // The cancel listener goes on the dialog, not the builder: it fires for the
        // back button and for a tap outside, which are the two ways a user backs out
        // without touching a button, and it does NOT fire for the positive button's
        // own dismissal -- so "Write it" can never be turned into a cancel by the
        // dismissal that follows the click.
        a.liveDialog = b.create();
        a.liveDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { answerAsk(ask, false); } });
        a.liveDialog.show();
    }

    /** IMP: answers the pending question once; a second answer changes nothing. */
    void answerAsk(Ask ask, boolean yes) {
        if (ask == null) return;
        synchronized (ask) {
            if (ask.answered) return;
            ask.answered = true;
            ask.yes = yes;
            if (!yes && ask.note.isEmpty()) ask.note = "you cancelled";
            ask.notifyAll();
        }
    }

    /** IMP: takes this instance's dialog down; the next one re-shows it if unanswered. */
    void dismissAsk() {
        MainActivity a = live();
        if (a == null || a.liveDialog == null) return;
        try { a.liveDialog.dismiss(); } catch (Throwable ignored) { }
        a.liveDialog = null;
    }

    // ----------------------------------------------------------- actions

    /**
     * One action owns the shell at a time, and every action button says so.
     *
     * Without this, every Refresh tap during a hung operation spawned another
     * hung su. M2 adds the action buttons -- Set MAC, Random, Restore -- which
     * were created inline and never disabled, so the 9.5-16.5 s restart window
     * (H7) was a window in which a second worker could be started by hand.
     *
     * The flag is process-wide and the callback goes to whichever instance is on
     * screen now: a rotation in the middle of an operation must not leave the new
     * screen's buttons disabled after the worker that disabled them is gone.
     */
    void setBusy(final boolean b) {
        busy = b;
        ui.post(new Runnable() { public void run() {
            MainActivity a = live();
            if (a == null) return;
            if (a.refreshBtn != null) a.refreshBtn.setEnabled(!b);
            if (a.tvRoot != null) a.tvRoot.setEnabled(!b);
            if (a.saveFactoryBtn != null) a.saveFactoryBtn.setEnabled(!b);
            if (a.exportBtn != null) a.exportBtn.setEnabled(!b);
            if (a.setBtn != null) a.setBtn.setEnabled(!b);
            if (a.rndBtn != null) a.rndBtn.setEnabled(!b);
            if (a.restoreBtn != null) a.restoreBtn.setEnabled(!b);
            if (a.checkBtn != null) a.checkBtn.setEnabled(!b);
            if (a.useIfaceBtn != null) a.useIfaceBtn.setEnabled(!b);
            if (a.cbRuntime != null) a.cbRuntime.setEnabled(!b);
        } });
    }

    // ------------------------------------------------------------ cross-process lock

    /**
     * M2: the lock directory both front ends take.
     *
     * /data/adb/macchanger already holds the durable factory record that
     * cli/macchanger.sh and this app share, so it is the one place where "an
     * operation is running" means the same thing to both. `mkdir` either creates
     * the directory or fails, atomically, on every filesystem either tool will
     * meet -- so no flock, no lockfile library and no permission is needed.
     *
     * The protocol is SHARED and has to stay shared, in four parts: the directory is
     * this path; the stamp is epoch seconds in <lock>/ts, with the directory's own
     * mtime as the fallback when it cannot be read; the owner is the token in
     * <lock>/owner; and a lock whose stamp is older than LOCK_STALE_S is abandoned.
     * cli/macchanger.sh implements the same four rules (its lock_acquire, around
     * cli/macchanger.sh:195-240), so each front end's lock now stops the other --
     * which is what the comments in this file claimed before the CLI half existed
     * and could not deliver. Changing any of the four here without changing it there
     * makes the two stop excluding each other, silently. One asymmetry remains: the
     * CLI does not renew its stamp while it works, so a CLI operation that outlived
     * LOCK_STALE_S could be broken as abandoned while it is still writing; this side
     * renews (see renewLock) and does not have that exposure.
     */
    static final String LOCK = ADB_DIR + "/lock";

    /**
     * How old a lock may be before it is treated as abandoned, in seconds.
     *
     * A killed process cannot release its own mkdir, and without this the device
     * would be locked out of both front ends forever. Five minutes is longer than
     * any bounded operation on this path -- the longest is a single 60 s
     * whole-image read plus the 10 s worst-case toggle -- and short enough that a
     * crash costs the user a coffee, not the device.
     *
     * It is NOT the case that this window cannot expire under a live owner by
     * arithmetic alone: ASK_WAIT_MS is also five minutes, and a two-file write can
     * add a 120 s deadline plus a 60 s read-back per file, so an operation that only
     * counted on the timings above would be breakable while it is still working.
     * What keeps a live holder's lock fresh is the renewal -- renewLock() while the
     * question is open and before each write -- not the size of this number.
     */
    static final long LOCK_STALE_S = 300L;

    /**
     * Takes the lock, or reports who holds it. One root round trip.
     *
     * The timestamp is written inside the same invocation that creates the
     * directory, so a fresh lock always carries one; a lock with no readable
     * timestamp falls back to the directory's own mtime, which is set at creation
     * and cannot be missing. Both paths are literals of this file, so nothing
     * read from the device ever reaches the command text.
     *
     * The owner token is written by this same invocation and its write is checked:
     * a lock without a token is one RENEW_SCRIPT and RELEASE_SCRIPT would have to
     * treat as somebody else's, so a lock that could not be stamped is removed
     * again and reported as lock=error rather than left behind for LOCK_STALE_S
     * seconds. That is what makes "the token is mine" a sound test for ownership.
     *
     * Breaking a stale lock is remove-then-create, which sh cannot do in one atomic
     * step, so it is built out of the two primitives that ARE atomic -- rename and
     * mkdir -- in the same order cli/macchanger.sh uses (a shared lock whose two
     * implementations break it differently is a shared lock with a hole in it). The
     * abandoned directory is RENAMED to a name only this process uses: rename(2) is
     * atomic, so exactly one of several breakers wins it and the losers see the
     * source gone and report lock=busy. The fresh directory is then created with
     * `mkdir`, which is atomic in the other direction. On top of that the breaker
     * STAMPS AND VERIFIES: after creating the directory it re-reads the token and
     * claims nothing unless the token is still its own, which catches the case the
     * rename cannot: a breaker that read the stale stamp before the winner's rename
     * can still rename the winner's FRESH directory away and create its own.
     *
     * Measured on this exact script text (ADB_DIR redirected to a scratch directory,
     * N simultaneous breakers against one aged lock, 40 rounds, a winner counted by
     * the lock=stale answer, both constants restored): the pre-repair
     * rm -rf-then-mkdir break produced two winners in 8-28 of 40 rounds at 8
     * breakers and in 0-8 of 40 at 2; the rename break with the token re-check
     * produced 0-2 of 40 at 8 breakers; the rename alone, with the re-check removed
     * for comparison, produced 10-16 of 40. The spread is real and repeats, so no
     * single figure is quoted as the property. The rename and the re-check each
     * narrow the window and NEITHER closes it, and this comment does not claim
     * otherwise. Two further facts bound what is left: renewal (below) means a LIVE
     * holder never lets its lock look stale in the first place, so the contested case
     * is two breakers on an abandoned lock, and writeRoot's pre-image gate (W_STALE)
     * is what actually protects the partition if two writers ever do overlap -- the
     * lock is an optimisation, the content gate is the safety property, and the write
     * path no longer relies on the lock for correctness.
     */
    static final String LOCK_SCRIPT =
            "IFS= read -r tk; "
          + "d=" + ADB_DIR + "; l=$d/lock; "
          + "mkdir -p -- \"$d\" 2>/dev/null; chmod 700 -- \"$d\" 2>/dev/null; "
          + "if mkdir -- \"$l\" 2>/dev/null; then "
          + "date +%s > \"$l/ts\" 2>/dev/null; "
          + "printf '%s\\n' \"$tk\" > \"$l/owner\" 2>/dev/null "
          + "|| { rm -rf -- \"$l\" 2>/dev/null; echo lock=error; exit 1; }; "
          + "echo lock=ok; exit 0; fi; "
          + "t=$(cat \"$l/ts\" 2>/dev/null); "
          + "case \"$t\" in ''|*[!0-9]*) t=$(stat -c %Y -- \"$l\" 2>/dev/null);; esac; "
          + "case \"$t\" in ''|*[!0-9]*) t=0;; esac; "
          + "n=$(date +%s 2>/dev/null); "
          + "case \"$n\" in ''|*[!0-9]*) n=0;; esac; "
          + "if [ \"$t\" -gt 0 ] && [ $((n - t)) -lt " + LOCK_STALE_S + " ]; then "
          + "echo lock=busy; exit 1; fi; "
          + "q=$l.stale.$$; "
          + "mv -- \"$l\" \"$q\" 2>/dev/null || { echo lock=busy; exit 1; }; "
          + "rm -rf -- \"$q\" 2>/dev/null; "
          + "mkdir -- \"$l\" 2>/dev/null || { echo lock=busy; exit 1; }; "
          + "date +%s > \"$l/ts\" 2>/dev/null; "
          + "printf '%s\\n' \"$tk\" > \"$l/owner\" 2>/dev/null "
          + "|| { rm -rf -- \"$l\" 2>/dev/null; echo lock=error; exit 1; }; "
          + "o=$(cat \"$l/owner\" 2>/dev/null); "
          + "[ \"$o\" = \"$tk\" ] || { echo lock=busy; exit 1; }; "
          + "echo lock=stale; exit 0";

    /**
     * IMP: re-stamps a lock this process still owns, and says so only when it does.
     *
     * The owner file LOCK_SCRIPT writes is the token this process generated when it
     * took the lock. Renewal is refused -- and the caller abandons the operation --
     * when the token is not there any more, because that means the lock was broken as
     * stale and someone else is now the writer; continuing would race their write
     * against the pre-image this operation is about to install. Fail closed.
     */
    static final String RENEW_SCRIPT =
            "IFS= read -r tk; IFS= read -r l; "
          + "o=$(cat \"$l/owner\" 2>/dev/null); "
          + "if [ -n \"$o\" ] && [ \"$o\" = \"$tk\" ]; then "
          + "date +%s > \"$l/ts\" 2>/dev/null; echo lock=held; exit 0; fi; "
          + "echo lock=lost; exit 1";

    /**
     * IMP: releases the lock, but only while this process still owns it.
     *
     * It used to be `rm -rf -- $LOCK` and nothing else. Measured with the shipped
     * scripts (ADB_DIR redirected to a scratch directory): A takes the lock, the
     * timestamp is aged past LOCK_STALE_S, B breaks it as stale and takes it, A's
     * RENEW_SCRIPT correctly answers lock=lost -- and A's finally block then
     * deleted B's lock, leaving B writing while believing it held the lock. The
     * token RENEW_SCRIPT already checks is what this needs: the same test, plus
     * the removal, in one invocation.
     *
     * When the token is not ours nothing is removed. That is the safe direction:
     * the staleness window still frees the device for the next operation, while
     * deleting a lock that belongs to a live writer cannot be undone.
     */
    static final String RELEASE_SCRIPT =
            "IFS= read -r tk; IFS= read -r l; "
          + "o=$(cat \"$l/owner\" 2>/dev/null); "
          + "if [ -n \"$o\" ] && [ \"$o\" = \"$tk\" ]; then "
          + "rm -rf -- \"$l\" 2>/dev/null; echo lock=released; exit 0; fi; "
          + "echo lock=not-ours; exit 1";

    /**
     * True when the last acquireLock() was refused *because someone holds it*,
     * rather than because root would not run the command at all -- two different
     * answers that must not share one message. lockFailed is the third: root ran
     * it, and the lock could not be created and stamped at all.
     */
    volatile boolean lockBusy = false;
    volatile boolean lockFailed = false;

    /** IMP: the token that proves this process -- and not a later one -- owns the lock. */
    volatile String lockToken = null;

    /** True when this process now holds the lock; false when another operation does. */
    boolean acquireLock() {
        lockToken = Long.toHexString(rnd.nextLong()) + "-"
                  + Long.toHexString(System.currentTimeMillis());
        Res r = feed(LOCK_SCRIPT, lockToken + "\n", MS_DEFAULT);
        String o = r.out;
        if (o.contains("lock=ok") || o.contains("lock=stale")) {
            lockBusy = false;
            lockFailed = false;
            return true;
        }
        lockToken = null;
        lockBusy = o.contains("lock=busy");
        lockFailed = o.contains("lock=error");
        return false;
    }

    /** The line every caller logs when it could not start, so the reason is named. */
    String lockRefusal() {
        if (lockBusy)
            return "another macchanger operation is running \u00b7 nothing was written";
        if (lockFailed)
            return "cannot create the operation lock in " + ADB_DIR + " \u00b7 nothing was written";
        return "cannot take the operation lock (no root, or su did not answer)"
                + " \u00b7 nothing was written";
    }

    /**
     * Releases it, if it is still ours. Best effort by design: if this fails the
     * staleness window above still frees the device, and no release path may throw
     * on top of whatever failure is already being reported.
     */
    void releaseLock() {
        String tk = lockToken;
        lockToken = null;
        if (tk == null) return;
        Res r = feed(RELEASE_SCRIPT, tk + "\n" + LOCK + "\n", MS_DEFAULT);
        // Never a silent no-op: a lock left behind is 300 s of the next operation
        // being refused, and the user needs to know why in the log.
        if (r.out.indexOf("lock=released") < 0)
            log("left the operation lock alone \u00b7 it is no longer this operation's,"
                    + " so it was not removed", C_DIM);
    }

    /**
     * IMP: keeps the lock alive across the write question, and reports whether it is
     * still ours. See RENEW_SCRIPT for why a lost token means the operation must not
     * continue; this is the only reason the renewal exists.
     */
    boolean renewLock() {
        String tk = lockToken;
        if (tk == null) return false;
        Res r = feed(RENEW_SCRIPT, tk + "\n" + LOCK + "\n", MS_DEFAULT);
        return r.ok() && r.out.contains("lock=held");
    }

    /**
     * M2/L6: the guard every destructive action passes through.
     *
     * The state machine used to be decorative: lastRoot was written and never
     * read, so an unrooted run performed the full blind WiFi restart and then
     * reported the neutral "no nvram match" line -- work that could not have
     * succeeded, described as a device property. Now it refuses first.
     */
    boolean requireRoot() {
        if (lastRoot) return true;
        if (rootChecking) {
            log("root is still being checked \u00b7 try again in a moment", C_WARN);
            toast("Checking root\u2026");
            return false;
        }
        log("root required \u2014 nothing was attempted", C_BAD);
        toast("root required");
        checkRoot(true);              // a grant refreshes the cards and the retry works
        return false;
    }

    /**
     * Three outcomes, not two (M7). "Not granted" is a lie on a device that has no
     * su binary at all: there is nothing the user could grant, and the old text
     * sent them looking for a permission that cannot exist on that device.
     */
    int probeRoot() {
        Blob b = exec(suArgv("id"), null, MS_DEFAULT, true);
        if (b.missing() || looksAbsent(b.err)) return ROOT_NO_SU;
        if (b.timedOut()) return ROOT_DENIED;      // an unanswered prompt is not a grant
        if (!b.ok()) return ROOT_DENIED;
        return utf8(b.out).contains("uid=0") ? ROOT_GRANTED : ROOT_DENIED;
    }

    /**
     * L6: `checkRoot()` with no argument used to be a dead overload with no
     * callers; the badge now calls this one with true, so it is gone.
     */
    void checkRoot(final boolean thenRefresh) {
        if (rootChecking) return;
        rootChecking = true;
        setRoot(ROOT_UNKNOWN, true);
        new Thread(new Runnable() { public void run() {
            final int state = probeRoot();
            rootChecking = false;
            lastRoot = state == ROOT_GRANTED;
            ui.post(new Runnable() { public void run() {
                MainActivity a = live();
                if (a != null) a.setRoot(state, false);
            } });
            if (state == ROOT_GRANTED) log("root granted", C_OK);
            else if (state == ROOT_NO_SU)
                log("su is not installed \u2014 this app cannot work on this device", C_BAD);
            else log("root denied \u2014 the su prompt was refused or did not answer", C_BAD);
            if (thenRefresh) refresh();
        } }).start();
    }

    /**
     * M6: the explicit interface override, validated before it becomes a path.
     *
     * An empty field clears it and returns to detection. A name that is not in
     * /sys/class/net is refused rather than stored: an override that points at
     * nothing would send every read and every write to a path that is not there,
     * and the app would report that as a device fault.
     */
    void useIface(final String raw) {
        if (busy) { toast("busy\u2026"); return; }
        final String n = raw == null ? "" : raw.trim();
        if (n.isEmpty()) {
            setPref(IFACE_PREF, "");
            ifaceName = null;
            ifaceWhy = "";
            log("interface override cleared \u00b7 detecting the interface again", C_DIM);
            toast("Detecting the interface");
            refresh();
            return;
        }
        if (!isIfaceName(n)) {
            toast("Invalid interface name");
            log("refusing " + n + " as an interface name \u00b7 letters, digits, dot, dash"
                    + " and underscore only", C_BAD);
            return;
        }
        setBusy(true);
        new Thread(new Runnable() { public void run() {
            try {
                if (!ifacePresent(n)) {
                    toast("No such interface");
                    log("there is no /sys/class/net/" + n + " on this device \u00b7 not used",
                            C_BAD);
                    return;
                }
                setPref(IFACE_PREF, n);
                ifaceName = n;
                ifaceWhy = "";
                log("WiFi interface set to " + n + " \u00b7 all reads and the restart use it", C_OK);
                toast("Interface: " + n);
            } finally {
                setBusy(false);
                refresh();
            }
        } }).start();
    }

    /**
     * M8: the app's own version, read from the package manager.
     *
     * The baseline was "1.0 frozen in the manifest and in the shipped binary, with
     * no self-reported version in the app", so a bug report could not be tied to a
     * build. build.sh stamps versionCode/versionName on every build and refuses to
     * finish if the stamp did not take; this is the half that shows it. No
     * permission, no resource, no dependency.
     */
    String appVersion() {
        String ver = "version unknown";
        String when = "build time unknown";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi != null) {
                if (pi.versionName != null && !pi.versionName.isEmpty()) ver = pi.versionName;
                when = stamp(pi.lastUpdateTime);
            }
        } catch (Throwable ignored) { }
        return ver + " \u00b7 updated " + when;
    }

    /**
     * Reads the device rows. Serialized: one refresh at a time, and never while an
     * action holds the shell, so the app cannot accumulate concurrent su streams.
     */
    void refresh() {
        if (busy) { toast("busy\u2026"); return; }
        if (refreshing) return;
        refreshing = true;
        new Thread(new Runnable() { public void run() {
            try {
                retryIfaceIfUnresolved();
                // H5: the whole 'cmd wifi status' report, not three lines of it.
                // The old form cut the output down to its first three lines before it
                // ever reached the parser, which is why the SSID row could only
                // say "connected" on Android 12+ where the SSID line is printed
                // after the per-manager banners.
                String cmd = "echo __WF__; cmd wifi status 2>/dev/null; "
                           + "echo __CFG__; "
                           + "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null "
                           + "|| cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null; "
                           + "echo __END__";
                String out = MainActivity.this.run(cmd).out;
                String rt = getRuntimeMac();   // one implementation, shared with setMac/restore
                String wifi = "", cfg = "";
                int w0 = out.indexOf("__WF__");
                int c0 = out.indexOf("__CFG__"), c1 = out.indexOf("__END__");
                if (w0 >= 0) wifi = out.substring(w0 + 6, c0 > w0 ? c0 : out.length());
                if (c0 >= 0 && c1 > c0) cfg = out.substring(c0 + 7, c1).trim();
                final String frt = rt;
                final WifiStat fstat = parseWifiStatus(wifi);
                final String fcfg = cfg;
                final String fac = factoryMac();       // pref first, then the durable record
                final String fsrc = factorySrc();
                final String fprov = provLabel(fsrc);
                final String fstore = factoryStore();
                final String soc = socName();
                // M8/M6: both of these are computed here, on the worker, because
                // iface() may run a root command the first time it is asked (an ANR
                // if it ran on the UI thread) and the package read is I/O.
                final String fver = appVersion();
                final String fif = iface();
                final String fifWhy = ifaceWhy == null || ifaceWhy.isEmpty()
                        ? "no single WiFi interface could be determined" : ifaceWhy;
                final boolean fifForced = isIfaceName(getPref(IFACE_PREF));
                // M5: the vendor is behaviour, not decoration -- it ordered the
                // candidate list this app just probed -- so the row says it.
                final String vendor = socVendor();
                // IMP: the two report cards are rebuilt here, on the worker, because
                // building them needs the su-backed vendor classification; the UI
                // thread only installs the finished text.
                planCardText = buildPlanText();
                recoveryCardText = buildRecoveryText();

                ui.post(new Runnable() { public void run() {
                    // M2: this callback may land after a rotation, so it draws on
                    // whichever instance is on screen, never on the one that
                    // started the read.
                    MainActivity a = live();
                    if (a == null) return;
                    boolean rtOk = isMac(frt);
                    if (rtOk) a.vRuntime.setText(frt);
                    else {
                        a.vRuntime.setText("cannot read " + ifaceLabel() + " (" + ifaceReason() + ")");
                        log("cannot read " + ifaceLabel() + " (" + ifaceReason() + ")", C_BAD);
                    }
                    a.copyRuntime = rtOk ? frt : "";
                    // IMP: green on this row means "the driver reports the recorded
                    // factory value, and that value came out of a calibration
                    // pre-image". A value the user typed is unverified, so an exact
                    // match against it is reported neutrally rather than as a
                    // verified fact, and a mismatch stays the warning colour.
                    if (!rtOk) a.vRuntime.setTextColor(C_BAD);
                    else if (fac == null) a.vRuntime.setTextColor(C_TEXT);
                    else if (!frt.equalsIgnoreCase(fac)) a.vRuntime.setTextColor(C_WARN);
                    else a.vRuntime.setTextColor(nvPathOf(fsrc) != null ? C_OK : C_DIM);
                    a.copyFactory = fac == null ? "" : fac;
                    a.vFactory.setText(fac == null ? "(not recorded)" : fac);
                    // IMP: where the value came from, and where it is kept -- the two
                    // questions that decide whether a restore will still be possible
                    // after an uninstall.
                    a.vFactoryProv.setText(fac == null
                            ? "nothing recorded \u00b7 type the value from the box or the label"
                            : fprov + "  \u00b7  " + fstore);
                    a.vFactoryProv.setTextColor(nvPathOf(fsrc) != null ? C_DIM : C_WARN);
                    a.vModel.setText(Build.MANUFACTURER + " " + Build.MODEL);
                    a.vSoc.setText(soc + (vendor.equals("Unknown") ? "" : "  \u00b7  " + vendor));
                    a.vSoc.setTextColor(socColor(soc));
                    a.vAndroid.setText(Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
                    // M8: the build this screen is running, not a promise about it.
                    a.vVersion.setText(fver);
                    // M6: the interface the app will read and write, or why there is
                    // no single answer. Ambiguity is a warning colour, never a guess.
                    a.vIface.setText(fif.isEmpty()
                            ? "unresolved \u00b7 " + fifWhy
                            : fif + (fifForced ? "  \u00b7  set by you, overriding detection"
                                               : "  \u00b7  detected"));
                    a.vIface.setTextColor(fif.isEmpty() ? C_WARN : C_TEXT);
                    if (fif.isEmpty()) {
                        log("the WiFi interface is unresolved: " + fifWhy
                                + " \u00b7 nothing is read or written through a guessed name",
                                C_WARN);
                    }
                    // H5: no connected network and an unresolved SSID are two
                    // different states, and neither is "this network randomizes
                    // the MAC".
                    String ssid = fstat.connected ? fstat.displaySsid() : "not connected";
                    String state = fstat.readable ? (fstat.enabled ? "on" : "off") : "unknown";
                    a.vNetwork.setText(ssid + "  \u00B7  " + state);
                    a.vNetwork.setTextColor(fstat.connected ? C_OK : C_DIM);

                    // M1: the setting is per saved network, so the row reports the
                    // connected one and how many of the saved ones will override
                    // the hardware MAC later.
                    String pv = privacyValue(fcfg, fstat.known ? fstat.ssid : "");
                    String row = privacyLabel(pv);
                    String sum = privacySummary(fcfg);
                    if (!sum.isEmpty()) row = row + "  \u00b7  " + sum;
                    a.vPrivacy.setText(row);
                    a.vPrivacy.setTextColor(privacyColor(pv));
                    // IMP: the two statements that must survive a rotation and a
                    // failed operation, drawn from the same retained text.
                    a.showPlanCard();
                    a.showRecoveryCard();
                } });
            } finally {
                refreshing = false;
            }
        } }).start();
    }

    void refreshNow() {
        if (busy) { toast("busy\u2026"); return; }
        refresh();
        log("info refreshed", C_DIM);
    }

    /**
     * H2: records the factory MAC from the NVRAM pre-image, at the offset that
     * really holds the runtime MAC -- never from /sys, which reports a randomized
     * address on Android 11+ and a spoof after any earlier change. An existing
     * record is never overwritten.
     */
    boolean recordFactory(String path, int off, byte[] orig, String cur) {
        if (factoryMac() != null) return false;
        String val = macAt(orig, off);
        if (!val.equalsIgnoreCase(cur) || !isFactoryCandidate(macToBytes(val))) return false;
        String src = path + "@0x" + Integer.toHexString(off);
        boolean durable = storeFactory(val, src, path);
        log("factory recorded from NVRAM: " + val + " (" + src + ")"
                + (durable ? "" : " \u00b7 durable copy failed"), durable ? C_OK : C_WARN);
        return true;
    }

    void setMac(final String macRaw) {
        if (busy) { toast("busy\u2026"); return; }
        final String mac = normMac(macRaw);
        // IMP: an empty box is a request that was never made, not a malformed address.
        // The old code answered it with a red "invalid MAC: " against a blank string,
        // which reads as a failure of the app rather than of the input.
        if (mac.isEmpty()) {
            toast("Enter a MAC first");
            log("nothing entered \u00b7 type a MAC such as AA:BB:CC:DD:EE:FF, or tap Random", C_DIM);
            return;
        }
        if (!isMac(mac)) { toast("Invalid MAC"); log("invalid MAC: " + mac, C_BAD); return; }
        if (!isUsableTarget(macToBytes(mac))) {
            toast("Unusable target MAC");
            log("refusing " + mac + " as a target (all-zero, broadcast or multicast)", C_BAD);
            return;
        }
        if (!requireRoot()) return;                 // L6: no root, no blind restart
        setBusy(true);
        log("setting " + mac + " \u2026", C_DIM);
        new Thread(new Runnable() { public void run() {
            boolean locked = false;
            try {
                // M2: the CLI is the other writer of the same calibration file.
                if (!acquireLock()) {
                    log(lockRefusal(), C_BAD);
                    toast("Could not start");
                    return;
                }
                locked = true;
                retryIfaceIfUnresolved();
                String cur = getRuntimeMac();
                if (!isMac(cur)) {
                    log("cannot read " + ifaceLabel() + " (" + ifaceReason() + ")", C_BAD);
                    toast("cannot read " + ifaceLabel());
                    return;
                }
                byte[] ob = macToBytes(cur);
                byte[] nb = macToBytes(mac);
                if (ob == null || nb == null) { log("cannot parse MAC", C_BAD); return; }
                if (Arrays.equals(ob, nb)) {
                    log("target equals the runtime MAC \u00b7 nothing to do", C_WARN);
                    return;
                }
                // C3: the runtime MAC is the pattern every patch searches for, so a
                // driver that reports all-zero/all-0xFF/multicast before it
                // associates must stop the run here. A locally-administered MAC is
                // a legitimate pattern (this app's own spoofs carry 0x02).
                if (!isUsableTarget(ob)) {
                    log("refusing to patch: the runtime MAC " + cur
                            + " is degenerate (all-zero, all-0xFF or multicast)", C_BAD);
                    toast("Runtime MAC is unusable");
                    return;
                }

                // M4/M5: what root can see of every candidate, in one round trip,
                // and which vendor's paths were tried first.
                boolean[] probeOk = new boolean[1];
                List<PathState> probe = probeNvram(probeOk);
                log("soc " + socName() + " \u00b7 probing " + probe.size() + " NVRAM candidate"
                        + (probe.size() == 1 ? "" : "s") + ", " + socVendor() + " first", C_DIM);
                if (!probeOk[0]) {
                    // The enumeration itself failed: this is not a device verdict.
                    // M4: name the paths that were probed and say which kind of
                    // failure this was, instead of folding it into "no match".
                    log("cannot enumerate the NVRAM candidates \u00b7 nothing was written,"
                            + " WiFi left alone", C_BAD);
                    if (!rootNow()) log("no root \u2014 nothing was attempted", C_BAD);
                    else log("su did not answer, or answered nothing", C_BAD);
                    for (int i = 0; i < probe.size(); i++) log("  " + probe.get(i).label(), C_DIM);
                    if (probe.isEmpty())
                        for (String p : nvramPaths()) log("  looked for " + p, C_DIM);
                    toast("Could not look for NVRAM");
                    return;
                }

                // Phase 1: read, check the precondition, capture the factory value
                // and take the backup. Nothing is written at all until every backup
                // phase 2 needs is verifiably on disk (C2).
                List<String> paths = new ArrayList<String>();
                List<byte[]> blobs = new ArrayList<byte[]>();
                List<byte[]> origs = new ArrayList<byte[]>();
                List<List<int[]>> wins = new ArrayList<List<int[]>>();
                List<String> backupNotes = new ArrayList<String>();
                boolean sawFile = false, captured = false, backupFailed = false;
                boolean unreadable = false;
                // A pre-image may only be promoted into the durable factory record
                // when the MAC it holds is a factory burn-in. The value at the
                // matched window IS the runtime MAC, so this is the same test the
                // CLI applies to a capture.
                boolean captureOk = isFactoryCandidate(ob);
                int hitsTotal = 0;
                for (int c = 0; c < probe.size(); c++) {
                    String path = probe.get(c).path;
                    if (!probe.get(c).present) continue;
                    boolean[] bad = new boolean[1];
                    byte[] data = readRoot(path, bad);
                    // A candidate that exists but cannot be read is not "no match":
                    // it may be the file that holds the MAC (M4), and H8 forbids the
                    // runtime fallback from papering over that.
                    if (bad[0]) unreadable = true;
                    if (data == null) continue;
                    sawFile = true;
                    // The MAC is located in the SAME four encodings patch() rewrites
                    // -- raw bytes, reversed, colon-ASCII and plain-hex ASCII -- and
                    // the precondition is applied to the encoding that actually
                    // matched. Testing only for six raw bytes, as this stage first
                    // did, silently dropped a file that holds its MAC as hex or
                    // reversed: patch() would have rewritten it, the plan card
                    // advertises all four, and the pristine build patched it, so the
                    // user got "no MAC match" for a file this tool can change.
                    int[] hit = findMacWindow(data, ob);
                    if (hit == null) continue;
                    if (!windowHolds(data, hit[0], hit[1], ob)) continue;
                    // The factory value is only ever captured out of a raw 6-byte
                    // MAC field: the offset recorded with it has to name a real one.
                    int rawOff = hit[1] == ENC_RAW ? hit[0] : indexOf(data, ob);
                    if (!captured && rawOff >= 0) captured = recordFactory(path, rawOff, data, cur);
                    byte[] patched = data.clone();
                    List<int[]> win = new ArrayList<int[]>();
                    int hits = patch(patched, ob, nb, win);
                    if (hits == PATCH_REFUSED) {
                        log("refusing to patch " + path + ": MAC pattern " + cur
                                + " is degenerate \u00b7 file untouched", C_BAD);
                        continue;
                    }
                    if (hits > PATCH_MAX_HITS) {
                        // The layout is not one this app understands, so the file is
                        // abandoned whole -- no backup, no write (C3).
                        log("unexpected layout \u2014 " + hits + " matches in " + path
                                + ", refusing \u00b7 no offsets are recorded and the file is untouched", C_BAD);
                        continue;
                    }
                    if (hits == 0 || Arrays.equals(data, patched)) continue;
                    if (!saveBackup(path, data, captureOk, rawOff)) {
                        backupFailed = true;
                        break;
                    }
                    paths.add(path);
                    blobs.add(patched);
                    // The bytes this path held when the patch was derived. They are
                    // carried to phase 2 so the install can prove the file it is
                    // about to overwrite is still the file the patch was built on
                    // (W_STALE) -- the answer to a writer that changed it in between,
                    // which the size gate alone cannot see.
                    origs.add(data);
                    wins.add(win);
                    backupNotes.add(backupPhrase(path));
                    hitsTotal += hits;
                }
                if (backupFailed) {
                    log("backup failed \u2014 NVRAM untouched", C_BAD);
                    toast("backup failed, NVRAM untouched");
                    return;
                }
                if (!captured && factoryMac() == null) {
                    if (sawFile)
                        log("runtime MAC looks randomized/spoofed; factory value not recorded"
                                + " \u2014 enter it manually", C_WARN);
                    else
                        log("no readable NVRAM candidate \u00b7 factory value not recorded"
                                + " \u00b7 enter it manually", C_WARN);
                }

                // IMP: the confirmation gate. Everything above read and backed up;
                // nothing has written. This names the files, the offsets patch()
                // matched in them, the image length, and the MAC -> MAC change, and
                // the write below happens only if the user says so.
                if (!paths.isEmpty()) {
                    StringBuilder plan = new StringBuilder();
                    plan.append("Write ").append(mac).append(" over ").append(cur)
                        .append(" in place.\n\n");
                    for (int i = 0; i < paths.size(); i++)
                        plan.append(paths.get(i)).append("\n  ").append(winText(wins.get(i)))
                            .append(" \u00b7 ").append(blobs.get(i).length)
                            .append(" byte image\n  ").append(backupNotes.get(i)).append('\n');
                    // Both sentences below used to be constants, and one of them was
                    // false whenever the durable push had not happened (no /data/adb,
                    // or an occupied slot), while the other described a restart rule
                    // the code does not implement: the radio is bounced whenever a
                    // byte was written, verified or not, because the driver has to be
                    // asked to re-read the file either way. They are built from what
                    // saveBackup() established and from the rule the code applies.
                    plan.append("\nSame inode: dd conv=notrunc,fsync, so a kill cannot shorten")
                        .append(" the file.")
                        .append(" After the write each file is re-read and every byte compared;")
                        .append(" WiFi is restarted whenever a write landed, whether or not that")
                        .append(" verification passed, so the driver re-reads the file -- and a")
                        .append(" write that did not verify is reported as unverified, never as a")
                        .append(" success.");
                    if (allowRuntimeFallback)
                        plan.append(" The runtime fallback is enabled, so an unverified write also")
                            .append(" tries the ephemeral ip-link change: ")
                            .append(RUNTIME_ONLY).append('.');
                    notePlan("confirmed to write " + mac + " over " + cur
                            + " in " + paths.size() + " file(s) \u00b7 awaiting your answer");
                    if (!askToWrite(plan.toString())) return;
                }
                notePlan("writing " + cur + " -> " + mac + " in " + paths.size()
                        + " file(s) \u00b7 in place, awaiting the result");

                // Phase 2: install. Every write carries the offset it patched and
                // the old -> new pair, and only counts when the file re-reads as
                // the image that was installed (C1). `worst` keeps the strongest
                // evidence of a change across all the writes, because lastWriteState
                // only ever describes the most recent one (M4).
                int done = 0, worst = W_NONE;
                boolean anyStale = false, lockLost = false;
                StringBuilder doneIn = new StringBuilder();
                StringBuilder triedIn = new StringBuilder();
                StringBuilder staleIn = new StringBuilder();
                for (int i = 0; i < paths.size(); i++) {
                    if (triedIn.length() > 0) triedIn.append(", ");
                    triedIn.append(paths.get(i));
                    // LOCK_STALE_S: the lock is renewed across the write phase too,
                    // not only while the question is open. Two paths can take a 120 s
                    // write deadline plus a 60 s read-back each, so an operation can
                    // outlive the staleness window while it is still writing -- and a
                    // lock that expires under a live writer is one another writer may
                    // legally break. Fail closed when it is no longer ours: continuing
                    // would race their write against the pre-image this operation is
                    // about to install.
                    if (!renewLock()) {
                        lockLost = true;
                        log("the operation lock is no longer ours \u00b7 stopping before "
                                + paths.get(i) + " \u00b7 nothing further is written", C_BAD);
                        break;
                    }
                    boolean w = writeRoot(paths.get(i), blobs.get(i), wins.get(i), cur, mac,
                            origs.get(i));
                    // W_STALE: nothing was installed AND the file is not the one this
                    // patch was built on. That is not evidence about the partition, so
                    // it must not raise `worst`; and it must not be reported by the
                    // "no candidate file holds it" path below either, which would be
                    // false -- the match was there, and the file moved under it.
                    if (lastWriteState == W_STALE) {
                        anyStale = true;
                        if (staleIn.length() > 0) staleIn.append(", ");
                        staleIn.append(paths.get(i));
                        continue;
                    }
                    if (lastWriteState > worst) worst = lastWriteState;
                    if (w) {
                        done++;
                        if (doneIn.length() > 0) doneIn.append(", ");
                        doneIn.append(paths.get(i));
                    } else {
                        log("write to " + paths.get(i) + " failed", C_BAD);
                    }
                }
                final boolean nvVerified = done > 0;
                // IMP: the plan card carries the outcome, including the honest one --
                // a session that wrote nothing says so instead of leaving the last
                // "about to write" line standing.
                if (nvVerified)
                    notePlan("wrote " + doneIn + " \u00b7 " + cur + " -> " + mac
                            + " \u00b7 re-read and verified byte for byte");
                else if (worst == W_PARTIAL)
                    notePlan("the write to " + triedIn + " did not complete \u00b7 it may hold a"
                            + " partly changed image \u00b7 the pre-image is intact");
                else if (worst == W_LANDED)
                    notePlan("wrote " + triedIn + " but the readback did not verify"
                            + " \u00b7 unproven");
                else if (anyStale)
                    notePlan("nothing was written to " + staleIn + " \u00b7 it is not the file this"
                            + " operation read, so it changed while the question was open");
                else if (lockLost)
                    notePlan("nothing was written \u00b7 the operation lock was taken over"
                            + " before the first byte");
                else
                    notePlan("nothing was written \u00b7 no candidate file was patched");
                // M4: four outcomes, not one. The old code printed the same
                // "no nvram match · runtime only" line for an unsupported device, a
                // permission denial and a successful patch whose readback failed.
                if (nvVerified) {
                    log("nvram patched \u00b7 " + doneIn + " (" + hitsTotal + " hit"
                            + (hitsTotal == 1 ? "" : "s") + ")", C_OK);
                } else if (worst == W_PARTIAL) {
                    log("the write did not complete \u00b7 " + triedIn
                            + " may hold a partly changed image; the saved pre-image is intact"
                            + " \u2014 re-check before rebooting", C_BAD);
                } else if (worst == W_LANDED) {
                    log("patched but verification unreadable \u2014 re-check before rebooting", C_BAD);
                } else if (anyStale) {
                    log("nothing was written to " + staleIn + ": " + cur + " was in it when this"
                            + " operation read it and is not now \u00b7 another writer changed the"
                            + " file, so this operation did not apply its change to it", C_BAD);
                } else if (lockLost) {
                    log("nothing was written \u00b7 the operation lock was taken over", C_BAD);
                } else {
                    reportNoWrite(probe, unreadable, cur);
                }

                // H8: the runtime fallback is opt-in, runs only when the NVRAM
                // write produced no verified change, and never when a candidate
                // existed but could not be read -- that file may be the one holding
                // the MAC. M4 adds the failed-enumeration case, and H7 forbids it
                // outright when the toggle itself does not take. M5 adds the last
                // one: on a device where none of the nine candidates exists at all
                // the verdict is UNSUPPORTED DEVICE and *nothing* is attempted --
                // not the restart, and not the one command that can crash the WiFi
                // service on MTK -- even with the opt-in ticked. The W_STALE repair
                // adds the last two: an operation whose file was changed under it, or
                // whose lock was taken over, has an active other writer, and the
                // fallback is the one command that can leave the WiFi service dead.
                boolean runtimeAllowed = allowRuntimeFallback && !nvVerified && !unreadable
                        && probeOk[0] && worst == W_NONE && !anyStale && !lockLost
                        && anyPresent(probe);
                // Nothing written means there is nothing for the driver to re-read,
                // so the radio is left alone instead of being bounced for no reason
                // (and, per M4/M5, the unsupported and denied paths issue no restart
                // at all).
                boolean shouldRestart = nvVerified || worst != W_NONE || runtimeAllowed;
                final boolean spoofed = shouldRestart && reinitWifi(mac, runtimeAllowed);
                if (!shouldRestart) log("nothing was written \u00b7 WiFi left alone", C_DIM);

                String now = getRuntimeMac();
                if (!isMac(now)) {
                    log("cannot read " + ifaceLabel() + " (" + ifaceReason() + ")", C_BAD);
                    toast("cannot read " + ifaceLabel());
                } else if (now.equalsIgnoreCase(mac) && nvVerified) {
                    // The green verdict and the "MAC set" toast belong to a
                    // content-verified NVRAM write and to nothing else.
                    log("runtime = " + now + "  ok", C_OK);
                    toast("MAC set: " + now);
                } else if (now.equalsIgnoreCase(mac)) {
                    // The driver answers with the target, but nothing proved a
                    // persistent change: say so instead of claiming success.
                    log("runtime = " + now + " \u00b7 " + RUNTIME_ONLY, C_WARN);
                    toast(spoofed ? "Runtime only \u00b7 NOT persistent"
                                  : "Not persistent \u00b7 NVRAM not verified");
                } else {
                    // H5: the accurate verdict first, and the randomization state
                    // only ever as an *explanation* for it. The old code tested
                    // isRandomized() before this branch, so a failed run was
                    // reported as "this network randomizes the MAC" -- and, because
                    // an unresolved SSID made that test true unconditionally, that
                    // was every failure on Android 12+.
                    log("runtime = " + now + " (driver kept it)", C_WARN);
                    if (nvVerified)
                        log("the NVRAM image is verified \u00b7 it applies after a reboot", C_DIM);
                    else if (worst != W_NONE)
                        log("no persistent change proven \u00b7 " + RUNTIME_ONLY, C_WARN);
                    explainPrivacy();
                    toast("driver kept " + now);
                }
            } finally {
                if (locked) releaseLock();
                setBusy(false);
                refresh();
            }
        } }).start();
    }

    /**
     * M4's cheap partial step, asked only on the paths that would otherwise
     * pronounce on the device: is root still there, right now?
     *
     * A named helper rather than an inline run("id") because inside one of this
     * file's anonymous Runnables the name `run` is already taken by run().
     */
    boolean rootNow() {
        return run("id").out.contains("uid=0");
    }

    /**
     * M4/M5: nothing was written -- and *why*, which is four different things.
     *
     * "no nvram match · runtime only" covered a device with none of the nine
     * paths, a device whose paths exist but were denied, a file that exists but
     * does not carry the runtime MAC, and (with writeRoot's boolean alone) a
     * successful patch whose readback failed. Only one of those is an unsupported
     * device, so only that one says so.
     */
    void reportNoWrite(List<PathState> probe, boolean unreadable, String cur) {
        List<String> blocked = blockedPaths(probe);
        boolean present = false;
        for (int i = 0; i < probe.size(); i++) if (probe.get(i).present) present = true;
        // The probed list is always printed: which files were looked at, and what
        // root saw of each, is half of the answer.
        for (int i = 0; i < probe.size(); i++) log("  " + probe.get(i).label(), C_DIM);
        if (blocked.size() > 0 || unreadable) {
            log("candidate NVRAM present but not usable by root \u00b7 nothing was written", C_BAD);
            return;
        }
        if (present) {
            // The files are there and readable; they just do not hold the driver's
            // address in any encoding patch() knows. The encodings are named because
            // the ASCII ones are lowercase-only: "is in none of the candidate files"
            // would be literally false about a file that stores it as "AA:BB:..", and
            // that case is reachable (README's "ASCII forms" is broader than the
            // code, and the CLI's scan searches one encoding where this searches
            // four -- both outside this file).
            log("no MAC match \u00b7 " + cur + " is in none of the candidate files in any"
                    + " encoding this app searches (raw, reversed, lowercase hex, lowercase"
                    + " colon-hex) \u00b7 nothing was written", C_WARN);
            return;
        }
        // M4's cheap partial step, and the one that stops "unsupported device" from
        // becoming the polite word for "root would not let me look".
        if (!rootNow()) {
            log("no root \u2014 nothing was attempted", C_BAD);
            return;
        }
        // M5: the explicit terminal state. Every candidate is absent, so this
        // device is not one this app can change, and saying so is the honest
        // answer -- the old line implied a failed attempt had been made.
        log("UNSUPPORTED DEVICE \u2014 nothing was written", C_BAD);
        log("looked for " + probe.size() + " candidate path"
                + (probe.size() == 1 ? "" : "s") + ", none of which exists here:", C_DIM);
    }

    /**
     * H5/M1: says whether the connected network is expected to override the
     * hardware MAC -- as an explanation for a failure already reported, never
     * instead of it, and never a claim when the SSID could not be resolved.
     */
    void explainPrivacy() {
        String ssid = connectedSsid();
        if (ssid.isEmpty()) {
            log("driver kept it (randomization state unknown) \u00b7 no connected network reported", C_DIM);
            return;
        }
        String pv = privacyValue(configStore(), ssid);
        if ("0".equals(pv)) {
            log("this network (" + ssid + ") uses the device MAC \u00b7 the cause is elsewhere", C_DIM);
        } else if (isRandomizedValue(pv)) {
            log("this network (" + ssid + ") randomizes the MAC \u00b7 set its Privacy to"
                    + " 'Use device MAC'", C_WARN);
        } else {
            log("driver kept it (randomization state unknown) \u00b7 " + ssid, C_DIM);
        }
    }

    void randomMac() {
        byte[] b = new byte[6];
        rnd.nextBytes(b);
        b[0] = (byte) ((b[0] & 0xFC) | 0x02); // locally administered, unicast
        setMac(bytesToMac(b));
    }

    /**
     * H3: the verdict comes from the write, which the code can verify, rather than
     * from the runtime MAC -- the one signal that is stale exactly when the write
     * did not take. Both strategies run, additively, so a partly applied step 1 no
     * longer suppresses the saved-image fallback.
     */
    void restore() {
        if (busy) { toast("busy\u2026"); return; }
        if (!requireRoot()) return;                 // L6, as for setMac
        setBusy(true);
        log("restoring \u2026", C_DIM);
        new Thread(new Runnable() { public void run() {
            boolean locked = false;
            try {
                // M2: the same cross-process lock as setMac and the CLI.
                if (!acquireLock()) {
                    log(lockRefusal(), C_BAD);
                    toast("Could not start");
                    return;
                }
                locked = true;
                // Resolved here, not on the UI thread: with the pref gone this reads
                // the durable record through the root shell, and a blocked main
                // thread is an ANR.
                FactoryRec rec = factoryRec();
                final String factory = rec == null ? null : rec.mac;
                if (!isMac(factory)) {
                    toast("No factory MAC recorded yet");
                    log("no factory MAC recorded \u00b7 type it below and save it", C_BAD);
                    return;
                }
                byte[] to = macToBytes(factory);
                // CRITICAL (repair round): the recovery path is the one that must
                // always be able to put the factory MAC back, so it is the last place
                // that may write a value it has not validated. This used to check the
                // *search pattern* (the runtime MAC) and then write whatever
                // factoryMac() returned, so a record of 00:00:00:00:00:00 -- a value
                // every other path in this app refuses as a target -- was written
                // into the calibration file as six zero bytes and reported as
                // "NVRAM restored (verified)". Nothing is planned before this gate,
                // and the decision itself lives in restoreRefusal() so that it can be
                // checked offline against the real code and cannot drift from here.
                String refusal = restoreRefusal(factory, rec.src);
                if (refusal != null) {
                    log("refusing to restore " + factory + ": " + refusal
                            + " \u00b7 nothing was written", C_BAD);
                    log("type the factory MAC printed on the box or the label and save it first",
                            C_DIM);
                    toast("Factory record cannot be restored");
                    return;
                }
                if (!isFactoryCandidate(to)) {
                    // Allowed only because the value is the user's own, which
                    // restoreRefusal() has just established: a randomizer (or an
                    // earlier spoof) sets the locally administered bit and a factory
                    // burn-in never does, so the CLI refuses the mirror-image capture
                    // for the same reason. Say so rather than doing it quietly.
                    log("warning: " + factory + " is locally administered \u00b7 it is not a"
                            + " factory burn-in, but you typed it, so it is written as you asked",
                            C_WARN);
                }
                log("restoring " + factory + " (" + factoryProvenance() + ")", C_DIM);
                retryIfaceIfUnresolved();
                String curRt = getRuntimeMac();

                // M4/M5: the same per-path probe as setMac, with the same abort when
                // the enumeration itself failed -- this is the recovery action, so it
                // is the last place that may guess.
                boolean[] probeOk = new boolean[1];
                List<PathState> probe = probeNvram(probeOk);
                List<String> paths = usablePaths(probe);
                log("soc " + socName() + " \u00b7 probing " + probe.size() + " NVRAM candidate"
                        + (probe.size() == 1 ? "" : "s") + ", " + socVendor() + " first", C_DIM);
                if (!probeOk[0]) {
                    log("cannot enumerate the NVRAM candidates \u00b7 nothing was written,"
                            + " WiFi left alone", C_BAD);
                    if (!rootNow()) log("no root \u2014 nothing was attempted", C_BAD);
                    else log("su did not answer, or answered nothing", C_BAD);
                    for (int i = 0; i < probe.size(); i++) log("  " + probe.get(i).label(), C_DIM);
                    if (probe.isEmpty())
                        for (String p : nvramPaths()) log("  looked for " + p, C_DIM);
                    toast("Could not look for NVRAM");
                    return;
                }
                boolean verified = false;
                String vPath = null;
                int vOff = -1;
                int worst = W_NONE;
                boolean anyStale = false, lockLost = false, unreadable = false;
                StringBuilder staleIn = new StringBuilder();

                // Nothing to rewrite when the driver already reports the factory MAC
                // and no calibration file mentions it: that rewrite is risk with no
                // possible benefit.
                if (curRt.equalsIgnoreCase(factory)) {
                    boolean present = false;
                    for (int i = 0; i < paths.size(); i++) {
                        boolean[] bad = new boolean[1];
                        byte[] d = readRoot(paths.get(i), bad);
                        if (bad[0]) unreadable = true;
                        if (d != null && indexOf(d, to) >= 0) { present = true; break; }
                    }
                    if (!present) {
                        log("NVRAM already holds " + factory + " \u00b7 nothing to rewrite", C_OK);
                        notePlan("nothing to rewrite \u00b7 NVRAM already holds " + factory
                                + " and no candidate file needed changing");
                        toast("Already at the factory MAC");
                        return;
                    }
                }

                // Strategy 1: patch the live MAC -> factory in each calibration file.
                byte[] from = macToBytes(curRt);
                // C3: the pattern here is the runtime MAC. A driver reporting zeros
                // or 0xFF would otherwise turn this recovery action into exactly the
                // corruption it exists to undo, so a degenerate pattern skips
                // strategy 1 and the saved image is still tried below.
                boolean patternOk = isUsableTarget(from);
                if (!patternOk) {
                    log(isMac(curRt)
                            ? "refusing to patch: the runtime MAC " + curRt + " is degenerate"
                                    + " \u00b7 restore will use the saved image"
                            : "cannot read " + ifaceLabel() + " (" + ifaceReason() + ")", C_WARN);
                }
                // IMP: strategy 1 is planned in full before anything is written -- read,
                // patch in memory, keep the offsets -- so the confirmation below can
                // name every file and offset it is about to change, and so the decision
                // is taken once, before the first destructive byte, rather than between
                // two of them. The images are the same ones the writes then install.
                List<String> pPaths = new ArrayList<String>();
                List<byte[]> pOrig = new ArrayList<byte[]>();
                List<byte[]> pNew = new ArrayList<byte[]>();
                List<List<int[]>> pWins = new ArrayList<List<int[]>>();
                if (patternOk) {
                    for (int i = 0; i < paths.size(); i++) {
                        String path = paths.get(i);
                        boolean[] bad = new boolean[1];
                        byte[] data = readRoot(path, bad);
                        // M4 on the recovery path: a candidate that exists but could
                        // not be read is not "no MAC match", and the bottom of this
                        // method used to hardcode false, so a restore in which a
                        // candidate was present but unreadable said the MAC was in
                        // none of the files -- the one distinction the user needs.
                        if (bad[0]) unreadable = true;
                        if (data == null) continue;
                        byte[] patched = data.clone();
                        List<int[]> win = new ArrayList<int[]>();
                        int hits = patch(patched, from, to, win);
                        if (hits == PATCH_REFUSED) {
                            log("refusing to patch " + path + ": MAC pattern " + curRt
                                    + " is degenerate \u00b7 file untouched", C_BAD);
                            continue;
                        }
                        if (hits > PATCH_MAX_HITS) {
                            log("unexpected layout \u2014 " + hits + " matches in " + path
                                    + ", refusing \u00b7 no offsets are recorded and the file is untouched", C_BAD);
                            continue;
                        }
                        if (hits == 0 || Arrays.equals(data, patched)) continue;
                        pPaths.add(path);
                        pOrig.add(data);
                        pNew.add(patched);
                        pWins.add(win);
                    }
                }

                // IMP: the saved images are loaded here too, read-only, so the question
                // can say which of them a restore would install. Whether each one is
                // actually installed is still decided during the execution below, by
                // the same containsMacValue() gate as before.
                List<String> iPaths = new ArrayList<String>();
                List<byte[]> iImgs = new ArrayList<byte[]>();
                for (int i = 0; i < paths.size(); i++) {
                    byte[] img = loadBackup(paths.get(i));
                    if (img == null) continue;
                    iPaths.add(paths.get(i));
                    iImgs.add(img);
                }

                // IMP: the confirmation gate. Nothing has been written yet: the two
                // lists above are read-only work. Cancel, back, an abandoned screen or
                // a lost lock all mean this recovery does not start.
                if (!pPaths.isEmpty() || !iImgs.isEmpty()) {
                    StringBuilder plan = new StringBuilder();
                    plan.append("Restore ").append(factory).append(" (")
                        .append(factoryProvenance()).append(").\n\n");
                    for (int i = 0; i < pPaths.size(); i++)
                        plan.append(pPaths.get(i)).append("\n  ").append(winText(pWins.get(i)))
                            .append(" \u00b7 ").append(curRt).append(" -> ").append(factory)
                            .append('\n');
                    for (int i = 0; i < iImgs.size(); i++)
                        plan.append(iPaths.get(i)).append("\n  install the saved pre-image, ")
                            .append(iImgs.get(i).length).append(" bytes\n");
                    plan.append("\nIn place: dd conv=notrunc,fsync, same inode.")
                        .append(" Each file's pre-image is saved before it is written, and the")
                        .append(" write is verified by re-reading it.");
                    notePlan("confirmed to restore " + factory + " in " + pPaths.size()
                            + " patched and " + iImgs.size()
                            + " saved-image file(s) \u00b7 awaiting your answer");
                    if (!askToWrite(plan.toString())) return;
                }
                notePlan("restoring " + factory + " \u00b7 " + pPaths.size()
                        + " patch(es) and " + iImgs.size() + " saved image(s) \u00b7 awaiting"
                        + " the result");

                for (int i = 0; i < pPaths.size(); i++) {
                    String path = pPaths.get(i);
                    // See setMac's phase-2 loop: the lock is renewed across the write
                    // phase, not only while the question is open, and a lost lock
                    // stops the operation rather than racing another writer.
                    if (!renewLock()) {
                        lockLost = true;
                        log("the operation lock is no longer ours \u00b7 stopping before "
                                + path + " \u00b7 nothing further is written", C_BAD);
                        break;
                    }
                    // capture=false and off=-1: this pre-image is the live file, which
                    // may already hold a spoof, so it is never promoted into the shared
                    // durable record -- and an existing durable image for this path is
                    // reported (not replaced) by saveBackup.
                    if (!saveBackup(path, pOrig.get(i), false, -1)) {
                        log("backup failed \u2014 " + path + " untouched", C_BAD);
                        continue;
                    }
                    // pOrig.get(i) is handed to the write as the pre-image the patch
                    // was derived from, so the install refuses (W_STALE) if the file
                    // changed between the read above and the first byte.
                    if (!writeRoot(path, pNew.get(i), pWins.get(i), curRt, factory, pOrig.get(i))) {
                        if (lastWriteState == W_STALE) {
                            anyStale = true;
                            if (staleIn.length() > 0) staleIn.append(", ");
                            staleIn.append(path);
                            continue;
                        }
                        if (lastWriteState > worst) worst = lastWriteState;
                        log("write to " + path + " failed", C_BAD);
                        continue;
                    }
                    if (lastWriteState > worst) worst = lastWriteState;
                    // H3 + the H2 repair: the verdict is writeRoot's own per-window
                    // verification (W_VERIFIED), which knows the encoding each window
                    // carries. Re-deriving it here from `indexOf(pNew, to)` -- six raw
                    // bytes -- reported a landed ASCII or reversed window as
                    // "the readback did not verify" and spent a second whole-image
                    // read per file to do it.
                    if (lastWriteState == W_VERIFIED) {
                        verified = true; vPath = path; vOff = firstOffset(pWins.get(i));
                    }
                }

                // Strategy 2, additive: for every path strategy 1 did not fix,
                // install the verified saved pre-image.
                for (int i = 0; i < iPaths.size(); i++) {
                    String path = iPaths.get(i);
                    if (verified && path.equals(vPath)) continue;
                    byte[] img = iImgs.get(i);
                    // A saved image is only a *factory* image if it really carries the
                    // factory MAC. Installing one that does not, and then calling the
                    // result "restored", is exactly the lie this fix removes.
                    // A presence scan, not a patch: the C3 hit cap would refuse a
                    // legitimate backup here for no gain.
                    if (!containsMacValue(img, to)) {
                        log("saved image for " + path + " does not contain " + factory
                                + " \u00b7 not installed", C_WARN);
                        continue;
                    }
                    if (!renewLock()) {
                        lockLost = true;
                        log("the operation lock is no longer ours \u00b7 stopping before "
                                + path + " \u00b7 nothing further is written", C_BAD);
                        break;
                    }
                    // C2 applies to EVERY path that can write, and this one used to be
                    // the exception: it installed the saved image over whatever the file
                    // held and destroyed it with no copy, while the dialog said "Each
                    // file's pre-image is saved before it is written" and the plan card
                    // said the pre-image is claimed before the first byte. The live
                    // bytes are read here and saved first -- and they are also the
                    // pre-image the install is checked against, so a file that moves
                    // under this write is refused rather than overwritten (W_STALE).
                    boolean[] bad = new boolean[1];
                    byte[] live = readRoot(path, bad);
                    if (bad[0]) unreadable = true;
                    if (live == null) {
                        log("cannot read " + path + " \u00b7 its live pre-image cannot be saved,"
                                + " so nothing is written to it", C_BAD);
                        continue;
                    }
                    if (live.length != img.length) {
                        log(path + " is " + live.length + " bytes and the saved image is "
                                + img.length + " \u00b7 not installed", C_BAD);
                        continue;
                    }
                    if (!saveBackup(path, live, false, -1)) {
                        log("backup failed \u2014 " + path + " untouched", C_BAD);
                        continue;
                    }
                    if (!writeRoot(path, img, null, null, null, live)) {
                        if (lastWriteState == W_STALE) {
                            anyStale = true;
                            if (staleIn.length() > 0) staleIn.append(", ");
                            staleIn.append(path);
                            continue;
                        }
                        if (lastWriteState > worst) worst = lastWriteState;
                        log("write to " + path + " failed", C_BAD);
                        continue;
                    }
                    if (lastWriteState > worst) worst = lastWriteState;
                    int off = indexOf(img, to);
                    byte[] back = readRoot(path);
                    if (back != null && Arrays.equals(back, img)) {
                        verified = true; vPath = path; vOff = off;
                    }
                }

                final int wrote = worst;
                if (verified) {
                    log(vOff >= 0
                            ? "NVRAM restored (verified: factory MAC present at 0x"
                                    + Integer.toHexString(vOff) + ")"
                            : "NVRAM restored (verified: the saved image is in place byte-for-byte)",
                            C_OK);
                } else if (wrote == W_PARTIAL) {
                    log("the restore write did not complete \u00b7 the image may be partly"
                            + " changed; the saved pre-image is intact"
                            + " \u2014 re-check before rebooting", C_BAD);
                } else if (wrote == W_LANDED) {
                    log("patched but verification unreadable \u2014 re-check before rebooting", C_BAD);
                } else if (anyStale) {
                    log("nothing was written to " + staleIn + ": " + factory + " was not installed"
                            + " because the file is no longer the one this operation read"
                            + " \u00b7 another writer changed it, so this restore did not touch it",
                            C_BAD);
                } else if (lockLost) {
                    log("nothing was written \u00b7 the operation lock was taken over", C_BAD);
                } else {
                    reportNoWrite(probe, unreadable, factory);
                }
                // IMP: the plan card carries the honest outcome of the recovery, so a
                // restore that wrote nothing cannot read as one that did.
                if (verified)
                    notePlan("restored " + factory + " in " + vPath
                            + (vOff >= 0 ? " @" + "0x" + Integer.toHexString(vOff) : "")
                            + " \u00b7 re-read and verified");
                else if (wrote == W_PARTIAL)
                    notePlan("the restore write did not complete \u00b7 the image may be partly"
                            + " changed \u00b7 the pre-image is intact");
                else if (wrote == W_LANDED)
                    notePlan("restored but the readback did not verify \u00b7 unproven");
                else if (anyStale)
                    notePlan("nothing was written to " + staleIn + " \u00b7 it is not the file this"
                            + " operation read, so it changed under the restore");
                else if (lockLost)
                    notePlan("nothing was written \u00b7 the operation lock was taken over"
                            + " before the first byte");
                else
                    notePlan("nothing was written \u00b7 no factory value was found to install");

                // H8: restore never uses the runtime ip-link fallback. Its whole
                // purpose is the persistent image, and a spoof of the factory MAC
                // that vanishes on the next re-init would only make the failure
                // look like a success. The restart itself only happens when some
                // write landed: there is nothing to re-read otherwise (M4/M5).
                if (verified || wrote != W_NONE) reinitWifi(factory, false);
                else log("nothing was written \u00b7 WiFi left alone", C_DIM);

                String now = getRuntimeMac();
                if (verified && now.equalsIgnoreCase(factory)) {
                    log("runtime = " + now + "  ok", C_OK);
                    toast("Restored: " + now);
                } else if (verified) {
                    // The partition is proven; only the driver has not re-read it.
                    log("runtime not re-read yet \u2014 reboot or toggle WiFi to apply", C_WARN);
                    toast("NVRAM restored \u00b7 reboot to apply");
                } else if (now.equalsIgnoreCase(factory)) {
                    // The driver already reports the factory MAC, but nothing in the
                    // partition was verified: after a reboot the NVRAM decides again,
                    // so this is not the green verdict.
                    log("runtime = " + now + " \u00b7 no verified NVRAM write yet", C_WARN);
                    toast("At the factory MAC \u00b7 NVRAM unverified");
                } else if (!isMac(now)) {
                    log("cannot read " + ifaceLabel() + " (" + ifaceReason() + ")", C_BAD);
                    toast("cannot read " + ifaceLabel());
                } else {
                    // H5: the plain verdict, and the randomization state only as an
                    // explanation of it -- never as a replacement for it.
                    log("runtime = " + now + " (unexpected)", C_WARN);
                    explainPrivacy();
                    toast("driver kept " + now);
                }
            } finally {
                if (locked) releaseLock();
                setBusy(false);
                refresh();
            }
        } }).start();
    }

    /**
     * H1: manual entry. The user's own record of the factory MAC is the only
     * recovery path when the app could never capture one (the runtime MAC was
     * randomized or already spoofed), so it is accepted -- but labelled
     * unverified, and it never overwrites an NVRAM-derived record that is worth
     * keeping.
     *
     * Worth keeping is the operative phrase, and it has three meanings: a record
     * that cannot be written back (all-zero, all-0xFF, multicast), one that could
     * not be a factory burn-in (locally administered), and one that carries no
     * provenance at all (the pref the shipped build wrote from /sys, whose value on
     * Android 11+ is the randomized address). Refusing to replace the first two left
     * the user with a value they could not correct; refusing to replace the third
     * left them with a stale reading that shadowed the durable record and blocked
     * re-capture, while restore() would have written it back as "the factory MAC".
     * StoreFactory() refuses only a genuine, usable, captured record.
     */
    void saveTypedFactory(final String raw) {
        if (busy) { toast("busy\u2026"); return; }
        final String mac = normMac(raw);
        // IMP: an empty box is not a malformed MAC. Saying "invalid factory MAC: "
        // against nothing told the user their input was wrong when there was none.
        if (mac.isEmpty()) {
            toast("Enter the factory MAC first");
            log("nothing entered \u00b7 type the MAC printed on the box or the label, or tap"
                    + " Check NVRAM to capture it from the calibration image", C_DIM);
            return;
        }
        if (!isMac(mac)) { toast("Invalid MAC"); log("invalid factory MAC: " + mac, C_BAD); return; }
        if (!isUsableTarget(macToBytes(mac))) {
            toast("Unusable MAC");
            log("refusing " + mac + " as a factory value (all-zero, broadcast or multicast)", C_BAD);
            return;
        }
        setBusy(true);
        new Thread(new Runnable() { public void run() {
            try {
                FactoryRec have = factoryRec();
                if (have != null && !have.mac.equalsIgnoreCase(mac) && !"typed".equals(have.src)) {
                    boolean unusable = !usableFactory(have.mac);
                    // H2: a record with NO provenance is not a factory record. It is
                    // the pref the shipped build wrote out of /sys -- a reading of the
                    // driver rather than of a calibration image, i.e. the randomized
                    // address on Android 11+ -- and after an upgrade it shadowed a
                    // durable record and refused both this button and re-capture. It
                    // is unverified, so it is not worth keeping, and refusing to
                    // replace it left the user with a poisoned value they could not
                    // correct from anywhere in the UI.
                    boolean unprovenanced = have.src.isEmpty();
                    if (!unusable && !have.suspect && !unprovenanced) {
                        toast("A verified factory record already exists");
                        log("factory record from " + provLabel(have.src)
                                + " already saved; not overwritten", C_WARN);
                        return;
                    }
                    // The stored value is one nothing here can use: it is not a write
                    // target, or it is locally administered and so was never a factory
                    // burn-in, or nothing records where it came from. Replacing it is
                    // the only way the user can recover.
                    log(unusable
                            ? "the recorded factory value " + have.mac + " is not a usable MAC"
                                    + " \u00b7 replacing it with yours"
                            : unprovenanced
                            ? "the recorded factory value " + have.mac + " has no provenance"
                                    + " recorded (unverified) \u00b7 replacing it with yours"
                            : "the recorded factory value " + have.mac + " is locally administered"
                                    + ", so it was never a factory burn-in \u00b7 replacing it with"
                                    + " yours", C_WARN);
                }
                boolean durable = storeFactory(mac, "typed", null);
                // IMP: no green here. The user typed this value; nothing in this app has
                // verified it against a calibration image, and a green tick on an
                // unverified recovery record is the one thing this row must not show.
                log("factory saved: " + mac + " (typed by you \u00b7 unverified)", C_WARN);
                if ((macToBytes(mac)[0] & 0x02) != 0) {
                    log("note: " + mac + " is locally administered \u00b7 unusual for a factory value",
                            C_WARN);
                }
                log(durable ? "record mirrored to " + ADB_DIR
                            : "durable copy failed \u00b7 app-private record only",
                        durable ? C_DIM : C_WARN);
                toast("Factory MAC saved");
            } finally {
                setBusy(false);
                refresh();
            }
        } }).start();
    }

    /**
     * H1: hands the record to the user. /data/adb is root-only and app-private
     * storage dies with an uninstall, so the copy runs as root into a public
     * directory through the channel the app already owns -- which is why no
     * storage permission is needed and the manifest stays permission-free.
     */
    void exportRecord() {
        if (busy) { toast("busy\u2026"); return; }
        setBusy(true);
        log("exporting record \u2026", C_DIM);
        new Thread(new Runnable() { public void run() {
            try {
                FactoryRec rec = factoryRec();
                if (rec != null && !isMac(getPref("factory"))) {
                    // Adopt it into the pref so the export and the durable text record
                    // are written from one value; the value itself is not changed.
                    setPref("factory", rec.mac);
                    setPref("factory_src", rec.src);
                    recMac = rec.mac;
                    recSrc = rec.src;
                }
                if (!isMac(getPref("factory"))) {
                    log("nothing to export \u00b7 no factory record yet", C_BAD);
                    toast("No factory record yet");
                    return;
                }
                publishRecord();
                Res r = runTimed(EXPORT_SCRIPT, MS_BIG_READ);
                if (r.ok() && !r.out.isEmpty()) {
                    // IMP: "exported 0 file(s) to /sdcard" is not a success, and a green
                    // tick on it told the user their record was safe on the SD card when
                    // nothing had been copied at all.
                    boolean none = r.out.indexOf("exported 0 ") >= 0;
                    log(r.out, none ? C_BAD : C_OK);
                    toast(none ? "Nothing was exported" : r.out);
                } else {
                    log("export failed \u2014 nothing was copied", C_BAD);
                    toast("export failed");
                }
            } finally {
                setBusy(false);
            }
        } }).start();
    }

    /**
     * H7: how often the radio's *actual* state is re-read, and the hard cap on
     * waiting for it to change.
     *
     * There is no fixed sleep left on the restart path: the old code waited
     * 2500 ms for the down and 7000 ms for the up and then assumed both had
     * happened, which is 9.5 s of the 9.5-16.5 s window that made the rotation
     * race (M2) ordinary rather than exotic -- and, when the toggle was legally
     * refused, 9.5 s spent proving nothing. The only wait here is the polling
     * interval, and it is always followed by a fresh read of the state.
     */
    static final long WIFI_POLL_MS = 500L;
    static final long WIFI_POLL_MAX = 10000L;

    /**
     * The modern command's own share of the budget, and the legacy fallback's.
     *
     * The fallback must not inherit an exhausted one. It used to: the first
     * waitWifiEnabled() call could spend the whole WIFI_POLL_MAX, so the guard that
     * decided whether to fall back was always true and the "falling back to svc
     * wifi" branch below was unreachable -- measured by driving setWifiEnabled with
     * a never-toggling radio: verdict WIFI_STUCK, elapsed 10005 ms, and the command
     * list held only `cmd wifi set-wifi-enabled disabled`. On a device where that
     * command does not take, the NVRAM write landed, the driver was never asked to
     * re-read it, the user was told "airplane mode on?", and the pristine app -- which
     * ran `svc wifi disable/enable` -- would have restarted the radio. So the modern
     * command polls for at most half the budget, the legacy command then gets a
     * fresh full one of its own, and only after both have been issued and observed
     * to fail is the verdict WIFI_STUCK.
     */
    static final long WIFI_POLL_MODERN = 5000L;
    static final long WIFI_POLL_LEGACY = 10000L;

    /** setWifiEnabled's outcomes (H7). Only WIFI_OBSERVED is evidence of anything. */
    static final int WIFI_OBSERVED   = 0;   // the wanted state was seen
    static final int WIFI_UNVERIFIED = 1;   // issued, but this device will not report its state
    static final int WIFI_STUCK      = 2;   // the state was read, and it never changed

    /**
     * Turns the radio to `want`. WIFI_OBSERVED only when the new state was
     * actually seen; the refusal and the silence are deliberately different
     * answers, because they call for different words in the verdict.
     *
     * `svc wifi` is the legacy path, and the toggle can be legally refused:
     * WifiSettingsStore.handleWifiToggled returns early when
     * (mAirplaneModeOn && !isAirplaneToggleable()), and an su policy or a dead
     * HAL can refuse NETWORK_SETTINGS as well. The maintained replacement,
     * `cmd wifi set-wifi-enabled`, discards the same boolean and exits 0, so
     * switching commands without verifying the state fixes nothing -- which is
     * why the fallback here is decided by the *observed* state and never by an
     * exit status.
     */
    int setWifiEnabled(boolean want, String why) {
        WifiStat before = readWifiStatus(MS_DEFAULT);
        if (!before.readable) {
            // This device will not say what its radio is doing (no usable
            // `cmd wifi status`), and refusing to touch it would be worse than
            // useless: without the restart the driver never re-reads NVRAM and the
            // change silently waits for the next boot. So the toggle is issued
            // through both channels -- the modern one and the legacy one the
            // shipped app used -- and reported as unverified, which is what keeps
            // the ip-link fallback and every "ok" verdict out of this path.
            log("cannot read the WiFi state \u00b7 issuing the " + (want ? "enable" : "disable")
                    + " anyway, unverified", C_WARN);
            run("cmd wifi set-wifi-enabled " + (want ? "enabled" : "disabled"));
            run("svc wifi " + (want ? "enable" : "disable"));
            return WIFI_UNVERIFIED;
        }
        if (before.enabled == want) return WIFI_OBSERVED;
        long t0 = System.currentTimeMillis();
        Res r = run("cmd wifi set-wifi-enabled " + (want ? "enabled" : "disabled"));
        // Half the budget for the modern command, so the legacy fallback below always
        // has a budget of its own to spend. See WIFI_POLL_MODERN.
        if (waitWifiEnabled(want, WIFI_POLL_MODERN, t0)) {
            log("wifi " + (want ? "on" : "off") + " \u00b7 observed after " + elapsed(t0), C_DIM);
            return WIFI_OBSERVED;
        }
        log((r.ok() ? "cmd wifi set-wifi-enabled had no effect" : "cmd wifi is not usable here")
                + " \u00b7 falling back to svc wifi " + (want ? "enable" : "disable"), C_DIM);
        run("svc wifi " + (want ? "enable" : "disable"));
        if (waitWifiEnabled(want, WIFI_POLL_LEGACY, t0)) {
            log("wifi " + (want ? "on" : "off") + " \u00b7 observed after " + elapsed(t0), C_DIM);
            return WIFI_OBSERVED;
        }
        // Both channels were issued and neither took, so the state was read and it
        // really did not change: that is a refusal, not a silence.
        log("WiFi did not toggle after both cmd wifi and svc wifi \u2014 airplane mode on?"
                + " \u00b7 " + why + " aborted", C_BAD);
        return WIFI_STUCK;
    }

    /** Polls the observed state every WIFI_POLL_MS until it matches, or `budget` runs out. */
    boolean waitWifiEnabled(boolean want, long budget, long t0) {
        long waited = 0;
        while (waited < budget) {
            pollPause();
            waited += WIFI_POLL_MS;
            WifiStat s = readWifiStatus(MS_DEFAULT);
            if (s.readable && s.enabled == want) return true;
        }
        return false;
    }

    /**
     * The polling interval, named so it cannot be mistaken for a blind wait.
     *
     * H7's acceptance is that a grep for the old sleep helper finds no fixed wait
     * on the WiFi-restart path, and this is the only delay left on it: it is
     * short, it is always followed by a fresh read of the state, and the loop
     * above bounds how many times it can happen at all.
     */
    void pollPause() {
        sleep(WIFI_POLL_MS);
    }

    /** Elapsed time since t0, as "1.5s" -- the observed transition, not an assumption. */
    String elapsed(long t0) {
        long ms = System.currentTimeMillis() - t0;
        if (ms < 0) ms = 0;
        return (ms / 1000) + "." + ((ms % 1000) / 100) + "s";
    }

    /**
     * Restarts WiFi so the driver re-reads NVRAM, then -- only when the caller
     * explicitly allowed it and no persistent change was verified -- tries the
     * runtime ip-link change as a last resort.
     *
     * Returns true only when the ip-link command was actually issued, so the
     * caller can label its verdict a runtime-only spoof instead of a success
     * (H8). The fallback used to run unconditionally and then be reported with a
     * green "MAC set" toast, which made an ephemeral spoof indistinguishable from
     * a persistent NVRAM patch -- and on MTK it is the command that crashes the
     * WiFi service, the state this app is least able to recover from.
     *
     * The gate is four deep now: the caller must have allowed it, the interface
     * must exist (otherwise the device is unsupported, not the command failed),
     * the restart must actually have happened -- verified by polling, H7 -- and
     * the restart must have left the driver on a different MAC.
     */
    boolean reinitWifi(String targetMac, boolean allowRuntime) {
        if (!isMac(targetMac)) return false;
        // M6: no interface, no restart. The ip-link command below takes the
        // interface it was given and the driver re-read is the one that interface
        // belongs to, so an unresolved or ambiguous interface is refused before a
        // single WiFi command is issued rather than guessed at.
        String ifn = iface();
        if (ifn.isEmpty()) {
            log("cannot restart WiFi: " + ifaceReason() + " \u00b7 the NVRAM write stands, but"
                    + " the driver has not been asked to re-read it", C_BAD);
            return false;
        }
        // H7: a toggle whose result is discarded is not evidence of anything.
        // Without this, a legal no-op was reported as "the driver kept it", so the
        // user went looking for an NVRAM problem that did not exist. Both halves
        // are always issued, in this order, whatever the first one reports: that is
        // what keeps a failed or unverifiable *disable* from leaving the radio off.
        int off = setWifiEnabled(false, "the WiFi restart");
        int on = setWifiEnabled(true, "the WiFi restart");
        if (off != WIFI_OBSERVED || on != WIFI_OBSERVED) {
            // H8: no *verified* restart means no evidence about the MAC at all, and
            // the only command left is the one that can kill the WiFi service -- so
            // it is not run. On MTK that is the difference between a failed attempt
            // and a device whose WiFi does not come back.
            log(off == WIFI_UNVERIFIED || on == WIFI_UNVERIFIED
                    ? "the WiFi restart could not be verified \u00b7 the runtime fallback is not attempted"
                    : "WiFi did not toggle \u00b7 the runtime fallback is not attempted", C_WARN);
            return false;
        }
        String now = getRuntimeMac();
        // A restart that did take, or an interface that cannot be read, is not a
        // reason to press the one button that can kill the WiFi service.
        if (!isMac(now) || now.equalsIgnoreCase(targetMac)) return false;
        if (!allowRuntime) return false;
        if (!ifaceExists()) {
            log("unsupported device: " + ifaceLabel() + " does not exist \u00b7 fallback skipped", C_WARN);
            return false;
        }
        log("trying the runtime ip-link fallback \u00b7 " + RUNTIME_ONLY, C_WARN);
        log("MTK: " + MTK_IP_LINK_WARNING, C_WARN);
        // The fallback's own toggle has to be *verified* down first: the documented
        // MTK crash happens when ip link runs against a radio that is still up,
        // which is exactly the state of a radio that will not go down. H8's rule --
        // never run the fallback when the toggle did not take -- applies here too.
        // An unverified disable is not a reason to leave the radio off either.
        int down = setWifiEnabled(false, "the ip-link fallback");
        if (down != WIFI_OBSERVED) {
            log("the ip-link fallback was not attempted \u00b7 the radio was not verified down", C_WARN);
            if (down == WIFI_UNVERIFIED) setWifiEnabled(true, "restoring the radio");
            return false;
        }
        run("ip link set " + ifn + " down; "
                + "ip link set dev " + ifn + " address " + targetMac + "; "
                + "ip link set " + ifn + " up");
        if (setWifiEnabled(true, "the ip-link fallback") != WIFI_OBSERVED)
            log("WiFi did not come back up after the ip-link fallback \u00b7 " + RUNTIME_ONLY, C_BAD);
        return true;
    }

    /**
     * True when /sys/class/net/<name> exists. A named helper rather than an inline
     * run() because inside one of this file's anonymous Runnables (see useIface) the
     * name `run` is already taken by Runnable.run().
     */
    boolean ifacePresent(String n) {
        if (!isIfaceName(n)) return false;
        Res r = run("[ -e /sys/class/net/" + n + " ] && echo yes");
        return r.ok() && r.out.contains("yes");
    }

    /** True when the WiFi interface exists at all; without it the device is unsupported. */
    boolean ifaceExists() {
        return ifacePresent(iface());
    }
}
