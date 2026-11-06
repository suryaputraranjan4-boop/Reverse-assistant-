package com.reverseassistant.search

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Lightweight, user-triggered lookup using DuckDuckGo's Instant Answer endpoint.
 * This is not a full search-engine results API; it returns abstracts and related topics when available.
 */
object WebSearchClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun search(query: String): String {
        require(query.isNotBlank()) { "Enter a search query first." }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.duckduckgo.com")
            .addQueryParameter("q", query.take(240))
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Quick web lookup returned HTTP ${response.code}.")
            val json = JSONObject(response.body?.string().orEmpty())
            val lines = LinkedHashSet<String>()
            val abstractText = json.optString("AbstractText").trim()
            val abstractUrl = json.optString("AbstractURL").trim()
            val heading = json.optString("Heading").trim()
            if (abstractText.isNotBlank()) {
                lines.add((if (heading.isBlank()) "" else "$heading: ") + abstractText +
                    (if (abstractUrl.isBlank()) "" else "\nSource: $abstractUrl"))
            }
            collectTopics(json.optJSONArray("RelatedTopics"), lines)
            val answer = json.optString("Answer").trim()
            if (answer.isNotBlank()) lines.add("Instant answer: $answer")
            if (lines.isEmpty()) {
                return "No instant-answer results were returned for this query. Try a more specific query or use a full search engine in your browser."
            }
            return lines.take(8).joinToString("\n\n").take(9000)
        }
    }

    private fun collectTopics(topics: JSONArray?, output: LinkedHashSet<String>) {
        if (topics == null) return
        for (i in 0 until topics.length()) {
            val item = topics.optJSONObject(i) ?: continue
            val nested = item.optJSONArray("Topics")
            if (nested != null) {
                collectTopics(nested, output)
                continue
            }
            val text = item.optString("Text").trim()
            val url = item.optString("FirstURL").trim()
            if (text.isNotBlank()) output.add(text + if (url.isBlank()) "" else "\nSource: $url")
            if (output.size >= 8) return
        }
    }
}
