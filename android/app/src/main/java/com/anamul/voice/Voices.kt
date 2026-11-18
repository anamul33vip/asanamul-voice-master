package com.anamul.voice
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * নিজের ভয়েস লাইব্রেরি — ফোনের ভেতরে থাকে, সার্ভার লাগে না।
 * অডিও ফাইল সরাসরি যোগ করা যায়, ভিডিও থেকে অডিও টেনে নেওয়া যায়।
 * ব্যাকআপ নিজের .zip ফরম্যাটে (অন্য অ্যাপের .isfile ফরম্যাট নয়)।
 */
object Voices {
    data class V(val id: String, val title: String, val file: String, val source: String) {
        fun toJson() = JSONObject().put("id", id).put("title", title).put("file", file).put("source", source)
    }

    private const val META = "voices.json"

    fun dir(c: Context): File = File(c.getExternalFilesDir(null), "voices").apply { mkdirs() }

    fun load(c: Context): MutableList<V> {
        val out = mutableListOf<V>()
        try {
            val f = File(c.filesDir, META)
            if (!f.exists()) return out
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(V(o.optString("id"), o.optString("title"), o.optString("file"), o.optString("source", "audio")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(c: Context, list: List<V>) {
        try {
            val a = JSONArray()
            list.forEach { a.put(it.toJson()) }
            File(c.filesDir, META).writeText(a.toString())
        } catch (_: Exception) {}
    }

    fun file(c: Context, v: V): File = File(dir(c), v.file)

    private fun displayName(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val n = cur.getString(0)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "voice"
    }

    private fun mime(c: Context, uri: Uri): String {
        return try { c.contentResolver.getType(uri) ?: "" } catch (_: Exception) { "" }
    }

    private fun isVideo(c: Context, uri: Uri): Boolean {
        val m = mime(c, uri)
        if (m.startsWith("video/")) return true
        val ext = displayName(c, uri).substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "flv")
    }

    /** একটা ফাইল যোগ করা — অডিও হলে কপি, ভিডিও হলে অডিও ট্র্যাক বের করে .m4a বানানো হয়।
     *  ভিডিও থেকে অডিও বের করা না গেলে আসল ভিডিও ফাইলটাই রাখা হয় (MediaPlayer ভিডিওর অডিও বাজাতে পারে)। */
    fun addUri(c: Context, uri: Uri): V? {
        val name = displayName(c, uri)
        val base = name.substringBeforeLast('.').ifEmpty { "Voice" }
        val id = System.currentTimeMillis().toString() + "_" + (0..999).random()
        return try {
            if (isVideo(c, uri)) {
                val out = File(dir(c), "$id.m4a")
                val ok = runCatching { extractAudio(c, uri, out) }.getOrDefault(false) && out.exists() && out.length() > 0
                if (ok) return V(id, base, out.name, "video")
                out.delete()
                val ext2 = name.substringAfterLast('.', "mp4").lowercase().ifEmpty { "mp4" }
                val out2 = File(dir(c), "$id.$ext2")
                c.contentResolver.openInputStream(uri)?.use { inp -> out2.outputStream().use { inp.copyTo(it) } }
                if (out2.exists() && out2.length() > 0) V(id, base, out2.name, "video") else null
            } else {
                val ext = name.substringAfterLast('.', "mp3").lowercase().ifEmpty { "mp3" }
                val out = File(dir(c), "$id.$ext")
                c.contentResolver.openInputStream(uri)?.use { inp -> out.outputStream().use { inp.copyTo(it) } }
                if (out.exists() && out.length() > 0) V(id, base, out.name, "audio") else null
            }
        } catch (_: Exception) { null }
    }

    /** তৈরি করা ফাইল (যেমন TTS-এর MP3) লাইব্রেরিতে যোগ করা — ভয়েস চ্যাটের পর ভয়েস লিস্টে দেখা যায়। */
    fun addFile(c: Context, src: File, title: String, source: String = "tts"): V? {
        return try {
            val list = load(c)
            val id = System.currentTimeMillis().toString()
            val ext = src.extension.ifEmpty { "mp3" }
            val out = File(dir(c), "$id.$ext")
            src.copyTo(out, overwrite = true)
            val v = V(id, title, out.name, source)
            list.add(0, v)
            save(c, list)
            v
        } catch (_: Exception) { null }
    }

    /** ভিডিওর অডিও ট্র্যাকটুকু .m4a ফাইলে বের করা (Android-এর নিজের MediaExtractor/Muxer)। */
    fun extractAudio(c: Context, uri: Uri, out: File): Boolean {
        var ex: MediaExtractor? = null
        var mux: MediaMuxer? = null
        try {
            ex = MediaExtractor()
            ex.setDataSource(c, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
            }
            if (track < 0) return false
            ex.selectTrack(track)
            mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val idx = mux.addTrack(ex.getTrackFormat(track))
            mux.start()
            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, ex.sampleFlags)
                mux.writeSampleData(idx, buf, info)
                ex.advance()
            }
            mux.stop()
            return out.exists() && out.length() > 0
        } catch (_: Exception) { return false } finally {
            try { mux?.release() } catch (_: Exception) {}
            try { ex?.release() } catch (_: Exception) {}
        }
    }

    fun delete(c: Context, list: MutableList<V>, v: V) {
        try { file(c, v).delete() } catch (_: Exception) {}
        list.removeAll { it.id == v.id }
        save(c, list)
    }

    fun rename(c: Context, list: MutableList<V>, v: V, title: String) {
        val i = list.indexOfFirst { it.id == v.id }
        if (i >= 0) { list[i] = v.copy(title = title); save(c, list) }
    }

    /** ব্যাকআপ: voices.json + সব অডিও এক zip-এ। */
    fun exportZip(c: Context, list: List<V>, out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out).use { z ->
            val a = JSONArray()
            list.forEach { a.put(it.toJson()) }
            z.putNextEntry(ZipEntry("voices.json"))
            z.write(a.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
            list.forEach { v ->
                val f = file(c, v)
                if (f.exists()) {
                    z.putNextEntry(ZipEntry(v.file))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                    n++
                }
            }
        }
        return n
    }

    /** ব্যাকআপ ফেরত নেওয়া — একই id থাকলে নতুন id দিয়ে যোগ হয়। */
    fun importZip(c: Context, list: MutableList<V>, input: InputStream): Int {
        var added = 0
        val tmp = File(c.cacheDir, "voices_import")
        tmp.mkdirs()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            var meta: String? = null
            val files = mutableListOf<String>()
            while (e != null) {
                val name = e.name.substringAfterLast('/')
                if (name.isNotEmpty()) {
                    val f = File(tmp, name)
                    f.outputStream().use { z.copyTo(it) }
                    if (name == "voices.json") meta = f.readText() else files.add(name)
                }
                z.closeEntry()
                e = z.nextEntry
            }
            if (meta != null) {
                val a = JSONArray(meta)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val oldFile = o.optString("file")
                    val src = File(tmp, oldFile)
                    if (!src.exists()) continue
                    var id = o.optString("id")
                    if (list.any { it.id == id }) id = System.currentTimeMillis().toString() + "_" + (0..999).random()
                    val dest = File(dir(c), src.name)
                    val finalFile = if (dest.exists() && dest.name != oldFile) File(dir(c), id + "_" + oldFile) else dest
                    src.copyTo(finalFile, overwrite = true)
                    list.add(V(id, o.optString("title", "Voice"), finalFile.name, o.optString("source", "audio")))
                    added++
                }
            } else {
                files.forEach { name ->
                    val src = File(tmp, name)
                    val id = System.currentTimeMillis().toString() + "_" + (0..999).random()
                    val dest = File(dir(c), id + "_" + name)
                    src.copyTo(dest, overwrite = true)
                    list.add(V(id, name.substringBeforeLast('.'), dest.name, "audio"))
                    added++
                }
            }
        }
        try { tmp.deleteRecursively() } catch (_: Exception) {}
        if (added > 0) save(c, list)
        return added
    }
}
