package app.mymemory

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.mymemory.ledger.ingest.GmailAuth
import app.mymemory.ledger.ingest.SyncWorker
import app.mymemory.ui.Actions
import app.mymemory.ui.AppTheme
import app.mymemory.ui.LedgerApp
import app.mymemory.ui.LedgerViewModel
import com.google.android.gms.auth.api.identity.Identity

class MainActivity : ComponentActivity() {
    private val vm: LedgerViewModel by viewModels()

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPermissions()
        if (vm.smsGranted.value) SyncWorker.syncNow(this) else vm.toast("SMS access was not granted")
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    private val gmailConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            vm.setGmailEnabled(true)
        } catch (e: Exception) {
            vm.toast("Gmail was not connected")
        }
    }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.draft.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = Actions(
            pickModel = { modelPicker.launch(arrayOf("*/*")) },
            requestSms = { smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) },
            connectGmail = ::connectGmail,
            voice = ::startVoice,
        )
        setContent { AppTheme { LedgerApp(vm, actions) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    private fun connectGmail() {
        Identity.getAuthorizationClient(this).authorize(GmailAuth.request())
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) {
                    gmailConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    vm.setGmailEnabled(true)
                }
            }
            .addOnFailureListener { vm.toast("Gmail sign-in failed: ${it.message}") }
    }

    private fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "e.g. lent 500 to Rahul for lunch")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.toast("No speech recognizer found on this phone")
        }
    }
}
