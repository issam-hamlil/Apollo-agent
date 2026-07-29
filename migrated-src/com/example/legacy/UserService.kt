package com.example.legacy

import com.example.legacy.User

class UserService {
    private val userList = mutableListOf<User>()

    fun addUser(user: User) {
        requireNotNull(user) { "User cannot be null" }
        require(!user.id.isNullOrEmpty()) { "User ID cannot be null or empty" }
        userList.add(user)
    }

    fun findById(id: String): User? {
        return userList.find { it.id == id }
    }

    fun filterAdults(): List<User> {
        return userList.filter { it.age >= 18 }
    }

    fun formatUserSummary(user: User?): String {
        return user?.let { 
            val username = it.username ?: "Anonymous"
            val email = it.email ?: "no-email"
            "$username ($email)"
        } ?: "N/A"
    }
}