package com.anamul.voice
import android.app.*
import android.os.Build
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.*
import org.json.JSONObject

/** গেমের ওপরে ভাসমান RGB বাটন; ট্যাপ করলে ভয়েসের লিস্ট খোলে/বন্ধ হয় */
class OverlayService : Service() {
    private lateinit var wm: WindowManager; private var bubble: View? = null; private var panel: View? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "ov"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(ch, "Overlay", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(1, Notification.Builder(this, ch).setContentTitle("AS ANAMUL VOICE").setSmallIcon(android.R.drawable.ic_btn_speak_now).build())
        if (bubble == null) show(); return START_STICKY
    }
    private fun lp(w: Int, h: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
    private fun show() { wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = RgbFrame(this, TextView(this).apply { text = "🎤"; textSize = 22f; gravity = Gravity.CENTER }, 90f)
        val p = lp(dp(60), dp(60)).apply { x = 20; y = 300 }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        b.setOnTouchListener { _, e -> when (e.action) {
            MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
            MotionEvent.ACTION_MOVE -> { if (Math.abs(e.rawX - sx) > 10 || Math.abs(e.rawY - sy) > 10) moved = true; p.x = ox + (e.rawX - sx).toInt(); p.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(b, p) }
            MotionEvent.ACTION_UP -> if (!moved) toggle() }; true }
        wm.addView(b, p); bubble = b }
    private fun toggle() { if (panel != null) { wm.removeView(panel); panel = null; return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val ok = Api.expires == 0L || Api.expires > System.currentTimeMillis() / 1000
        Api.voices.forEach { v -> col.addView(neonButton((if (v.optBoolean("premium")) "💎 " else "") + v.optString("title")) { play(v, ok) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) }) }
        col.addView(neonButton("⏹ Stop") { stopRouted() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        col.addView(neonButton("✖ Close") { stopSelf() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        val sv = ScrollView(this).apply { addView(col) }
        val f = RgbFrame(this, sv); wm.addView(f, lp(dp(220), dp(320)).apply { x = 90; y = 300; flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE }); panel = f }
    private fun play(v: JSONObject, ok: Boolean) { if (v.optBoolean("premium") && !ok) return
        Thread { try { playRouted(Api.download(this, v.getString("audio"), "a_" + v.optString("id") + "." + v.getString("audio").substringAfterLast('.'))) } catch (_: Exception) {} }.start() }
    override fun onDestroy() { stopRouted(); bubble?.let { wm.removeView(it) }; panel?.let { wm.removeView(it) }; super.onDestroy() }
}
