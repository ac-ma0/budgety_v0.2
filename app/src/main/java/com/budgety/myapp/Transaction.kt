package com.budgety.myapp

enum class TransactionType {
    INCOME, EXPENSE
}

enum class ExpenseCategory(val label: String) {
    FOOD("Food"),
    TRANSPORTATION("Transportation"),
    BILLS("Bills"),
    SHOPPING("Shopping"),
    EDUCATION("Education"),
    HEALTH("Health"),
    ENTERTAINMENT("Entertainment"),
    OTHER("Other");

    companion object {
        fun fromStored(value: String?): String =
            values().firstOrNull { it.label.equals(value, ignoreCase = true) }?.label
                ?: OTHER.label
    }
}

data class Transaction(
    val id: Int,
    val userId: Int,
    val title: String,
    val amount: Double,
    val dateTime: String,
    val type: TransactionType,
    val category: String = ExpenseCategory.OTHER.label
)
