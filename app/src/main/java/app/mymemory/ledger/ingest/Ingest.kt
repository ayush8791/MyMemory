package app.mymemory.ledger.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.mymemory.Prefs
import app.mymemory.ledger.data.LedgerDao
import app.mymemory.ledger.data.RawMessage
import app.mymemory.ledger.parse.RuleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pulls new SMS and e-mails into the `raw` table. Idempotent: running it twice adds nothing. */
class Ingest(private val ctx: Context, private val dao: LedgerDao, private val prefs: Prefs) {

    private val day = 24L * 3600_000L

    suspend fun syncSources() {
        runCatching { syncSms() }
        runCatching { syncGmail() }
    }

    fun smsPermitted() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private suspend fun syncSms() = withContext(Dispatchers.IO) {
        if (!smsPermitted()) return@withContext
        val now = System.currentTimeMillis()
        // One day of overlap so nothing is missed around the boundary; duplicates are ignored by the DB.
        val since = if (prefs.lastSmsTs > 0) prefs.lastSmsTs - day else now - prefs.backfillDays * day
        SmsReader.readFinancial(ctx, since).forEach { dao.insertRaw(it) }
        prefs.lastSmsTs = now
    }

    private suspend fun syncGmail() {
        if (!prefs.gmailEnabled) return
        val token = GmailAuth.silentToken(ctx) ?: return
        val now = System.currentTimeMillis()
        val since = if (prefs.lastGmailTs > 0) prefs.lastGmailTs - day else now - prefs.backfillDays * day
        withContext(Dispatchers.IO) {
            val gmail = GmailClient(token)
            var page: String? = null
            var seen = 0
            do {
                val (ids, next) = gmail.list(GmailClient.financialQuery(since / 1000), page)
                for (id in ids) {
                    seen++
                    if (dao.hasRaw("GMAIL", id)) continue
                    val mail = gmail.fetch(id) ?: continue
                    // Only keep mails that carry an amount: receipts, alerts, statements.
                    if (!RuleParser.hasAmount(mail.body) || RuleParser.isOtp(mail.body)) continue
                    dao.insertRaw(
                        RawMessage(
                            source = "GMAIL", externalId = id, sender = mail.from,
                            receivedAt = mail.dateMs, body = mail.body,
                        )
                    )
                }
                page = next
            } while (page != null && seen < 400)
        }
        prefs.lastGmailTs = now
    }
}
