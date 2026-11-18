package com.anamul.voice
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.*

/**
 * v2.4.0 — স্টাইলিশ নতুন ডিজাইন (নিজের AS ANAMUL লুক):
 * ওপরে লোগো + Key স্ট্যাটাস কার্ড, বড় Injector টগল, টেস্ট সাউন্ড,
 * ছোট এক লাইনের বোতাম (আর কাটে না)।
 */
class MainActivity : Activity() {
    private lateinit var list: LinearLayout; private lateinit var info: TextView
    private lateinit var micStatus: TextView; private lateinit var micBtn: Button
    private lateinit var crashText: TextView; private lateinit var volBar: SeekBar
    private lateinit var searchInput: EditText
    private var localVoices = mutableListOf<Voices.V>()
    private var searchQuery = ""
    private var pendingThumb: Voices.V? = null

    // Shizuku অনুমতির উত্তর এলে Mic স্ট্যাটাস আপডেট হয়।
    private val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != MicInjector.REQ_SHIZUKU_PERMISSION) return@OnRequestPermissionResultListener
        refreshMic()
        if (grantResult == PackageManager.PERMISSION_GRANTED) toast("✅ Shizuku অনুমতি দেওয়া হয়েছে — Injector চালু করুন")
        else toast("❌ Shizuku অনুমতি দেওয়া হয়নি — Shizuku খুলে Allow দিন")
    }

    override fun onCreate(s: Bundle?) { super.onCreate(s)
        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (_: Exception) {}
        localVoices = Voices.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(28), dp(14), dp(10)) }

        // ---- ওপরের স্ট্যাটাস কার্ড: লোগো (RGB রিং) + Key মেয়াদ ----
        val headRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logoImg = ImageView(this).apply { setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }
        headRow.addView(RgbFrame(this, logoImg, 90f), LinearLayout.LayoutParams(dp(62), dp(62)))
        val headCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headCol.addView(neonText("⚡ AS ANAMUL VOICE", 20f))
        info = neonText("", 12f, false).apply { setTextColor(0xFF9FFFFF.toInt()) }
        headCol.addView(info)
        headRow.addView(headCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        root.addView(RgbFrame(this, headRow), LinearLayout.LayoutParams(-1, -2))
        val notice = neonText(Api.cfg?.optString("notice") ?: "", 13f, false).apply { gravity = Gravity.CENTER; setTextColor(0xFFFFFF66.toInt()) }
        root.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // ---- Injector কার্ড: বড় ON/OFF টগল ----
        val injRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        injRow.addView(neonText("⚡ Injector", 16f), LinearLayout.LayoutParams(0, -2, 1f))
        micBtn = rgbButton("Injector: OFF") { toggleMic() }
        injRow.addView(micBtn, LinearLayout.LayoutParams(dp(150), dp(46)))
        val injCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        injCol.addView(injRow)
        micStatus = neonText("Mic Mode: বন্ধ — স্পিকারে বাজছে", 12f, false).apply { gravity = Gravity.CENTER; setTextColor(0xFF9FFFFF.toInt()) }
        injCol.addView(micStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        root.addView(RgbFrame(this, injCol), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val bar = LinearLayout(this)
        bar.addView(neonButton("🔄 Refresh") { refresh() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(neonButton("🎈 Overlay") { startOverlay() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        bar.addView(neonButton("⏹ Stop") { stopRouted() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(neonButton("🎙 TTS Voice") {
            startActivity(Intent(this, TtsActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        // ---- টেস্ট সাউন্ড: ভয়েস ছাড়াই স্পিকার/মাইক পথ যাচাই ----
        root.addView(neonButton("🔊 টেস্ট সাউন্ড বাজান") { testSound() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        // ---- ভলিউম ----
        val volRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        volRow.addView(neonText("🔊", 16f))
        volBar = SeekBar(this).apply {
            max = musicVolumeMax(); progress = musicVolume()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) setMusicVolume(v) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        volRow.addView(volBar, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        volRow.addView(neonButton("Max") { setMusicVolume(Int.MAX_VALUE); volBar.progress = musicVolumeMax(); toast("🔊 ভলিউম সর্বোচ্চ") }, LinearLayout.LayoutParams(dp(64), dp(40)))
        root.addView(volRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        // ---- ভয়েস যোগ + ব্যাকআপ + Shizuku অপশন ----
        val addBar = LinearLayout(this)
        addBar.addView(neonButton("➕ ভয়েস যোগ") { pickVoices() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        addBar.addView(neonButton("📦 ব্যাকআপ") { backupMenu() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(addBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(neonButton("🛡 Shizuku অপশন") { shizukuOptions() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        val cp = LinearLayout(this); val ci = neonInput("🎟 কুপন কোড")
        cp.addView(ci, LinearLayout.LayoutParams(0, -2, 1f)); cp.addView(neonButton("Redeem") { redeem(ci.text.toString()) }, LinearLayout.LayoutParams(dp(100), dp(46)))
        root.addView(cp, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // ---- শেষ সমস্যার লাইন (ক্র্যাশ লগ থাকলে দেখায় — চাপলে মুছে যায়) ----
        crashText = neonText("", 11f, false).apply { gravity = Gravity.CENTER; setTextColor(0xFFFF8888.toInt()); setOnClickListener { clearCrashLog(); refreshCrashLine(); toast("লগ মুছে গেছে") } }
        root.addView(crashText)
        // ---- খোঁজা ----
        searchInput = neonInput("🔍 আমার ভয়েস খুঁজুন…")
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { searchQuery = s?.toString()?.trim() ?: ""; render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        root.addView(searchInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val links = LinearLayout(this)
        links.addView(neonButton("▶ YouTube") { open(Api.cfg?.optString("youtube")) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        links.addView(neonButton("✈ Telegram") { open(Api.cfg?.optString("telegram")) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        root.addView(links)
        setContentView(FrameLayout(this).apply { addView(Glow(this@MainActivity)); addView(root) })
        render()
        refreshMic()
        refreshCrashLine()
    }

    override fun onResume() { super.onResume(); refreshMic(); localVoices = Voices.load(this); render(); refreshCrashLine(); refreshQuiet() }
    override fun onDestroy() { try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (_: Exception) {}; super.onDestroy() }

    /** Admin থেকে ক্রেডিট/মেয়াদ বদলালে অ্যাপ খুললেই নতুন মান দেখাবে (কোনো টোস্ট ছাড়া)। */
    private fun refreshQuiet() = Thread { if (Api.loadConfig()) runOnUiThread { render() } }.start()

    private fun refreshCrashLine() {
        val last = readCrashLogLast()
        crashText.text = if (last != null) "⚠ শেষ সমস্যা: $last (চাপলে মুছবে)" else ""
    }

    private fun refreshMic() {
        if (!::micStatus.isInitialized) return
        micStatus.text = when {
            MicInjector.isMicActive() -> "✅ Injector চালু — সাউন্ড মাইক্রোফোনে যাবে"
            !MicInjector.apiLevelOk() -> "Android 6.0+ লাগবে"
            !MicInjector.isShizukuInstalled(this) -> "Shizuku অ্যাপ ইনস্টল নেই"
            !MicInjector.isShizukuRunning() -> "Shizuku চলছে না — Shizuku খুলে Start করুন"
            !MicInjector.hasPermission() -> "Shizuku অনুমতি লাগবে — Shizuku অপশন চাপুন"
            else -> "Mic Mode: বন্ধ — স্পিকারে বাজছে"
        }
        if (::micBtn.isInitialized) micBtn.text = if (MicInjector.isMicActive()) "✅ Injector: ON" else "⚡ Injector: OFF"
    }

    private fun toggleMic() {
        if (MicInjector.isMicActive()) { MicInjector.stopInjector(this); refreshMic(); toast("Injector বন্ধ — স্পিকারে বাজবে"); return }
        micStatus.text = "⏳ Injector চালু হচ্ছে…"
        MicInjector.startInjector(this, object : MicInjector.StartCallback {
            override fun done(ok: Boolean, messageBn: String, messageEn: String) { toast(messageBn); refreshMic() }
        })
    }

    /** বিল্ট-ইন টেস্ট সাউন্ড — ভয়েস ছাড়াই স্পিকার/মাইক পথ যাচাই করা যায়। */
    private fun testSound() {
        Thread {
            try {
                val f = testToneFile()
                val toMic = MicInjector.isMicActive()
                runOnUiThread {
                    playRouted(f)
                    toast(if (toMic) "🔊 টেস্ট সাউন্ড মাইকে যাচ্ছে — গেমে শুনুন" else "🔊 টেস্ট সাউন্ড স্পিকারে বাজছে")
                }
            } catch (_: Exception) { runOnUiThread { toast("❌ টেস্ট সাউন্ড হয়নি") } }
        }.start()
    }

    private fun checkShizuku() {
        when {
            !MicInjector.isShizukuInstalled(this) -> { toast("Shizuku অ্যাপ ইনস্টল নেই — খুলছি"); MicInjector.openShizukuApp(this) }
            !MicInjector.isShizukuRunning() -> toast("Shizuku চলছে না — Shizuku খুলে Start করুন, তারপর আবার চাপুন")
            !MicInjector.hasPermission() -> { MicInjector.requestPermission(); toast("Shizuku অনুমতি চাওয়া হয়েছে — Allow দিন") }
            else -> toast("✅ Shizuku ঠিক আছে — Injector চালু করুন")
        }
        refreshMic()
    }

    /** Shizuku / Root অপশন — স্ট্যাটাস, অনুমতি, ডায়াগনস্টিক, সেটআপ গাইড। */
    private fun shizukuOptions() {
        val sb = StringBuilder()
        sb.append("Shizuku ইনস্টল: ${if (MicInjector.isShizukuInstalled(this)) "✅ হ্যাঁ" else "❌ না"}\n")
        sb.append("Shizuku চলছে: ${if (MicInjector.isShizukuRunning()) "✅ হ্যাঁ" else "❌ না — Shizuku খুলে Start করুন"}\n")
        sb.append("অনুমতি: ${if (MicInjector.hasPermission()) "✅ দেওয়া আছে" else "❌ দেওয়া হয়নি — Always allow দিন"}\n")
        sb.append("Injector: ${if (MicInjector.isMicActive()) "✅ চালু" else "বন্ধ"}\n")
        try {
            val last = MicInjector.getInjectorLogLastLine(this)
            if (!last.isNullOrBlank()) sb.append("Injector লগ: $last\n")
        } catch (_: Exception) {}
        sb.append("\nসেটআপ গাইড:\n1. Shizuku অ্যাপ খুলে Start চাপুন\n2. এই অ্যাপে 🛡 Shizuku অপশন থেকে অনুমতি চান\n3. Allow / Always allow দিন\n4. ⚡ Injector চালু করুন চাপুন\n5. গেমে গিয়ে ভয়েস বাজান — অন্য প্লেয়ার শুনবে")
        val tv = TextView(this).apply { text = sb.toString(); setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        AlertDialog.Builder(this)
            .setTitle("🛡 Shizuku / Root অপশন")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("অনুমতি চান") { _, _ -> checkShizuku() }
            .setNeutralButton("Shizuku খুলুন") { _, _ -> MicInjector.openShizukuApp(this) }
            .setNegativeButton("বন্ধ করুন", null)
            .show()
    }

    private fun pickVoices() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, PICK_VOICES)
    }

    private fun backupMenu() {
        AlertDialog.Builder(this).setTitle("📦 ভয়েস ব্যাকআপ")
            .setItems(arrayOf("📤 Export — ব্যাকআপ নিন (.zip)", "📥 Import — ব্যাকআপ ফেরান (.zip)")) { _, which ->
                if (which == 0) {
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "AS_ANAMUL_VOICES_backup.zip")
                    }
                    startActivityForResult(i, EXPORT_BACKUP)
                } else {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                    }
                    startActivityForResult(i, IMPORT_BACKUP)
                }
            }.show()
    }

    @Deprecated("Legacy activity result used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            PICK_VOICES -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
                data.data?.let { uris.add(it) }
                if (uris.isEmpty()) return
                toast("⏳ ${uris.size}টি ফাইল যোগ হচ্ছে…")
                Thread {
                    var added = 0
                    uris.forEach { u -> try { if (Voices.addUri(this, u) != null) added++ } catch (_: Exception) {} }
                    localVoices = Voices.load(this)
                    runOnUiThread { render(); toast(if (added > 0) "✅ ${added}টি ভয়েস যোগ হয়েছে" else "❌ কোনো ভয়েস যোগ হয়নি — অডিও/ভিডিও ফাইল দিন") }
                }.start()
            }
            PICK_THUMB -> {
                val uri = data.data ?: return
                val v = pendingThumb ?: return
                pendingThumb = null
                Thread {
                    val nv = Voices.setThumb(this, localVoices, v, uri)
                    localVoices = Voices.load(this)
                    runOnUiThread { render(); toast(if (nv != null) "✅ থাম্বনেইল বসানো হয়েছে" else "❌ থাম্বনেইল হয়নি") }
                }.start()
            }
            EXPORT_BACKUP -> {
                val uri = data.data ?: return
                Thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            val n = Voices.exportZip(this, localVoices, out)
                            runOnUiThread { toast("✅ ব্যাকআপ হয়েছে — ${n}টি ভয়েস") }
                        }
                    } catch (e: Exception) { runOnUiThread { toast("❌ ব্যাকআপ হয়নি: ${e.message}") } }
                }.start()
            }
            IMPORT_BACKUP -> {
                val uri = data.data ?: return
                toast("⏳ ব্যাকআপ ফেরানো হচ্ছে…")
                Thread {
                    try {
                        contentResolver.openInputStream(uri)?.use { inp ->
                            val n = Voices.importZip(this, localVoices, inp)
                            localVoices = Voices.load(this)
                            runOnUiThread { render(); toast(if (n > 0) "✅ ${n}টি ভয়েস ফেরানো হয়েছে" else "❌ ব্যাকআপে কোনো ভয়েস পাওয়া যায়নি") }
                        }
                    } catch (e: Exception) { runOnUiThread { toast("❌ Import হয়নি: ${e.message}") } }
                }.start()
            }
        }
    }

    private fun render() {
        val nowSec = System.currentTimeMillis() / 1000
        val d = if (Api.expires == 0L) "∞ আনলিমিটেড" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(Api.expires * 1000))
        val left = if (Api.expires == 0L) "" else {
            val days = (Api.expires - nowSec) / 86400
            if (days < 0) " (মেয়াদ শেষ)" else " ($days দিন বাকি)"
        }
        info.text = "মেয়াদ: $d$left   |   Credits: ${Api.credits}"
        list.removeAllViews()
        val premium = Api.expires == 0L || Api.expires > nowSec
        if (Api.voices.isNotEmpty()) {
            list.addView(neonText("☁ সার্ভার ভয়েস (${Api.voices.size})", 14f).apply { setTextColor(0xFF9FFFFF.toInt()) })
            Api.voices.forEach { v -> list.addView(card(v, premium), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        }
        val shown = if (searchQuery.isEmpty()) localVoices else localVoices.filter { it.title.contains(searchQuery, ignoreCase = true) }
        list.addView(neonText("🎵 আমার ভয়েস (${shown.size}) — চাপলে বাজবে, চেপে ধরলে Rename/Delete/Thumbnail", 14f).apply { setTextColor(0xFF9FFFFF.toInt()) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        shown.forEach { v -> list.addView(localCard(v), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        if (Api.voices.isEmpty() && localVoices.isEmpty()) list.addView(neonText("কোনো ভয়েস নেই। ➕ ভয়েস যোগ চাপুন, বা 🔊 টেস্ট সাউন্ড বাজান।", 14f))
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

    private fun localCard(v: Voices.V): FrameLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val thumb = Voices.thumbFile(this, v)
        var thumbShown = false
        if (thumb != null) {
            val bmp = try { BitmapFactory.decodeFile(thumb.path, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (_: Exception) { null }
            if (bmp != null) {
                row.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(52), dp(52)))
                thumbShown = true
            }
        }
        if (!thumbShown) row.addView(neonText("🎵", 22f), LinearLayout.LayoutParams(dp(40), dp(40)))
        val tag = when (v.source) { "video" -> " 🎬" ; "tts" -> " ✨" ; else -> "" }
        row.addView(neonText(v.title + tag, 16f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        val frame = RgbFrame(this, row)
        frame.setOnClickListener { playLocal(v) }
        frame.setOnLongClickListener { localMenu(v); true }
        return frame
    }

    private fun localMenu(v: Voices.V) {
        val input = neonInput("নতুন নাম লিখুন").apply { setText(v.title) }
        AlertDialog.Builder(this).setTitle("🎵 ${v.title}")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { Voices.rename(this, localVoices, v, t); render(); toast("✅ নাম বদলে গেছে") }
            }
            .setNegativeButton("🗑 Delete") { _, _ -> Voices.delete(this, localVoices, v); render(); toast("🗑 মুছে গেছে") }
            .setNeutralButton("🖼 থাম্বনেইল") { _, _ -> pickThumb(v) }
            .show()
    }

    private fun pickThumb(v: Voices.V) {
        pendingThumb = v
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(Intent.createChooser(i, "থাম্বনেইল ছবি বেছে নিন"), PICK_THUMB)
    }

    private fun playLocal(v: Voices.V) {
        Thread { try { val f = Voices.file(this, v); if (f.exists()) playRouted(f) else runOnUiThread { toast("❌ ফাইল পাওয়া যায়নি") } } catch (_: Exception) { runOnUiThread { toast("❌ প্লে হয়নি") } } }.start()
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

    companion object {
        private const val PICK_VOICES = 7021
        private const val EXPORT_BACKUP = 7022
        private const val IMPORT_BACKUP = 7023
        private const val PICK_THUMB = 7024
    }
}
