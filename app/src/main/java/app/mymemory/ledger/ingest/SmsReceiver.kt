package app.mymemory.ledger.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Only a nudge: "a text just arrived, go and sync". The SMS inbox stays the single source of truth,
 * which avoids the same message being ingested twice (once live, once from the inbox scan).
 * The small delay lets the default SMS app finish writing the message to the inbox first.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            SyncWorker.syncNow(context, delaySeconds = 5)
        }
    }
}
