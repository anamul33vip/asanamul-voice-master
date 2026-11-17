package com.anamul.voice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Own original Shizuku integration for AS ANAMUL VOICE.
 * Mic Mode runs our own Injector (com.anamul.voice.Injector) as a
 * shell-UID process via Shizuku's app_process, so its audio is mixed into
 * the microphone stream. No third-party injector code is used.
 * v2.22 — the app talks to the injector through FILES in the app's
 * EXTERNAL files dir (cmd + inject.log), not a socket: a shell-UID
 * process's abstract LocalServerSocket and an app-UID process
 * connecting to it can be blocked, and a socket gives the app no log
 * when it fails. The external dir is writable by the shell
 * (sdcard_rw); the private files dir (mode 700) is not.
 */
public class MicInjector {

    private static final String TAG = "AVMic";
    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    // v2.16 — injector_shell.log holds the shell+nohup path's log
    // (shell diagnostic + the injector's stdout+stderr, drained
    // Java-side into the app's own files dir). injector_last.txt
    // stays for the direct-exec fallback path.
    private static final String SHELL_LOG_NAME = "injector_shell.log";
    private static final String DIRECT_LOG_NAME = "injector_last.txt";
    // v2.21 — the injector writes its own log to <filesDir>/inject.log
    // (Injector.fileLog). The app reads it FIRST for the failure
    // message + log viewer; injector_shell.log (the Java-side drain)
    // and injector_last.txt (direct-exec fallback) are the fallbacks.
    // (v2.17–v2.20 redirected the injector's output to a file on
    // /sdcard via `tee` — REMOVED in v2.21: if the shell cannot
    // write /sdcard, `tee` exits and the injector dies on its first
    // print (SIGPIPE), or the failed redirect stops the command
    // from running at all. The reference app sends the output to
    // /dev/null and the injector logs to its own file instead.)
    public static final String INJECT_LOG_NAME = "inject.log";
    // v2.23 — GUARANTEED log mirror: /data/local/tmp is
    // world-writable (the shell can ALWAYS write it) and
    // world-readable (the app can ALWAYS read it). The Injector
    // mirrors every fileLog() line here (Injector.TMP_LOG) in
    // ADDITION to the working dir's inject.log, so the injector's
    // output can never be lost again — even if scoped-storage
    // FUSE blocks the shell from the external files dir. The
    // log viewer reads this FIRST; the liveness checks accept a
    // hit in EITHER log. The pre-clean deletes it on every fresh
    // attempt so a stale "Injector ready" can never fake a start.
    public static final String TMP_LOG_PATH = "/data/local/tmp/anamul_voice_master_inject.log";
    // v2.19 — FULL Android runtime environment for the injector
    // launch. app_process needs these to initialise ART (and to
    // write oat/dex files); the Shizuku newProcess env may NOT
    // carry them, and the shell may not inherit them — so set
    // them EXPLICITLY, inline in every shell command. v2.24 —
    // the reference app's K0.C passes env=NULL to
    // Shizuku.newProcess and puts EVERYTHING inline in the
    // command string (CLASSPATH + ANDROID_DATA/ROOT/STORAGE).
    // A non-null env array may REPLACE the process environment
    // and break ART — so v2.24 passes env=NULL to EVERY
    // newProcess call (shell, pre-flight, test) and sets the env
    // ONLY inline. These are the standard values adb shell uses.
    // v2.24 — REMOVED: the ENV_ANDROID_DATA / ENV_ANDROID_ROOT /
    // ENV_ANDROID_STORAGE constants (v2.19–v2.23 built the
    // newProcess env array from them). The runtime env now lives
    // ONLY inline in the command string (RUNTIME_ENV_INLINE
    // below) — env=NULL is passed to every newProcess call.
    // Inline form for `sh -c` commands (env assignments before
    // the command apply to it and to everything it execs).
    private static final String RUNTIME_ENV_INLINE =
            "ANDROID_DATA=/data ANDROID_ROOT=/system ANDROID_STORAGE=/storage ";
    // v2.17 — SHORT failure reason for the on-screen message
    // (e.g. "injector did not become ready after 10s"). Set at every
    // failure point; the message shows this + the injector's own
    // last line ONLY — never a long diagnostic dump.
    private static volatile String lastFailShort = "";
    // v2.25 — which path started the CURRENT injector ("shizuku" /
    // "root"), set on every successful start. The policy=false
    // retry in startInjector() uses it to know whether ROOT has
    // already been tried (never retry root twice in one start).
    private static volatile String lastStartPath = "";
    public static final int REQ_SHIZUKU_PERMISSION = 1001;

    private static boolean micModeOn = false;
    private static boolean injectorConnected = false;
    // v2.24 — REMOVED: the injectorProcess field (v2.11–v2.23 held
    // the direct-exec process here for exitValue()/destroy()).
    // The direct newProcess fallback is gone; the shell and root
    // paths are detached (nohup + &), so there is no process to
    // hold — stopInjector() kills the injector via the cmd file
    // + pkill only.

    public interface StartCallback {
        void done(boolean ok, String messageBn, String messageEn);
    }

    /** v2.24 — REMOVED: injectorEnv() (v2.19–v2.23 passed a
     * non-null env array — CLASSPATH + ANDROID_DATA/ROOT/STORAGE
     * — to Shizuku.newProcess). The reference app's K0.C passes
     * env=NULL and puts EVERYTHING inline in the command string;
     * a non-null env may REPLACE the process environment and
     * break ART. v2.24 passes env=NULL to every newProcess call
     * and sets CLASSPATH + the runtime env ONLY inline. */

    /** v2.23 — the injector's working dir, the reference app's
     * EXACT form (read from its K0.k(Context)): a SUBDIRECTORY
     * named "inject" inside the app's EXTERNAL files dir
     * (getExternalFilesDir("inject") =
     * /sdcard/Android/data/<pkg>/files/inject/), falling back to
     * new File(getFilesDir(), "inject") when external is null,
     * then mkdirs(). v2.22 used getExternalFilesDir(null) (the
     * ROOT) — the only dir difference left. The injector runs as
     * shell uid 2000 (sdcard_rw, CAN write the external dir) but
     * CANNOT write the private files dir (mode 700). The cmd file,
     * inject.log and the injector's working dir all live here.
     * Uses getApplicationContext() like the reference. Never
     * throws (null when neither dir is available). */
    public static java.io.File injectorDir(Context ctx) {
        try {
            Context appCtx = ctx;
            try {
                Context ac = ctx.getApplicationContext();
                if (ac != null) appCtx = ac;
            } catch (Throwable ignored) {}
            java.io.File d = null;
            try {
                d = appCtx.getExternalFilesDir("inject");
            } catch (Throwable ignored) {}
            if (d == null) {
                try {
                    d = new java.io.File(appCtx.getFilesDir(), "inject");
                } catch (Throwable ignored) {}
            }
            if (d != null) {
                try { d.mkdirs(); } catch (Throwable ignored) {}
                return d;
            }
        } catch (Throwable ignored) {}
        try {
            return ctx.getFilesDir();
        } catch (Throwable t) {
            return null;
        }
    }

