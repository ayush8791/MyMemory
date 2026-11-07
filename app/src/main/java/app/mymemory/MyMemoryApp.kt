package app.mymemory

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.mymemory.ai.LiteRtLlmEngine
import app.mymemory.ai.LlmEngine
import app.mymemory.ledger.LedgerProcessor
import app.mymemory.ledger.data.AppDb
import app.mymemory.ledger.data.LedgerDao
import app.mymemory.ledger.ingest.Ingest
import app.mymemory.ledger.ingest.SyncWorker
import app.mymemory.ledger.parse.TxnParser

/** Tiny manual DI container: one instance per process. */
class Container(ctx: Context) {
    val prefs = Prefs(ctx)
    private val db: AppDb = Room.databaseBuilder(ctx, AppDb::class.java, "mymemory.db").build()
    val dao: LedgerDao = db.ledgerDao()
    val llm: LlmEngine = LiteRtLlmEngine(ctx, prefs)
    val parser = TxnParser(llm, dao)
    val processor = LedgerProcessor(dao, parser)
    val ingest = Ingest(ctx, dao, prefs)
}

class MyMemoryApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        SyncWorker.schedulePeriodic(this)
    }

    companion object {
        fun container(ctx: Context): Container = (ctx.applicationContext as MyMemoryApp).container
    }
}
