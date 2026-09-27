package com.offgrid.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.offgrid.shared.models.AnswerSource
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class WebResult(val title: String, val url: String, val snippet: String)

/** Read-only public-web tools. Never attach chat history, memories, or documents. */
class WebTools(private val context: Context) {
    @Volatile var allowed = false
    // Deliberately session-only: a personal key is never written to logs, backup, or source.
    @Volatile var braveKey = ""
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc) }) { "Only public websites are supported." }
            }
        }).build()

    fun connected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private fun checkAccess() {
        check(allowed) { "Enable Web allowed first. Only your search or selected URL will be sent." }
        check(connected()) { "You are offline. Saved library items and local tools still work." }
    }
    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        checkAccess()
        var current = url.toHttpUrl()
        repeat(5) {
            require(current.isHttps && current.username.isEmpty() && current.password.isEmpty() && current.port == 443) { "Use a public HTTPS URL." }
            checkAccess()
            val request = Request.Builder().url(current).header("User-Agent", "Offgrid/0.2 (personal reader)")
            // Credentials only go to the exact API host, never to a redirect target.
            if (current.host == "api.search.brave.com") headers.forEach { (k,v) -> request.header(k,v) }
            http.newCall(request.build()).execute().use { response ->
                if(response.code in 300..399) {
                    current = current.resolve(response.header("Location") ?: error("Page redirect has no destination.")) ?: error("Invalid redirect.")
                } else {
                    check(response.isSuccessful) { "Website returned HTTP ${response.code}. Try another result." }
                    val type = response.header("Content-Type").orEmpty()
                    require(type.contains("text/") || type.contains("json") || type.contains("xml")) { "This link is not a readable web page." }
                    val body = response.body ?: error("Empty response.")
                    return body.byteStream().use { it.readBytesLimited(2 * 1024 * 1024).toString(Charsets.UTF_8) }
                }
            }
        }
        error("Too many page redirects.")
    }
    fun search(query: String, brave: Boolean): List<WebResult> {
        require(query.isNotBlank() && query.length <= 300) { "Enter a search up to 300 characters." }
        if (brave) {
            require(braveKey.isNotBlank()) { "Add your Brave Search API key in Settings, or choose Wikipedia search." }
            val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("count", "5").build()
            val a = JSONObject(get(url.toString(), mapOf("X-Subscription-Token" to braveKey))).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it).let { o -> WebResult(o.getString("title"), o.getString("url"), Jsoup.parse(o.optString("description")).text()) } }
        }
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("list", "search").addQueryParameter("srsearch", query)
            .addQueryParameter("srlimit", "5").addQueryParameter("format", "json").build()
        val a = JSONObject(get(url.toString())).getJSONObject("query").getJSONArray("search")
        return (0 until a.length()).map { a.getJSONObject(it).let { o ->
            WebResult(o.getString("title"), "https://en.wikipedia.org/?curid=${o.getLong("pageid")}", Jsoup.parse(o.optString("snippet")).text())
        } }
    }
    fun read(url: String): AnswerSource {
        val doc = Jsoup.parse(get(url), url)
        doc.select("script,style,nav,footer,header,aside,form,button,noscript").remove()
        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val text = root.select("h1,h2,h3,p,li,pre,blockquote").joinToString("\n\n") { it.text() }.ifBlank { root.text() }.take(100_000)
        require(text.length >= 80) { "This page has little readable text. It may require login or JavaScript." }
        return AnswerSource(doc.title().ifBlank { url }.take(160), text, url, System.currentTimeMillis())
    }
    fun cancel() { http.dispatcher.cancelAll() }
}
