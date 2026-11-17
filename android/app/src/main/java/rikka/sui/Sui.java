package rikka.sui;

import android.os.IBinder;

/**
 * v2.11 — STUB Sui, matching what R8 does to IS FF VOICE: isSui() is a
 * constant false. The stock ShizukuProvider calls Sui.isSui() first in
 * onCreate() (to decide whether to call Sui.init) and in call() (warning
 * log only); with isSui() == false both paths skip Sui entirely, so the
 * real Sui — which NPEs on this phone's Android 11 via
 * SystemServiceHelper.getSystemService (hidden-API block) — is never
 * touched. Sui/root mode is not used; the user runs Shizuku in ADB mode.
 *
 * Signatures verified by javap against the real Sui in the API jar
 * (~/workspace/shizuku_api/api/classes.jar):
 *   public Sui()
 *   private static IBinder requestBinder()
 *   public static boolean isSui()
 *   public static boolean init(String)
 * The real class's private static fields are not needed — nothing
 * outside Sui itself reads them.
 */
public class Sui {
    public Sui() {}

    public static boolean isSui() { return false; }

    public static boolean init(String s) { return false; }

    private static IBinder requestBinder() { return null; }
}
