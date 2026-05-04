package com.sync.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    private lateinit var webView: WebView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun evaluateJs(js: String) {
        scope.launch(Dispatchers.Main) {
            webView.evaluateJavascript(js, null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Full-screen immersive
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
        createNotificationChannel()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }

        // Inject JavaScript interface
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                android.util.Log.d("SYNC_JS", "${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Allow YouTube IFrame API and local files
                val url = request.url.toString()
                return !(url.startsWith("file://") || url.contains("youtube.com") ||
                         url.contains("ytimg.com") || url.contains("lrclib.net") ||
                         url.contains("music.163.com") || url.contains("google.com"))
            }
        }

        webView.loadUrl("file:///android_asset/index_android.html")

        // Start media service so app is recognized as music app
        val serviceIntent = android.content.Intent(this, MediaPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun postMessage(json: String) {
            scope.launch {
                try {
                    val msg = JSONObject(json)
                    when (msg.optString("type")) {
                        "search" -> {
                            val query = msg.optString("query")
                            val id = msg.optString("id", "0")
                            withContext(Dispatchers.IO) { doSearch(query, id) }
                        }
                        "suggest" -> {
                            val query = msg.optString("query")
                            val id = msg.optString("id", "0")
                            withContext(Dispatchers.IO) { doSuggest(query, id) }
                        }
                        "fetchLyrics" -> {
                            val title = msg.optString("title")
                            val channel = msg.optString("channel")
                            val duration = msg.optDouble("duration", 0.0)
                            val id = msg.optString("id", "0")
                            withContext(Dispatchers.IO) { doFetchLyrics(title, channel, duration, id) }
                        }
                        "setTitle" -> {
                            // handled in JS
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SYNC", "Bridge error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun setTitle(title: String) {
            android.util.Log.d("SYNC", "Now playing: $title")
        }

        @JavascriptInterface
        fun updateNowPlaying(title: String, artist: String, thumbUrl: String, isPlaying: Boolean) {
            MediaPlaybackService.instance?.updateMetadata(title, artist, thumbUrl)
            MediaPlaybackService.instance?.updatePlaybackState(isPlaying)
        }
    }

    // ── Send result back to JS ──────────────────
    private fun sendToJs(payload: JSONObject) {
        val json = payload.toString().replace("'", "\\'")
        val b64 = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        scope.launch(Dispatchers.Main) {
            webView.evaluateJavascript(
                "window.__androidCallback && window.__androidCallback(atob('$b64'))",
                null
            )
        }
    }

    // ── YouTube Search ──────────────────────────
    private fun doSearch(query: String, callbackId: String) {
        try {
            val musicQuery = "$query official audio OR music video OR mv"
            val url = "https://www.youtube.com/youtubei/v1/search?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("query", musicQuery)
                put("params", "EgIQAQ==")
            }

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-YouTube-Client-Name", "1")
            conn.setRequestProperty("X-YouTube-Client-Version", "2.20240101.00.00")
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.setRequestProperty("Referer", "https://www.youtube.com/")
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = conn.inputStream.bufferedReader().readText()

            val tracks = parseSearchResults(json)
            val result = JSONObject().apply {
                put("type", "searchResult")
                put("id", callbackId)
                put("success", true)
                put("tracks", tracks)
            }
            sendToJs(result)
        } catch (e: Exception) {
            sendToJs(JSONObject().apply {
                put("type", "searchResult")
                put("id", callbackId)
                put("success", false)
                put("error", e.message)
            })
        }
    }

    private fun parseSearchResults(json: String): JSONArray {
        val list = JSONArray()
        try {
            val doc = JSONObject(json)
            val sections = doc
                .getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            for (si in 0 until sections.length()) {
                val sec = sections.getJSONObject(si)
                val isr = sec.optJSONObject("itemSectionRenderer") ?: continue
                val items = isr.optJSONArray("contents") ?: continue

                for (ii in 0 until items.length()) {
                    val item = items.getJSONObject(ii)
                    val vr = item.optJSONObject("videoRenderer") ?: continue
                    val id = vr.optString("videoId").takeIf { it.isNotEmpty() } ?: continue

                    val title = vr.optJSONObject("title")
                        ?.optJSONArray("runs")?.optJSONObject(0)
                        ?.optString("text") ?: continue

                    val ch = (vr.optJSONObject("ownerText") ?: vr.optJSONObject("shortBylineText"))
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""

                    val durStr = vr.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                    val dur = parseDur(durStr)

                    if (!isMusicVideo(title, ch, dur)) continue

                    list.put(JSONObject().apply {
                        put("id", id)
                        put("title", title)
                        put("channel", ch)
                        put("dur", dur)
                        put("thumb", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
                    })
                    if (list.length() >= 20) break
                }
                if (list.length() >= 20) break
            }
        } catch (e: Exception) {
            android.util.Log.e("SYNC", "Parse error: ${e.message}")
        }
        return list
    }

    private fun isMusicVideo(title: String, channel: String, dur: Int): Boolean {
        val t = title.lowercase()
        val c = channel.lowercase()
        if (c.contains("vevo") || c.contains("topic") || c.contains("music") ||
            c.contains("records") || c.contains("official") || c.contains("sound") ||
            c.contains("audio") || c.contains("entertainment")) return true
        if (t.contains("official") || t.contains("mv") || t.contains("m/v") ||
            t.contains("music video") || t.contains("audio") || t.contains("lyrics") ||
            t.contains("lyric") || t.contains("live") || t.contains("performance")) return true
        if (dur >= 60) return true
        return false
    }

    private fun parseDur(s: String): Int {
        if (s.isEmpty()) return 0
        val p = s.split(":")
        return try {
            when (p.size) {
                3 -> p[0].toInt() * 3600 + p[1].toInt() * 60 + p[2].toInt()
                2 -> p[0].toInt() * 60 + p[1].toInt()
                else -> 0
            }
        } catch (e: Exception) { 0 }
    }

    // ── Autocomplete Suggest ────────────────────
    private fun doSuggest(query: String, callbackId: String) {
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$enc&hl=ko"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val json = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(json)
            val suggestions = JSONArray()
            if (arr.length() > 1) {
                val sugs = arr.getJSONArray(1)
                for (i in 0 until min(8, sugs.length())) {
                    suggestions.put(sugs.getString(i))
                }
            }
            sendToJs(JSONObject().apply {
                put("type", "suggestResult")
                put("id", callbackId)
                put("success", true)
                put("suggestions", suggestions)
            })
        } catch (e: Exception) {
            sendToJs(JSONObject().apply {
                put("type", "suggestResult")
                put("id", callbackId)
                put("success", false)
                put("suggestions", JSONArray())
            })
        }
    }

    // ── Lyrics Fetch ────────────────────────────
    private fun doFetchLyrics(rawTitle: String, channel: String, ytDuration: Double, callbackId: String) {
        var lines: JSONArray? = tryLrclib(rawTitle, channel, ytDuration)
        if (lines == null) lines = tryNetEase(rawTitle, channel, ytDuration)

        if (lines != null) {
            sendToJs(JSONObject().apply {
                put("type", "lyricsResult")
                put("id", callbackId)
                put("success", true)
                put("lines", lines)
            })
        } else {
            sendToJs(JSONObject().apply {
                put("type", "lyricsResult")
                put("id", callbackId)
                put("success", false)
                put("lines", JSONArray())
            })
        }
    }

    private fun tryLrclib(rawTitle: String, channel: String, ytDuration: Double): JSONArray? {
        return try {
            val cleanTitle = cleanTitle(rawTitle)
            val cleanArtist = cleanArtist(channel)

            val queries = listOf(
                "$cleanTitle $cleanArtist",
                cleanTitle,
                stripBrackets(cleanTitle),
                "$cleanArtist $cleanTitle"
            ).distinct()

            var results: JSONArray? = null
            for (q in queries) {
                val res = searchLrclib(q) ?: continue
                if (hasSyncedResults(res)) { results = res; break }
            }
            if (results == null) return null

            // Score candidates
            data class Candidate(val lrc: String, val score: Double)
            val candidates = mutableListOf<Candidate>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val lrcText = item.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: continue
                val lrcDur = getLrcLastTimestamp(lrcText)
                    .takeIf { it > 0 } ?: item.optDouble("duration", 0.0)

                var score = 0.0
                if (ytDuration > 0 && lrcDur > 0) {
                    val diff = abs(lrcDur - ytDuration)
                    score += when {
                        diff <= 3 -> 50.0
                        diff <= 10 -> 35.0
                        diff <= 30 -> 15.0
                        diff <= 60 -> 5.0
                        else -> -25.0
                    }
                }
                score += titleSimilarityScore(cleanTitle, item.optString("trackName")) * 30
                score += titleSimilarityScore(cleanArtist, item.optString("artistName")) * 20
                candidates.add(Candidate(lrcText, score))
            }

            if (candidates.isEmpty()) return null
            candidates.sortByDescending { it.score }
            val parsed = parseLrc(candidates[0].lrc)
            if (parsed.length() > 0) parsed else null
        } catch (e: Exception) { null }
    }

    private fun searchLrclib(query: String): JSONArray? {
        return try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://lrclib.net/api/search?q=$enc").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "SYNC Android/1.0")
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val json = conn.inputStream.bufferedReader().readText()
            JSONArray(json)
        } catch (e: Exception) { null }
    }

    private fun hasSyncedResults(arr: JSONArray): Boolean {
        for (i in 0 until arr.length()) {
            val sl = arr.getJSONObject(i).optString("syncedLyrics")
            if (sl.isNotBlank()) return true
        }
        return false
    }

    private fun tryNetEase(rawTitle: String, channel: String, ytDuration: Double): JSONArray? {
        return try {
            val cleanTitle = cleanTitle(rawTitle)
            val cleanArtist = cleanArtist(channel)
            val queries = listOf("$cleanTitle $cleanArtist", cleanTitle, stripBrackets(cleanTitle)).distinct()

            var candidates: List<Pair<Long, Double>>? = null
            for (q in queries) {
                candidates = searchNetEase(q, cleanTitle, cleanArtist, ytDuration)
                if (!candidates.isNullOrEmpty()) break
            }
            if (candidates.isNullOrEmpty()) return null

            for (i in 0 until min(3, candidates.size)) {
                if (candidates[i].second < 40) break
                val lines = fetchNetEaseLrc(candidates[i].first)
                if (lines != null && lines.length() > 0) return lines
            }
            null
        } catch (e: Exception) { null }
    }

    private fun searchNetEase(query: String, cleanTitle: String, cleanArtist: String, ytDuration: Double): List<Pair<Long, Double>> {
        return try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://music.163.com/api/search/get?s=$enc&type=1&limit=10").openConnection() as HttpURLConnection
            conn.setRequestProperty("Referer", "https://music.163.com")
            conn.setRequestProperty("Cookie", "appver=8.0.0")
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val json = conn.inputStream.bufferedReader().readText()
            val doc = JSONObject(json)
            val songs = doc.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()

            val list = mutableListOf<Pair<Long, Double>>()
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                val id = song.optLong("id")
                val songTitle = song.optString("name")
                val artists = buildString {
                    song.optJSONArray("artists")?.let { arr ->
                        for (j in 0 until arr.length()) append(arr.getJSONObject(j).optString("name") + " ")
                    }
                }.trim()
                val duration = song.optDouble("duration", 0.0) / 1000.0

                var score = titleSimilarityScore(cleanTitle, songTitle) * 40
                score += titleSimilarityScore(cleanArtist, artists) * 25
                if (ytDuration > 0 && duration > 0) {
                    val diff = abs(duration - ytDuration)
                    score += when {
                        diff <= 3 -> 30.0
                        diff <= 10 -> 18.0
                        diff <= 30 -> 8.0
                        else -> -15.0
                    }
                }
                list.add(Pair(id, score))
            }
            list.sortByDescending { it.second }
            list
        } catch (e: Exception) { emptyList() }
    }

    private fun fetchNetEaseLrc(songId: Long): JSONArray? {
        return try {
            val conn = URL("https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1").openConnection() as HttpURLConnection
            conn.setRequestProperty("Referer", "https://music.163.com")
            conn.setRequestProperty("Cookie", "appver=8.0.0")
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val json = conn.inputStream.bufferedReader().readText()
            val doc = JSONObject(json)
            val lrcText = doc.optJSONObject("klyric")?.optString("lyric")?.takeIf { it.isNotBlank() }
                ?: doc.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
                ?: return null
            val parsed = parseLrc(lrcText)
            if (parsed.length() > 0) parsed else null
        } catch (e: Exception) { null }
    }

    // ── LRC Parser ──────────────────────────────
    private val timestampRegex = Regex("""^\[(\d+):(\d+)\.(\d+)](.*)""")
    private val creditRegex = Regex("""^\s*(?:作词|作曲|编曲|混音|制作人|出品|录音|母带|OP|SP|厂牌|발행|MV監製)\s*[：:].{0,80}$""")

    private fun parseLrc(lrc: String): JSONArray {
        val list = mutableListOf<Pair<Double, String>>()
        for (line in lrc.split("\n")) {
            val l = line.trim()
            val m = timestampRegex.find(l) ?: continue
            val min = m.groupValues[1].toIntOrNull() ?: continue
            val sec = m.groupValues[2].toIntOrNull() ?: continue
            val ms = m.groupValues[3].padEnd(3, '0').take(3).toIntOrNull() ?: 0
            val t = min * 60.0 + sec + ms / 1000.0
            val text = m.groupValues[4].trim()
            if (text.isEmpty() || creditRegex.containsMatchIn(text)) continue
            list.add(Pair(t, text))
        }
        list.sortBy { it.first }

        val result = JSONArray()
        for (i in list.indices) {
            val start = list[i].first
            val end = if (i + 1 < list.size) list[i + 1].first else start + 5.0
            result.put(JSONObject().apply {
                put("start", start)
                put("end", end)
                put("text", list[i].second)
            })
        }
        return result
    }

    private fun getLrcLastTimestamp(lrc: String): Double {
        var last = 0.0
        val rx = Regex("""^\[(\d+):(\d+)\.(\d+)]""")
        for (line in lrc.split("\n")) {
            val m = rx.find(line.trim()) ?: continue
            val t = m.groupValues[1].toInt() * 60.0 + m.groupValues[2].toInt() +
                    m.groupValues[3].padEnd(3, '0').take(3).toInt() / 1000.0
            if (t > last) last = t
        }
        return last
    }

    // ── Title/Artist Cleaners ───────────────────
    private fun cleanTitle(t: String): String {
        var r = t
        val opts = setOf(RegexOption.IGNORE_CASE)
        val tagRx = """official\s*(?:music\s*)?(?:video|audio|mv|lyric\s*video)?|m/?v|music\s*video|audio(?:\s*only)?|lyrics?\s*(?:video|ver(?:sion)?)?|live(?:\s+(?:performance|version|session))?|hd|4k|1080p|720p|remaster(?:ed)?(?:\s+version)?|feat\.?\s*.+?|ft\.?\s*.+?"""
        r = Regex("""\(\s*(?:$tagRx)[^)]*\)""", opts).replace(r, "").trim()
        r = Regex("""\[\s*(?:$tagRx)[^\]]*]""", opts).replace(r, "").trim()
        r = Regex("""\s*[-|]\s*(?:$tagRx)\s*$""", opts).replace(r, "").trim()
        r = Regex("""\s+(?:feat\.?|ft\.?|with)\s+.+$""", opts).replace(r, "").trim()
        r = Regex("""\s{2,}""").replace(r, " ").trim()
        return r
    }

    private fun cleanArtist(c: String): String {
        var r = c
        val opts = setOf(RegexOption.IGNORE_CASE)
        r = Regex("""\s*[-–]\s*Topic\s*$""", opts).replace(r, "").trim()
        r = Regex("""VEVO$""", opts).replace(r, "").trim()
        r = Regex("""\s*(?:Records|Entertainment|Music|Official|Label|Studios?)\s*$""", opts).replace(r, "").trim()
        return r.trim()
    }

    private fun stripBrackets(t: String): String {
        var r = Regex("""\([^)]*\)""").replace(t, "").trim()
        r = Regex("""\[[^\]]*]""").replace(r, "").trim()
        return Regex("""\s{2,}""").replace(r, " ").trim()
    }

    private fun titleSimilarityScore(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val al = a.lowercase(); val bl = b.lowercase()
        if (al == bl) return 1.0
        if (bl.contains(al) || al.contains(bl)) return 0.85
        val wa = Regex("""\W+""").split(al).filter { it.length > 1 }.toSet()
        val wb = Regex("""\W+""").split(bl).filter { it.length > 1 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return wa.intersect(wb).size.toDouble() / max(wa.size, wb.size)
    }

    // ── Notification Channel ────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sync_media",
                "SYNC 음악 재생",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "현재 재생 중인 음악 알림"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        webView.destroy()
    }
}
