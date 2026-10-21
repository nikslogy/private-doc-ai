package com.privateassistant.platform.vault

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** The production open policy is shared with the actual SQLCipher regression probes. */
internal object VaultDatabaseSecurity : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA cipher_memory_security = ON")
        // SQLCipher returns a row for this setter. execSQL rejects SQLITE_ROW.
        db.query("PRAGMA secure_delete = ON").use { result ->
            check(result.moveToFirst() && result.getInt(0) == 1 && !result.moveToNext()) {
                "Encrypted database secure deletion could not be enabled"
            }
        }
        db.execSQL("PRAGMA temp_store = MEMORY")
        db.execSQL("PRAGMA synchronous = FULL")
    }
}
