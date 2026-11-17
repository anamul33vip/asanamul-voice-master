package com.anamul.voice
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var list: LinearLayout; private lateinit var info: TextView
    private lateinit var micStatus: TextView; private lateinit var micBtn: Button

    // Shizuku অনুমতির উত্তর এলে Mic স্ট্যাটাস আপডেট হয়।
    private val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != MicInjector.REQ_SHIZUKU_PERMISSION) return@OnRequestPermissionResultListener
        refreshMic()
        if (grantResult == PackageManager.PERMISSION_GRANTED) toast("✅ Shizuku অনুমতি দেওয়া হয়েছে — Mic Mode চালু করুন")
        else toast("❌ Shizuku অনুমতি দেওয়া হয়নি — Shizuku খুলে Allow দিন")
    }

    override fun onCreate(s: Bundle?) { super.onCreate(s)
        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (_: Exception) {}
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(30), dp(14), dp(10)) }
        root.addView(neonText("⚡ AS ANAMUL VOICE", 22f).apply { gravity = Gravity.CENTER })
        info = neonText("", 12f, false).apply { gravity = Gravity.CENTER; setTextColor(0xFF9FFFFF.toInt()) }; root.addView(info)
        val notice = neonText(Api.cfg?.optString("notice") ?: "", 13f, false).apply { gravity = Gravity.CENTER; setTextColor(0xFFFFFF66.toInt()) }; root.addView(notice)
        val bar = LinearLayout(this)
        bar.addView(neonButton("🔄 Refresh") { refresh() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(neonButton("🎈 Overlay") { startOverlay() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        bar.addView(neonButton("⏹ Stop") { stopRouted() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(neonButton("🎙 TTS Voice Generation — Text to Speech") {
            startActivity(Intent(this, TtsActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        // ---- Mic Mode (Shizuku injector) ----
        micStatus = neonText("🎙 Mic Mode: বন্ধ — স্পিকারে বাজছে", 13f, false).apply { gravity = Gravity.CENTER; setTextColor(0xFF9FFFFF.toInt()) }
        root.addView(micStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val micBar = LinearLayout(this)
        micBtn = neonButton("🎙 Mic Mode চালু করুন") { toggleMic() }
        micBar.addView(micBtn, LinearLayout.LayoutParams(0, dp(46), 1f))
        micBar.addView(neonButton("🛡 Shizuku চেক") { checkShizuku() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(micBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
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
        refreshMic()
    }

    override fun onResume() { super.onResume(); refreshMic() }
    override fun onDestroy() { try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (_: Exception) {}; super.onDestroy() }

    private fun refreshMic() {
        if (!::micStatus.isInitialized) return
        micStatus.text = when {
            MicInjector.isMicActive() -> "🎙 Mic Mode: চালু — সাউন্ড মাইক্রোফোনে যাবে"
            !MicInjector.apiLevelOk() -> "🎙 Mic Mode: Android 6.0+ লাগবে"
            !MicInjector.isShizukuInstalled(this) -> "🎙 Mic Mode: Shizuku অ্যাপ ইনস্টল নেই"
            !MicInjector.isShizukuRunning() -> "🎙 Mic Mode: Shizuku চলছে না — Shizuku খুলে Start করুন"
            !MicInjector.hasPermission() -> "🎙 Mic Mode: Shizuku অনুমতি লাগবে — Shizuku চেক চাপুন"
            else -> "🎙 Mic Mode: বন্ধ — স্পিকারে বাজছে"
        }
        if (::micBtn.isInitialized) micBtn.text = if (MicInjector.isMicActive()) "⏹ Mic Mode বন্ধ করুন" else "🎙 Mic Mode চালু করুন"
    }

    private fun toggleMic() {
        if (MicInjector.isMicActive()) { MicInjector.stopInjector(this); refreshMic(); toast("Mic Mode বন্ধ — স্পিকারে বাজবে"); return }
        micStatus.text = "⏳ Injector চালু হচ্ছে…"
        MicInjector.startInjector(this, object : MicInjector.StartCallback {
            override fun done(ok: Boolean, messageBn: String, messageEn: String) { toast(messageBn); refreshMic() }
        })
    }

    private fun checkShizuku() {
        when {
            !MicInjector.isShizukuInstalled(this) -> { toast("Shizuku অ্যাপ ইনস্টল নেই — খুলছি"); MicInjector.openShizukuApp(this) }
            !MicInjector.isShizukuRunning() -> toast("Shizuku চলছে না — Shizuku খুলে Start করুন, তারপর আবার চাপুন")
            !MicInjector.hasPermission() -> { MicInjector.requestPermission(); toast("Shizuku অনুমতি চাওয়া হয়েছে — Allow দিন") }
            else -> toast("✅ Shizuku ঠিক আছে — Mic Mode চালু করুন")
        }
        refreshMic()
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
        Thread { try { val f = Api.download(this, v.getString("audio"), "a_" + v.optString("id") + "." + v.getString("audio").substringAfterLast('.')); runOnUiThread { playRouted(f) } } catch (e: Exception) { runOnUiThread { toast("❌ প্লে হয়নি") } } }.start()
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
