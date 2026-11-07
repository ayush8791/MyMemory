package app.mymemory.ledger.ingest

import android.text.Html
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Gmail REST client (list + get). Blocking: call from a background dispatcher. */
class GmailClient(private val token: String) {

    data class Mail(val id: String, val from: String, val body: String, val dateMs: Long)

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IOException("Gmail HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    fun list(query: String, pageToken: String?): Pair<List<String>, String?> {
        var url = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=100&q=" +
            URLEncoder.encode(query, "UTF-8")
        if (pageToken != null) url += "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8")
        val j = get(url)
        val arr = j.optJSONArray("messages") ?: JSONArray()
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        return ids to j.optString("nextPageToken").takeIf { it.isNotEmpty() }
    }

    fun fetch(id: String): Mail? {
        val j = get("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full")
        val payload = j.optJSONObject("payload") ?: return null
        val headers = payload.optJSONArray("headers") ?: JSONArray()
        fun header(name: String): String = (0 until headers.length())
            .map { headers.getJSONObject(it) }
            .firstOrNull { it.optString("name").equals(name, ignoreCase = true) }
            ?.optString("value").orEmpty()

        val plain = StringBuilder()
        val html = StringBuilder()
        collect(payload, plain, html)
        val text = plain.toString().ifBlank { stripHtml(html.toString()) }
            .replace(Regex("""\s+"""), " ").trim()
        if (text.isBlank()) return null
        return Mail(
            id = id,
            from = header("From").ifBlank { "unknown" },
            body = "Subject: ${header("Subject")}\n$text".take(3500),
            dateMs = j.optString("internalDate").toLongOrNull() ?: System.currentTimeMillis(),
        )
    }

    private fun collect(part: JSONObject, plain: StringBuilder, html: StringBuilder) {
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (data.isNotEmpty()) {
            val text = String(Base64.decode(data, Base64.URL_SAFE), Charsets.UTF_8)
            when (part.optString("mimeType")) {
                "text/plain" -> plain.append(text).append('\n')
                "text/html" -> html.append(text).append('\n')
            }
        }
        val parts = part.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) collect(parts.getJSONObject(i), plain, html)
    }

    private fun stripHtml(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

    companion object {
        /** Receipts and bank alerts, minus promotions and social noise. */
        fun financialQuery(afterEpochSec: Long) =
            "after:$afterEpochSec -category:promotions -category:social " +
                "(debited OR credited OR spent OR payment OR transaction OR order OR receipt OR invoice OR paid OR refund OR UPI)"
    }
}
