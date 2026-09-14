package com.budgety.myapp

data class User(
    val id: Int,
    val name: String
) {
    override fun toString(): String = name
}
