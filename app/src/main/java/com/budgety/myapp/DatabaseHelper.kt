package com.budgety.myapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    private val auditLogDatabase = AuditLogDatabaseHelper(context.applicationContext)

    init {
        // Repair audit rows created by older versions before they can be exported.
        writableDatabase
        val users = allUsers
        users.firstOrNull()?.let { auditLogDatabase.repairUserIds(users.map { u -> u.id }.toSet(), it.id) }
    }

    companion object {
        private const val DATABASE_NAME = "budgety_v3.db"
        private const val DATABASE_VERSION = 4

        private const val TABLE_USERS = "users"
        private const val COLUMN_USER_ID = "id"
        private const val COLUMN_USER_NAME = "name"

        private const val TABLE_EXPENSES = "expenses"
        private const val COLUMN_EXPENSE_ID = "id"
        private const val COLUMN_EXPENSE_USER_ID = "user_id"
        private const val COLUMN_EXPENSE_TITLE = "title"
        private const val COLUMN_EXPENSE_AMOUNT = "amount"
        private const val COLUMN_EXPENSE_DATETIME = "datetime"
        private const val COLUMN_EXPENSE_CATEGORY = "category"

        private const val TABLE_INCOMES = "incomes"
        private const val COLUMN_INCOME_ID = "id"
        private const val COLUMN_INCOME_USER_ID = "user_id"
        private const val COLUMN_INCOME_TITLE = "title"
        private const val COLUMN_INCOME_AMOUNT = "amount"
        private const val COLUMN_INCOME_DATETIME = "datetime"
        private const val COLUMN_INCOME_CATEGORY = "category"

        private const val TABLE_DEBTS = "debts"
        private const val COLUMN_DEBT_ID = "id"
        private const val COLUMN_DEBT_USER_ID = "user_id"
        private const val COLUMN_DEBT_NAME = "name"
        private const val COLUMN_DEBT_AMOUNT = "amount"
        private const val COLUMN_DEBT_DUE_DATE = "due_date"
        private const val COLUMN_DEBT_PAID = "paid"

    }

    override fun onCreate(db: SQLiteDatabase) {
        val createUsers = ("CREATE TABLE $TABLE_USERS ("
                + "$COLUMN_USER_ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "$COLUMN_USER_NAME TEXT UNIQUE)")

        val createExpenses = ("CREATE TABLE $TABLE_EXPENSES ("
                + "$COLUMN_EXPENSE_ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "$COLUMN_EXPENSE_USER_ID INTEGER, "
                + "$COLUMN_EXPENSE_TITLE TEXT, "
                + "$COLUMN_EXPENSE_AMOUNT REAL, "
                + "$COLUMN_EXPENSE_DATETIME TEXT, "
                + "$COLUMN_EXPENSE_CATEGORY TEXT NOT NULL DEFAULT 'Other')")

        val createIncomes = ("CREATE TABLE $TABLE_INCOMES ("
                + "$COLUMN_INCOME_ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "$COLUMN_INCOME_USER_ID INTEGER, "
                + "$COLUMN_INCOME_TITLE TEXT, "
                + "$COLUMN_INCOME_AMOUNT REAL, "
                + "$COLUMN_INCOME_DATETIME TEXT, "
                + "$COLUMN_INCOME_CATEGORY TEXT NOT NULL DEFAULT 'Other')")

        val createDebts = ("CREATE TABLE $TABLE_DEBTS ("
                + "$COLUMN_DEBT_ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "$COLUMN_DEBT_USER_ID INTEGER NOT NULL, "
                + "$COLUMN_DEBT_NAME TEXT NOT NULL, "
                + "$COLUMN_DEBT_AMOUNT REAL NOT NULL, "
                + "$COLUMN_DEBT_DUE_DATE TEXT, "
                + "$COLUMN_DEBT_PAID INTEGER NOT NULL DEFAULT 0)")

        db.execSQL(createUsers)
        db.execSQL(createExpenses)
        db.execSQL(createIncomes)
        db.execSQL(createDebts)
        db.execSQL("CREATE TABLE sync_metadata (record_key TEXT PRIMARY KEY, record_type TEXT NOT NULL, payload TEXT NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")

        val values = ContentValues().apply { put(COLUMN_USER_NAME, "Main Account") }
        db.insert(TABLE_USERS, null, values)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations are additive so existing accounts and transactions are never lost.
        if (oldVersion < 3) {
            if (!hasColumn(db, TABLE_EXPENSES, COLUMN_EXPENSE_CATEGORY)) {
                db.execSQL("ALTER TABLE $TABLE_EXPENSES ADD COLUMN $COLUMN_EXPENSE_CATEGORY TEXT NOT NULL DEFAULT 'Other'")
            }
            if (!hasColumn(db, TABLE_INCOMES, COLUMN_INCOME_CATEGORY)) {
                db.execSQL("ALTER TABLE $TABLE_INCOMES ADD COLUMN $COLUMN_INCOME_CATEGORY TEXT NOT NULL DEFAULT 'Other'")
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_DEBTS ("
                    + "$COLUMN_DEBT_ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "$COLUMN_DEBT_USER_ID INTEGER NOT NULL, "
                    + "$COLUMN_DEBT_NAME TEXT NOT NULL, "
                    + "$COLUMN_DEBT_AMOUNT REAL NOT NULL, "
                    + "$COLUMN_DEBT_DUE_DATE TEXT, "
                    + "$COLUMN_DEBT_PAID INTEGER NOT NULL DEFAULT 0)")
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_metadata (record_key TEXT PRIMARY KEY, record_type TEXT NOT NULL, payload TEXT NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        val cursor = db.rawQuery("PRAGMA table_info($table)", null)
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                if (nameIndex >= 0 && it.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    // ==========================================
    //               USER METHODS
    // ==========================================
    fun addUser(name: String): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply { put(COLUMN_USER_NAME, name) }
        return db.insert(TABLE_USERS, null, values) != -1L
    }

    fun deleteUser(userId: Int): Boolean {
        val db = writableDatabase
        if (allUsers.size <= 1) return false
        val user = db.query(TABLE_USERS, arrayOf(COLUMN_USER_NAME), "$COLUMN_USER_ID=?",
            arrayOf(userId.toString()), null, null, null).use {
            if (it.moveToFirst()) it.getString(0) else return false
        }
        db.beginTransaction()
        try {
            val records = ArrayList<Pair<String, String>>()
            db.query(TABLE_EXPENSES, arrayOf(COLUMN_EXPENSE_ID), "$COLUMN_EXPENSE_USER_ID=?",
                arrayOf(userId.toString()), null, null, null).use { while (it.moveToNext()) records.add("expense-${it.getInt(0)}" to "expense") }
            db.query(TABLE_INCOMES, arrayOf(COLUMN_INCOME_ID), "$COLUMN_INCOME_USER_ID=?",
                arrayOf(userId.toString()), null, null, null).use { while (it.moveToNext()) records.add("income-${it.getInt(0)}" to "income") }
            db.query(TABLE_DEBTS, arrayOf(COLUMN_DEBT_ID), "$COLUMN_DEBT_USER_ID=?",
                arrayOf(userId.toString()), null, null, null).use { while (it.moveToNext()) records.add("debt-${it.getInt(0)}" to "debt") }
            db.delete(TABLE_EXPENSES, "$COLUMN_EXPENSE_USER_ID=?", arrayOf(userId.toString()))
            db.delete(TABLE_INCOMES, "$COLUMN_INCOME_USER_ID=?", arrayOf(userId.toString()))
            db.delete(TABLE_DEBTS, "$COLUMN_DEBT_USER_ID=?", arrayOf(userId.toString()))
            auditLogDatabase.deleteLogsByUser(userId).forEach { records.add(it to "audit_log") }
            db.delete(TABLE_USERS, "$COLUMN_USER_ID=?", arrayOf(userId.toString()))
            val timestamp = System.currentTimeMillis()
            records.add("user-$userId" to "user")
            records.forEach { (key, type) ->
                val payload = JSONObject().put("id", key.substringAfterLast("-").toIntOrNull() ?: -1)
                    .put("user_id", userId).put("name", user)
                db.execSQL("INSERT OR REPLACE INTO sync_metadata(record_key,record_type,payload,updated_at,deleted) VALUES(?,?,?,?,1)",
                    arrayOf(key, type, payload.toString(), timestamp))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    // BAGO: Rename User Function
    fun updateUserName(id: Int, newName: String): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_USER_NAME, newName)
        }
        return db.update(TABLE_USERS, values, "$COLUMN_USER_ID=?", arrayOf(id.toString())) > 0
    }

    val allUsers: List<User>
        get() {
            val list = ArrayList<User>()
            val db = this.readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_USERS ORDER BY $COLUMN_USER_ID ASC", null)
            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_USER_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_USER_NAME))
                    list.add(User(id, name))
                } while (cursor.moveToNext())
            }
            cursor.close()
            return list
        }

    // ==========================================
    //             EXPENSE METHODS
    // ==========================================
    fun addExpense(userId: Int, title: String, amount: Double, dateTime: String,
                   category: String = ExpenseCategory.OTHER.label): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_EXPENSE_USER_ID, userId)
            put(COLUMN_EXPENSE_TITLE, title)
            put(COLUMN_EXPENSE_AMOUNT, amount)
            put(COLUMN_EXPENSE_DATETIME, dateTime)
            put(COLUMN_EXPENSE_CATEGORY, ExpenseCategory.fromStored(category))
        }
        val success = db.insert(TABLE_EXPENSES, null, values) != -1L
        if (success) {
            auditLogDatabase.addAuditLog(userId, "EXPENSE ADDED", "Added expense: $title (-₱$amount)")
        }
        return success
    }

    fun updateExpense(id: Int, title: String, amount: Double, dateTime: String,
                      category: String = ExpenseCategory.OTHER.label): Boolean {
        val db = this.writableDatabase
        
        // Kunin muna ang User ID para sa Audit Log
        var userId = -1
        val cursor = db.rawQuery("SELECT $COLUMN_EXPENSE_USER_ID FROM $TABLE_EXPENSES WHERE $COLUMN_EXPENSE_ID=?", arrayOf(id.toString()))
        if (cursor.moveToFirst()) userId = cursor.getInt(0)
        cursor.close()

        val values = ContentValues().apply {
            put(COLUMN_EXPENSE_TITLE, title)
            put(COLUMN_EXPENSE_AMOUNT, amount)
            put(COLUMN_EXPENSE_DATETIME, dateTime)
            put(COLUMN_EXPENSE_CATEGORY, ExpenseCategory.fromStored(category))
        }
        val success = db.update(TABLE_EXPENSES, values, "$COLUMN_EXPENSE_ID=?", arrayOf(id.toString())) > 0
        if (success && userId != -1) {
            auditLogDatabase.addAuditLog(userId, "EXPENSE EDITED", "Edited expense: $title (New amount: -₱$amount)")
        }
        return success
    }

    fun deleteExpense(id: Int): Boolean {
        val db = this.writableDatabase
        
        // Kunin muna ang detalye bago burahin para sa Audit Log
        var userId = -1
        var title = ""
        var amount = 0.0
        val cursor = db.rawQuery("SELECT * FROM $TABLE_EXPENSES WHERE $COLUMN_EXPENSE_ID=?", arrayOf(id.toString()))
        if (cursor.moveToFirst()) {
            userId = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_EXPENSE_USER_ID))
            title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EXPENSE_TITLE))
            amount = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_EXPENSE_AMOUNT))
        }
        cursor.close()

        val success = db.delete(TABLE_EXPENSES, "$COLUMN_EXPENSE_ID=?", arrayOf(id.toString())) > 0
        if (success && userId != -1) {
            auditLogDatabase.addAuditLog(userId, "EXPENSE DELETED", "Deleted expense: $title (-₱$amount)")
        }
        return success
    }

    fun getExpensesByUser(userId: Int): List<Expense> {
        val list = ArrayList<Expense>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_EXPENSES WHERE $COLUMN_EXPENSE_USER_ID=? ORDER BY $COLUMN_EXPENSE_ID DESC", arrayOf(userId.toString()))
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_EXPENSE_ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EXPENSE_TITLE))
                val amount = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_EXPENSE_AMOUNT))
                val dateTime = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EXPENSE_DATETIME))
                val category = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EXPENSE_CATEGORY))
                list.add(Expense(id, userId, title, amount, dateTime, ExpenseCategory.fromStored(category)))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getTotalExpensesByUser(userId: Int): Double {
        var total = 0.0
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT SUM($COLUMN_EXPENSE_AMOUNT) FROM $TABLE_EXPENSES WHERE $COLUMN_EXPENSE_USER_ID=?", arrayOf(userId.toString()))
        if (cursor.moveToFirst()) total = cursor.getDouble(0)
        cursor.close()
        return total
    }

    // ==========================================
    //              INCOME METHODS
    // ==========================================
    fun addIncome(userId: Int, title: String, amount: Double, dateTime: String,
                  category: String = ExpenseCategory.OTHER.label): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_INCOME_USER_ID, userId)
            put(COLUMN_INCOME_TITLE, title)
            put(COLUMN_INCOME_AMOUNT, amount)
            put(COLUMN_INCOME_DATETIME, dateTime)
            put(COLUMN_INCOME_CATEGORY, ExpenseCategory.fromStored(category))
        }
        val success = db.insert(TABLE_INCOMES, null, values) != -1L
        if (success) {
            auditLogDatabase.addAuditLog(userId, "INCOME ADDED", "Added income: $title (+₱$amount)")
        }
        return success
    }

    fun updateIncome(id: Int, title: String, amount: Double, dateTime: String,
                     category: String = ExpenseCategory.OTHER.label): Boolean {
        val db = this.writableDatabase
        
        var userId = -1
        val cursor = db.rawQuery("SELECT $COLUMN_INCOME_USER_ID FROM $TABLE_INCOMES WHERE $COLUMN_INCOME_ID=?", arrayOf(id.toString()))
        if (cursor.moveToFirst()) userId = cursor.getInt(0)
        cursor.close()

        val values = ContentValues().apply {
            put(COLUMN_INCOME_TITLE, title)
            put(COLUMN_INCOME_AMOUNT, amount)
            put(COLUMN_INCOME_DATETIME, dateTime)
            put(COLUMN_INCOME_CATEGORY, ExpenseCategory.fromStored(category))
        }
        val success = db.update(TABLE_INCOMES, values, "$COLUMN_INCOME_ID=?", arrayOf(id.toString())) > 0
        if (success && userId != -1) {
            auditLogDatabase.addAuditLog(userId, "INCOME EDITED", "Edited income: $title (New amount: +₱$amount)")
        }
        return success
    }

    fun deleteIncome(id: Int): Boolean {
        val db = this.writableDatabase
        
        var userId = -1
        var title = ""
        var amount = 0.0
        val cursor = db.rawQuery("SELECT * FROM $TABLE_INCOMES WHERE $COLUMN_INCOME_ID=?", arrayOf(id.toString()))
        if (cursor.moveToFirst()) {
            userId = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_INCOME_USER_ID))
            title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INCOME_TITLE))
            amount = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_INCOME_AMOUNT))
        }
        cursor.close()

        val success = db.delete(TABLE_INCOMES, "$COLUMN_INCOME_ID=?", arrayOf(id.toString())) > 0
        if (success && userId != -1) {
            auditLogDatabase.addAuditLog(userId, "INCOME DELETED", "Deleted income: $title (+₱$amount)")
        }
        return success
    }

    fun getIncomesByUser(userId: Int): List<Income> {
        val list = ArrayList<Income>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_INCOMES WHERE $COLUMN_INCOME_USER_ID=? ORDER BY $COLUMN_INCOME_ID DESC", arrayOf(userId.toString()))
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_INCOME_ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INCOME_TITLE))
                val amount = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_INCOME_AMOUNT))
                val dateTime = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INCOME_DATETIME))
                val category = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INCOME_CATEGORY))
                list.add(Income(id, userId, title, amount, dateTime, ExpenseCategory.fromStored(category)))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getTotalIncomeByUser(userId: Int): Double {
        var total = 0.0
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT SUM($COLUMN_INCOME_AMOUNT) FROM $TABLE_INCOMES WHERE $COLUMN_INCOME_USER_ID=?", arrayOf(userId.toString()))
        if (cursor.moveToFirst()) total = cursor.getDouble(0)
        cursor.close()
        return total
    }

    // ==========================================
    //            TRANSACTIONS & HISTORY
    // ==========================================
    fun getAllTransactionsByUser(userId: Int, filterType: String = "ALL"): List<Transaction> {
        val list = ArrayList<Transaction>()
        val db = this.readableDatabase

        if (filterType == "ALL" || filterType == "INCOME") {
            val cursorIncome = db.rawQuery("SELECT * FROM $TABLE_INCOMES WHERE $COLUMN_INCOME_USER_ID=?", arrayOf(userId.toString()))
            if (cursorIncome.moveToFirst()) {
                do {
                    val id = cursorIncome.getInt(cursorIncome.getColumnIndexOrThrow(COLUMN_INCOME_ID))
                    val title = cursorIncome.getString(cursorIncome.getColumnIndexOrThrow(COLUMN_INCOME_TITLE))
                    val amount = cursorIncome.getDouble(cursorIncome.getColumnIndexOrThrow(COLUMN_INCOME_AMOUNT))
                    val dateTime = cursorIncome.getString(cursorIncome.getColumnIndexOrThrow(COLUMN_INCOME_DATETIME))
                    val category = cursorIncome.getString(cursorIncome.getColumnIndexOrThrow(COLUMN_INCOME_CATEGORY))
                    list.add(Transaction(id, userId, title, amount, dateTime, TransactionType.INCOME,
                        ExpenseCategory.fromStored(category)))
                } while (cursorIncome.moveToNext())
            }
            cursorIncome.close()
        }

        if (filterType == "ALL" || filterType == "EXPENSE") {
            val cursorExpense = db.rawQuery("SELECT * FROM $TABLE_EXPENSES WHERE $COLUMN_EXPENSE_USER_ID=?", arrayOf(userId.toString()))
            if (cursorExpense.moveToFirst()) {
                do {
                    val id = cursorExpense.getInt(cursorExpense.getColumnIndexOrThrow(COLUMN_EXPENSE_ID))
                    val title = cursorExpense.getString(cursorExpense.getColumnIndexOrThrow(COLUMN_EXPENSE_TITLE))
                    val amount = cursorExpense.getDouble(cursorExpense.getColumnIndexOrThrow(COLUMN_EXPENSE_AMOUNT))
                    val dateTime = cursorExpense.getString(cursorExpense.getColumnIndexOrThrow(COLUMN_EXPENSE_DATETIME))
                    val category = cursorExpense.getString(cursorExpense.getColumnIndexOrThrow(COLUMN_EXPENSE_CATEGORY))
                    list.add(Transaction(id, userId, title, amount, dateTime, TransactionType.EXPENSE,
                        ExpenseCategory.fromStored(category)))
                } while (cursorExpense.moveToNext())
            }
            cursorExpense.close()
        }

        list.sortByDescending { it.id }
        return list
    }

    /** Restores a transaction deleted in the current session (used by Undo). */
    fun restoreTransaction(transaction: Transaction): Boolean {
        val db = writableDatabase
        val table = if (transaction.type == TransactionType.INCOME) TABLE_INCOMES else TABLE_EXPENSES
        val values = ContentValues().apply {
            put(if (transaction.type == TransactionType.INCOME) COLUMN_INCOME_ID else COLUMN_EXPENSE_ID, transaction.id)
            put(if (transaction.type == TransactionType.INCOME) COLUMN_INCOME_USER_ID else COLUMN_EXPENSE_USER_ID, transaction.userId)
            put(if (transaction.type == TransactionType.INCOME) COLUMN_INCOME_TITLE else COLUMN_EXPENSE_TITLE, transaction.title)
            put(if (transaction.type == TransactionType.INCOME) COLUMN_INCOME_AMOUNT else COLUMN_EXPENSE_AMOUNT, transaction.amount)
            put(if (transaction.type == TransactionType.INCOME) COLUMN_INCOME_DATETIME else COLUMN_EXPENSE_DATETIME, transaction.dateTime)
            put(if (transaction.type == TransactionType.INCOME) COLUMN_INCOME_CATEGORY else COLUMN_EXPENSE_CATEGORY, transaction.category)
        }
        return db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun addDebt(userId: Int, name: String, amount: Double, dueDate: String?): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_DEBT_USER_ID, userId)
            put(COLUMN_DEBT_NAME, name)
            put(COLUMN_DEBT_AMOUNT, amount)
            put(COLUMN_DEBT_DUE_DATE, dueDate)
        }
        val success = writableDatabase.insert(TABLE_DEBTS, null, values) != -1L
        if (success) auditLogDatabase.addAuditLog(userId, "DEBT ADDED", "Added debt: $name (-₱$amount)")
        return success
    }

    fun setDebtPaid(id: Int, paid: Boolean): Boolean {
        val values = ContentValues().apply { put(COLUMN_DEBT_PAID, if (paid) 1 else 0) }
        val userId = getDebtUserId(id)
        val success = writableDatabase.update(TABLE_DEBTS, values, "$COLUMN_DEBT_ID=?",
            arrayOf(id.toString())) > 0
        if (success && userId != -1) auditLogDatabase.addAuditLog(userId, "DEBT EDITED", "Changed debt paid status to $paid")
        return success
    }

    fun updateDebt(id: Int, name: String, amount: Double, dueDate: String?): Boolean {
        val userId = getDebtUserId(id)
        val values = ContentValues().apply {
            put(COLUMN_DEBT_NAME, name)
            put(COLUMN_DEBT_AMOUNT, amount)
            put(COLUMN_DEBT_DUE_DATE, dueDate)
        }
        val success = writableDatabase.update(TABLE_DEBTS, values, "$COLUMN_DEBT_ID=?",
            arrayOf(id.toString())) > 0
        if (success && userId != -1) auditLogDatabase.addAuditLog(userId, "DEBT EDITED", "Edited debt: $name (-₱$amount)")
        return success
    }

    fun deleteDebt(id: Int): Boolean {
        val userId = getDebtUserId(id)
        val success = writableDatabase.delete(TABLE_DEBTS, "$COLUMN_DEBT_ID=?", arrayOf(id.toString())) > 0
        if (success && userId != -1) auditLogDatabase.addAuditLog(userId, "DEBT DELETED", "Deleted debt")
        return success
    }

    private fun getDebtUserId(id: Int): Int =
        readableDatabase.query(TABLE_DEBTS, arrayOf(COLUMN_DEBT_USER_ID), "$COLUMN_DEBT_ID=?",
            arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    fun getDebtsByUser(userId: Int): List<Debt> {
        val result = ArrayList<Debt>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_DEBTS WHERE $COLUMN_DEBT_USER_ID=? ORDER BY $COLUMN_DEBT_PAID ASC, $COLUMN_DEBT_ID DESC",
            arrayOf(userId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(Debt(
                    it.getInt(it.getColumnIndexOrThrow(COLUMN_DEBT_ID)),
                    userId,
                    it.getString(it.getColumnIndexOrThrow(COLUMN_DEBT_NAME)),
                    it.getDouble(it.getColumnIndexOrThrow(COLUMN_DEBT_AMOUNT)),
                    it.getString(it.getColumnIndexOrThrow(COLUMN_DEBT_DUE_DATE)),
                    it.getInt(it.getColumnIndexOrThrow(COLUMN_DEBT_PAID)) == 1
                ))
            }
        }
        return result
    }

    /** Maintains a durable outbox, including tombstones, and exports it without changing
     * timestamps for unchanged records. */
    fun exportSyncRecords(userId: Int): JSONArray {
        val current = HashMap<String, Pair<String, JSONObject>>()
        fun nextTimestamp(): Long = System.currentTimeMillis() + (readableDatabase.query(
            "sync_metadata", arrayOf("MAX(updated_at)"), null, null, null, null, null
        ).use { if (it.moveToFirst() && !it.isNull(0)) maxOf(0L, it.getLong(0) - System.currentTimeMillis() + 1L) else 0L })
        fun add(key: String, type: String, value: JSONObject) { current[key] = Pair(type, value) }
        // Export every local account, not only the currently selected account.
        // Sync is account-wide, so omitting another local user's records would
        // incorrectly turn them into cloud tombstones.
        allUsers.forEach { localUser ->
            getAllTransactionsByUser(localUser.id).forEach {
                add("${if (it.type == TransactionType.INCOME) "income" else "expense"}-${it.id}",
                    if (it.type == TransactionType.INCOME) "income" else "expense",
                    JSONObject().put("id", it.id).put("user_id", it.userId).put("title", it.title)
                        .put("amount", it.amount).put("datetime", it.dateTime).put("category", it.category))
            }
            getDebtsByUser(localUser.id).forEach {
                add("debt-${it.id}", "debt", JSONObject().put("id", it.id).put("user_id", it.userId)
                    .put("name", it.name).put("amount", it.amount).put("due_date", it.dueDate)
                    .put("paid", it.paid))
            }

        }
        allUsers.forEach {
            // A user's SQLite id can differ between devices. Reuse the cloud
            // metadata key for a matching name so a restored account is not
            // uploaded a second time under the new device's id.
            var key = "user-${it.id}"
            readableDatabase.rawQuery("SELECT record_key,payload FROM sync_metadata WHERE record_type='user'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (JSONObject(cursor.getString(1)).optString("name") == it.name) {
                        key = cursor.getString(0)
                        break
                    }
                }
            }
            add(key, "user", JSONObject().put("id", it.id).put("name", it.name))
        }
        val metadata = writableDatabase.rawQuery("SELECT record_key,record_type,payload,updated_at,deleted FROM sync_metadata", null)
        metadata.use {
            while (it.moveToNext()) {
                val key = it.getString(0)
                val existing = current[key]
                if (existing == null && it.getInt(4) == 0) {
                    writableDatabase.execSQL("UPDATE sync_metadata SET updated_at=?, deleted=1 WHERE record_key=?",
                        arrayOf(nextTimestamp(), key))
                } else if (existing != null && (it.getInt(4) == 1 || it.getString(2) != existing.second.toString())) {
                    writableDatabase.execSQL("UPDATE sync_metadata SET record_type=?,payload=?,updated_at=?,deleted=0 WHERE record_key=?",
                        arrayOf(existing.first, existing.second.toString(), nextTimestamp(), key))
                }
            }
        }
        current.forEach { (key, value) ->
            if (writableDatabase.query("sync_metadata", arrayOf("record_key"), "record_key=?",
                    arrayOf(key), null, null, null).use { !it.moveToFirst() }) {
                writableDatabase.execSQL("INSERT INTO sync_metadata(record_key,record_type,payload,updated_at,deleted) VALUES(?,?,?,?,0)",
                    arrayOf(key, value.first, value.second.toString(), nextTimestamp()))
            }
        }
        val result = JSONArray()
        val outbox = readableDatabase.rawQuery("SELECT record_key,record_type,payload,updated_at,deleted FROM sync_metadata", null)
        outbox.use {
            while (it.moveToNext()) result.put(JSONObject().put("record_key", it.getString(0))
                .put("record_type", it.getString(1)).put("payload", JSONObject(it.getString(2)))
                .put("updated_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(it.getLong(3)))).put("deleted", it.getInt(4) == 1))
        }
        return result
    }

    /** Imports only records that are not already present; REST upserts provide LWW ordering. */
    fun importSyncRecord(type: String, payload: JSONObject): Boolean {
        val id = payload.optInt("id", -1)
        if (id < 0) return false
        val values = ContentValues().apply {
            put("id", id)
        }

        val table: String
        when (type) {
            "user" -> {
                val localId = importRemoteUser(payload)
                return localId != -1
            }
            "income", "expense" -> {
                table = if (type == "income") TABLE_INCOMES else TABLE_EXPENSES
                values.put("user_id", payload.optInt("local_user_id", payload.optInt("user_id")))
                values.put("title", payload.optString("title"))
                values.put("amount", payload.optDouble("amount"))
                values.put("datetime", payload.optString("datetime"))
                values.put("category", payload.optString("category", ExpenseCategory.OTHER.label))
            }
            "debt" -> {
                table = TABLE_DEBTS
                values.put("user_id", payload.optInt("local_user_id", payload.optInt("user_id")))
                values.put("name", payload.optString("name"))
                values.put("amount", payload.optDouble("amount"))
                values.put("due_date", payload.optString("due_date"))
                values.put("paid", if (payload.optBoolean("paid")) 1 else 0)
            }
            else -> return false
        }
        return writableDatabase.insertWithOnConflict(table, null, values,
            SQLiteDatabase.CONFLICT_REPLACE) != -1L
    }

    private fun getSyncTimestamp(key: String): Long =
        readableDatabase.query("sync_metadata", arrayOf("updated_at"), "record_key=?",
            arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun applyRemoteRecord(key: String, type: String, payload: JSONObject, updatedAt: Long,
                          deleted: Boolean): Boolean {
        if (type == "audit_log") {
            if (updatedAt <= getSyncTimestamp(key)) return false
            val applied = auditLogDatabase.applyRemoteRecord(key, payload, updatedAt, deleted)
            if (applied) {
                writableDatabase.execSQL("INSERT OR REPLACE INTO sync_metadata(record_key,record_type,payload,updated_at,deleted) VALUES(?,?,?,?,?)",
                    arrayOf(key, type, payload.toString(), updatedAt, if (deleted) 1 else 0))
            }
            return applied
        }
        if (updatedAt <= getSyncTimestamp(key)) return false
        val database = writableDatabase
        if (deleted) {
            val table = when (type) {
                "user" -> {
                    val name = payload.optString("name")
                    val localId = readableDatabase.query(TABLE_USERS, arrayOf(COLUMN_USER_ID),
                        "$COLUMN_USER_NAME=?", arrayOf(name), null, null, null).use {
                        if (it.moveToFirst()) it.getInt(0) else -1
                    }
                    if (localId != -1) database.delete(TABLE_USERS, "id=?",
                        arrayOf(localId.toString()))
                    database.execSQL("INSERT OR REPLACE INTO sync_metadata(record_key,record_type,payload,updated_at,deleted) VALUES(?,?,?,?,1)",
                        arrayOf(key, type, payload.toString(), updatedAt))
                    return true
                }

                "income" -> TABLE_INCOMES
                "expense" -> TABLE_EXPENSES
                "debt" -> TABLE_DEBTS
                else -> return false
            }
            database.delete(table, "id=?", arrayOf(payload.optInt("id").toString()))
        } else if (!importSyncRecord(type, payload)) {
            return false
        }
        database.execSQL("INSERT OR REPLACE INTO sync_metadata(record_key,record_type,payload,updated_at,deleted) VALUES(?,?,?,?,?)",
            arrayOf(key, type, payload.toString(), updatedAt, if (deleted) 1 else 0))
        return true
    }

    fun exportAuditSyncRecords(): JSONArray {
        val result = auditLogDatabase.exportSyncRecords()
        readableDatabase.query("sync_metadata", arrayOf("record_key","payload","updated_at","deleted"),
            "record_type='audit_log'", null, null, null, null).use {
            while (it.moveToNext()) {
                if (it.getInt(3) == 1) result.put(JSONObject().put("record_key", it.getString(0))
                    .put("record_type", "audit_log").put("payload", JSONObject(it.getString(1)))
                    .put("updated_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it.getLong(2))))
                    .put("deleted", true))
            }
        }
        return result
    }

    /** Maps a cloud user (whose numeric id is only meaningful on that device) to
     * an id in this database. Names are the account-level identity used by the
     * app, while the cloud id is retained in sync_metadata for LWW. */
    fun importRemoteUser(payload: JSONObject): Int {
        val name = payload.optString("name")
        val existing = findLocalUserId(name)
        if (existing != -1) return existing
        val values = ContentValues().apply { put(COLUMN_USER_NAME, name) }
        return writableDatabase.insert(TABLE_USERS, null, values).toInt()
    }

    fun findLocalUserId(name: String): Int =
        readableDatabase.query(TABLE_USERS, arrayOf(COLUMN_USER_ID), "$COLUMN_USER_NAME=?",
            arrayOf(name), null, null, null).use { if (it.moveToFirst()) it.getInt(0) else -1 }

}