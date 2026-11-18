package com.anamul.voice
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONObject

/**
 * গেমের ওপরে ভাসমান বাবল + প্যানেল।
 * প্যানেলে আছে: Inject ON/OFF, সাউন্ড কার্ড, টাইপ ও পাঠান (TTS), ভলিউম, Stop all।
 * v2.2.0: ক্র্যাশ-সেফ — যেকোনো ভুল Toast + লগে লিখে সার্ভিস বাঁচিয়ে রাখে।
 */
class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var panel: View? = null
    private var lastTap = 0L
    private val main = Handler(Looper.getMainLooper())
    private var injectBtn: Button? = null
    private var soundBox: LinearLayout? = null
    private var typeBox: LinearLayout? = null
    private var soundTabBtn: Button? = null
    private var typeTabBtn: Button? = null
    private var typeInput: EditText? = null
    private var sendBtn: Button? = null
    private var volBar: SeekBar? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            val ch = "ov"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(NotificationChannel(ch, "Overlay", NotificationManager.IMPORTANCE_LOW))
            }
            val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, ch) else Notification.Builder(this)
            val notif = nb.setContentTitle("AS ANAMUL VOICE").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
            // Android 14 (targetSdk 34): টাইপ ছাড়া startForeground() করলে MissingForegroundServiceTypeException হয়।
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else
                startForeground(1, notif)
            if (bubble == null) showBubble()
        } catch (e: Exception) {
            logCrash("overlay-start", e)
        }
        return START_STICKY
    }

    private fun lp(w: Int, h: Int, flags: Int) =
        WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    private fun showBubble() {
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val b = RgbFrame(this, TextView(this).apply { text = "🎤"; textSize = 22f; gravity = Gravity.CENTER }, 90f)
            val p = lp(dp(60), dp(60), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                .apply { x = 20; y = 300 }
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            b.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - sx) > 10 || Math.abs(e.rawY - sy) > 10) moved = true
                        p.x = ox + (e.rawX - sx).toInt(); p.y = oy + (e.rawY - sy).toInt()
                        try { wm?.updateViewLayout(b, p) } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            val now = System.currentTimeMillis()
                            if (now - lastTap > 300) { lastTap = now; togglePanel() }
                        }
                    }
                }
                true
            }
            wm?.addView(b, p)
            bubble = b
        } catch (e: Exception) {
            logCrash("overlay-bubble", e)
            toast("❌ ওভারলে খুলতে সমস্যা: ${e.message}")
        }
    }

    private fun togglePanel() {
        try {
            if (panel != null) { removePanel(); return }
            buildPanel()
        } catch (e: Exception) {
            logCrash("overlay-panel", e)
            toast("❌ প্যানেল খুলতে সমস্যা: ${e.message}")
            removePanel()
        }
    }

    private fun removePanel() {
        try { panel?.let { wm?.removeView(it) } } catch (_: Exception) {}
        panel = null
    }

    private fun buildPanel() {
        val w = wm ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        col.addView(neonText("🎤 AS ANAMUL VOICE", 16f).apply { gravity = Gravity.CENTER })

        // Inject ON/OFF
        injectBtn = neonButton(injectLabel()) { toggleInject() }
        col.addView(injectBtn, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })

        // Tabs
        val tabs = LinearLayout(this)
        soundTabBtn = neonButton("🎵 সাউন্ড কার্ড") { showTab(true) }
        typeTabBtn = neonButton("⌨ টাইপ ও পাঠান") { showTab(false) }
        tabs.addView(soundTabBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(typeTabBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        col.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // Sound cards tab
        soundBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ok = Api.expires == 0L || Api.expires > System.currentTimeMillis() / 1000
        val local = Voices.load(this)
        if (local.isEmpty() && Api.voices.isEmpty()) {
            soundBox!!.addView(neonText("কোনো ভয়েস নেই — অ্যাপে গিয়ে ➕ ভয়েস যোগ করুন", 12f, false))
        }
        local.forEach { v ->
            soundBox!!.addView(neonButton("▶ " + v.title) { playLocal(v) },
                LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        }
        Api.voices.forEach { v ->
            soundBox!!.addView(neonButton("▶ " + (if (v.optBoolean("premium")) "💎 " else "") + v.optString("title")) { playServer(v, ok) },
                LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        }
        val soundScroll = ScrollView(this).apply { addView(soundBox) }
        col.addView(soundScroll, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(6) })

        // Type & send tab (hidden first)
        typeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val lastVid = getSharedPreferences("tts_preferences", MODE_PRIVATE).getString("last_voice_id", "") ?: ""
        typeBox!!.addView(neonText(if (lastVid.isEmpty()) "Voice ID সেট করা নেই — অ্যাপের TTS স্ক্রিনে Voice ID সেভ করুন" else "Voice: $lastVid", 12f, false))
        typeInput = neonInput("এখানে লিখুন…").apply { minLines = 3; maxLines = 5 }
        typeBox!!.addView(typeInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        sendBtn = neonButton("➤ Send (মাইকে যাবে)") { sendTyped() }
        typeBox!!.addView(sendBtn, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        col.addView(typeBox, LinearLayout.LayoutParams(-1, -2))

        // Volume
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        volRow.addView(neonText("🔊", 16f))
        volBar = SeekBar(this).apply {
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) setMusicVolume(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        volRow.addView(volBar, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        volRow.addView(neonButton("Max") { setMusicVolume(Int.MAX_VALUE) }, LinearLayout.LayoutParams(dp(64), dp(40)))
        col.addView(volRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // Bottom row
        val bottom = LinearLayout(this)
        bottom.addView(neonButton("⏹ Stop all") { stopRouted() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottom.addView(neonButton("✖ বন্ধ করুন") { removePanel() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        col.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val f = RgbFrame(this, ScrollView(this).apply { addView(col) })
        val params = lp(dp(280), dp(480), WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply { x = 60; y = 200 }
        w.addView(f, params)
        panel = f
        refreshInjectBtn()
    }

    private fun showTab(sound: Boolean) {
        soundBox?.visibility = if (sound) View.VISIBLE else View.GONE
        typeBox?.visibility = if (sound) View.GONE else View.VISIBLE
        // টাইপ ট্যাবে কীবোর্ড লাগে — প্যানেল focusable রাখা হয়েছে (FLAG_NOT_TOUCH_MODAL)।
    }

    private fun injectLabel() =
        if (MicInjector.isMicActive()) "✅ Inject ON (Shizuku) — চাপলে বন্ধ হবে" else "▶ Inject OFF — চাপলে Mic Mode চালু হবে"

    private fun refreshInjectBtn() {
        main.post { try { injectBtn?.text = injectLabel() } catch (_: Exception) {} }
    }

    private fun toggleInject() {
        if (MicInjector.isMicActive()) {
            MicInjector.stopInjector(this)
            toast("Mic Mode বন্ধ — স্পিকারে বাজবে")
            refreshInjectBtn()
            return
        }
        toast("⏳ Injector চালু হচ্ছে…")
        MicInjector.startInjector(this, object : MicInjector.StartCallback {
            override fun done(ok: Boolean, messageBn: String, messageEn: String) {
                main.post { toast(messageBn); refreshInjectBtn() }
            }
        })
    }

    private fun playLocal(v: Voices.V) {
        Thread {
            try {
                val f = Voices.file(this, v)
                if (f.exists()) playRouted(f) else main.post { toast("❌ ফাইল পাওয়া যায়নি") }
            } catch (e: Exception) { logCrash("overlay-play-local", e) }
        }.start()
    }

    private fun playServer(v: JSONObject, ok: Boolean) {
        if (v.optBoolean("premium") && !ok) { toast("💎 Premium ভয়েস — মেয়াদ শেষ"); return }
        Thread {
            try {
                playRouted(Api.download(this, v.getString("audio"), "a_" + v.optString("id") + "." + v.getString("audio").substringAfterLast('.')))
            } catch (e: Exception) { logCrash("overlay-play-server", e) }
        }.start()
    }

    private fun sendTyped() {
        val txt = typeInput?.text?.toString()?.trim() ?: ""
        if (txt.isEmpty()) { toast("লেখা লিখুন"); return }
        val vid = getSharedPreferences("tts_preferences", MODE_PRIVATE).getString("last_voice_id", "") ?: ""
        if (vid.isEmpty()) { toast("Voice ID সেট করা নেই — অ্যাপের TTS স্ক্রিনে সেভ করুন"); return }
        sendBtn?.isEnabled = false
        toast("⏳ Voice তৈরি হচ্ছে…")
        Thread {
            try {
                val req = JSONObject()
                    .put("text", txt)
                    .put("voice_id", vid)
                    .put("model_id", "eleven_v3")
                    .put("language_code", if (txt.any { it in '\u0980'..'\u09FF' }) "bn" else "en")
                val r = Api.call("/api/app/tts", req, 120000)
                if (r.optBoolean("ok")) {
                    val f = Api.download(this, r.getString("audio_url"), "tts_" + r.optString("id", System.currentTimeMillis().toString()) + ".mp3")
                    main.post { sendBtn?.isEnabled = true; playRouted(f) }
                } else {
                    val err = r.optString("error", "unknown")
                    main.post {
                        sendBtn?.isEnabled = true
                        toast(if (err == "tts_not_configured") "❌ সার্ভারে ElevenLabs Key সেট করা নেই" else "❌ Voice হয়নি: $err")
                    }
                }
            } catch (e: Exception) {
                logCrash("overlay-tts", e)
                main.post { sendBtn?.isEnabled = true; toast("❌ Voice হয়নি") }
            }
        }.start()
    }

    override fun onDestroy() {
        try { stopRouted() } catch (_: Exception) {}
        try { panel?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        panel = null; bubble = null; wm = null
        super.onDestroy()
    }

    private fun toast(t: String) = main.post { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }
}
