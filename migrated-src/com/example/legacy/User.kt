package com.example.legacy

data class User(
    val id: String,
    val username: String? = null,
    val email: String? = null,
    val age: Int = 0
)

fun validateUser(user: User) {
    require(user.id.isNotEmpty()) { "User ID cannot be empty" }
}