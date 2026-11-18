package com.anamul.voice
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.provider.Settings
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ====== Admin server URL: এখানে নিজের সার্ভারের ঠিকানা বসাও ======
const val SERVER = BuildConfig.SERVER_URL

object Api {
    var token = ""; var cfg: JSONObject? = null; var voices = listOf<JSONObject>()
    var expires = 0L; var credits = 0; var ttsEnabled = false
    // ---- APK Update (Admin Panel -> Version + APK URL + Release Note) ----
    var updateAvailable = false; var latestVersion = ""; var apkUrl = ""; var releaseNote = ""; var updateRequired = false
    // ---- Voice Profiles (Admin Panel -> Display Name / Provider / Voice ID / Language / Status) ----
    var voiceProfiles = listOf<JSONObject>(); var activeVoiceProfile: JSONObject? = null
    fun cmpVer(a: String, b: String): Int { val pa = a.split('.').map { it.toIntOrNull() ?: 0 }; val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) { val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0); if (d != 0) return d }; return 0 }
    fun applyUpdate(u: JSONObject?) { if (u == null) return
        latestVersion = u.optString("latest_version"); apkUrl = u.optString("apk_url"); releaseNote = u.optString("release_note"); updateRequired = u.optBoolean("required")
        updateAvailable = latestVersion.isNotEmpty() && cmpVer(latestVersion, BuildConfig.VERSION_NAME) > 0 && apkUrl.isNotEmpty() }
    /** Public update check — no login needed: GET /api/update?v=currentVersion */
    fun checkUpdate(): Boolean { val r = call("/api/update?v=" + BuildConfig.VERSION_NAME); if (!r.optBoolean("ok")) return false; applyUpdate(r); return updateAvailable }
    fun call(path: String, body: JSONObject? = null, readTimeoutMs: Int = 15000): JSONObject = try {
        val c = URL(SERVER + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = readTimeoutMs; c.setRequestProperty("Accept", "application/json")
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        JSONObject(stream.bufferedReader().use { it.readText() })
    } catch (e: Exception) { JSONObject().put("ok", false).put("error", "net:" + e.message) }
    fun loadConfig(): Boolean { val r = call("/api/app/config"); if (!r.optBoolean("ok")) return false
        cfg = r.getJSONObject("config")
        applyUpdate(r.optJSONObject("update"))
        val vp = r.optJSONArray("voice_profiles"); voiceProfiles = if (vp != null) (0 until vp.length()).map { vp.getJSONObject(it) } else emptyList()
        activeVoiceProfile = r.optJSONObject("active_voice_profile") ?: voiceProfiles.firstOrNull { it.optBoolean("active") }
        // Merge server-side environment defaults without exposing the provider API key.
        r.optJSONObject("tts")?.let { tts ->
            if (cfg?.optString("tts_voice_id").isNullOrBlank()) cfg?.put("tts_voice_id", tts.optString("default_voice_id"))
            if (cfg?.optString("tts_model_id").isNullOrBlank()) cfg?.put("tts_model_id", tts.optString("model_id", "eleven_v3"))
            ttsEnabled = tts.optBoolean("enabled")
        }
        expires = r.optLong("expires_epoch"); credits = r.optInt("credits")
        val a = r.getJSONArray("voices"); voices = (0 until a.length()).map { a.getJSONObject(it) }; return true }
    fun deviceId(c: Context) = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "x"
    fun download(c: Context, url: String, name: String): File { val f = File(c.cacheDir, name)
        if (!f.exists()) { val conn = URL(url).openConnection() as HttpURLConnection; conn.connectTimeout = 15000; conn.readTimeout = 90000
            conn.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }; conn.disconnect() }; return f }
}

object Player { private var mp: MediaPlayer? = null
    fun play(f: File) { stop(); mp = MediaPlayer().apply { setDataSource(f.path); prepare(); start() } }
    fun stop() { try { mp?.release() } catch (_: Exception) {}; mp = null } }

