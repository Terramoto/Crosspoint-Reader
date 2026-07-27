package org.crosspoint.companion.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", indices = [Index("devicePath")])
data class BookEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val sha256: String,
    val title: String,
    val author: String,
    val sourceFileName: String,
    val size: Long,
    val modifiedAt: Long,
    val exportDirty: Boolean = true,
    val desiredOnDevice: Boolean = false,
    val devicePath: String? = null,
    val installedSha256: String? = null,
    val exportFolderUri: String? = null,
)

@Entity(tableName = "highlights", indices = [Index("bookId"), Index("deviceSequence")])
data class HighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val revision: String,
    val spineHref: String,
    val startBlock: Long,
    val startOffset: Long,
    val endBlock: Long,
    val endOffset: Long,
    val quote: String,
    val page: Long = 0,
    val line: Long = 0,
    val note: String = "",
    val deviceSequence: Long,
    val deleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val pendingReaderDelete: Boolean = false,
    val pendingReaderUpsert: Boolean = false,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE") fun observeBooks(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE LIMIT :limit OFFSET :offset")
    suspend fun booksPage(offset: Int, limit: Int): List<BookEntity>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): BookEntity?
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE") suspend fun allBooks(): List<BookEntity>
    @Query("SELECT * FROM books WHERE (desiredOnDevice = 1 AND (installedSha256 IS NULL OR installedSha256 != sha256)) OR (desiredOnDevice = 0 AND devicePath IS NOT NULL) ORDER BY title COLLATE NOCASE")
    suspend fun pendingDeviceActions(): List<BookEntity>
    @Query("SELECT COUNT(*) FROM books WHERE (desiredOnDevice = 1 AND (installedSha256 IS NULL OR installedSha256 != sha256)) OR (desiredOnDevice = 0 AND devicePath IS NOT NULL)")
    fun observePendingDeviceActionCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE (desiredOnDevice = 1 AND (installedSha256 IS NULL OR installedSha256 != sha256)) OR (desiredOnDevice = 0 AND devicePath IS NOT NULL)")
    suspend fun pendingDeviceActionCount(): Int
    @Query("SELECT * FROM books WHERE devicePath = :path LIMIT 1") suspend fun bookByDevicePath(path: String): BookEntity?
    @Query("SELECT * FROM books WHERE sha256 = :sha256 LIMIT 1") suspend fun bookBySha256(sha256: String): BookEntity?
    @Query("SELECT * FROM books WHERE sourceFileName = :filename LIMIT 1") suspend fun bookByFilename(filename: String): BookEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBook(book: BookEntity)
    @Delete suspend fun deleteBook(book: BookEntity)
    @Query("DELETE FROM highlights WHERE bookId = :bookId") suspend fun deleteHighlightsForBook(bookId: String)
    @Transaction
    suspend fun deleteBookRecord(book: BookEntity) {
        deleteHighlightsForBook(book.id)
        deleteBook(book)
    }
    @Query("UPDATE books SET desiredOnDevice = :desired WHERE id = :id")
    suspend fun setDesiredOnDevice(id: String, desired: Boolean)
    @Query("UPDATE books SET devicePath = :path, installedSha256 = :sha256, desiredOnDevice = 1 WHERE id = :id")
    suspend fun markBookInstalled(id: String, path: String, sha256: String)
    @Query("UPDATE highlights SET pendingReaderUpsert = 1 WHERE bookId = :bookId AND deleted = 0")
    suspend fun markHighlightsForReader(bookId: String)
    @Transaction
    suspend fun markInstalled(id: String, path: String, sha256: String) {
        markBookInstalled(id, path, sha256)
        markHighlightsForReader(id)
    }
    @Query("UPDATE books SET devicePath = NULL, installedSha256 = NULL, desiredOnDevice = 0 WHERE id = :id")
    suspend fun markRemoved(id: String)
    @Query("UPDATE books SET devicePath = :path WHERE id = :id")
    suspend fun associateDevicePath(id: String, path: String)
    @Query("UPDATE books SET devicePath = :path, installedSha256 = :sha256, desiredOnDevice = 1 WHERE id = :id")
    suspend fun associateReaderBook(id: String, path: String, sha256: String)
    @Query("UPDATE books SET installedSha256 = NULL WHERE id = :id")
    suspend fun markDeviceMissing(id: String)
    @Query("UPDATE books SET exportFolderUri = :folderUri, exportDirty = 1 WHERE id = :id")
    suspend fun setExportFolder(id: String, folderUri: String)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND deleted = 0 ORDER BY deviceSequence")
    fun observeHighlights(bookId: String): Flow<List<HighlightEntity>>
    @Query("SELECT * FROM highlights WHERE deviceSequence > :sequence ORDER BY deviceSequence")
    suspend fun highlightChanges(sequence: Long): List<HighlightEntity>
    @Query("SELECT * FROM highlights WHERE id = :id") suspend fun highlight(id: String): HighlightEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertHighlight(highlight: HighlightEntity)
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY deviceSequence")
    suspend fun allHighlights(bookId: String): List<HighlightEntity>
    @Query("SELECT * FROM highlights WHERE pendingReaderDelete = 1 ORDER BY deletedAt")
    suspend fun pendingDeletes(): List<HighlightEntity>
    @Query("SELECT * FROM highlights WHERE pendingReaderUpsert = 1 AND deleted = 0 ORDER BY updatedAt")
    suspend fun pendingUpserts(): List<HighlightEntity>
    @Query("SELECT COUNT(*) FROM highlights WHERE pendingReaderDelete = 1")
    fun observePendingDeleteCount(): Flow<Int>
    @Query("UPDATE highlights SET pendingReaderDelete = 0 WHERE id = :id")
    suspend fun acknowledgeDelete(id: String)
    @Query("UPDATE highlights SET pendingReaderUpsert = 0 WHERE id = :id")
    suspend fun acknowledgeUpsert(id: String)
    @Query("UPDATE books SET exportDirty = 1 WHERE id = :bookId") suspend fun markExportDirty(bookId: String)
    @Query("UPDATE books SET exportDirty = 0 WHERE id = :bookId") suspend fun clearExportDirty(bookId: String)
}

@Database(entities = [BookEntity::class, HighlightEntity::class], version = 5, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceFileName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN exportDirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE highlights ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE highlights ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE highlights ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE highlights ADD COLUMN pendingReaderDelete INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN desiredOnDevice INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN devicePath TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN installedSha256 TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_devicePath ON books(devicePath)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN exportFolderUri TEXT")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE highlights ADD COLUMN page INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE highlights ADD COLUMN line INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE highlights ADD COLUMN pendingReaderUpsert INTEGER NOT NULL DEFAULT 0")
                // Existing installations may already have phone-side highlights
                // that never had a reader-bound protocol path. Replay them once.
                db.execSQL("UPDATE highlights SET pendingReaderUpsert = 1 WHERE deleted = 0")
            }
        }
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "crosspoint.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
        }
    }
}
