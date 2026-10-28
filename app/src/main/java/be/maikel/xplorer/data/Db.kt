package be.maikel.xplorer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import be.maikel.xplorer.domain.Op

@Entity(tableName = "ops")
data class OpEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val t: Long,
    val type: String,
    val cur: String,
    val amount: Double,
    val eur: Double,
    val fee: Double,
    val feeCur: String,
    val unitPrice: Double,
    val priceCur: String,
    val productId: String,
    val orderId: String,
    val title: String,
) {
    fun toOp() = Op(id, t, type, cur, amount, eur, fee, feeCur, if (unitPrice < 0) Double.NaN else unitPrice, priceCur, productId, orderId, title, accountId)
    companion object {
        // SQLite ne stocke pas NaN : on utilise -1 pour « inconnu ».
        private fun safe(v: Double, unknown: Double = 0.0) = if (v.isNaN() || v.isInfinite()) unknown else v
        fun of(o: Op) = OpEntity(o.id, o.accountId, o.t, o.type, o.cur, safe(o.amount), safe(o.eur), safe(o.fee), o.feeCur, safe(o.unitPrice, -1.0), o.priceCur, o.productId, o.orderId, o.title)
    }
}

@Entity(tableName = "balances")
data class BalanceEntity(@PrimaryKey val cur: String, val qty: Double)

@Entity(tableName = "accounts")
data class AccountEntity(@PrimaryKey val id: String, val cur: String, val balance: Double, val synced: Boolean)

@Entity(tableName = "fills")
data class FillEntity(@PrimaryKey val id: String, val t: Long, val productId: String, val buy: Boolean, val price: Double, val size: Double, val commission: Double, val orderId: String)

@Entity(tableName = "daily", primaryKeys = ["sym", "day"])
data class DailyEntity(val sym: String, val day: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

/** Journal des recommandations : une ligne n'est jamais modifiée, sauf l'ajout unique du résultat. */
@Entity(tableName = "journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val t: Long,
    val symbol: String,
    val action: String,
    val score: Int,
    val price: Double,
    val confidence: String,
    val risk: String,
    val horizonDays: Int,
    val invalidation: String,
    val reasons: String,
    val version: String,
    val outcomePrice: Double? = null,
    val outcomeMove: Double? = null,
    val verdict: String? = null,
    val gradedAt: Long? = null,
)

@Entity(tableName = "alerts")
data class AlertEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val t: Long, val kind: String, val title: String, val body: String)

@Entity(tableName = "intents")
data class IntentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val t: Long, val symbol: String, val eur: Double, val matchedOpId: String? = null)

/** Corrections manuelles, avec journal d'audit (rien n'est effacé). */
@Entity(tableName = "corrections")
data class CorrectionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val t: Long, val key: String, val value: String, val previous: String, val note: String)

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface XpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOps(ops: List<OpEntity>)
    @Query("SELECT * FROM ops ORDER BY t") suspend fun ops(): List<OpEntity>
    @Query("SELECT id FROM ops WHERE accountId = :acc") suspend fun opIds(acc: String): List<String>
    @Query("SELECT COUNT(*) FROM ops") suspend fun opCount(): Int

    @Query("DELETE FROM balances") suspend fun clearBalances()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBalances(b: List<BalanceEntity>)
    @Query("SELECT * FROM balances") suspend fun balances(): List<BalanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccounts(a: List<AccountEntity>)
    @Query("SELECT * FROM accounts") suspend fun accounts(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFills(f: List<FillEntity>)
    @Query("SELECT * FROM fills ORDER BY t") suspend fun fills(): List<FillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDaily(d: List<DailyEntity>)
    @Query("SELECT * FROM daily WHERE sym = :sym ORDER BY day") suspend fun daily(sym: String): List<DailyEntity>
    @Query("SELECT MAX(day) FROM daily WHERE sym = :sym") suspend fun lastDay(sym: String): Long?

    @Insert suspend fun addJournal(j: JournalEntity): Long
    @Query("SELECT * FROM journal ORDER BY t DESC") suspend fun journal(): List<JournalEntity>
    @Query("SELECT * FROM journal WHERE symbol = :sym ORDER BY t DESC LIMIT 1") suspend fun lastJournal(sym: String): JournalEntity?
    @Query("UPDATE journal SET outcomePrice = :p, outcomeMove = :m, verdict = :v, gradedAt = :at WHERE id = :id AND gradedAt IS NULL")
    suspend fun grade(id: Long, p: Double, m: Double, v: String, at: Long)

    @Insert suspend fun addAlert(a: AlertEntity): Long
    @Query("SELECT * FROM alerts ORDER BY t DESC LIMIT 200") suspend fun alerts(): List<AlertEntity>
    @Query("SELECT COUNT(*) FROM alerts WHERE t > :since") suspend fun alertsSince(since: Long): Int
    @Query("SELECT COUNT(*) FROM alerts WHERE title = :title AND t > :since") suspend fun sameAlertSince(title: String, since: Long): Int

    @Insert suspend fun addIntent(i: IntentEntity): Long
    @Query("SELECT * FROM intents ORDER BY t DESC") suspend fun intents(): List<IntentEntity>
    @Query("UPDATE intents SET matchedOpId = :op WHERE id = :id") suspend fun matchIntent(id: Long, op: String)

    @Insert suspend fun addCorrection(c: CorrectionEntity): Long
    @Query("SELECT * FROM corrections ORDER BY t DESC") suspend fun corrections(): List<CorrectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(m: MetaEntity)
    @Query("SELECT value FROM meta WHERE `key` = :k") suspend fun meta(k: String): String?

    @Query("DELETE FROM ops") suspend fun wipeOps()
    @Query("DELETE FROM accounts") suspend fun wipeAccounts()
    @Query("DELETE FROM fills") suspend fun wipeFills()
    @Query("DELETE FROM meta") suspend fun wipeMeta()
}

@Database(
    entities = [OpEntity::class, BalanceEntity::class, AccountEntity::class, FillEntity::class, DailyEntity::class,
        JournalEntity::class, AlertEntity::class, IntentEntity::class, CorrectionEntity::class, MetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class XpDb : RoomDatabase() {
    abstract fun dao(): XpDao

    companion object {
        @Volatile private var inst: XpDb? = null
        fun get(ctx: Context): XpDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, XpDb::class.java, "xplorer.db").build().also { inst = it }
        }
    }
}
