package com.anamul.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MasterLink (lean) — connects this app to the Master Admin registry.
 * Self-registers at adwatch/cfg/_master/apps/{appId}/runtime via Firebase REST
 * (PATCH only — never touches the admin's schema) and applies the standard
 * controls: notice / maintenance / force-update. Offline → the app just runs.
 */
object MasterLink {
    private const val DB = "https://as-anamul-admin-default-rtdb.firebaseio.com"
    private const val VERSION = "2.6.1"

    fun sync(a: Activity, appId: String) {
        Thread {
            try {
                val rt = JSONObject().put("version", VERSION).put("pkg", a.packageName)
                    .put("lastSeen", System.currentTimeMillis())
                rest("PATCH", "/adwatch/cfg/_master/apps/$appId/runtime.json", rt.toString())
                val raw = rest("GET", "/adwatch/cfg/_master/apps/$appId/runtime.json", null)
                if (raw.isNotBlank() && raw != "null") {
                    val r = JSONObject(raw)
                    a.runOnUiThread { apply(a, r) }
                }
            } catch (_: Exception) { /* offline -> just run the app */ }
        }.start()
    }

    private fun rest(method: String, path: String, body: String?): String {
        val c = URL(DB + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 8000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val s = try { c.inputStream } catch (_: Exception) { c.errorStream } ?: return ""
        return s.bufferedReader().use { it.readText() }
    }

    private fun apply(a: Activity, r: JSONObject) {
        try {
            if (r.optBoolean("maintenance", false)) {
                AlertDialog.Builder(a)
                    .setTitle("🔧 রক্ষণাবেক্ষণ চলছে")
                    .setMessage("অ্যাপটি এখন রক্ষণাবেক্ষণে আছে। পরে আবার চেষ্টা করুন।")
                    .setCancelable(false)
                    .setPositiveButton("ঠিক আছে") { _, _ -> a.finish() }
                    .show()
                return
            }
            val fv = r.optString("forceVersion", "")
            val apk = r.optString("forceApk", "")
            if (fv.isNotEmpty() && fv != VERSION && apk.isNotEmpty()) {
                AlertDialog.Builder(a)
                    .setTitle("⬆️ নতুন আপডেট")
                    .setMessage("নতুন ভার্সন এসেছে, আপডেট করুন।")
                    .setCancelable(false)
                    .setPositiveButton("আপডেট") { _, _ ->
                        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk)))
                    }
                    .setNegativeButton("পরে", null)
                    .show()
            }
            val notice = r.optString("notice", "")
            if (notice.isNotEmpty()) {
                val key = "ml_notice_" + notice.hashCode()
                val sp = a.getSharedPreferences("aw", 0)
                if (!sp.getBoolean(key, false)) {
                    sp.edit().putBoolean(key, true).apply()
                    AlertDialog.Builder(a)
                        .setTitle("📢 নোটিশ")
                        .setMessage(notice)
                        .setPositiveButton("ঠিক আছে", null)
                        .show()
                }
            }
        } catch (_: Exception) { }
    }
}
