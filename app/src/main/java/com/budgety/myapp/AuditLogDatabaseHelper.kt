package com.budgety.myapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AuditLogDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "budgety_audit_logs.db"
        private const val DATABASE_VERSION = 3

        private const val TABLE_AUDIT_LOGS = "audit_logs"
        private const val COLUMN_ID = "id"
        private const val COLUMN_USER_ID = "user_id"
        private const val COLUMN_ACTION = "action"
        private const val COLUMN_DETAILS = "details"
        private const val COLUMN_DATE = "date"
        private const val COLUMN_SYNC_KEY = "sync_key"
        private const val COLUMN_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_AUDIT_LOGS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_USER_ID INTEGER NOT NULL, " +
                "$COLUMN_ACTION TEXT NOT NULL, " +
                "$COLUMN_DETAILS TEXT NOT NULL, " +
                "$COLUMN_DATE TEXT NOT NULL, " +
                "$COLUMN_SYNC_KEY TEXT UNIQUE, " +
                "$COLUMN_UPDATED_AT INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_AUDIT_LOGS ADD COLUMN $COLUMN_SYNC_KEY TEXT")
            db.execSQL("ALTER TABLE $TABLE_AUDIT_LOGS ADD COLUMN $COLUMN_UPDATED_AT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE $TABLE_AUDIT_LOGS SET $COLUMN_SYNC_KEY='legacy-audit-' || $COLUMN_ID WHERE $COLUMN_SYNC_KEY IS NULL")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS audit_logs_sync_key ON $TABLE_AUDIT_LOGS($COLUMN_SYNC_KEY)")
        }
        if (oldVersion < 3) {
            // Older builds could leave null/zero ids behind.  They are repaired by
            // DatabaseHelper once the users database has been opened.
            db.execSQL("UPDATE $TABLE_AUDIT_LOGS SET $COLUMN_USER_ID=0 WHERE $COLUMN_USER_ID IS NULL")
        }
    }

    fun repairUserIds(validUserIds: Set<Int>, fallbackUserId: Int) {
        if (fallbackUserId <= 0) return
        val db = writableDatabase
        val valid = validUserIds.filter { it > 0 }
        if (valid.isEmpty()) return
        val placeholders = valid.joinToString(",") { "?" }
        val args = (listOf(fallbackUserId.toString()) + valid.map { it.toString() }).toTypedArray()
        db.execSQL("UPDATE $TABLE_AUDIT_LOGS SET $COLUMN_USER_ID=? WHERE $COLUMN_USER_ID IS NULL OR $COLUMN_USER_ID<=0 OR $COLUMN_USER_ID NOT IN ($placeholders)", args)
    }

    fun addAuditLog(userId: Int, action: String, details: String) {
        val values = ContentValues().apply {
            put(COLUMN_USER_ID, userId)
            put(COLUMN_ACTION, action)
            put(COLUMN_DETAILS, details)
            put(
                COLUMN_DATE,
                SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(Date())
            )
            put(COLUMN_SYNC_KEY, "audit-" + UUID.randomUUID().toString())
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow(TABLE_AUDIT_LOGS, null, values)
    }

    fun getAuditLogsByUser(userId: Int, filterType: String = "ALL"): List<AuditLog> {
        val logs = ArrayList<AuditLog>()
        val db = readableDatabase
        val selection = if (filterType == "ALL") {
            "$COLUMN_USER_ID=?"
        } else {
            "$COLUMN_USER_ID=? AND $COLUMN_ACTION LIKE ?"
        }
        val selectionArgs = if (filterType == "ALL") {
            arrayOf(userId.toString())
        } else {
            arrayOf(userId.toString(), "$filterType %")
        }
        val cursor = db.query(
            TABLE_AUDIT_LOGS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COLUMN_ID DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                logs.add(
                    AuditLog(
                        id = it.getInt(it.getColumnIndexOrThrow(COLUMN_ID)),
                        userId = it.getInt(it.getColumnIndexOrThrow(COLUMN_USER_ID)),
                        action = it.getString(it.getColumnIndexOrThrow(COLUMN_ACTION)),
                        details = it.getString(it.getColumnIndexOrThrow(COLUMN_DETAILS)),
                        date = it.getString(it.getColumnIndexOrThrow(COLUMN_DATE))
                    )
                )
            }
        }
        return logs
    }

    fun exportSyncRecords(): JSONArray {
        val result = JSONArray()
        readableDatabase.query(TABLE_AUDIT_LOGS, null, null, null, null, null, COLUMN_ID).use {
            while (it.moveToNext()) {
                val key = it.getString(it.getColumnIndexOrThrow(COLUMN_SYNC_KEY))
                result.put(JSONObject()
                    .put("record_key", key)
                    .put("record_type", "audit_log")
                    .put("payload", JSONObject()
                        .put("id", it.getInt(it.getColumnIndexOrThrow(COLUMN_ID)))
                                .put("user_id", it.getInt(it.getColumnIndexOrThrow(COLUMN_USER_ID)))
                        .put("action", it.getString(it.getColumnIndexOrThrow(COLUMN_ACTION)))
                        .put("details", it.getString(it.getColumnIndexOrThrow(COLUMN_DETAILS)))
                        .put("date", it.getString(it.getColumnIndexOrThrow(COLUMN_DATE)))
                        .put("sync_key", key))
                    .put("updated_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(Date(it.getLong(it.getColumnIndexOrThrow(COLUMN_UPDATED_AT)))))
                    .put("deleted", false))
            }
        }
        return result
    }

    fun applyRemoteRecord(key: String, payload: JSONObject, updatedAt: Long, deleted: Boolean): Boolean {
        val db = writableDatabase
        if (deleted) {
            val removed = db.delete(TABLE_AUDIT_LOGS, "$COLUMN_SYNC_KEY=?", arrayOf(key)) > 0
            return removed
        }
        val values = ContentValues().apply {
            put(COLUMN_USER_ID, payload.optInt("local_user_id", payload.optInt("user_id")))
            put(COLUMN_ACTION, payload.optString("action"))
            put(COLUMN_DETAILS, payload.optString("details"))
            put(COLUMN_DATE, payload.optString("date"))
            put(COLUMN_SYNC_KEY, key)
            put(COLUMN_UPDATED_AT, updatedAt)
        }
        val existing = db.query(TABLE_AUDIT_LOGS, arrayOf(COLUMN_ID), "$COLUMN_SYNC_KEY=?",
            arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }
        if (values.getAsInteger(COLUMN_USER_ID) == null || values.getAsInteger(COLUMN_USER_ID) <= 0) return false
        return if (existing == -1) db.insert(TABLE_AUDIT_LOGS, null, values) != -1L
        else db.update(TABLE_AUDIT_LOGS, values, "$COLUMN_SYNC_KEY=?", arrayOf(key)) > 0
    }

    fun deleteLogsByUser(userId: Int): List<String> {
        val keys = ArrayList<String>()
        writableDatabase.query(TABLE_AUDIT_LOGS, arrayOf(COLUMN_SYNC_KEY),
            "$COLUMN_USER_ID=?", arrayOf(userId.toString()), null, null, null).use {
            while (it.moveToNext()) keys.add(it.getString(0))
        }
        writableDatabase.delete(TABLE_AUDIT_LOGS, "$COLUMN_USER_ID=?", arrayOf(userId.toString()))
        return keys
    }
}
