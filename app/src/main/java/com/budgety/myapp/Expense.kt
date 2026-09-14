package com.budgety.myapp

data class Expense(
    val id: Int,
    val userId: Int,
    val title: String,
    val amount: Double,
    val dateTime: String,
    val category: String = ExpenseCategory.OTHER.label
)