/** সাউন্ড বাজানো: Mic Mode চালু থাকলে মাইক্রোফোনে (injector), নইলে স্পিকারে। */
fun Context.playRouted(f: File) {
    if (MicInjector.isMicActive()) {
        val p = MicInjector.resolveMicPath(this, f)
        if (p != null && MicInjector.sendInjectorCommand(this, "play:$p")) return
    }
    Player.play(f)
}

/** সব থামানো: স্পিকার + মাইকের সাউন্ড। */
fun Context.stopRouted() {
    Player.stop()
    try { if (MicInjector.isMicActive()) MicInjector.sendInjectorCommand(this, "stop") } catch (_: Exception) {}
}

// ====== RGB Neon UI helpers ======
val RGB = intArrayOf(0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF66.toInt(), 0xFFFFFF00.toInt(), 0xFFFF2222.toInt(), 0xFFFF00FF.toInt())
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** একটা ভিউ যার বর্ডারে ঘুরন্ত RGB আলো */
class RgbFrame(c: Context, private val inner: View, private val radius: Float = 36f) : FrameLayout(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.dp(3).toFloat() }
    private var ang = 0f
    init { setWillNotDraw(false); setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
        inner.background = GradientDrawable().apply { setColor(0xFF0B0B18.toInt()); cornerRadius = radius }
        addView(inner)
        ValueAnimator.ofFloat(0f, 360f).apply { duration = 3500; repeatCount = -1; interpolator = null
            addUpdateListener { ang = it.animatedValue as Float; invalidate() }; start() } }
    override fun onDraw(cv: Canvas) { val r = RectF(p.strokeWidth, p.strokeWidth, width - p.strokeWidth, height - p.strokeWidth)
        p.shader = SweepGradient(width / 2f, height / 2f, RGB, null).also { val m = Matrix(); m.postRotate(ang, width / 2f, height / 2f); it.setLocalMatrix(m) }
        p.maskFilter = null; cv.drawRoundRect(r, radius, radius, p); super.onDraw(cv) }
}

fun Context.neonText(t: String, size: Float = 16f, bold: Boolean = true) = TextView(this).apply {
    text = t; textSize = size; setTextColor(Color.WHITE); if (bold) setTypeface(null, Typeface.BOLD)
    setShadowLayer(12f, 0f, 0f, 0xFF00FFFF.toInt()) }

fun Context.neonButton(t: String, onClick: () -> Unit) = Button(this).apply {
    text = t; textSize = 13.5f; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
    setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD); isAllCaps = false
    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFF00FF.toInt(), 0xFF00FFFF.toInt())).apply { cornerRadius = 60f }
    setOnClickListener { onClick() } }

/** ঘুরন্ত RGB গ্রেডিয়েন্ট বোতাম — Injector টগলের মতো হাইলাইট বোতামের জন্য। */
fun Context.rgbButton(t: String, onClick: () -> Unit) = Button(this).apply {
    text = t; textSize = 13.5f; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
    setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD); isAllCaps = false
    val gd = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF66.toInt())).apply { cornerRadius = 60f }
    background = gd
    ValueAnimator.ofFloat(0f, 360f).apply { duration = 2500; repeatCount = -1; interpolator = null
        addUpdateListener {
            val a = it.animatedValue as Float
            gd.colors = intArrayOf(
                Color.HSVToColor(floatArrayOf(a % 360, 1f, 1f)),
                Color.HSVToColor(floatArrayOf((a + 120) % 360, 1f, 1f)),
                Color.HSVToColor(floatArrayOf((a + 240) % 360, 1f, 1f)))
        }; start() }
    setOnClickListener { onClick() } }

