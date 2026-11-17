package com.anamul.voice
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var list: LinearLayout; private lateinit var info: TextView
    override fun onCreate(s: Bundle?) { super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(30), dp(14), dp(10)) }
        root.addView(neonText("⚡ AS ANAMUL VOICE", 22f).apply { gravity = Gravity.CENTER })
        info = neonText("", 12f, false).apply { gravity = Gravity.CENTER; setTextColor(0xFF9FFFFF.toInt()) }; root.addView(info)
        val notice = neonText(Api.cfg?.optString("notice") ?: "", 13f, false).apply { gravity = Gravity.CENTER; setTextColor(0xFFFFFF66.toInt()) }; root.addView(notice)
        val bar = LinearLayout(this)
        bar.addView(neonButton("🔄 Refresh") { refresh() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(neonButton("🎈 Overlay") { startOverlay() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        bar.addView(neonButton("⏹ Stop") { Player.stop() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(neonButton("🎙 TTS Voice Generation — Text to Speech") {
            startActivity(Intent(this, TtsActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val cp = LinearLayout(this); val ci = neonInput("🎟 কুপন কোড")
        cp.addView(ci, LinearLayout.LayoutParams(0, -2, 1f)); cp.addView(neonButton("Redeem") { redeem(ci.text.toString()) }, LinearLayout.LayoutParams(dp(100), dp(46)))
        root.addView(cp, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val links = LinearLayout(this)
        links.addView(neonButton("▶ YouTube") { open(Api.cfg?.optString("youtube")) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        links.addView(neonButton("✈ Telegram") { open(Api.cfg?.optString("telegram")) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        root.addView(links)
        setContentView(FrameLayout(this).apply { addView(Glow(this@MainActivity)); addView(root) })
        render()
    }
    private fun render() {
        val d = if (Api.expires == 0L) "∞ আনলিমিটেড" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(Api.expires * 1000))
        info.text = "মেয়াদ: $d   |   Credits: ${Api.credits}"
        list.removeAllViews()
        val premium = Api.expires == 0L || Api.expires > System.currentTimeMillis() / 1000
        Api.voices.forEach { v -> list.addView(card(v, premium), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        if (Api.voices.isEmpty()) list.addView(neonText("কোনো ভয়েস নেই। Refresh চাপুন।", 14f))
    }
    private fun card(v: JSONObject, ok: Boolean): FrameLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF222244.toInt()) }
        row.addView(img, LinearLayout.LayoutParams(dp(56), dp(56)))
        row.addView(neonText((if (v.optBoolean("premium")) "💎 " else "") + v.optString("title"), 16f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        row.addView(neonButton("▶") { playVoice(v, ok) }, LinearLayout.LayoutParams(dp(60), dp(44)))
        val u = v.optString("image"); if (u.isNotEmpty()) Thread { try { val f = Api.download(this, u, "i_" + v.optString("id")); val b = BitmapFactory.decodeFile(f.path); runOnUiThread { img.setImageBitmap(b) } } catch (_: Exception) {} }.start()
        return RgbFrame(this, row)
    }
    private fun playVoice(v: JSONObject, ok: Boolean) {
        if (v.optBoolean("premium") && !ok) { toast("💎 Premium ভয়েস — মেয়াদ শেষ, কুপন/Key নিন"); return }
        Thread { try { val f = Api.download(this, v.getString("audio"), "a_" + v.optString("id") + "." + v.getString("audio").substringAfterLast('.')); runOnUiThread { Player.play(f) } } catch (e: Exception) { runOnUiThread { toast("❌ প্লে হয়নি") } } }.start()
    }
    private fun refresh() = Thread { val ok = Api.loadConfig(); runOnUiThread { if (ok) render() else toast("Session শেষ — আবার লগইন করুন") } }.start()
    private fun redeem(c: String) = Thread { val r = Api.call("/api/app/redeem", JSONObject().put("coupon", c.trim()))
        runOnUiThread { if (r.optBoolean("ok")) { Api.expires = r.optLong("expires_epoch"); Api.credits = r.optInt("credits"); render(); toast("✅ কুপন যোগ হয়েছে") } else toast("❌ কুপন হয়নি") } }.start()
    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        val oi = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(oi) else startService(oi)
        moveTaskToBack(true)
    }
    private fun open(u: String?) { if (!u.isNullOrEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
