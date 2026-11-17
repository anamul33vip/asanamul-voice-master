package com.anamul.voice

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Text-to-speech screen. The ElevenLabs API key stays on the backend server. */
class TtsActivity : Activity() {
    private lateinit var voiceId: EditText
    private lateinit var voiceName: EditText
    private lateinit var savedVoiceSpinner: Spinner
    private val savedVoiceIds = mutableListOf<JSONObject>()
    private lateinit var textInput: EditText
    private lateinit var model: Spinner
    private lateinit var status: TextView
    private lateinit var generate: Button
    private var lastAudio: File? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(16))
        }
        root.addView(neonText("🎙 TTS VOICE GENERATION", 21f).apply { gravity = Gravity.CENTER })
        root.addView(neonText("লেখা দিন, Voice ID নির্বাচন করুন, অডিও তৈরি করুন", 13f, false).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFB8B8D8.toInt())
        })

        root.addView(neonText("ElevenLabs Voice ID", 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        voiceId = neonInput("আপনার Voice ID লিখুন")
        voiceId.setSingleLine(true)
        voiceId.setText(Api.cfg?.optString("tts_voice_id", "") ?: "")
        root.addView(voiceId, LinearLayout.LayoutParams(-1, -2))
        voiceName = neonInput("Saved voice name (e.g. My Bangla Voice)")
        root.addView(voiceName, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        savedVoiceSpinner = Spinner(this)
        loadSavedVoiceIds()
        root.addView(savedVoiceSpinner, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        savedVoiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position > 0 && position - 1 < savedVoiceIds.size) {
                    val item = savedVoiceIds[position - 1]
                    voiceName.setText(item.optString("name")); voiceId.setText(item.optString("id"))
                }
            }
        }
        root.addView(neonButton("💾 Save Voice ID") { saveVoiceId() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })

        root.addView(neonText("AI Model", 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        model = Spinner(this)
        val models = listOf("eleven_v3", "eleven_multilingual_v2", "eleven_flash_v2_5")
        model.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        val configured = Api.cfg?.optString("tts_model_id", "eleven_v3") ?: "eleven_v3"
        model.setSelection(models.indexOf(configured).coerceAtLeast(0))
        root.addView(model, LinearLayout.LayoutParams(-1, dp(48)))

        root.addView(neonText("যে লেখা Voice-এ রূপান্তর করবেন", 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        textInput = neonInput("এখানে বাংলা বা ইংরেজি লেখা লিখুন…").apply {
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(textInput, LinearLayout.LayoutParams(-1, dp(150)))

        status = neonText("API Key সার্ভারে সেট করা থাকলে Generate কাজ করবে।", 12f, false).apply {
            setTextColor(0xFF9FFFFF.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        generate = neonButton("✨ Generate Voice") { generateVoice() }
        root.addView(generate, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        root.addView(neonButton("▶ Play Generated Audio") { lastAudio?.let { playRouted(it) } ?: toast("আগে Voice Generate করুন") },
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        root.addView(neonButton("⬇ Save MP3 to phone") { saveAudio() },
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        root.addView(neonButton("← Back") { finish() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        setContentView(FrameLayout(this).apply { addView(Glow(this@TtsActivity)); addView(ScrollView(this@TtsActivity).apply { addView(root) }) })
    }

    private fun loadSavedVoiceIds() {
        savedVoiceIds.clear()
        try {
            val array = JSONArray(getSharedPreferences("tts_saved_voices", MODE_PRIVATE).getString("items", "[]"))
            for (i in 0 until array.length()) savedVoiceIds.add(array.getJSONObject(i))
        } catch (_: Exception) { }
        refreshSavedVoiceSpinner()
    }

    private fun refreshSavedVoiceSpinner() {
        val labels = mutableListOf("Saved Voice IDs — নির্বাচন করুন")
        savedVoiceIds.forEach { labels.add(it.optString("name") + " • " + it.optString("id")) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        savedVoiceSpinner.adapter = adapter
        savedVoiceSpinner.setSelection(0)
    }

    private fun saveVoiceId() {
        val id = voiceId.text.toString().trim()
        if (id.isEmpty()) { toast("Voice ID লিখুন"); return }
        val name = voiceName.text.toString().trim().ifEmpty { "My Voice ${savedVoiceIds.size + 1}" }
        val index = savedVoiceIds.indexOfFirst { it.optString("id") == id }
        val item = JSONObject().put("name", name).put("id", id)
        if (index >= 0) savedVoiceIds[index] = item else savedVoiceIds.add(item)
        val array = JSONArray(); savedVoiceIds.forEach { array.put(it) }
        getSharedPreferences("tts_saved_voices", MODE_PRIVATE).edit().putString("items", array.toString()).apply()
        refreshSavedVoiceSpinner()
        toast("Voice ID সেভ হয়েছে")
    }

    private fun generateVoice() {
        val txt = textInput.text.toString().trim()
        val id = voiceId.text.toString().trim()
        if (txt.isEmpty()) { toast("লেখা লিখুন"); return }
        if (id.isEmpty()) { toast("Voice ID লিখুন"); return }
        if (txt.length > 3000 && model.selectedItem.toString() == "eleven_v3") {
            toast("Eleven v3-তে একবারে সর্বোচ্চ 3000 অক্ষর দিন"); return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(textInput.windowToken, 0)
        generate.isEnabled = false
        status.text = "⏳ Voice তৈরি হচ্ছে…"
        val selectedModel = model.selectedItem.toString()
        getSharedPreferences("tts_preferences", MODE_PRIVATE).edit().putString("last_voice_id", id).apply()
        Thread {
            val request = JSONObject()
                .put("text", txt)
                .put("voice_id", id)
                .put("model_id", selectedModel)
                .put("language_code", if (txt.any { it in '\u0980'..'\u09FF' }) "bn" else "en")
            val response = Api.call("/api/app/tts", request, 120000)
            if (response.optBoolean("ok")) {
                try {
                    val url = response.getString("audio_url")
                    val f = Api.download(this, url, "tts_" + response.optString("id", System.currentTimeMillis().toString()) + ".mp3")
                    runOnUiThread {
                        lastAudio = f
                        generate.isEnabled = true
                        status.text = "✅ Voice তৈরি হয়েছে — ${f.length() / 1024} KB"
                        playRouted(f)
                    }
                } catch (e: Exception) {
                    runOnUiThread { generate.isEnabled = true; status.text = "❌ অডিও ডাউনলোড হয়নি: ${e.message}" }
                }
            } else {
                val err = response.optString("error", "unknown_error")
                val detail = response.optString("detail", response.optString("message", ""))
                runOnUiThread {
                    generate.isEnabled = true
                    status.text = when (err) {
                        "tts_not_configured" -> "❌ সার্ভারে ElevenLabs API Key সেট করা নেই।"
                        "voice_id_required" -> "❌ Voice ID দিন।"
                        "text_too_long" -> "❌ লেখাটি বেশি বড়। ছোট করে দিন।"
                        "session" -> "❌ Login session শেষ। আবার Login করুন।"
                        "invalid_voice_id" -> "❌ Voice ID সঠিক নয়। ElevenLabs থেকে Voice ID কপি করুন।"
                        "tts_provider_error" -> "❌ Provider অনুরোধ গ্রহণ করেনি। Voice ID, API Key ও quota পরীক্ষা করুন।"
                        "insufficient_credits" -> "❌ অ্যাপের TTS credit যথেষ্ট নেই।"
                        "rate_limited" -> "⏳ একটু অপেক্ষা করে আবার চেষ্টা করুন।"
                        "expired" -> "⏳ Access Key-এর মেয়াদ শেষ।"
                        "provider_auth_failed" -> "❌ ElevenLabs API Key ভুল বা অনুমতি নেই।"
                        "provider_quota_or_plan" -> "❌ API credit/plan limit শেষ হয়েছে।"
                        else -> "❌ Voice তৈরি হয়নি: $err ${detail.take(160)}"
                    }
                }
            }
        }.start()
    }

    private fun saveAudio() {
        val f = lastAudio ?: run { toast("আগে Voice Generate করুন"); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_TITLE, "AS_ANAMUL_VOICE_TTS.mp3")
        }
        pendingSave = f
        startActivityForResult(intent, SAVE_AUDIO_REQUEST)
    }

    @Deprecated("Legacy activity result used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SAVE_AUDIO_REQUEST && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val source = pendingSave ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(source).use { input -> input.copyTo(out) } }
                toast("MP3 সেভ হয়েছে")
            } catch (e: Exception) { toast("সেভ হয়নি: ${e.message}") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    companion object {
        private const val SAVE_AUDIO_REQUEST = 7012
        private var pendingSave: File? = null
    }
}