/** বিল্ট-ইন টেস্ট সাউন্ড (১ সেকেন্ডের বিপ) — স্পিকার/মাইক পথ যাচাইয়ের জন্য, সার্ভার লাগে না। */
fun Context.testToneFile(): File {
    val f = File(cacheDir, "test_tone.wav")
    if (f.exists() && f.length() > 1000) return f
    val sr = 22050; val n = sr
    val data = ByteArray(44 + n * 2)
    fun wstr(off: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(data, off) }
    fun w32(off: Int, v: Int) { data[off] = (v and 0xFF).toByte(); data[off + 1] = ((v shr 8) and 0xFF).toByte(); data[off + 2] = ((v shr 16) and 0xFF).toByte(); data[off + 3] = ((v shr 24) and 0xFF).toByte() }
    fun w16(off: Int, v: Int) { data[off] = (v and 0xFF).toByte(); data[off + 1] = ((v shr 8) and 0xFF).toByte() }
    wstr(0, "RIFF"); w32(4, 36 + n * 2); wstr(8, "WAVE"); wstr(12, "fmt "); w32(16, 16); w16(20, 1); w16(22, 1); w32(24, sr); w32(28, sr * 2); w16(32, 2); w16(34, 16); wstr(36, "data"); w32(40, n * 2)
    for (i in 0 until n) {
        val env = if (i < n / 10) i.toDouble() / (n / 10) else if (i > n * 9 / 10) (n - i).toDouble() / (n / 10) else 1.0
        val s = (Math.sin(2.0 * Math.PI * 880.0 * i / sr) * env * 28000).toInt() and 0xFFFF
        w16(44 + i * 2, s)
    }
    f.writeBytes(data)
    return f
}

fun Context.neonInput(hint: String) = EditText(this).apply { this.hint = hint; setHintTextColor(0xFF777799.toInt()); setTextColor(Color.WHITE)
    setPadding(dp(16), dp(12), dp(16), dp(12))
    background = GradientDrawable().apply { setColor(0xFF12122A.toInt()); cornerRadius = 40f; setStroke(2, 0xFF00FFFF.toInt()) } }

/** RGB আলোর পুরো স্ক্রিন ব্যাকগ্রাউন্ড (ধীরে ঘুরন্ত গ্রেডিয়েন্ট গ্লো) */
class Glow(c: Context) : View(c) { private var a = 0f
    private val p = Paint()
    init { ValueAnimator.ofFloat(0f, 360f).apply { duration = 8000; repeatCount = -1; addUpdateListener { a = it.animatedValue as Float; invalidate() }; start() } }
    override fun onDraw(cv: Canvas) { cv.drawColor(0xFF05050C.toInt())
        for (i in 0..1) { val t = Math.toRadians((a + i * 180).toDouble()); val x = width / 2f + Math.cos(t).toFloat() * width / 2; val y = height / 2f + Math.sin(t).toFloat() * height / 3
            p.shader = RadialGradient(x, y, width * 0.8f, Color.HSVToColor(70, floatArrayOf((a * 2 + i * 120) % 360, 1f, 1f)), 0, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) } } }

// ====== Crash log + ভলিউম সাহায্যকারী (v2.2.0) ======
/** কোনো ভুল হলে অ্যাপ না ভেঙে লগ ফাইলে লিখে রাখা — Main স্ক্রিনে শেষ লাইন দেখা যায়। */
fun Context.logCrash(tag: String, e: Throwable) {
    try {
        val d = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
        val f = File(d, "app_crash.log")
        val stamp = java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        f.appendText("[$stamp] $tag: ${e.javaClass.simpleName}: ${e.message}\n")
        val lines = f.readLines()
        if (lines.size > 60) f.writeText(lines.takeLast(60).joinToString("\n") + "\n")
    } catch (_: Exception) {}
}

fun Context.readCrashLogLast(): String? {
    return try {
        val f = File(File(getExternalFilesDir(null), "logs"), "app_crash.log")
        if (!f.exists()) null else f.readLines().lastOrNull { it.isNotBlank() }
    } catch (_: Exception) { null }
}

fun Context.clearCrashLog() {
    try { File(File(getExternalFilesDir(null), "logs"), "app_crash.log").delete() } catch (_: Exception) {}
}

/** মিউজিক ভলিউম (STREAM_MUSIC) — গেমের সাউন্ডের জন্য। */
fun Context.musicVolumeMax(): Int {
    return try { (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
}

fun Context.musicVolume(): Int {
    return try { (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(android.media.AudioManager.STREAM_MUSIC) } catch (_: Exception) { 0 }
}

fun Context.setMusicVolume(v: Int) {
    try {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v.coerceIn(0, max), 0)
    } catch (_: Exception) {}
}