    /** v2.21 — progress while the injector is starting: the
     * auto-launch waits up to 10 s (the reference app turns Inject
     * ON within ~1–2 s when Shizuku is running + permitted), so the
     * UI shows "starting… (Xs)" instead of looking stuck. Set by
     * the UI before startInjector(), cleared when it finishes; may
     * be null (overlay starts never set it). Posted on the main
     * thread; never throws. */
    public interface ProgressCallback {
        void onProgress(int elapsedSeconds);
    }
    private static volatile ProgressCallback progressCb = null;
    public static void setProgressCallback(ProgressCallback cb) { progressCb = cb; }
    private static void postProgress(final int elapsedSeconds) {
        try {
            final ProgressCallback cb = progressCb;
            if (cb == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try { cb.onProgress(elapsedSeconds); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    public static boolean isMicActive() {
        // v2.7 — must never throw to a caller.
        try { return micModeOn && injectorConnected; } catch (Throwable t) { return false; }
    }

    public static boolean isMicModeOn() {
        // v2.7 — must never throw to a caller.
        try { return micModeOn; } catch (Throwable t) { return false; }
    }

    public static void setMicMode(boolean on) {
        micModeOn = on;
        if (!on) injectorConnected = false;
    }

    /** Shizuku API needs Android 6.0+ (API 23). */
    public static boolean apiLevelOk() {
        return Build.VERSION.SDK_INT >= 23;
    }

    public static boolean isShizukuInstalled(Context ctx) {
        try {
            if (ctx == null) return false;
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isShizukuRunning() {
        if (!apiLevelOk()) return false;
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        if (!apiLevelOk()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        }
    }

    /** Open the Shizuku app (or its Play Store page if it cannot be launched). */
    public static void openShizukuApp(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            }
        } catch (Exception ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + SHIZUKU_PKG));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://play.google.com/store/apps/details?id=" + SHIZUKU_PKG));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Start the Injector via Shizuku. Runs the checks in order and reports
     * a clear Bengali/English message. Must be called from the UI thread
     * (permission dialog needs an Activity context).
     */
    public static void startInjector(final Context ctx, final StartCallback cb) {
        if (!apiLevelOk()) {
            cb.done(false, "মাইক মোডের জন্য Android 6.0+ লাগবে", "Mic Mode needs Android 6.0+");
            return;
        }
        if (!isShizukuInstalled(ctx)) {
            cb.done(false,
                    "Shizuku অ্যাপ ইনস্টল নেই। Play Store থেকে Shizuku ইনস্টল করুন।",
                    "Shizuku app is not installed. Install Shizuku from Play Store.");
            return;
        }
        if (!isShizukuRunning()) {
            // v2.10 — one short neutral line, no restart lecture, and
            // do NOT open Shizuku from here (the old nag toast is gone).
            cb.done(false,
                    "Shizuku কানেক্ট হয়নি — ইনজেক্ট ট্যাবে 'Shizuku চেক / পারমিশন' চাপুন",
                    "Shizuku not connected — tap 'Shizuku Check / Permission' on the Inject tab");
            return;
        }
        if (!hasPermission()) {
            // Ask Shizuku for permission; the caller listens for the result
            // and calls startInjector() again once granted.
            requestPermission();
            cb.done(false,
                    "Shizuku অনুমতি চাওয়া হয়েছে — অনুমতি দিলে আবার Mic Mode চালু করুন।",
                    "Shizuku permission requested — allow it, then turn Mic Mode on again.");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                // v2.21 — try in order: shell+nohup (the reference
                // app's exact command) -> direct newProcess.
                boolean ok = startInjectorAll(ctx);
                // v2.25 — the injector may be "ready" while its
                // AudioPolicy NEVER registered (v2.24 log:
                // "setupAudioPolicy failed:
                // InvocationTargetException", "(policy=false)") —
                // then the AudioTrack plays through the SPEAKER
                // instead of the mic, so the other player hears
                // nothing. A ready injector with policy=false is
                // NOT a success: stop it and retry via ROOT (uid 0),
                // where registerAudioPolicy may be allowed (the
                // shell uid 2000 may be rejected). At most ONE root
                // retry per start; if root ALSO yields policy=false,
                // fail with the policy reason from the log so the
                // message names the cause.
                if (ok) {
                    try {
                        java.io.File injDir = injectorDir(ctx);
                        java.io.File injLog = injDir == null ? null
                                : new java.io.File(injDir, INJECT_LOG_NAME);
                        boolean policyFalse = injLog != null
                                && anyInjectorLogHasLine(injLog, "policy=false");
                        if (policyFalse && !"root".equals(lastStartPath)) {
                            try { sendInjectorCommand(ctx, "quit"); } catch (Throwable ignored) {}
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                            try {
                                newProcessReflective(
                                        new String[]{"sh", "-c",
                                                "pkill -f 'com.anamul.voice.Injecto[r]'"},
                                        null, null);
                            } catch (Throwable ignored) {}
                            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                            boolean rootOk = false;
                            try { rootOk = startInjectorRoot(ctx); } catch (Throwable ignored) {}
                            if (rootOk) {
                                boolean stillFalse = injLog != null
                                        && anyInjectorLogHasLine(injLog, "policy=false");
                                if (!stillFalse) {
                                    ok = true;
                                } else {
                                    ok = false;
                                    setPolicyFailReason(ctx);
                                }
                            } else {
                                ok = false;
                                setPolicyFailReason(ctx);
                            }
                        } else if (policyFalse) {
                            // Already root and still policy=false.
                            ok = false;
                            setPolicyFailReason(ctx);
                        }
                    } catch (Throwable ignored) {}
                }
                if (!ok) {
                    // v2.15 — give the drain thread 500 ms to flush
                    // the injector's stderr into injector_last.txt
                    // BEFORE the callback reads it (close the drain
                    // race: the failure message would otherwise show
                    // a stale/empty log).
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
                // NOTE (Master): no auto-upload here — the injector log
                // stays on the phone (MainActivity shows its last line).
                final boolean fok = ok;
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    public void run() {
                        if (fok) {
                            injectorConnected = true;
                            micModeOn = true;
                            cb.done(true,
                                    "মাইক মোড: কানেক্টেড — এখন সাউন্ড মাইক্রোফোনে যাবে",
                                    "Mic Mode: connected — sounds now go to the mic");
                        } else {
                            injectorConnected = false;
                            // v2.17 — SHORT failure message: the
                            // reason (lastFailShort) + the injector's
                            // own last line ONLY, under ~120 chars.
                            // The long `id` dump and the pre-flight
                            // output stay in the LOG FILE ONLY — they
                            // NEVER go in the message (v2.16 confused
                            // the user: the id dump ate the whole
                            // message and hid the real error).
                            String reason = lastFailShort;
                            if (reason == null || reason.length() == 0) {
                                reason = "Shizuku চলছে কিনা দেখুন।";
                            }
                            String last = getInjectorMessageLine(ctx);
                            String detail = reason;
                            if (last.length() > 0) {
                                if (last.length() > 60) last = last.substring(0, 60);
                                detail = reason + " | " + last;
                            }
                            if (detail.length() > 120) detail = detail.substring(0, 120);
                            cb.done(false,
                                    "Injector চালু হয়নি — " + detail,
                                    "Injector did not start — " + detail);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * Shizuku API 13 made Shizuku.newProcess() private — invoke it via
     * reflection. Same arguments as the old public call (cmd, env, dir).
     * Throws on failure; the caller (startInjectorProcess) catches it
     * and reports the failure in Bengali through the callback.
     */
    private static ShizukuRemoteProcess newProcessReflective(
            String[] cmd, String[] env, String dir) throws Exception {
        java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        Object o = m.invoke(null, cmd, env, dir);
        return (ShizukuRemoteProcess) o;
    }

    /**
     * v2.16 — run one SHORT shell command via Shizuku (`sh -c
     * <cmd>`) and capture its combined stdout+stderr. Returns the
     * output (possibly "") when the shell ran, or null when the
     * shell itself could not be started (newProcess threw /
     * returned null). The command must exit on its own within
     * timeoutMs. Runs on the caller's (background) thread; never
     * throws. v2.24 — env=NULL (the reference app's K0.C): the
     * env assignments live ONLY inline in the command string.
     */
    private static String runShellCapture(Context ctx, String shellCmd, int timeoutMs) {
        try {
            final ShizukuRemoteProcess p = newProcessReflective(
                    new String[]{"sh", "-c", shellCmd},
                    null, null);
            if (p == null) return null;
            final StringBuffer out = new StringBuffer();
            Thread tOut = new Thread(new Runnable() {
                public void run() {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (out) { out.append(line).append("\n"); }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Thread tErr = new Thread(new Runnable() {
                public void run() {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getErrorStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (out) { out.append(line).append("\n"); }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            tOut.start();
            tErr.start();
            final boolean[] done = new boolean[1];
            Thread w = new Thread(new Runnable() {
                public void run() {
                    try { p.waitFor(); } catch (Throwable ignored) {}
                    done[0] = true;
                }
            });
            w.start();
            try { w.join(timeoutMs); } catch (InterruptedException ignored) {}
            if (!done[0]) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
            try { tOut.join(500); } catch (Throwable ignored) {}
            try { tErr.join(500); } catch (Throwable ignored) {}
            String s;
            synchronized (out) { s = out.toString(); }
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v2.16 — launch the injector via `sh -c <shellCmd>` where the
     * command backgrounds itself with nohup + `&` (the shell+nohup app_process
     * path). Returns true when the shell was launched; false when
     * newProcess threw or returned null. Does NOT wait for the
     * shell (it exits at once) and does NOT use exitValue() — the
     * inject.log "Injector ready" line is the liveness signal. The shell's stdout+stderr
     * are drained (Java-side) into the shell log file — v2.21: the
     * injector's own output goes to /dev/null, so this drain only
     * sees the SHELL's output (e.g. "command not found"); the
     * injector's own lines live in <filesDir>/inject.log. The drain
     * threads run until EOF (the injector exits) and never throw.
     */
    private static boolean launchShellInjector(Context ctx, String shellCmd,
            java.io.File shellLog) {
        try {
            // v2.24 — env=NULL (the reference app's K0.C):
            // CLASSPATH + the runtime env are ONLY inline in the
            // command string, never in the env array.
            final ShizukuRemoteProcess p = newProcessReflective(
                    new String[]{"sh", "-c", shellCmd},
                    null, null);
            if (p == null) return false;
            // Drain BOTH streams into the shell log (the injector
            // inherits these pipes from sh). No shell redirect is
            // used (see the NOTE in startInjectorProcess).
            drainStreamToFile(p.getInputStream(), shellLog);
            drainStreamToFile(p.getErrorStream(), shellLog);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "launchShellInjector failed", t);
            return false;
        }
    }

    /**
     * v2.24 — the injector start chain. PRIMARY: shell + nohup
     * via Shizuku with the reference app's EXACT command and
     * env=NULL (K0.C). FALLBACK: ROOT — `su -c` with the SAME
     * command string (the reference app's K0.x). The direct
     * newProcess exec (v2.11–v2.23's fallback) is REMOVED: with
     * env=NULL it cannot carry CLASSPATH, so app_process could
     * never find the class — and it returned "exited (code 0)
     * — no startup output" in every version. Returns true as
     * soon as one path's inject.log shows "Injector ready".
     * Never throws.
     */
    private static boolean startInjectorAll(Context ctx) {
        try {
            if (startInjectorProcess(ctx)) return true;
        } catch (Throwable ignored) {}
        try {
            if (startInjectorRoot(ctx)) return true;
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /**
     * v2.16 — ROOT-CAUSE FIX: start the Injector via the SHIZUKU
     * SHELL with `nohup app_process ...`, exactly the way the reference mechanism
     * VOICE does it (K0.G: "nohup app_process /system/bin
     * com.issakib.isffvoice.Injector <dir>") — and the way it
     * WORKS on his phone. The direct newProcess exec (v2.11-v2.15)
     * returned a process that exited with code 0 before main()
     * ever printed ("no startup output", v2.15 verdict) — five
     * versions of the same failure, so the direct-exec path is
     * the problem, not the Injector code. The `sh` process exits
     * immediately (code 0) after launching nohup — that is
     * EXPECTED (the injector is detached); the inject.log
     * "Injector ready" line is the only liveness signal.
     * v2.21 — the EXACT reference command (read from the reference
     * app's K0.smali, lines 930–980): BEFORE starting, delete
     * `<dir>/cmd` and `<dir>/inject.log` and run
     * `pkill -f <Injector class>` (kills any stale injector); then
     * ONE command string:
     * `CLASSPATH=<apk> nohup app_process /system/bin
     * com.anamul.voice.Injector <dir> >/dev/null 2>&1 &`
     * Output goes to /dev/null — NOT to a file. The injector logs
     * to its OWN file (<filesDir>/inject.log, Injector.fileLog),
     * and the inject.log "Injector ready" line is the ONLY liveness
     * signal. v2.17–v2.20
     * redirected the output to /sdcard via `tee` — that is what
     * killed every version since v2.17: if the shell cannot write
     * /sdcard, `tee` exits and the injector dies on its first
     * print (SIGPIPE), or the failed redirect stops the command
     * from running at all ("no startup output"). v2.21 is the
     * FIRST version with BOTH fixes: explicit CLASSPATH inline
     * (v2.18) AND /dev/null (no /sdcard redirect).
     * v2.24 — the chain is now: this Shizuku path (env=NULL,
     * K0.C) -> the ROOT path (`su -c`, K0.x, startInjectorRoot).
     * The direct newProcess exec is REMOVED (env=NULL cannot
     * carry CLASSPATH, so app_process could never find the
     * class). Failure reasons are prefixed "shizuku: " so the
     * message shows which path was tried.
     */
    private static boolean startInjectorProcess(Context ctx) {
        // v2.13 — PRE-CHECK: an injector from an earlier attempt may
        // already be listening. Reuse it instead of starting a
        // second one. v2.22 — the check is FILE-based: "ping" -> the
        // injector appends "pong" to inject.log (a nonce guards
        // against a stale pong from an earlier run).
        try {
            if (pingInjector(ctx)) {
                lastStartPath = "shizuku";
                return true;
            }
        } catch (Throwable ignored) {}
        // v2.17 — a stale reason must never be shown for a new attempt.
        lastFailShort = "";
        java.io.File shellLog = new java.io.File(ctx.getFilesDir(), SHELL_LOG_NAME);
        java.io.File logFile = new java.io.File(ctx.getFilesDir(), DIRECT_LOG_NAME);
        try {
            // Fresh logs for this attempt (a stale reason must
            // never be shown for a new attempt).
            if (shellLog.exists()) shellLog.delete();
            if (logFile.exists()) logFile.delete();
        } catch (Throwable ignored) {}
        try {
            // v2.22 — the injector's working dir is the EXTERNAL
            // files dir (shell-writable); the private files dir is
            // only the fallback. The injector's <dir>/cmd and
            // <dir>/inject.log live here.
            java.io.File injDir = injectorDir(ctx);
            String filesDir = injDir != null ? injDir.getAbsolutePath()
                    : ctx.getFilesDir().getAbsolutePath();
            // --- v2.21 PRE-FLIGHT CHECK: do nohup / app_process
            // even exist? The output goes to the LOG FILE ONLY
            // — never into the failure message (v2.16's long `id`
            // dump confused the user and hid the real error).
            String pre = runShellCapture(ctx,
                    "command -v nohup app_process; ls -l /system/bin/app_process", 5000);
            if (pre == null) {
                // The shell itself could not be launched — the
                // caller (startInjectorAll) tries the ROOT path
                // next. The direct newProcess fallback is REMOVED
                // in v2.24 (env=NULL cannot carry CLASSPATH).
                lastFailShort = "shizuku: shell start failed";
                appendInjectorLog(logFile,
                        "shizuku: shell start failed — trying root next");
                return false;
            }
            appendInjectorLog(shellLog, "shell: " + pre);
            boolean hasNohup = pre.contains("/nohup");
            boolean appProcessMissing = pre.contains("No such file");
            if (appProcessMissing) {
                // Clear short reason: the user must see this.
                lastFailShort = "shizuku: app_process not found";
                appendInjectorLog(shellLog, "shizuku: app_process not found");
                return false;
            }
            // --- v2.21 PRE-CLEAN (the reference app does this
            // BEFORE every start): kill any stale injector and
            // delete the stale cmd + inject.log files, so a dead
            // injector can never hold the cmd file and the log
            // is fresh for this attempt. Best-effort, ignore the
            // result — the start below must not depend on it.
            try {
                runShellCapture(ctx,
                        "pkill -f 'com.anamul.voice.Injecto[r]'; rm -f '"
                        + filesDir + "/cmd' '" + filesDir + "/inject.log' "
                        + TMP_LOG_PATH, 5000);
            } catch (Throwable ignored) {}
            // --- START VIA SHELL + nohup (the shell+nohup app_process path) ---
            // v2.21 — the EXACT reference command: explicit
            // CLASSPATH inline (v2.18) + the full runtime env
            // inline (v2.19) + output to /dev/null (NO /sdcard
            // redirect, NO tee — the tee/redirect is what killed
            // v2.17–v2.20). When `nohup` is missing, app_process
            // runs without it (backgrounded with & so the shell
            // still exits).
            String apk = "";
            try { apk = ctx.getApplicationInfo().sourceDir; } catch (Throwable ignored) {}
            String cpPrefix = "CLASSPATH='" + apk + "' ";
            String shellCmd;
            if (hasNohup) {
                shellCmd = RUNTIME_ENV_INLINE + cpPrefix + "nohup app_process /system/bin "
                        + "com.anamul.voice.Injector '" + filesDir + "' >/dev/null 2>&1 &";
            } else {
                shellCmd = RUNTIME_ENV_INLINE + cpPrefix + "app_process /system/bin "
                        + "com.anamul.voice.Injector '" + filesDir + "' >/dev/null 2>&1 &";
            }
            boolean launched = launchShellInjector(ctx, shellCmd, shellLog);
            if (!launched) {
                lastFailShort = "shizuku: shell start failed";
                appendInjectorLog(logFile,
                        "shizuku: shell start failed — trying root next");
                return false;
            }
            // --- RETRY LOOP (v2.22): poll the injector's OWN file
            // log for "Injector ready" every 400 ms for up to 10 s.
            // Do NOT use exitValue() here — the `sh` process exits
            // immediately BY DESIGN (it only launched nohup), so an
            // exit code from it says nothing about the injector.
            // The "Injector ready" line in <dir>/inject.log is the
            // liveness signal (the injector writes it after the
            // AudioTrack is built). 10 s, not 60 s: the reference
            // app turns Inject ON within ~1–2 s when Shizuku is
            // running + permitted — the user asked for no more
            // 1-minute waits. Progress is posted (once per second)
            // so the UI shows "starting… (Xs)".
            java.io.File injectLog = new java.io.File(filesDir, INJECT_LOG_NAME);
            int lastPosted = -1;
            for (int i = 0; i < 25; i++) {
                if (anyInjectorLogHasLine(injectLog, "Injector ready")) {
                    lastStartPath = "shizuku";
                    return true;
                }
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                int elapsed = (i + 1) * 2 / 5;
                if (elapsed > 0 && elapsed != lastPosted) {
                    lastPosted = elapsed;
                    postProgress(elapsed);
                }
            }
            // The shell ran and the injector was launched, but
            // inject.log never got "Injector ready" in 10 s. The
            // injector's own last line (read by the failure
            // callback from its file log) now names the cause:
            // "Injector main started" present = main() ran but the
            // AudioPolicy/track never finished; absent = the
            // process died on load (or its log was never created).
            boolean noStartup = !logHasLine(shellLog, "Injector main started")
                    && !anyInjectorLogHasLine(injectLog, "Injector main started");
            String timeoutLine;
            if (noStartup) {
                timeoutLine = "shizuku: injector did not start after 10s — no startup output";
                lastFailShort = "shizuku: injector did not start after 10s — no startup output";
            } else {
                timeoutLine = "shizuku: injector did not become ready after 10s";
                lastFailShort = "shizuku: injector did not become ready after 10s";
            }
            appendInjectorLog(shellLog, timeoutLine);
            return false;
        } catch (Throwable t) {
            try {
                lastFailShort = "shizuku: start failed";
                String msg = t.getMessage();
                String line = "startInjectorProcess failed: " + t.getClass().getSimpleName()
                        + (msg != null && msg.length() > 0 ? ": " + msg : "");
                if (line.length() > 200) line = line.substring(0, 200);
                appendInjectorLog(logFile, line);
            } catch (Throwable ignored) {}
            Log.w(TAG, "startInjectorProcess failed", t);
            return false;
        }
    }

    /**
     * v2.24 — ROOT probe: is `su` available AND does it give uid 0?
     * Runs `su -c id` via a LOCAL ProcessBuilder (NOT Shizuku) with
     * a 5 s timeout and checks the output for "uid=0". Returns
     * false when su is missing, when it exits without uid 0, or
     * when it hangs (e.g. a root prompt the user never answers —
     * the probe process is destroyed after the timeout). Uses the
     * thread-join pattern (NOT Process.waitFor(timeout, unit) —
     * that is API 26+ and this app is minSdk 21). Never throws.
     */
    private static boolean isRootAvailable() {
        try {
            final Process p = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true).start();
            if (p == null) return false;
            final StringBuffer out = new StringBuffer();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (out) { out.append(line).append("\n"); }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            t.start();
            final boolean[] done = new boolean[1];
            Thread w = new Thread(new Runnable() {
                public void run() {
                    try { p.waitFor(); } catch (Throwable ignored) {}
                    done[0] = true;
                }
            });
            w.start();
            try { w.join(5000); } catch (InterruptedException ignored) {}
            if (!done[0]) {
                try { p.destroy(); } catch (Throwable ignored) {}
                try { t.join(300); } catch (Throwable ignored) {}
                return false;
            }
            try { t.join(500); } catch (Throwable ignored) {}
            String s;
            synchronized (out) { s = out.toString(); }
            return s != null && s.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v2.24 — run one SHORT command via `su -c <cmd>` (a LOCAL
     * ProcessBuilder, NOT Shizuku) and capture its combined
     * stdout+stderr (redirectErrorStream(true) merges them).
     * Returns the output (possibly "") when su ran, or null when
     * su could not be started. The command must exit on its own
     * within timeoutMs. Never throws.
     */
    private static String runRootCapture(String rootCmd, int timeoutMs) {
        try {
            final Process p = new ProcessBuilder("su", "-c", rootCmd)
                    .redirectErrorStream(true).start();
            if (p == null) return null;
            final StringBuffer out = new StringBuffer();
            Thread tOut = new Thread(new Runnable() {
                public void run() {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (out) { out.append(line).append("\n"); }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            tOut.start();
            final boolean[] done = new boolean[1];
            Thread w = new Thread(new Runnable() {
                public void run() {
                    try { p.waitFor(); } catch (Throwable ignored) {}
                    done[0] = true;
                }
            });
            w.start();
            try { w.join(timeoutMs); } catch (InterruptedException ignored) {}
            if (!done[0]) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
            try { tOut.join(500); } catch (Throwable ignored) {}
            String s;
            synchronized (out) { s = out.toString(); }
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v2.24 — ROOT FALLBACK (the reference app's K0.x, read from
     * its smali): `new ProcessBuilder("su", "-c", cmd)` with
     * redirectErrorStream(true), start(), and the input stream
     * read. The command string is the SAME as the Shizuku path
     * (CLASSPATH + full runtime env inline, nohup app_process
     * ... >/dev/null 2>&1 &). Only tried AFTER the Shizuku path
     * failed (startInjectorAll). If the phone is not rooted
     * (no `su`, or `su -c id` is not uid=0), the root path is
     * SKIPPED and the Shizuku failure reason is KEPT (the root
     * check is a fallback, not the primary error — only a log
     * line records the skip). The pre-clean (pkill + rm -f)
     * runs through the SAME path (su) that will launch the
     * injector. The inject.log "Injector ready" line is the
     * liveness signal (10 s, same as the Shizuku path). Failure
     * reasons are prefixed "root: " so the message shows which
     * path was tried. Never throws.
     */
    private static boolean startInjectorRoot(Context ctx) {
        try {
            if (!isRootAvailable()) {
                appendInjectorLog(new java.io.File(ctx.getFilesDir(), DIRECT_LOG_NAME),
                        "root: su not available — skipped root fallback");
                return false;
            }
            java.io.File shellLog = new java.io.File(ctx.getFilesDir(), SHELL_LOG_NAME);
            java.io.File injDir = injectorDir(ctx);
            String filesDir = injDir != null ? injDir.getAbsolutePath()
                    : ctx.getFilesDir().getAbsolutePath();
            // --- ROOT PRE-CLEAN (same path that launches): kill
            // any stale injector and delete the stale cmd +
            // inject.log + TMP mirror, so a dead injector can
            // never hold the cmd file and the log is fresh.
            try {
                runRootCapture("pkill -f 'com.anamul.voice.Injecto[r]'; rm -f '"
                        + filesDir + "/cmd' '" + filesDir + "/inject.log' "
                        + TMP_LOG_PATH, 5000);
            } catch (Throwable ignored) {}
            // --- START VIA ROOT (`su -c`, K0.x) ---
            // The SAME full command string as the Shizuku path:
            // runtime env + CLASSPATH inline, nohup app_process,
            // output to /dev/null. Root can write anywhere, so
            // the shell-uid-2000 write blocks do not apply here.
            String apk = "";
            try { apk = ctx.getApplicationInfo().sourceDir; } catch (Throwable ignored) {}
            String rootCmd = RUNTIME_ENV_INLINE + "CLASSPATH='" + apk + "' nohup app_process /system/bin "
                    + "com.anamul.voice.Injector '" + filesDir + "' >/dev/null 2>&1 &";
            final Process p;
            try {
                p = new ProcessBuilder("su", "-c", rootCmd).redirectErrorStream(true).start();
            } catch (Throwable t) {
                lastFailShort = "root: start failed";
                appendInjectorLog(shellLog,
                        "root: start failed: " + t.getClass().getSimpleName());
                return false;
            }
            if (p == null) {
                lastFailShort = "root: start failed";
                appendInjectorLog(shellLog, "root: start failed: null process");
                return false;
            }
            // Drain the merged stdout+stderr into the shell log so
            // the su child never blocks on a full pipe (the
            // injector's own output goes to /dev/null; this drain
            // only sees su's own errors, e.g. "Permission denied").
            drainStreamToFile(p.getInputStream(), shellLog);
            // --- RETRY LOOP: poll the injector's OWN file log for
            // "Injector ready" every 400 ms for up to 10 s (same
            // as the Shizuku path). Do NOT use exitValue() — su
            // exits at once after launching nohup, so its exit
            // code says nothing about the injector.
            java.io.File injectLog = new java.io.File(filesDir, INJECT_LOG_NAME);
            int lastPosted = -1;
            for (int i = 0; i < 25; i++) {
                if (anyInjectorLogHasLine(injectLog, "Injector ready")) {
                    lastStartPath = "root";
                    return true;
                }
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                int elapsed = (i + 1) * 2 / 5;
                if (elapsed > 0 && elapsed != lastPosted) {
                    lastPosted = elapsed;
                    postProgress(elapsed);
                }
            }
            // su should have exited long ago (nohup + & detaches);
            // if it is still alive it never completed — destroy
            // it best-effort (the injector is detached by nohup,
            // so destroying su cannot kill a started injector).
            try { p.destroy(); } catch (Throwable ignored) {}
            boolean noStartup = !logHasLine(shellLog, "Injector main started")
                    && !anyInjectorLogHasLine(injectLog, "Injector main started");
            if (noStartup) {
                lastFailShort = "root: injector did not start after 10s — no startup output";
            } else {
                lastFailShort = "root: injector did not become ready after 10s";
            }
            appendInjectorLog(shellLog, lastFailShort);
            return false;
        } catch (Throwable t) {
            try {
                lastFailShort = "root: start failed";
                appendInjectorLog(new java.io.File(ctx.getFilesDir(), DIRECT_LOG_NAME),
                        "root: start failed: " + t.getClass().getSimpleName());
            } catch (Throwable ignored) {}
            return false;
        }
    }

    /** v2.12 — drain a stream AND append every line to a log file
     * (kept to the last ~4 KB). Runs on its own thread, never blocks
     * the process start, and never throws. Used for the injector's
     * stderr so a failed start leaves its real reason behind. */
    private static void drainStreamToFile(final java.io.InputStream in,
            final java.io.File logFile) {
        if (in == null || logFile == null) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        appendInjectorLog(logFile, line);
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /** Append one line to the injector log, trimming it to the last
     * ~4 KB. Synchronized: only the drain thread writes, but the
     * file is also read from the UI thread. Never throws. */
    private static synchronized void appendInjectorLog(java.io.File logFile, String line) {
        try {
            String old = "";
            try {
                java.io.FileInputStream fin = new java.io.FileInputStream(logFile);
                java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = fin.read(buf)) > 0) bout.write(buf, 0, n);
                fin.close();
                old = new String(bout.toByteArray(), "UTF-8");
            } catch (Exception ignored) {}
            String all = old + line + "\n";
            if (all.length() > 4096) all = all.substring(all.length() - 4096);
            java.io.FileOutputStream fout = new java.io.FileOutputStream(logFile, false);
            fout.write(all.getBytes("UTF-8"));
            fout.close();
        } catch (Throwable ignored) {}
    }

    /** v2.23 — does EITHER injector log contain the given line?
     * The TMP mirror (/data/local/tmp/anamul_voice_master_inject.log) is
     * checked FIRST — it is the guaranteed record (the shell can
     * always write /data/local/tmp); the working dir's inject.log
     * is the primary. Used by every liveness check so a hit in
     * either log counts. Never throws. */
    private static boolean anyInjectorLogHasLine(java.io.File injectLog, String text) {
        try {
            if (logHasLine(new java.io.File(TMP_LOG_PATH), text)) return true;
        } catch (Throwable ignored) {}
        return logHasLine(injectLog, text);
    }

    /** v2.15 — does injector_last.txt contain a line with the given
     * text? Used to say whether the injector's main() ever printed
     * its startup line ("Injector main started"). Never throws. */
    private static boolean logHasLine(java.io.File logFile, String text) {
        try {
            if (logFile == null || !logFile.exists()) return false;
            java.io.FileInputStream in = new java.io.FileInputStream(logFile);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8").contains(text);
        } catch (Throwable t) {
            return false;
        }
    }

    /** v2.21 — ALL injector log sources concatenated with clear
     * separators, so NO output is missed: the TMP mirror
     * (/data/local/tmp/anamul_voice_master_inject.log — the injector's
     * guaranteed log, FIRST), the injector's OWN file log
     * (<dir>/inject.log — the injector writes its startup
     * lines there, since its stderr goes to /dev/null), the
     * Java-side drain (injector_shell.log — the shell's own stderr,
     * e.g. "command not found", lives HERE), and injector_last.txt
     * (the direct-exec fallback path). A missing/empty source is
     * noted, not silently skipped. v2.22 — inject.log lives in the
     * EXTERNAL files dir (injectorDir), not the private files dir.
     * v2.23 — the TMP mirror is read FIRST (it can never be
     * lost). Never throws. */
    public static String getInjectorLogCombined(Context ctx) {
        try {
            if (ctx == null) return "";
            java.io.File injDir = injectorDir(ctx);
            StringBuilder sb = new StringBuilder();
            appendLogSection(sb, "--- /data/local/tmp/anamul_voice_master_inject.log (TMP mirror) ---",
                    readLogFile(new java.io.File(TMP_LOG_PATH)));
            appendLogSection(sb, "--- inject.log (injector's own log) ---",
                    readLogFile(injDir == null ? null : new java.io.File(injDir, INJECT_LOG_NAME)));
            appendLogSection(sb, "--- injector_shell.log (drain) ---",
                    readLogFile(new java.io.File(ctx.getFilesDir(), SHELL_LOG_NAME)));
            appendLogSection(sb, "--- injector_last.txt ---",
                    readLogFile(new java.io.File(ctx.getFilesDir(), DIRECT_LOG_NAME)));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** One section of the combined log: title + content, or a note
     * when the source is missing/empty. Never throws. */
    private static void appendLogSection(StringBuilder sb, String title, String content) {
        try {
            sb.append(title).append("\n");
            if (content == null || content.length() == 0) {
                sb.append("(empty / not found)\n");
            } else {
                sb.append(content);
                if (!content.endsWith("\n")) sb.append("\n");
            }
        } catch (Throwable ignored) {}
    }

    /** v2.21 — the injector's saved log: the TMP mirror
     * (/data/local/tmp/anamul_voice_master_inject.log) FIRST — it is the
     * guaranteed record (the shell can always write
     * /data/local/tmp, the app can always read it) — then
     * <dir>/inject.log (the injector's own file log), then
     * injector_shell.log (the Java-side drain, secondary
     * capture), then injector_last.txt (the direct-exec fallback
     * path). Returns "" when there is none. v2.22 — inject.log
     * lives in the EXTERNAL files dir (injectorDir). v2.23 —
     * the TMP mirror is read FIRST. Never throws. Diagnostic
     * aid: shows the REAL log, never invented text. */
    public static String getInjectorLog(Context ctx) {
        try {
            if (ctx == null) return "";
            String log = readLogFile(new java.io.File(TMP_LOG_PATH));
            if (log == null || log.length() == 0) {
                java.io.File injDir = injectorDir(ctx);
                log = injDir == null ? "" : readLogFile(new java.io.File(injDir, INJECT_LOG_NAME));
            }
            if (log == null || log.length() == 0) {
                log = readLogFile(new java.io.File(ctx.getFilesDir(), SHELL_LOG_NAME));
            }
            if (log == null || log.length() == 0) {
                log = readLogFile(new java.io.File(ctx.getFilesDir(), DIRECT_LOG_NAME));
            }
            return log == null ? "" : log;
        } catch (Throwable t) {
            return "";
        }
    }

    /** v2.17 — the last line of the injector log that is the INJECTOR's
     * own output (not a shell diagnostic, not the timeout/exit reason
     * line). Used in the SHORT failure message only.
     * v2.18 — skips ALL pre-flight output. v2.17 bug: the `ls -l`
     * OUTPUT line ("lrwxr-xr-x 1 root shell 13 ... /system/bin/app_")
     * was NOT skipped — the filter only skipped lines starting with
     * "-", so the ls output became the message's "last line" (the
     * user's v2.17 screenshot). Returns "" when there is no such
     * line — then the message shows ONLY the short reason
     * ("no startup output") with NO "| ..." suffix. Never throws. */
    public static String getInjectorMessageLine(Context ctx) {
        try {
            String log = getInjectorLog(ctx);
            if (log == null || log.length() == 0) return "";
            String[] lines = log.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (l.length() == 0) continue;
                if (isPreflightLine(l)) continue;
                return l;
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** v2.25 — the "setupAudioPolicy failed: ..." line from the
     * injector log (TMP mirror first), first 120 chars, or "".
     * Used in the failure message so the user sees WHY the mic
     * policy did not register instead of a generic "did not
     * start". Never throws. */
    private static String getPolicyFailureLine(Context ctx) {
        try {
            String log = getInjectorLog(ctx);
            if (log == null || log.length() == 0) return "";
            String[] lines = log.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.contains("setupAudioPolicy failed")) {
                    if (l.length() > 120) l = l.substring(0, 120);
                    return l;
                }
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** v2.25 — set lastFailShort to the policy failure reason from
     * the injector log when one exists ("mic policy failed:
     * <setupAudioPolicy failed line, 120 chars>"), so the failure
     * message names the cause. Leaves lastFailShort untouched
     * when the log has no policy line. Never throws. */
    private static void setPolicyFailReason(Context ctx) {
        try {
            String pl = getPolicyFailureLine(ctx);
            if (pl != null && pl.length() > 0) {
                lastFailShort = "mic policy failed: " + pl;
            }
        } catch (Throwable ignored) {}
    }

    /** v2.28 — did the injector's log record a registered mic
     * policy ("policy=true")? Ground truth for the Firebase
     * auto-upload (the "policy" field). Never throws. */
    private static boolean injectorLogPolicyOk(Context ctx) {
        try {
            java.io.File injDir = injectorDir(ctx);
            java.io.File injLog = injDir == null ? null
                    : new java.io.File(injDir, INJECT_LOG_NAME);
            return injLog != null && anyInjectorLogHasLine(injLog, "policy=true");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** v2.18 — is this line pre-flight / diagnostic output rather
     * than the injector's own output? Skips: the "shell: " block,
     * bare paths (`command -v` output), `ls: ` errors, "total "
     * lines, "No such file", timeout/exit/fallback reason lines,
     * and `ls -l` OUTPUT lines (a permission string in the first
     * 10 chars: first char in [l d c b s p -] and the next 9 chars
     * all in [r w x s t -] — e.g. "lrwxr-xr-x", "drwxr-xr-x",
     * "-rw-r--r--"). v2.22 — the timeout lines are now the
     * file-based ones ("did not become ready after" /
     * "did not start after"). Never throws. */
    private static boolean isPreflightLine(String l) {
        try {
            if (l.startsWith("shell: ")) return true;
            if (l.startsWith("shizuku: ")) return true;
            if (l.startsWith("root: ")) return true;
            if (l.contains("su not available")) return true;
            if (l.startsWith("/")) return true;
            if (l.startsWith("ls: ")) return true;
            if (l.startsWith("total ")) return true;
            if (l.contains("No such file")) return true;
            if (l.contains("did not become ready after")) return true;
            if (l.contains("did not start after")) return true;
            if (l.contains("injector process exited")) return true;
            if (l.contains("falling back to direct exec")) return true;
            if (l.contains("shell start failed")) return true;
            if (l.contains("app_process not found")) return true;
            // ls -l output line: permission string in the first 10 chars.
            if (l.length() >= 10) {
                char c0 = l.charAt(0);
                if ("ldcbps-".indexOf(c0) >= 0) {
                    boolean perm = true;
                    for (int i = 1; i < 10; i++) {
                        if ("rwxst-".indexOf(l.charAt(i)) < 0) { perm = false; break; }
                    }
                    if (perm) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read one whole log file as UTF-8, or "" when missing/unreadable. Never throws. */
    private static String readLogFile(java.io.File f) {
        try {
            if (f == null || !f.exists()) return "";
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** v2.12 — the last non-empty line of the injector log, or "".
     * Used in the start-failure message so the user sees WHY it
     * failed. Never throws. */
    public static String getInjectorLogLastLine(Context ctx) {
        try {
            String log = getInjectorLog(ctx);
            if (log == null || log.length() == 0) return "";
            String[] lines = log.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (l.length() > 0) return l;
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** v2.14 — the first non-empty line of the injector log, or "".
     * Shown with the last line in the start-failure message: the
     * first line proves whether the process ran at all
     * ("Injector main started") or died on load (an app_process
     * error line). Never throws. */
    public static String getInjectorLogFirstLine(Context ctx) {
        try {
            String log = getInjectorLog(ctx);
            if (log == null || log.length() == 0) return "";
            String[] lines = log.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.length() > 0) return l;
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** v2.22 — FILE-based command: write the command line to
     * <dir>/cmd.tmp, then ATOMICALLY rename it to <dir>/cmd. The
     * injector's cmdLoop polls cmd every 100 ms, reads the first
     * line, DELETES the file, and executes it. Returns true when
     * the cmd file was written. Never throws. (The rename is
     * atomic: the injector only ever sees a complete cmd file.
     * If a previous cmd is still unconsumed, it is replaced —
     * commands are sent one at a time, so nothing is lost that
     * matters.) */
    public static boolean sendInjectorCommand(Context ctx, String cmd) {
        try {
            if (ctx == null || cmd == null) return false;
            java.io.File dir = injectorDir(ctx);
            if (dir == null) return false;
            java.io.File tmp = new java.io.File(dir, "cmd.tmp");
            java.io.File target = new java.io.File(dir, "cmd");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp, false);
            out.write((cmd + "\n").getBytes("UTF-8"));
            out.flush();
            out.close();
            if (!tmp.renameTo(target)) {
                // rename failed (e.g. a previous cmd is still there)
                try { target.delete(); } catch (Throwable ignored) {}
                if (!tmp.renameTo(target)) {
                    java.io.FileOutputStream o2 = new java.io.FileOutputStream(target, false);
                    o2.write((cmd + "\n").getBytes("UTF-8"));
                    o2.flush();
                    o2.close();
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v2.22 — FILE-based "ping": write "ping:<nonce>" to <dir>/cmd
     * and wait up to 3 s for "pong:<nonce>" to appear in
     * <dir>/inject.log. The nonce guards against a stale pong from
     * an earlier run (the log is only truncated by the pre-clean).
     * Returns true when the injector answers. Never throws. */
    public static boolean pingInjector(Context ctx) {
        // v2.7 — must never throw to a caller.
        try {
            if (ctx == null) return false;
            java.io.File dir = injectorDir(ctx);
            if (dir == null) return false;
            String nonce = String.valueOf(System.currentTimeMillis());
            if (!sendInjectorCommand(ctx, "ping:" + nonce)) return false;
            String want = "pong:" + nonce;
            java.io.File injectLog = new java.io.File(dir, INJECT_LOG_NAME);
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end) {
                if (anyInjectorLogHasLine(injectLog, want)) return true;
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Stop the injector process: "quit" via the cmd file, then
     * pkill any detached injector by class name (the injector is
     * detached — nohup + & — so there is no process handle to
     * destroy; v2.24 removed the direct-exec process field).
     * Never throws. */
    public static void stopInjector(Context ctx) {
        micModeOn = false;
        injectorConnected = false;
        try {
            if (ctx != null) sendInjectorCommand(ctx, "quit");
        } catch (Exception ignored) {}
        // v2.16 — belt-and-braces stop (the shell+nohup app_process K0.H also
        // does "quit" + pkill): if the cmd file was never read the
        // "quit" above never arrives, so kill any detached injector
        // by its class name. Best-effort, background, never throws.
        // The [r] trick keeps pkill from matching its own shell.
        new Thread(new Runnable() {
            public void run() {
                try {
                    newProcessReflective(
                            new String[]{"sh", "-c",
                                    "pkill -f 'com.anamul.voice.Injecto[r]'"},
                            null, null);
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    /** No-Context stop (destroy + pkill only — no "quit" cmd file,
     * since there is no Context to resolve the injector dir). */
    public static void stopInjector() {
        stopInjector(null);
    }

    /** Resolve a sound file to a mic-readable path: copy it into the
     * mic-readable dir (external files dir — the shell-uid injector can
     * read /sdcard) and return its absolute path. Returns null when the
     * copy fails. The injector's AudioTrack reads the file, so it must
     * be world-readable. */
    public static String resolveMicPath(Context ctx, java.io.File src) {
        try {
            if (ctx == null || src == null || !src.exists()) return null;
            java.io.File dir = micDir(ctx);
            if (dir == null) return null;
            java.io.File out = new java.io.File(dir, src.getName());
            if (out.exists() && out.length() == src.length() && out.length() > 0) {
                return out.getAbsolutePath();
            }
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream o = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.flush(); o.close(); in.close();
            out.setReadable(true, false);
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Mic-readable dir: external files dir (shell can read /sdcard), fallback to internal. */
    public static java.io.File micDir(Context ctx) {
        try {
            java.io.File d = ctx.getExternalFilesDir("mic_sounds");
            if (d != null) {
                if (!d.exists()) d.mkdirs();
                return d;
            }
        } catch (Exception ignored) {}
        try {
            java.io.File d = new java.io.File(ctx.getFilesDir(), "mic_sounds");
            if (!d.exists()) d.mkdirs();
            d.setExecutable(true, false);
            return d;
        } catch (Exception ignored) {}
        return null;
    }
}
