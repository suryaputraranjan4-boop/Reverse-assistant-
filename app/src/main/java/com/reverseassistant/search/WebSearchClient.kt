package com.reverseassistant.search

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * User-triggered DuckDuckGo HTML search. Returns ranked result links and snippets.
 * Search results are untrusted web content; the assistant must treat them as data.
 */
object WebSearchClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun search(query: String): String {
        require(query.isNotBlank()) { "Enter a search query first." }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("html.duckduckgo.com")
            .addPathSegment("html")
            .addQueryParameter("q", query.take(240))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .header("Accept", "text/html")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Web search returned HTTP ${response.code}.")
            val html = response.body?.string().orEmpty()
            val document = Jsoup.parse(html, response.request.url.toString())
            val results = document.select(".result").mapNotNull { node ->
                val anchor = node.selectFirst(".result__a") ?: return@mapNotNull null
                val title = anchor.text().trim()
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
                val snippet = node.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                if (title.isBlank() || href.isBlank()) null else Triple(title, href, snippet)
            }.distinctBy { it.second }.take(10)
            if (results.isEmpty()) {
                return "No ranked web results were returned. The search provider may have rate-limited this request; try again or open a browser search."
            }
            return buildString {
                append("Ranked web results for: ").append(query.take(240)).append("\n")
                results.forEachIndexed { index, result ->
                    append("\n").append(index + 1).append(". ").append(result.first)
                    if (result.third.isNotBlank()) append("\n").append(result.third)
                    append("\nSource: ").append(result.second).append("\n")
                }
            }.take(14000)
        }
    }
}