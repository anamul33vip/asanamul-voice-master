package com.anamul.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Own original mic injector for AS ANAMUL VOICE MASTER.
 *
 * Runs as a shell-UID process via Shizuku (app_process). It builds an
 * AudioPolicy whose LOOP_BACK mix routes our AudioTrack into the
 * microphone (record) stream, then reads play commands from a FILE
 * (<dir>/cmd) and plays the requested audio through that AudioTrack.
 *
 * v2.22 — FILE-based commands, the same MECHANISM the reference app
 * uses (reimplemented here in my own Java — no third-party code is
 * copied): the app writes a command line to <dir>/cmd, this injector
 * polls for it every 100 ms, reads the first line, DELETES the file,
 * and executes it. Status goes to <dir>/inject.log. There is NO
 * socket: a shell-UID process's abstract LocalServerSocket and an
 * app-UID process connecting to it can be blocked, and a socket
 * gives the app no log when it fails. Files in the app's EXTERNAL
 * files dir (shell has sdcard_rw) are readable AND writable by
 * both sides — the private files dir (mode 700) is NOT writable by
 * the shell, which is why v2.21's inject.log was never created.
 *
 * Usage: app_process /system/bin com.anamul.voice.Injector <dir>
 *   (CLASSPATH must point at the app APK; <dir> is the app's
 *   external files dir, getExternalFilesDir(null).)
 */
public class Injector {

    private static final String TAG = "AVInjector";
    private static final int SAMPLE_RATE = 44100;

    private static volatile boolean running = true;
    private static AudioTrack track;
    private static Context sysCtx;
    private static Object audioPolicy; // android.media.audiopolicy.AudioPolicy
    private static final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
    // v2.21 — the injector ALSO writes its startup lines to a FILE
    // <dir>/inject.log (the reference app's Injector.log() does the
    // same): its stderr goes to /dev/null, so the file is the ONLY
    // log the app can read. Append, create if missing,
    // best-effort — logging must NEVER break the injector.
    // v2.22 — <dir> is the EXTERNAL files dir (shell-writable),
    // so this file is actually created now (v2.21 put it in the
    // private files dir, which the shell cannot write to).
    // v2.23 — GUARANTEED mirror: EVERY line is ALSO appended to
    // /data/local/tmp/anamul_voice_master_inject.log. /data/local/tmp is
    // world-writable (the shell can ALWAYS write it) and
    // world-readable (the app can ALWAYS read it) — so even if
    // scoped-storage FUSE blocks the shell from the external
    // files dir, the injector's output still exists and the app
    // still sees it. The app reads the TMP log FIRST. Both
    // writes are best-effort and independent: one failing must
    // never stop the other, and logging must NEVER break the
    // injector.
    private static final String TMP_LOG = "/data/local/tmp/anamul_voice_master_inject.log";
    private static String logFilePath = null;
    private static File dir = null;
    private static File cmdFile = null;
    private static synchronized void fileLog(String line) {
        try {
            if (logFilePath != null) {
                FileOutputStream fout = new FileOutputStream(logFilePath, true);
                fout.write((line + "\n").getBytes("UTF-8"));
                fout.close();
            }
        } catch (Throwable ignored) {}
        try {
            FileOutputStream fout = new FileOutputStream(TMP_LOG, true);
            fout.write((line + "\n").getBytes("UTF-8"));
            fout.close();
        } catch (Throwable ignored) {}
    }

