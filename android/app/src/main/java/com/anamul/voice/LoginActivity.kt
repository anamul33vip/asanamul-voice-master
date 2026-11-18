package com.anamul.voice
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import org.json.JSONObject

class LoginActivity : Activity() {
    override fun onCreate(s: Bundle?) { super.onCreate(s)
        MasterLink.sync(this, "voicemaster")
        val sp = getSharedPreferences("a", MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(28), dp(28), dp(28)) }
        col.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(180), dp(180)))
        col.addView(neonText("AS ANAMUL VOICE", 24f))
        col.addView(neonText("লগইন করে শুরু করুন", 14f, false).apply { setTextColor(0xFFAAAAFF.toInt()) })
        val key = neonInput("Access Key").apply { setText(sp.getString("key", "")) }
        val st = neonText("", 13f, false)
        col.addView(key, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        col.addView(neonButton("🔑 লগইন") { go(key.text.toString(), st, sp) }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        col.addView(neonButton("🔑 GET FREE KEY — ৬ ধাপে ফ্রি Key") { openUrl(SERVER.trimEnd('/') + "/getkey") }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        col.addView(neonButton("🛒 Key কিনুন (Premium)") { openUrl("https://t.me/crackffpanel") }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        col.addView(st)
        setContentView(FrameLayout(this).apply { addView(Glow(this@LoginActivity)); addView(col) })
        if (key.text.isNotEmpty()) go(key.text.toString(), st, sp)
    }
    private fun go(k: String, st: TextView, sp: android.content.SharedPreferences) {
        st.text = "⏳ লগইন হচ্ছে..."
        Thread {
            val r = Api.call("/api/app/login", JSONObject().put("key", k.trim()).put("device", Api.deviceId(this)))
            runOnUiThread {
                if (r.optBoolean("ok")) { Api.token = r.getString("token"); sp.edit().putString("key", k.trim()).apply()
                    Thread { val ok = Api.loadConfig(); runOnUiThread { if (ok) { startActivity(Intent(this, MainActivity::class.java)); finish() } else st.text = "❌ কনফিগ লোড হয়নি" } }.start()
                } else st.text = when (r.optString("error")) { "invalid_key" -> "❌ ভুল Key"; "expired" -> "⏳ Key-র মেয়াদ শেষ"; "device_mismatch" -> "🔒 Key অন্য ডিভাইসে লক"; "banned" -> "🚫 Key ব্যান"; else -> "❌ " + r.optString("error") }
            }
        }.start()
    }
    private fun openUrl(u: String) = startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)))
}
