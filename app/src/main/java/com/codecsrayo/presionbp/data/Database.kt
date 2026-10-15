package com.codecsrayo.presionbp.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Una medición = (aparato, nº de secuencia, hora): el nº puede reiniciarse si se borra la memoria del tensiómetro. */
@Entity(tableName = "readings", indices = [Index(value = ["deviceAddress", "sequence", "measuredAtEpoch"], unique = true)])
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val sequence: Int,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    /** Hora del tensiómetro (local) en segundos epoch. */
    val measuredAtEpoch: Long,
    /** El reloj del tensiómetro no estaba en hora: la fecha no es fiable. */
    val clockUnset: Boolean,
    val irregularHeartbeat: Boolean,
    val bodyMovement: Boolean,
    val rawHex: String,
    val importedAtEpoch: Long,
    val inHealthConnect: Boolean = false,
    /** Eliminada por el usuario: se conserva la fila para que no se reimporte del tensiómetro. */
    val deleted: Boolean = false,
) {
    val measuredAt: LocalDateTime
        get() = LocalDateTime.ofInstant(Instant.ofEpochSecond(measuredAtEpoch), ZoneId.systemDefault())
}

@Dao
interface ReadingDao {
    @Query("SELECT * FROM readings WHERE deleted = 0 ORDER BY measuredAtEpoch DESC, sequence DESC")
    fun observeAll(): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE deleted = 0 ORDER BY measuredAtEpoch DESC, sequence DESC")
    suspend fun all(): List<Reading>

    @Query("UPDATE readings SET deleted = 1 WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE readings SET deleted = 1")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(readings: List<Reading>): List<Long>

    @Query("SELECT * FROM readings WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Reading>

    @Query("SELECT * FROM readings WHERE inHealthConnect = 0 AND clockUnset = 0 AND deleted = 0")
    suspend fun pendingHealthConnect(): List<Reading>

    @Query("UPDATE readings SET inHealthConnect = 1 WHERE id IN (:ids)")
    suspend fun markInHealthConnect(ids: List<Long>)
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE readings ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_readings_deviceAddress_sequence")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_readings_deviceAddress_sequence_measuredAtEpoch " +
                "ON readings (deviceAddress, sequence, measuredAtEpoch)",
        )
    }
}

@Database(entities = [Reading::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "presion.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
