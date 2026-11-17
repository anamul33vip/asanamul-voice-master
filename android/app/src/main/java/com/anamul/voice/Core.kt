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
    var expires = 0L; var credits = 0
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
        // Merge server-side environment defaults without exposing the provider API key.
        r.optJSONObject("tts")?.let { tts ->
            if (cfg?.optString("tts_voice_id").isNullOrBlank()) cfg?.put("tts_voice_id", tts.optString("default_voice_id"))
            if (cfg?.optString("tts_model_id").isNullOrBlank()) cfg?.put("tts_model_id", tts.optString("model_id", "eleven_v3"))
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
    text = t; setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD); isAllCaps = false
    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFF00FF.toInt(), 0xFF00FFFF.toInt())).apply { cornerRadius = 60f }
    setOnClickListener { onClick() } }

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
