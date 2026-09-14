package com.budgety.myapp

data class AuditLog(
    val id: Int,
    val userId: Int,
    val action: String,
    val details: String,
    val date: String
)