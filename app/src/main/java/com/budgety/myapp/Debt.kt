package com.budgety.myapp

data class Debt(
    val id: Int,
    val userId: Int,
    val name: String,
    val amount: Double,
    val dueDate: String?,
    val paid: Boolean
)
