package app.mymemory.ledger.ingest

import android.content.Context
import android.provider.Telephony
import app.mymemory.ledger.data.RawMessage
import app.mymemory.ledger.parse.RuleParser

object SmsReader {
    /**
     * Reads the inbox since [sinceMs] and keeps ONLY messages that look like money movement.
     * Personal conversations never reach the app's database.
     * The SMS provider's own `_id` is the external id, so re-scans can never create duplicates.
     */
    fun readFinancial(ctx: Context, sinceMs: Long): List<RawMessage> {
        val out = mutableListOf<RawMessage>()
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(sinceMs.toString()),
            "${Telephony.Sms.DATE} ASC"
        ) ?: return out
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val body = c.getString(iBody) ?: continue
                if (RuleParser.parse(body) == null) continue
                out += RawMessage(
                    source = "SMS",
                    externalId = c.getLong(iId).toString(),
                    sender = c.getString(iAddr) ?: "unknown",
                    receivedAt = c.getLong(iDate),
                    body = body,
                )
            }
        }
        return out
    }
}
