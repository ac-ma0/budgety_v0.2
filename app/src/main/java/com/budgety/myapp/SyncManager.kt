package com.budgety.myapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class SyncManager(context: Context, private val db: DatabaseHelper) {
    private val client = SupabaseClient(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun isSignedIn(): Boolean = client.isSignedIn
    val signedInEmail: String? get() = client.email
    fun currentEmail(callback: (Result<String>) -> Unit) = executor.execute {
        val result = try { Result.success(client.currentUserEmail()) }
        catch (e: Exception) { Result.failure<String>(e) }
        main.post { callback(result) }
    }

    fun authenticate(email: String, password: String, register: Boolean,
                     callback: (Result<Unit>) -> Unit) {
        executor.execute {
            val result = try {
                if (register) client.signUp(email, password) else client.signIn(email, password)
                Result.success(Unit)
            } catch (e: java.io.IOException) {
                Result.failure<Unit>(e)
            } catch (e: org.json.JSONException) {
                Result.failure<Unit>(e)
            }
            main.post { callback(result) }
        }
    }

    fun updateEmail(email: String, callback: (Result<Unit>) -> Unit) =
        runAuthAction({ client.updateEmail(email) }, callback)

    fun updatePassword(password: String, callback: (Result<Unit>) -> Unit) =
        runAuthAction({ client.updatePassword(password) }, callback)

    private fun runAuthAction(action: () -> Unit, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            val result = try {
                action()
                Result.success(Unit)
            } catch (e: java.io.IOException) {
                Result.failure<Unit>(e)
            } catch (e: org.json.JSONException) {
                Result.failure<Unit>(e)
            }
            main.post { callback(result) }
        }
    }

    fun sync(userId: Int = -1, callback: (Result<Unit>) -> Unit = {}) {
        if (!client.isSignedIn) return
        executor.execute {
            val result = try {
                val remoteUserId = currentRemoteUserId()
                // Always download first. Uploading an empty/new local database
                // before this point can turn the device defaults into the cloud
                // copy and permanently hide the user's existing budget.
                val remote = client.getRecords(currentRemoteUserId())
                val remoteAudits = client.getAuditLogs(currentRemoteUserId())
                val userIds = HashMap<Int, Int>()
                val users = ArrayList<JSONObject>()
                val other = ArrayList<JSONObject>()
                for (i in 0 until remote.length()) {
                    val row = remote.getJSONObject(i)
                    if (row.optString("record_type") == "user") users.add(row) else other.add(row)
                }
                // User ids are local SQLite ids, so build a mapping before
                // importing transactions/debts from another device.
                for (row in users) {
                    val payload = row.optJSONObject("payload") ?: continue
                    val remoteName = payload.optString("name")
                    if (!row.optBoolean("deleted", false) && db.hasDeletedUserTombstone(remoteName)) {
                        // A local deletion is authoritative until its newer
                        // tombstone is accepted by the cloud. Do not recreate
                        // this user while performing the download-first sync.
                        continue
                    }
                    val cloudId = payload.optInt("id", -1)
                    if (cloudId >= 0) {
                        userIds[cloudId] = if (row.optBoolean("deleted", false)) {
                            db.findLocalUserId(payload.optString("name"))
                        } else {
                            db.importRemoteUser(payload)
                        }
                    }
                    apply(row, payload)
                }
                for (row in other) {
                    val original = row.optJSONObject("payload") ?: continue
                    val payload = JSONObject(original.toString())
                    val cloudLocalId = payload.optInt("user_id", -1)
                    // Records without a known user belong to the currently
                    // selected local account rather than an invalid cloud id.
                    val localUserId = userIds[cloudLocalId]?.takeIf { it > 0 }
                    if (localUserId == null) continue
                    payload.put("local_user_id", localUserId)
                    apply(row, payload)
                }
                for (i in 0 until remoteAudits.length()) {
                    val row = remoteAudits.getJSONObject(i)
                    val cloudUserId = row.optInt("budget_user_id", -1)
                    val localUserId = userIds[cloudUserId]?.takeIf { it > 0 } ?: continue
                    val payload = JSONObject().put("user_id", localUserId)
                        .put("local_user_id", localUserId)
                        .put("action", row.optString("action"))
                        .put("details", row.optString("details"))
                        .put("date", row.optString("log_date"))
                        .put("sync_key", row.optString("log_key"))
                    db.applyRemoteRecord(row.optString("log_key"), "audit_log", payload,
                        parseTimestamp(row.optString("updated_at")), row.optBoolean("deleted", false))
                }

                // Only now publish local changes. Supabase and the local
                // metadata table both enforce latest-update-wins, so an older
                // local record cannot replace a newer downloaded record.
                val local = if (userId > 0) db.exportSyncRecords(userId) else JSONArray()
                val recordRows = ArrayList<JSONObject>()
                for (i in 0 until local.length()) {
                    val row = local.getJSONObject(i)
                    row.put("user_id", remoteUserId)
                    recordRows.add(row)
                }
                val records = deduplicateRows(recordRows, "record_key")
                val audits = db.exportAuditSyncRecords()
                val auditRowsList = ArrayList<JSONObject>()
                for (i in 0 until audits.length()) {
                    val row = audits.getJSONObject(i)
                    val payload = row.optJSONObject("payload")
                        ?: JSONObject().put("user_id", userId)
                    val exportedUserId = payload?.optInt("user_id", 0) ?: 0
                    val budgetUserId = exportedUserId.takeIf { it > 0 } ?: userId
                    auditRowsList.add(JSONObject().put("user_id", remoteUserId)
                        .put("log_key", row.optString("record_key"))
                        .put("budget_user_id", budgetUserId)
                        .put("action", payload.optString("action"))
                        .put("details", payload.optString("details"))
                        .put("log_date", payload.optString("date"))
                        .put("updated_at", row.optString("updated_at"))
                        .put("deleted", row.optBoolean("deleted", false)))
                }
                val auditRows = deduplicateRows(auditRowsList, "log_key")
                if (records.length() > 0) {
                    client.upsertRecords(records)
                    val uploadedKeys = HashSet<String>()
                    val verified = client.getRecords(remoteUserId)
                    for (i in 0 until verified.length()) {
                        uploadedKeys.add(verified.getJSONObject(i).optString("record_key"))
                    }
                    for (i in 0 until records.length()) {
                        val key = records.getJSONObject(i).optString("record_key")
                        if (!uploadedKeys.contains(key)) {
                            throw java.io.IOException("Supabase did not store record: $key")
                        }
                    }
                }
                if (auditRows.length() > 0) {
                    client.upsertAuditLogs(auditRows)
                    val uploadedAuditKeys = HashSet<String>()
                    val verifiedAudits = client.getAuditLogs(remoteUserId)
                    for (i in 0 until verifiedAudits.length()) {
                        uploadedAuditKeys.add(verifiedAudits.getJSONObject(i).optString("log_key"))
                    }
                    for (i in 0 until auditRows.length()) {
                        val key = auditRows.getJSONObject(i).optString("log_key")
                        if (!uploadedAuditKeys.contains(key)) {
                            throw java.io.IOException("Supabase did not store audit log: $key")
                        }
                    }
                }

                Result.success(Unit)
            } catch (e: java.io.IOException) {
                Result.failure<Unit>(e)
            } catch (e: org.json.JSONException) {
                Result.failure<Unit>(e)
            }
            main.post { callback(result) }
        }
    }

    private fun apply(row: JSONObject, payload: JSONObject) {
        val timestamp = parseTimestamp(row.optString("updated_at"))
        db.applyRemoteRecord(row.optString("record_key"), row.optString("record_type"),
            payload, timestamp, row.optBoolean("deleted", false))
    }

    private fun deduplicateRows(rows: List<JSONObject>, keyField: String): JSONArray {
        val unique = LinkedHashMap<String, JSONObject>()
        rows.forEach { row ->
            val key = row.optString(keyField)
            if (key.isBlank()) return@forEach
            val previous = unique[key]
            if (previous == null ||
                row.optString("updated_at") >= previous.optString("updated_at")) {
                unique[key] = row
            }
        }
        val result = JSONArray()
        unique.values.forEach { result.put(it) }
        return result
    }

    private fun currentRemoteUserId(): String {
        // SupabaseClient intentionally keeps credentials private; the REST endpoint
        // accepts the JWT and derives this value, so a stable key is unnecessary here.
        return client.userId ?: throw java.io.IOException("Supabase user session is missing")
    }

    private fun parseTimestamp(value: String): Long {
        if (value.isBlank()) return 0L
        val normalized = value
            .replace(Regex("""(\.\d{3})\d+"""), "$1")
            .replace("+00:00", "Z")
        val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (format in formats) {
            try {
                val parsed = SimpleDateFormat(format, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(normalized)
                if (parsed != null) return parsed.time
            } catch (_: java.text.ParseException) {
                // Try the next Supabase timestamp representation.
            }
        }
        return 0L
    }
}