    public static void main(String[] args) {
        // v2.15 — defense: make sure running is true even if a
        // previous life of this class somehow left it false.
        running = true;
        String filesDir = args.length > 0 ? args[0] : "/tmp";
        dir = new File(filesDir);
        cmdFile = new File(dir, "cmd");
        // v2.21 — the file log lives in the dir the app passed.
        logFilePath = filesDir + "/inject.log";
        // v2.13 — print to STDERR first: the app saves the injector's
        // stderr to injector_last.txt, so this line proves the process
        // actually ran. An EMPTY log means newProcess never started
        // it. Log.* only reaches logcat, which the user cannot see
        // on his phone — so the same lines go to inject.log too.
        System.err.println("Injector main started, filesDir=" + filesDir);
        fileLog("Injector main started, filesDir=" + filesDir);
        Log.i(TAG, "Injector starting, filesDir=" + filesDir);
        try {
            System.err.println("start uid=" + android.os.Process.myUid());
            fileLog("start uid=" + android.os.Process.myUid());
        } catch (Throwable ignored) {}
        // v2.22 — prepareMainLooper() (NOT prepare()): the reference
        // app calls Looper.prepareMainLooper() here, so the main
        // thread's looper is the MAIN looper (AudioPolicy.setLooper
        // needs a Looper to exist).
        try {
            Looper.prepareMainLooper();
        } catch (Exception ignored) {}
        try {
            sysCtx = getSystemContext();
            Log.i(TAG, "system context: " + sysCtx);
            // v2.15 — stderr after EACH setup step: the app saves
            // stderr, so the log shows exactly how far main() got.
            System.err.println("system context: " + sysCtx);
            fileLog("system context: " + sysCtx);
            fileLog("context ok");
        } catch (Throwable t) {
            System.err.println("getSystemContext failed: " + t);
            fileLog("getSystemContext failed: " + t);
            Log.e(TAG, "getSystemContext failed", t);
        }
        // v2.22 — fake the shell identity BEFORE the AudioPolicy:
        // the reference app calls fakeShellIdentity(Context) right
        // after getSystemContext() so Binder calls appear as the
        // shell package ("com.android.shell") and the AudioPolicy
        // registration succeeds as shell.
        try {
            fakeShellIdentity(sysCtx);
        } catch (Throwable ignored) {}
        boolean policyOk = false;
        try {
            policyOk = setupAudioPolicy();
            Log.i(TAG, "audio policy registered: " + policyOk);
            System.err.println("audio policy registered: " + policyOk);
            fileLog("audio policy registered: " + policyOk);
        } catch (Throwable t) {
            // v2.25 — log the FULL cause chain, not just the
            // wrapper type: "setupAudioPolicy failed:
            // InvocationTargetException cause: <real cause>".
            String chain = causeChain(t);
            System.err.println("setupAudioPolicy failed: " + chain);
            fileLog("setupAudioPolicy failed: " + chain);
            Log.e(TAG, "setupAudioPolicy failed", t);
        }
        try {
            track = buildAudioTrack();
            Log.i(TAG, "audio track: " + track);
            System.err.println("audio track: " + track);
            fileLog("audio track: " + track);
        } catch (Throwable t) {
            System.err.println("buildAudioTrack failed: " + t);
            fileLog("buildAudioTrack failed: " + t);
            Log.e(TAG, "buildAudioTrack failed", t);
        }
        Log.i(TAG, "Injector ready (policy=" + policyOk + ")");
        // v2.15 — the ready line ALSO to stderr (the app saves stderr).
        // v2.22 — the app's liveness check is THIS line in inject.log
        // (it polls the file for "Injector ready" for up to 10 s).
        System.err.println("Injector ready, waiting for commands (policy=" + policyOk + ")");
        fileLog("Injector ready, waiting for commands (policy=" + policyOk + ")");
        // v2.22 — the command loop starts AFTER the track is built,
        // the reference app's order: a non-daemon thread polls the
        // <dir>/cmd file every 100 ms (the app writes a command by
        // renaming cmd.tmp -> cmd), and a playback thread drains the
        // queue. v2.15 — the threads are explicitly NON-daemon:
        // app_process exits when main() returns, and a process whose
        // only live threads are daemon threads has nothing keeping
        // it alive. (The real fix is main() blocking forever below;
        // non-daemon is the belt-and-braces half.)
        Thread cmdThread = new Thread(new Runnable() {
            public void run() { cmdLoop(); }
        }, "av-cmd");
        cmdThread.setDaemon(false);
        cmdThread.start();
        Thread playThread = new Thread(new Runnable() {
            public void run() { playLoop(); }
        }, "av-play");
        playThread.setDaemon(false);
        playThread.start();
        // v2.15 — Looper.loop() is REMOVED as the blocking mechanism.
        // v2.14 proved the process exits with code 0 (a CLEAN exit,
        // not a crash) and the log had NO "main started" line — main()
        // returned instead of blocking forever. Looper.loop() can
        // return (quit / no messages / runtime behaviour) and then
        // main() returned and app_process exited. Looper.prepareMainLooper()
        // is still called at the top (AudioPolicy.setLooper needs a
        // Looper to EXIST), but the process now blocks ONLY on this
        // sleep loop, which cannot return spuriously while running
        // is true.
        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        System.err.println("Injector main returning (running=false)");
        fileLog("Injector main returning (running=false)");
        Log.i(TAG, "Injector exiting");
        try { System.exit(0); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // System context (reflection: ActivityThread.systemMain + getSystemContext)
    // ------------------------------------------------------------------
    private static Context getSystemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getMethod("systemMain");
        Object thread = systemMain.invoke(null);
        Method getCtx = at.getMethod("getSystemContext");
        return (Context) getCtx.invoke(thread);
    }

    // ------------------------------------------------------------------
    // v2.22 — fake the shell identity (the reference app's
    // fakeShellIdentity behaviour, reimplemented in my own Java):
    // if our uid is 0 we are already root/shell — nothing to do.
    // Otherwise set the system Context's hidden "mOpPackageName"
    // field to "com.android.shell" via reflection, so Binder calls
    // (e.g. registerAudioPolicy) appear to come from the shell
    // package. Best-effort, never throws — a failure only means the
    // AudioPolicy registers as the app instead of as shell.
    // ------------------------------------------------------------------
    private static void fakeShellIdentity(Context ctx) {
        try {
            if (android.os.Process.myUid() == 0) {
                fileLog("shell identity: uid 0, skip");
                return;
            }
            if (ctx == null) {
                fileLog("shell identity: no context, skip");
                return;
            }
            Class<?> c = ctx.getClass();
            Field f = c.getDeclaredField("mOpPackageName");
            f.setAccessible(true);
            f.set(ctx, "com.android.shell");
            System.err.println("shell identity: mOpPackageName=com.android.shell");
            fileLog("shell identity: mOpPackageName=com.android.shell");
        } catch (Throwable t) {
            System.err.println("shell identity failed: " + t);
            fileLog("shell identity failed: " + t);
            Log.w(TAG, "fakeShellIdentity failed", t);
        }
    }

    // ------------------------------------------------------------------
    // AudioPolicy with a LOOP_BACK mix (all hidden APIs via reflection).
    // The mix matches players with USAGE_MEDIA and loops them back into
    // the record (mic) stream.
    // v2.22 — FIVE capture presets: the mix rule is added for EACH
    // of MIC(1), CAMCORDER(5), VOICE_RECOGNITION(6),
    // VOICE_COMMUNICATION(7) and UNPROCESSED(9), so the injection
    // covers whatever audio source the game captures on — a single
    // preset misses the game's actual capture source. v2.27: the
    // presets apply ONLY in the presets-only fallback (addRule
    // rejected) — on the primary path the rule is usage-only,
    // because a usage rule + a capture-preset rule in one builder
    // is "Incompatible rule for mix" (v2.26 log). v2.28 — POLICY
    // MULTI-ATTEMPT: setupAudioPolicy() tries THREE rule
    // constructions in order and returns true on the FIRST
    // whose registerAudioPolicy returns 0; each attempt builds a
    // FRESH rule + FRESH mix + FRESH policy, and the AudioMix
    // ctor fallback fires on ANY Builder exception.
    // v2.29 — the v2.28 log (his phone) proved the AudioMix builds
    // fine but AudioPolicy$Builder has NO no-arg constructor on
    // his Android 11 (NoSuchMethodException <init> [] in ALL 3
    // attempts) — so the policy Builder is now resolved by
    // findPolicyBuilderCtor() (declared no-arg -> public no-arg ->
    // scan declared ctors with Context / no args / Looper).
    // v2.30 — the v2.29 log proved the policy REGISTERS
    // (policy=true, Context ctor, registerAudioPolicy returned 0)
    // but the other player still did NOT hear the sound — the
    // wall moved from registration to the AUDIO PATH. The order
    // is now LOOP_BACK|RENDER-first: (1) usage-only render,
    // (2) presets-only render, (3) usage-only LOOP_BACK (the
    // v2.29 registration, kept as fallback). The AudioTrack
    // already matches the rule/mix exactly (USAGE_MEDIA,
    // CONTENT_TYPE_MUSIC, 44100 Hz, stereo, PCM_16BIT) — v2.30
    // only ADDS diagnostics proving it (track attrs / rule attrs
    // / per-play bytes + playState in the uploaded log).
    // ------------------------------------------------------------------
    private static final int[] CAPTURE_PRESETS = new int[]{1, 5, 6, 7, 9};

    private static boolean setupAudioPolicy() throws Exception {
        if (sysCtx == null) return false;
        AudioManager am = (AudioManager) sysCtx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();

        // v2.30 — log the RULE's attributes once, so the uploaded
        // log lets the next diagnosis compare rule usage vs track
        // usage directly. (USAGE_MEDIA is 1 and CONTENT_TYPE_MUSIC
        // is 2 in the Android SDK — the parenthetical states the
        // true constant, not a guess.)
        String ruleAttrsLine = "rule attrs: usage=" + attr.getUsage()
                + " (USAGE_MEDIA=1) contentType=" + attr.getContentType();
        System.err.println(ruleAttrsLine);
        fileLog(ruleAttrsLine);

        Class<?> ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixCls = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> policyCls = Class.forName("android.media.audiopolicy.AudioPolicy");

        int ruleMatchUsage = staticInt(ruleCls, "RULE_MATCH_ATTRIBUTE_USAGE", 1);
        int routeLoopBack = staticInt(mixCls, "ROUTE_FLAG_LOOP_BACK", 2);
        int routeRender = staticInt(mixCls, "ROUTE_FLAG_RENDER", 1);
        int ruleMatchCapturePreset = staticInt(ruleCls, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET", 4);

        // AudioMixingRule.Builder() / AudioMix.Builder() /
        // AudioPolicy.Builder() classes (looked up once, reused by
        // every attempt — each attempt still builds FRESH builder
        // instances, a failed builder is never reused).
        Class<?> ruleBuilderCls = null;
        for (Class<?> c : ruleCls.getDeclaredClasses()) {
            if (c.getSimpleName().equals("Builder")) ruleBuilderCls = c;
        }
        if (ruleBuilderCls == null) ruleBuilderCls = ruleCls.getDeclaredClasses()[0];
        Class<?> mixBuilderCls = null;
        for (Class<?> c : mixCls.getDeclaredClasses()) {
            if (c.getSimpleName().equals("Builder")) mixBuilderCls = c;
        }
        if (mixBuilderCls == null) mixBuilderCls = mixCls.getDeclaredClasses()[0];
        Class<?> policyBuilderCls = null;
        for (Class<?> c : policyCls.getDeclaredClasses()) {
            if (c.getSimpleName().equals("Builder")) policyBuilderCls = c;
        }
        if (policyBuilderCls == null) policyBuilderCls = policyCls.getDeclaredClasses()[0];

        // v2.30 — POLICY MULTI-ATTEMPT, reordered: v2.29's log
        // proved the policy REGISTERS (policy=true, Context ctor,
        // registerAudioPolicy returned 0) but the other player
        // still did NOT hear the sound — the wall moved from
        // registration to the AUDIO PATH. The reference mechanism
        // uses LOOP_BACK|RENDER together, and v2.29 registered
        // LOOP_BACK-only (attempt 1) so the RENDER variant never
        // ran. So the FIRST registered policy is now the one most
        // likely to actually route audio:
        //   (1) usage-only rule + LOOP_BACK|RENDER,
        //   (2) presets-only rule + LOOP_BACK|RENDER,
        //   (3) usage-only rule + LOOP_BACK (the v2.29
        //   registration, kept as fallback).
        // Each attempt builds a FRESH rule + FRESH mix + FRESH
        // policy. An attempt that throws is logged with its cause
        // chain and the next attempt runs; a non-zero register
        // code is logged and the next attempt runs. After all 3
        // fail, throw with the last failure so main()'s catch
        // logs it as "setupAudioPolicy failed: ..." (the failure
        // message names the cause, and the v2.28 auto-upload
        // sends the whole log to Firebase).
        String[] descs = new String[]{"usage-only render", "presets-only render", "usage-only"};
        String lastFail = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            // v2.30 — attempts 1 and 2 carry LOOP_BACK|RENDER (3);
            // attempt 3 keeps LOOP_BACK alone (2) — the v2.29
            // registration, kept as fallback.
            int routeFlags = (attempt == 3) ? routeLoopBack : (routeLoopBack | routeRender);
            try {
                boolean ok = tryPolicyAttempt(attempt, descs[attempt - 1], am, attr, format,
                        ruleCls, mixCls, policyCls, ruleBuilderCls, mixBuilderCls,
                        policyBuilderCls, ruleMatchUsage, routeFlags,
                        ruleMatchCapturePreset);
                if (ok) {
                    System.err.println("policy attempt " + attempt + ": " + descs[attempt - 1] + " -> registered");
                    fileLog("policy attempt " + attempt + ": " + descs[attempt - 1] + " -> registered");
                    return true;
                }
                lastFail = "attempt " + attempt + " (" + descs[attempt - 1] + "): registerAudioPolicy returned non-zero";
                System.err.println("policy attempt " + attempt + ": " + descs[attempt - 1] + " -> register returned non-zero");
                fileLog("policy attempt " + attempt + ": " + descs[attempt - 1] + " -> register returned non-zero");
            } catch (Throwable t) {
                lastFail = "attempt " + attempt + " (" + descs[attempt - 1] + "): " + causeChain(t);
                System.err.println("policy attempt " + attempt + ": " + descs[attempt - 1] + " -> failed: " + causeChain(t));
                fileLog("policy attempt " + attempt + ": " + descs[attempt - 1] + " -> failed: " + causeChain(t));
            }
        }
        throw new Exception("all policy attempts failed — last: " + lastFail);
    }

    /** v2.28 — ONE policy attempt: FRESH rule + FRESH mix + FRESH
     * policy, then registerAudioPolicy. Returns true only when
     * register returns 0. Throws when any build step fails (the
     * caller logs the cause chain and tries the next attempt).
     * attempt 1 = usage-only rule (ONLY addRule USAGE_MEDIA),
     * route LOOP_BACK|RENDER; attempt 2 = presets-only rule
     * (ONLY the 5 addMixRule calls), route LOOP_BACK|RENDER;
     * attempt 3 = usage-only rule, route LOOP_BACK (the v2.29
     * registration, kept as fallback). v2.29 — the policy Builder
     * is built by findPolicyBuilderCtor() (his Android 11 has NO
     * no-arg AudioPolicy$Builder constructor). */
    private static boolean tryPolicyAttempt(int attempt, String desc, AudioManager am,
            AudioAttributes attr, AudioFormat format,
            Class<?> ruleCls, Class<?> mixCls, Class<?> policyCls,
            Class<?> ruleBuilderCls, Class<?> mixBuilderCls, Class<?> policyBuilderCls,
            int ruleMatchUsage, int routeFlags, int ruleMatchCapturePreset) throws Exception {
        // --- FRESH rule ---
        Object rb = ruleBuilderCls.getDeclaredConstructor().newInstance();
        // v2.26 — the mix-role call is REMOVED: calling it with (1)
        // (the RECORDERS role) BEFORE addRule(USAGE_MEDIA,
        // RULE_MATCH_ATTRIBUTE_USAGE) made AOSP throw
        // IllegalArgumentException("Incompatible rule for mix") from
        // addRule on his phone (v2.25 log) — a USAGE rule is a
        // PLAYER-side rule, incompatible with the RECORDERS role.
        // Do NOT set the target mix role at all; leave the
        // Builder's default.
        if (attempt == 1) {
            // v2.30 — attempt 1 is now usage-only + LOOP_BACK|RENDER
            // (routeFlags passed in by the caller): the v2.29
            // LOOP_BACK-only registration produced no audible
            // result for the other player, and the reference
            // mechanism uses LOOP_BACK|RENDER together.
            // v2.27 — RULE = USAGE-ONLY on the primary path. v2.26's
            // log proved a rule can contain EITHER usage rules OR
            // capture-preset rules, NOT BOTH: once addRule(USAGE_MEDIA)
            // is in the builder, EVERY addMixRule call throws
            // IllegalArgumentException("Incompatible rule for mix").
            // So attempt 1 does NOT call addMixRule at all — the mix
            // matches the player by USAGE and ROUTE_FLAG_LOOP_BACK
            // routes it into the mic (the standard loopback pattern).
            Method addRule = ruleBuilderCls.getMethod("addRule", AudioAttributes.class, int.class);
            addRule.invoke(rb, attr, ruleMatchUsage);
            System.err.println("addRule ok");
            fileLog("addRule ok");
            System.err.println("policy attempt 1 rule: usage-only render");
            fileLog("policy attempt 1 rule: usage-only render");
        } else if (attempt == 2) {
            // v2.28 — presets-only: the 5 addMixRule calls, no
            // addRule at all (addRule is never called, so the mix
            // rules are not blocked by a usage rule). v2.30 —
            // route is LOOP_BACK|RENDER (passed in by the caller).
            addCapturePresets(rb, ruleBuilderCls, ruleMatchCapturePreset);
            System.err.println("policy attempt 2 rule: presets-only render");
            fileLog("policy attempt 2 rule: presets-only render");
        } else {
            // v2.30 — attempt 3 = usage-only rule + LOOP_BACK
            // alone (routeFlags passed in by the caller): the v2.29
            // registration, kept as fallback in case the
            // LOOP_BACK|RENDER attempts fail to register on his
            // ROM. (The v2.28 third attempt — a rule with ZERO
            // rules — was PROVABLY invalid: "Cannot build
            // AudioMixingRule with no rules".)
            Method addRule = ruleBuilderCls.getMethod("addRule", AudioAttributes.class, int.class);
            addRule.invoke(rb, attr, ruleMatchUsage);
            System.err.println("addRule ok");
            fileLog("addRule ok");
            System.err.println("policy attempt 3 rule: usage-only");
            fileLog("policy attempt 3 rule: usage-only");
        }
        Method buildRule = ruleBuilderCls.getMethod("build");
        Object rule = buildRule.invoke(rb);
        System.err.println("rule built");
        fileLog("rule built");

        // --- FRESH mix ---
        // AudioMix.Builder().setRouteFlags(LOOP_BACK).setFormat(f).setMixingRule(r).build()
        // v2.27 — setMixingRule is NOT a public method on his device:
        // v2.26's getMethod("setMixingRule", ruleCls) threw
        // NoSuchMethodException. It is resolved robustly by
        // findSetMixingRule() (public -> declared -> scan by name ->
        // scan by parameter type). setRouteFlags/setFormat/build use
        // invokeBuilderMethod(), which retries with getDeclaredMethod
        // + setAccessible when the public lookup fails.
        // v2.28 — the ctor fallback now triggers on ANY exception
        // from the Builder path (not only NoSuchMethodException):
        // if the Builder path throws anything, AudioMix is built
        // directly via its constructor ("mix: via ctor"); if the
        // ctor ALSO fails, the attempt fails and the next attempt
        // runs (the whole setup no longer aborts on one bad call).
        Object mix;
        try {
            Object mb = mixBuilderCls.getDeclaredConstructor().newInstance();
            invokeBuilderMethod(mb, mixBuilderCls, "setRouteFlags",
                    new Class<?>[]{int.class}, new Object[]{routeFlags}, "mixBuilder");
            invokeBuilderMethod(mb, mixBuilderCls, "setFormat",
                    new Class<?>[]{AudioFormat.class}, new Object[]{format}, "mixBuilder");
            Method setMixingRule = findSetMixingRule(mixBuilderCls, ruleCls);
            setMixingRule.invoke(mb, rule);
            mix = invokeBuilderMethod(mb, mixBuilderCls, "build",
                    new Class<?>[0], new Object[0], "mixBuilder");
        } catch (Throwable t) {
            System.err.println("mix builder failed: " + causeChain(t) + " — trying AudioMix ctor");
            fileLog("mix builder failed: " + causeChain(t) + " — trying AudioMix ctor");
            try {
                mix = buildAudioMixViaCtor(mixCls, routeFlags, format, rule);
                System.err.println("mix: via ctor");
                fileLog("mix: via ctor");
            } catch (Throwable t2) {
                System.err.println("mix ctor failed: " + causeChain(t2));
                fileLog("mix ctor failed: " + causeChain(t2));
                throw t2;
            }
        }
        System.err.println("mix built");
        fileLog("mix built");

        // --- FRESH policy ---
        // AudioPolicy.Builder().addMix(mix).setLooper(...).build()
        // v2.29 — the Builder is NOT built with
        // getDeclaredConstructor() (no-arg): his Android 11 has NO
        // no-arg AudioPolicy$Builder constructor (v2.28 log:
        // NoSuchMethodException <init> [] in ALL 3 attempts).
        // findPolicyBuilderCtor() resolves it robustly (declared
        // no-arg -> public no-arg -> scan declared ctors with
        // Context / no args / Looper) and logs which one worked.
        Object pb = findPolicyBuilderCtor(policyBuilderCls);
        invokeBuilderMethod(pb, policyBuilderCls, "addMix",
                new Class<?>[]{mixCls}, new Object[]{mix}, "policyBuilder");
        try {
            invokeBuilderMethod(pb, policyBuilderCls, "setLooper",
                    new Class<?>[]{Looper.class}, new Object[]{Looper.myLooper()}, "policyBuilder");
        } catch (Throwable ignored) {}
        audioPolicy = invokeBuilderMethod(pb, policyBuilderCls, "build",
                new Class<?>[0], new Object[0], "policyBuilder");
        System.err.println("policy built");
        fileLog("policy built");

        // AudioManager.registerAudioPolicy(policy) -> 0 on success
        Method reg = AudioManager.class.getMethod("registerAudioPolicy", policyCls);
        Object res = reg.invoke(am, audioPolicy);
        int code = (res instanceof Number) ? ((Number) res).intValue() : -1;
        Log.i(TAG, "registerAudioPolicy returned " + code);
        System.err.println("registerAudioPolicy returned " + code);
        fileLog("registerAudioPolicy returned " + code);
        return code == 0;
    }

    /** v2.28 — addMixRule for EACH capture preset (extracted from
     * setupAudioPolicy; runs ONLY in the presets-only attempt):
     * build an AudioAttributes with setCapturePreset(preset) and
     * add it with RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET. A preset
     * that cannot be added is logged and skipped, the others
     * still apply. setCapturePreset is a HIDDEN API — called by
     * reflection. */
    private static void addCapturePresets(Object rb, Class<?> ruleBuilderCls,
            int ruleMatchCapturePreset) {
        Method addMixRule = findAddMixRule(ruleBuilderCls);
        if (addMixRule == null) return;
        for (int i = 0; i < CAPTURE_PRESETS.length; i++) {
            try {
                AudioAttributes.Builder ab = new AudioAttributes.Builder();
                try {
                    Method setPreset = AudioAttributes.Builder.class
                            .getMethod("setCapturePreset", int.class);
                    setPreset.invoke(ab, CAPTURE_PRESETS[i]);
                } catch (Throwable t2) {
                    System.err.println("setCapturePreset preset " + CAPTURE_PRESETS[i] + " failed: " + causeChain(t2));
                    fileLog("setCapturePreset preset " + CAPTURE_PRESETS[i] + " failed: " + causeChain(t2));
                }
                AudioAttributes presetAttr = ab.build();
                Class<?>[] pts = addMixRule.getParameterTypes();
                if (pts.length == 2 && pts[1] == int.class) {
                    addMixRule.invoke(rb, ruleMatchCapturePreset, CAPTURE_PRESETS[i]);
                } else {
                    addMixRule.invoke(rb, ruleMatchCapturePreset, presetAttr);
                }
                System.err.println("addMixRule preset " + CAPTURE_PRESETS[i] + " ok");
                fileLog("addMixRule preset " + CAPTURE_PRESETS[i] + " ok");
            } catch (Throwable t) {
                System.err.println("addMixRule preset " + CAPTURE_PRESETS[i] + " failed: " + causeChain(t));
                fileLog("addMixRule preset " + CAPTURE_PRESETS[i] + " failed: " + causeChain(t));
            }
        }
    }

    /** Find AudioMixingRule.Builder.addMixRule(int, ...) by name,
     * tolerating the declared second parameter type (AudioAttributes
     * or Object or int) varying by Android version. Returns null
     * when the method does not exist on this version. */
    private static Method findAddMixRule(Class<?> ruleBuilderCls) {
        try {
            Method[] ms = ruleBuilderCls.getMethods();
            for (int i = 0; i < ms.length; i++) {
                if (!ms[i].getName().equals("addMixRule")) continue;
                Class<?>[] pts = ms[i].getParameterTypes();
                if (pts.length != 2) continue;
                if (pts[0] != int.class && pts[0] != Integer.class) continue;
                return ms[i];
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** v2.27 — invoke a builder method, retrying with
     * getDeclaredMethod + setAccessible when the public lookup
     * fails (hidden/non-public method on this vendor ROM — the
     * same case as setMixingRule on his phone). Logs
     * "<tag>: <name> via declared" when the fallback fires.
     * Throws the underlying failure when both lookups fail. */
    private static Object invokeBuilderMethod(Object target, Class<?> cls,
            String name, Class<?>[] pts, Object[] args, String tag) throws Exception {
        try {
            return cls.getMethod(name, pts).invoke(target, args);
        } catch (NoSuchMethodException e) {
            Method m = cls.getDeclaredMethod(name, pts);
            m.setAccessible(true);
            System.err.println(tag + ": " + name + " via declared");
            fileLog(tag + ": " + name + " via declared");
            return m.invoke(target, args);
        }
    }

    /** v2.27 — resolve AudioMix$Builder.setMixingRule robustly.
     * On his phone v2.26's getMethod("setMixingRule",
     * AudioMixingRule.class) threw NoSuchMethodException — the
     * method is hidden/non-public or renamed on this vendor ROM.
     * Tries, in order: (a) public getMethod, (b)
     * getDeclaredMethod + setAccessible, (c) scan declared
     * methods for a method named "setMixingRule" with exactly 1
     * parameter, (d) scan declared methods for ANY method whose
     * single parameter type is AudioMixingRule, (e) log the full
     * declared method list and throw. Logs which step worked. */
    private static Method findSetMixingRule(Class<?> mixBuilderCls, Class<?> ruleCls)
            throws NoSuchMethodException {
        try {
            Method m = mixBuilderCls.getMethod("setMixingRule", ruleCls);
            System.err.println("mixingRule: public");
            fileLog("mixingRule: public");
            return m;
        } catch (NoSuchMethodException ignored) {}
        try {
            Method m = mixBuilderCls.getDeclaredMethod("setMixingRule", ruleCls);
            m.setAccessible(true);
            System.err.println("mixingRule: declared");
            fileLog("mixingRule: declared");
            return m;
        } catch (NoSuchMethodException ignored) {}
        try {
            for (Method m : mixBuilderCls.getDeclaredMethods()) {
                if (!m.getName().equals("setMixingRule")) continue;
                if (m.getParameterTypes().length != 1) continue;
                m.setAccessible(true);
                System.err.println("mixingRule: scanned");
                fileLog("mixingRule: scanned");
                return m;
            }
        } catch (Throwable ignored) {}
        try {
            for (Method m : mixBuilderCls.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == ruleCls) {
                    m.setAccessible(true);
                    System.err.println("mixingRule: by-param-type (" + m.getName() + ")");
                    fileLog("mixingRule: by-param-type (" + m.getName() + ")");
                    return m;
                }
            }
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder("mixBuilder methods: ");
        try {
            Method[] ms = mixBuilderCls.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(ms[i].getName());
            }
        } catch (Throwable ignored) {}
        System.err.println(sb.toString());
        fileLog(sb.toString());
        throw new NoSuchMethodException(mixBuilderCls.getName() + ".setMixingRule");
    }

    /** v2.29 — resolve AudioPolicy$Builder's constructor robustly
     * and return a constructed Builder OBJECT. v2.28's log (his
     * phone) proved EVERYTHING up to the AudioMix works ("mix
     * built") but ALL 3 attempts then failed with
     * NoSuchMethodException: android.media.audiopolicy.AudioPolicy$Builder.<init> []
     * — his Android 11 has NO public no-arg constructor on
     * AudioPolicy$Builder (the old code called
     * getDeclaredConstructor() with no args). Tries, in order:
     * (a) declared no-arg + setAccessible, (b) public no-arg,
     * (c) scan EVERY declared constructor and try it with
     * plausible arguments — (i) the injector's system Context,
     * (ii) no arguments, (iii) a Looper, (iv) Context + Looper
     * together — (d) log the FULL declared constructor list and
     * throw. Logs which step worked, so the next log names the
     * exact constructor his ROM provides. */
    private static Object findPolicyBuilderCtor(Class<?> policyBuilderCls) throws Exception {
        // (a) declared no-arg
        try {
            java.lang.reflect.Constructor<?> c = policyBuilderCls.getDeclaredConstructor();
            c.setAccessible(true);
            Object b = c.newInstance();
            System.err.println("policyBuilder ctor: declared no-arg");
            fileLog("policyBuilder ctor: declared no-arg");
            return b;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            System.err.println("policyBuilder ctor declared no-arg failed: " + causeChain(t));
            fileLog("policyBuilder ctor declared no-arg failed: " + causeChain(t));
        }
        // (b) public no-arg
        try {
            java.lang.reflect.Constructor<?> c = policyBuilderCls.getConstructor();
            Object b = c.newInstance();
            System.err.println("policyBuilder ctor: public no-arg");
            fileLog("policyBuilder ctor: public no-arg");
            return b;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            System.err.println("policyBuilder ctor public no-arg failed: " + causeChain(t));
            fileLog("policyBuilder ctor public no-arg failed: " + causeChain(t));
        }
        // (c) scan every declared constructor
        java.lang.reflect.Constructor<?>[] ctors = policyBuilderCls.getDeclaredConstructors();
        for (int i = 0; i < ctors.length; i++) {
            java.lang.reflect.Constructor<?> c = ctors[i];
            Class<?>[] pts = c.getParameterTypes();
            String sig = ctorSig(c);
            String lastCause = "";
            // (i) the injector's system Context for Context params
            try {
                c.setAccessible(true);
                Object b = c.newInstance(fillCtorArgs(pts, sysCtx, null));
                System.err.println("policyBuilder ctor: " + sig);
                fileLog("policyBuilder ctor: " + sig);
                return b;
            } catch (Throwable t) { lastCause = causeChain(t); }
            // (ii) no arguments (only meaningful for a 0-param ctor)
            if (pts.length == 0) {
                try {
                    c.setAccessible(true);
                    Object b = c.newInstance(new Object[0]);
                    System.err.println("policyBuilder ctor: " + sig);
                    fileLog("policyBuilder ctor: " + sig);
                    return b;
                } catch (Throwable t) { lastCause = causeChain(t); }
            }
            // (iii) a Looper for Looper params
            try {
                c.setAccessible(true);
                Object b = c.newInstance(fillCtorArgs(pts, null, Looper.getMainLooper()));
                System.err.println("policyBuilder ctor: " + sig);
                fileLog("policyBuilder ctor: " + sig);
                return b;
            } catch (Throwable t) { lastCause = causeChain(t); }
            // (iv) Context + Looper together (a ctor that needs both)
            try {
                c.setAccessible(true);
                Object b = c.newInstance(fillCtorArgs(pts, sysCtx, Looper.getMainLooper()));
                System.err.println("policyBuilder ctor: " + sig);
                fileLog("policyBuilder ctor: " + sig);
                return b;
            } catch (Throwable t) { lastCause = causeChain(t); }
            System.err.println("policyBuilder ctor " + sig + " failed: " + lastCause);
            fileLog("policyBuilder ctor " + sig + " failed: " + lastCause);
        }
        // (d) log the FULL declared constructor list and throw, so
        // the next log names every constructor his ROM provides.
        StringBuilder sb = new StringBuilder("policyBuilder ctors: ");
        for (int i = 0; i < ctors.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ctorSig(ctors[i]));
        }
        System.err.println(sb.toString());
        fileLog(sb.toString());
        throw new NoSuchMethodException(policyBuilderCls.getName() + ".<init> — " + sb.toString());
    }

    /** v2.29 — build the argument array for a constructor:
     * Context params get the injector's system Context, Looper
     * params get the main Looper, everything else gets null.
     * A null ctx/looper means "leave that type as null". */
    private static Object[] fillCtorArgs(Class<?>[] pts, Context ctx, Looper looper) {
        Object[] args = new Object[pts.length];
        for (int j = 0; j < pts.length; j++) {
            if (ctx != null && Context.class.isAssignableFrom(pts[j])) args[j] = ctx;
            else if (looper != null && pts[j] == Looper.class) args[j] = looper;
            else args[j] = null;
        }
        return args;
    }

    /** v2.29 — "android.content.Context" or
     * "android.content.Context, android.os.Looper" (or
     * "<no args>"): the parameter-type signature of a
     * constructor, for the log. */
    private static String ctorSig(java.lang.reflect.Constructor<?> c) {
        Class<?>[] pts = c.getParameterTypes();
        if (pts.length == 0) return "<no args>";
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < pts.length; j++) {
            if (j > 0) sb.append(", ");
            sb.append(pts[j].getName());
        }
        return sb.toString();
    }

    /** v2.27 — construct AudioMix directly via a declared
     * constructor whose parameters include AudioMixingRule.
     * LAST RESORT when the Builder path throws
     * NoSuchMethodException even after the declared fallbacks.
     * Arguments are matched by parameter type: int -> routeFlags,
     * AudioFormat -> format, AudioMixingRule -> rule, everything
     * else -> null (device / registration). */
    private static Object buildAudioMixViaCtor(Class<?> mixCls, int routeFlags,
            AudioFormat format, Object rule) throws Exception {
        java.lang.reflect.Constructor<?>[] ctors = mixCls.getDeclaredConstructors();
        for (int i = 0; i < ctors.length; i++) {
            java.lang.reflect.Constructor<?> c = ctors[i];
            Class<?>[] pts = c.getParameterTypes();
            boolean hasRule = false;
            for (int j = 0; j < pts.length; j++) {
                if (pts[j].getName().equals("android.media.audiopolicy.AudioMixingRule")) {
                    hasRule = true;
                    break;
                }
            }
            if (!hasRule) continue;
            Object[] args = new Object[pts.length];
            for (int j = 0; j < pts.length; j++) {
                if (pts[j] == int.class || pts[j] == Integer.class) args[j] = routeFlags;
                else if (pts[j] == AudioFormat.class) args[j] = format;
                else if (pts[j].getName().equals("android.media.audiopolicy.AudioMixingRule")) args[j] = rule;
                else args[j] = null;
            }
            c.setAccessible(true);
            return c.newInstance(args);
        }
        throw new NoSuchMethodException(mixCls.getName() + " ctor with AudioMixingRule");
    }

    /** v2.25 — the full cause chain of a reflection failure, one
     * line: "T cause: C1 cause: C2". InvocationTargetException
     * alone hides the real failing call — the cause IS the answer
     * (v2.24's log only showed the wrapper type). Never throws. */
    private static String causeChain(Throwable t) {
        try {
            StringBuilder sb = new StringBuilder();
            Throwable cur = t;
            int depth = 0;
            while (cur != null && depth < 4) {
                if (depth > 0) sb.append(" cause: ");
                sb.append(cur.toString());
                cur = cur.getCause();
                depth++;
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return String.valueOf(t);
        }
    }

    private static int staticInt(Class<?> cls, String name, int def) {
        try {
            Field f = cls.getField(name);
            return f.getInt(null);
        } catch (Throwable t) {
            return def;
        }
    }

    private static AudioTrack buildAudioTrack() {
        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(minBuf * 2, SAMPLE_RATE * 2 * 2);
        AudioTrack t = new AudioTrack(attr, format, buf, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        // v2.30 — TRACK ATTRIBUTES MUST MATCH THE RULE: the track
        // already uses EXACTLY the rule's AudioAttributes
        // (USAGE_MEDIA, CONTENT_TYPE_MUSIC) and the mix's format
        // (PCM_16BIT, 44100 Hz, stereo) — verified by reading the
        // code, nothing changed here. Log the ACTUAL attributes
        // read back from the built objects, so the uploaded log
        // proves the match (rule attrs vs track attrs) instead of
        // trusting the source.
        String trackAttrsLine = "track attrs: usage=" + attr.getUsage()
                + " contentType=" + attr.getContentType()
                + " sampleRate=" + format.getSampleRate()
                + " channelMask=" + format.getChannelMask()
                + " encoding=" + format.getEncoding();
        System.err.println(trackAttrsLine);
        fileLog(trackAttrsLine);
        return t;
    }

    // ------------------------------------------------------------------
    // v2.22 — FILE-based command loop (replaces the LocalServerSocket
    // listenLoop): poll <dir>/cmd every 100 ms; when the file exists,
    // read its first line, DELETE the file, and hand the command to
    // the playback thread. The app writes commands by renaming
    // cmd.tmp -> cmd, so the file only ever appears complete.
    // ------------------------------------------------------------------
    private static void cmdLoop() {
        while (running) {
            try {
                if (cmdFile != null && cmdFile.exists()) {
                    String line = null;
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(new FileInputStream(cmdFile), "UTF-8"));
                        line = r.readLine();
                        r.close();
                    } catch (Throwable ignored) {}
                    try { cmdFile.delete(); } catch (Throwable ignored) {}
                    if (line != null && line.trim().length() > 0) {
                        queue.offer(line.trim());
                    }
                }
            } catch (Throwable ignored) {}
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
    }

    private static void playLoop() {
        while (running) {
            String cmd = null;
            try {
                cmd = queue.take();
            } catch (InterruptedException e) {
                continue;
            }
            if (cmd == null) continue;
            try {
                if (cmd.equals("quit")) {
                    running = false;
                    break;
                } else if (cmd.equals("ping") || cmd.startsWith("ping:")) {
                    // v2.22 — the app's liveness check: append "pong"
                    // to inject.log (echoing any nonce after "ping:").
                    String nonce = "";
                    if (cmd.startsWith("ping:")) nonce = cmd.substring(5);
                    fileLog(nonce.length() > 0 ? "pong:" + nonce : "pong");
                } else if (cmd.equals("stop")) {
                    // Stop All: silence the track (streamPcm already breaks
                    // out of its write loop as soon as a command is queued).
                    try {
                        if (track != null) {
                            track.pause();
                            track.flush();
                        }
                    } catch (Throwable ignored) {}
                } else if (cmd.startsWith("play:")) {
                    playFile(cmd.substring(5));
                } else if (cmd.startsWith("tone:")) {
                    playTone(cmd.substring(5));
                } else if (cmd.startsWith("tts:")) {
                    String rest = cmd.substring(4);
                    int idx = rest.indexOf(':');
                    if (idx > 0) speakTts(rest.substring(0, idx), rest.substring(idx + 1));
                }
            } catch (Throwable t) {
                Log.w(TAG, "command failed: " + cmd, t);
            }
        }
    }

    // ------------------------------------------------------------------
    // v2.22 — tone synthesis (the AI Studio MicInjector.playTone
    // algorithm, reimplemented in my own Java): a sine sweep from f0
    // to f1 over ms milliseconds, 44.1kHz stereo, 0.7 amplitude,
    // 10 ms fade-in / 50 ms fade-out. Command form: tone:<f0>:<f1>:<ms>.
    // ------------------------------------------------------------------
    private static void playTone(String rest) {
        try {
            String[] parts = rest.split(":");
            float f0 = Float.parseFloat(parts[0]);
            float f1 = Float.parseFloat(parts[1]);
            int ms = Integer.parseInt(parts[2]);
            if (ms <= 0) ms = 500;
            if (ms > 10000) ms = 10000;
            int frames = 44100 * ms / 1000;
            byte[] pcm = new byte[frames * 4];
            double phase = 0.0;
            for (int i = 0; i < frames; i++) {
                double t = (double) i / Math.max(1, frames);
                double freq = f0 + (f1 - f0) * t;
                double env = Math.min(1.0, i / 441.0)
                        * Math.min(1.0, (frames - i) / 2205.0);
                if (env < 0) env = 0;
                double s = Math.sin(phase) * 0.7 * env;
                phase += 2.0 * Math.PI * freq / 44100.0;
                int v = (int) (s * 32767.0);
                int off = i * 4;
                pcm[off] = (byte) (v & 0xff);
                pcm[off + 1] = (byte) ((v >> 8) & 0xff);
                pcm[off + 2] = (byte) (v & 0xff);
                pcm[off + 3] = (byte) ((v >> 8) & 0xff);
            }
            fileLog("tone " + f0 + "->" + f1 + "Hz " + ms + "ms");
            int wrote = streamPcm(pcm);
            String toneLine = "tone: " + f0 + ":" + f1 + ":" + ms + " wrote " + wrote + " bytes";
            System.err.println(toneLine);
            fileLog(toneLine);
        } catch (Throwable t) {
            fileLog("tone failed: " + t);
            Log.w(TAG, "playTone failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Playback: decode to 44.1k stereo PCM and stream to the AudioTrack.
    // ------------------------------------------------------------------
    private static void playFile(String path) {
        Log.i(TAG, "play " + path);
        // v2.30 — log the audio path so the uploaded log proves
        // what happens on a play: file existence + size, then
        // bytes written + play state after streaming.
        File f = new File(path);
        boolean exists = f.exists();
        long size = exists ? f.length() : 0;
        String arriveLine = "play: " + path + " exists=" + exists + " size=" + size;
        System.err.println(arriveLine);
        fileLog(arriveLine);
        if (!exists) {
            String m = "play: file missing/unreadable: " + path;
            System.err.println(m);
            fileLog(m);
            Log.w(TAG, "decode failed: " + path);
            return;
        }
        byte[] pcm = decodeToPcm44100Stereo(path);
        if (pcm == null || pcm.length == 0) {
            String m = "play: file missing/unreadable: " + path;
            System.err.println(m);
            fileLog(m);
            Log.w(TAG, "decode failed: " + path);
            return;
        }
        int wrote = streamPcm(pcm);
        int state = -1;
        try { if (track != null) state = track.getPlayState(); } catch (Throwable ignored) {}
        String wroteLine = "play: wrote " + wrote + " bytes to track, playState=" + state;
        System.err.println(wroteLine);
        fileLog(wroteLine);
    }

    /** Decode any audio file to 16-bit 44.1kHz stereo PCM (MediaExtractor+Codec). */
    private static byte[] decodeToPcm44100Stereo(String path) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(path);
        } catch (Exception e) {
            Log.w(TAG, "setDataSource failed: " + path, e);
            return null;
        }
        int trackIdx = -1;
        MediaFormat format = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIdx = i;
                format = f;
                break;
            }
        }
        if (trackIdx < 0) {
            ex.release();
            return null;
        }
        ex.selectTrack(trackIdx);
        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec codec;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (Exception e) {
            Log.w(TAG, "codec setup failed", e);
            ex.release();
            return null;
        }
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        int outRate = SAMPLE_RATE;
        int outCh = 2;
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEos = false;
            boolean sawOutputEos = false;
            long timeoutUs = 10000;
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    int inIdx = codec.dequeueInputBuffer(timeoutUs);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEos = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
                if (outIdx >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIdx);
                    if (info.size > 0 && out != null) {
                        MediaFormat of = codec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        }
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                        byte[] chunk = new byte[info.size];
                        out.get(chunk);
                        raw.write(chunk, 0, chunk.length);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "decode loop failed", e);
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            ex.release();
        }
        byte[] data = raw.toByteArray();
        if (data.length == 0) return null;
        return resampleTo44100Stereo(data, outRate, outCh);
    }

    /**
     * Convert decoded PCM (16-bit, any rate/channels) to 16-bit 44.1kHz stereo.
     * Mono is duplicated; other rates are linearly interpolated.
     */
    private static byte[] resampleTo44100Stereo(byte[] data, int rate, int ch) {
        int frames = data.length / 2 / Math.max(1, ch);
        if (frames <= 0) return null;
        int outFrames = (int) Math.round((double) frames * SAMPLE_RATE / Math.max(1, rate));
        if (outFrames <= 0) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(outFrames * 4);
        for (int i = 0; i < outFrames; i++) {
            double pos = (double) i * frames / outFrames;
            int p0 = (int) Math.floor(pos);
            int p1 = Math.min(frames - 1, p0 + 1);
            double frac = pos - p0;
            for (int c = 0; c < 2; c++) {
                int sc = Math.min(c, ch - 1);
                int s0 = sampleAt(data, p0, sc, ch);
                int s1 = sampleAt(data, p1, sc, ch);
                int v = (int) Math.round(s0 + (s1 - s0) * frac);
                out.write(v & 0xff);
                out.write((v >> 8) & 0xff);
            }
        }
        return out.toByteArray();
    }

    private static int sampleAt(byte[] data, int frame, int ch, int channels) {
        int idx = (frame * channels + ch) * 2;
        if (idx + 1 >= data.length) return 0;
        int v = (data[idx] & 0xff) | (data[idx + 1] << 8);
        return v;
    }

    // v2.30 — returns the bytes actually written, so the caller
    // can log them ("play: wrote <N> bytes..."). track.write's
    // return value is honoured: a non-positive result stops the
    // loop instead of spinning on a dead track.
    private static int streamPcm(byte[] pcm) {
        if (track == null) return 0;
        int off = 0;
        try {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
            int chunk = 8192;
            while (off < pcm.length && running) {
                // A queued command interrupts the current sound.
                if (!queue.isEmpty()) break;
                int n = Math.min(chunk, pcm.length - off);
                int w = track.write(pcm, off, n);
                if (w <= 0) break;
                off += w;
            }
            if (off >= pcm.length) {
                // let the tail finish, then pause
                try { Thread.sleep(60); } catch (InterruptedException ignored) {}
                try { track.pause(); track.flush(); } catch (Exception ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "streamPcm failed", t);
        }
        return off;
    }

    // ------------------------------------------------------------------
    // TTS into the mic: synthesise to a file, then play that file.
    // ------------------------------------------------------------------
    private static void speakTts(final String lang, final String text) {
        if (sysCtx == null) return;
        try {
            final File wav = new File(sysCtx.getCacheDir(), "av_tts.wav");
            final Object lock = new Object();
            final boolean[] done = new boolean[]{false};
            final boolean[] ok = new boolean[]{false};
            TextToSpeech tts = new TextToSpeech(sysCtx, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    synchronized (lock) { lock.notifyAll(); }
                }
            });
            synchronized (lock) { lock.wait(8000); }
            Locale loc = "bn".equals(lang) ? new Locale("bn") : Locale.ENGLISH;
            int r = tts.setLanguage(loc);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.ENGLISH);
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) {}
                public void onDone(String id) {
                    synchronized (lock) { ok[0] = true; done[0] = true; lock.notifyAll(); }
                }
                public void onError(String id) {
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                }
            });
            tts.synthesizeToFile(text, null, wav, "av1");
            synchronized (lock) {
                long end = System.currentTimeMillis() + 15000;
                while (!done[0] && System.currentTimeMillis() < end) {
                    lock.wait(1000);
                }
            }
            try { tts.shutdown(); } catch (Exception ignored) {}
            if (ok[0] && wav.exists() && wav.length() > 0) {
                playFile(wav.getAbsolutePath());
            } else {
                Log.w(TAG, "TTS synthesis failed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "speakTts failed", t);
        }
    }
}
