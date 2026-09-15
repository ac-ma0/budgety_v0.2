package com.budgety.myapp

import android.content.Context
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Small REST client so the app does not need to expose a service-role key. */
class SupabaseClient(context: Context) {
    companion object {
        private const val URL = "https://dzncegmxbjhzhjjrtcyt.supabase.co"
        private const val KEY = "sb_publishable_Sr51Kf51AEd1reCpwliZPw_9zuo258M"
        private val JSON = MediaType.parse("application/json; charset=utf-8")
    }

    private val preferences = context.getSharedPreferences("supabase", Context.MODE_PRIVATE)
    private val http = OkHttpClient()

    val isSignedIn: Boolean get() = preferences.getString("access_token", null) != null
    val userId: String? get() = preferences.getString("user_id", null)
    val email: String? get() = preferences.getString("email", null)

    fun signIn(email: String, password: String): Boolean {
        return authenticate("token?grant_type=password", email, password)
    }

    fun signUp(email: String, password: String): Boolean {
        return authenticate("signup", email, password)
    }

    fun resendSignupEmail(email: String) {
        request(
            "POST",
            "/auth/v1/resend",
            JSONObject().put("type", "signup").put("email", email).toString(),
            false
        )
    }

    fun signOut() {
        preferences.edit().remove("access_token").remove("refresh_token")
            .remove("user_id").remove("email").apply()
    }

    fun updateEmail(email: String) {
        val user = JSONObject(request("PUT", "/auth/v1/user", JSONObject().put("email", email).toString(), true))
        preferences.edit().putString("email", user.optString("email", email)).apply()
    }

    fun currentUserEmail(): String {
        val user = JSONObject(request("GET", "/auth/v1/user", null, true))
        val value = user.optString("email")
        if (value.isNotBlank()) preferences.edit().putString("email", value).apply()
        return value
    }

    fun updatePassword(password: String) {
        request("PUT", "/auth/v1/user", JSONObject().put("password", password).toString(), true)
    }

    private fun authenticate(path: String, email: String, password: String): Boolean {
        val body = JSONObject().put("email", email).put("password", password)
        val result = request("POST", "/auth/v1/$path", body.toString(), false)
        val json = JSONObject(result)
        val token = json.optString("access_token")
        if (token.isEmpty()) {
            if (path == "signup" && json.optJSONObject("user") != null) {
                preferences.edit().putString("email", email).apply()
                return false
            }
            throw IOException(json.optString("msg", json.optString("message", "Authentication failed")))
        }
        preferences.edit().putString("access_token", token)
            .putString("refresh_token", json.optString("refresh_token",
                preferences.getString("refresh_token", "")))
            .putString("user_id", json.optJSONObject("user")?.optString("id", "") ?: "")
            .putString("email", json.optJSONObject("user")?.optString("email", email) ?: email)
            .apply()
        return true
    }

    fun upsertRecords(records: JSONArray) {
        request("POST", "/rest/v1/budget_sync_records?on_conflict=user_id,record_key",
            records.toString(), true, "resolution=merge-duplicates,return=minimal")
    }

    fun getRecords(userId: String): JSONArray =
        JSONArray(request("GET", "/rest/v1/budget_sync_records?user_id=eq.$userId&select=*", null, true))

    fun upsertAuditLogs(records: JSONArray) {
        request("POST", "/rest/v1/audit_logs?on_conflict=user_id,log_key",
            records.toString(), true, "resolution=merge-duplicates,return=minimal")
    }

    fun getAuditLogs(userId: String): JSONArray =
        JSONArray(request("GET", "/rest/v1/audit_logs?user_id=eq.$userId&select=*", null, true))

    private fun request(method: String, path: String, content: String?, auth: Boolean,
                        prefer: String? = null, retryOnExpired: Boolean = true): String {
        val builder = Request.Builder().url(URL + path)
            .header("apikey", KEY).header("Accept", "application/json")
        if (auth) builder.header("Authorization", "Bearer " + preferences.getString("access_token", ""))
        if (prefer != null) builder.header("Prefer", prefer)
        if (content != null) builder.method(method, RequestBody.create(JSON, content))
        else builder.method(method, null)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body()?.string() ?: ""
            if (response.code() == 401 && auth && retryOnExpired && refreshAccessToken()) {
                return request(method, path, content, auth, prefer, false)
            }
            if (!response.isSuccessful) {
                val message = try {
                    val error = JSONObject(text)
                    listOf(
                        error.optString("message"),
                        error.optString("error_description"),
                        error.optString("error"),
                        error.optString("hint"),
                        error.optString("msg")
                    ).firstOrNull { it.isNotBlank() } ?: text
                }
                catch (e: org.json.JSONException) { text }
                throw IOException("Supabase request failed (${response.code()}): $message")
            }
            return text
        }
    }

    private fun refreshAccessToken(): Boolean {
        val refreshToken = preferences.getString("refresh_token", null)
            ?: return false
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        val request = Request.Builder()
            .url(URL + "/auth/v1/token?grant_type=refresh_token")
            .header("apikey", KEY)
            .header("Accept", "application/json")
            .post(RequestBody.create(JSON, body))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body()?.string() ?: ""
            if (!response.isSuccessful) return false
            val json = JSONObject(text)
            val token = json.optString("access_token")
            if (token.isBlank()) return false
            preferences.edit().putString("access_token", token)
                .putString("refresh_token", json.optString("refresh_token", refreshToken))
                .putString("user_id", json.optJSONObject("user")?.optString("id",
                    preferences.getString("user_id", "")) ?: preferences.getString("user_id", ""))
                .apply()
            return true
        }
    }
}
