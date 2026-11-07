package app.mymemory.ledger.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    // ---- raw messages ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRaw(r: RawMessage): Long

    @Query("SELECT EXISTS(SELECT 1 FROM raw WHERE source = :source AND externalId = :externalId)")
    suspend fun hasRaw(source: String, externalId: String): Boolean

    @Query("SELECT * FROM raw WHERE status = 'PENDING' AND attempts < 3 ORDER BY receivedAt ASC LIMIT :n")
    suspend fun pending(n: Int): List<RawMessage>

    @Query("SELECT COUNT(*) FROM raw WHERE status = 'PENDING' AND attempts < 3")
    suspend fun pendingNow(): Int

    @Query("SELECT COUNT(*) FROM raw WHERE status = 'PENDING' AND attempts < 3")
    fun pendingCount(): Flow<Int>

    @Query("UPDATE raw SET status = :status, txnId = :txnId, attempts = attempts + 1 WHERE id = :id")
    suspend fun markRaw(id: Long, status: String, txnId: Long?)

    // ---- ledger ----
    @Insert
    suspend fun insertTxn(t: LedgerTxn): Long

    @Update
    suspend fun updateTxn(t: LedgerTxn)

    @Query("SELECT * FROM txn ORDER BY occurredAt DESC")
    fun all(): Flow<List<LedgerTxn>>

    @Query("SELECT * FROM txn WHERE needsReview = 1 ORDER BY occurredAt DESC")
    fun review(): Flow<List<LedgerTxn>>

    /** Possible duplicates of a new transaction: same direction + amount, nearby in time. */
    @Query(
        "SELECT * FROM txn WHERE direction = :dir AND amountPaise = :amount " +
            "AND occurredAt BETWEEN :from AND :to"
    )
    suspend fun candidates(dir: String, amount: Long, from: Long, to: Long): List<LedgerTxn>

    @Query(
        "SELECT * FROM txn WHERE counterpartyKey = :key " +
            "AND kind IN ('LENT','BORROWED','REPAYMENT_RECEIVED','REPAYMENT_MADE')"
    )
    suspend fun peopleLedgerFor(key: String): List<LedgerTxn>

    /** "Teach once, fix many": re-categorise every untouched row of the same merchant. */
    @Query(
        "UPDATE txn SET category = :category, needsReview = 0 " +
            "WHERE counterpartyKey = :key AND userEdited = 0 AND kind = 'NORMAL'"
    )
    suspend fun recategorise(key: String, category: String)

    // ---- learned rules ----
    @Query("SELECT * FROM merchant_rule WHERE ruleKey = :key")
    suspend fun rule(key: String): MerchantRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRule(r: MerchantRule)
}

@Database(
    entities = [RawMessage::class, LedgerTxn::class, MerchantRule::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
}
