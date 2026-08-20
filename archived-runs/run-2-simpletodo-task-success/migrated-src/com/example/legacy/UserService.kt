package com.example.legacy

class UserService {
    private val userList = mutableListOf<User>()

    fun addUser(user: User?) {
        if (user == null) {
            throw IllegalArgumentException("User cannot be null")
        }
        if (user.id.isNullOrEmpty()) {
            throw IllegalArgumentException("User ID cannot be null or empty")
        }
        userList.add(user)
    }

    fun findById(id: String?): User? {
        if (id == null) return null
        return userList.firstOrNull { it.id == id }
    }

    fun filterAdults(): List<User> {
        return userList.filter { it.age >= 18 }
    }

    fun formatUserSummary(user: User?): String {
        if (user == null) return "N/A"
        val username = if (!user.username.isNullOrEmpty()) user.username else "Anonymous"
        val email = if (!user.email.isNullOrEmpty()) user.email else "no-email"
        return "$username ($email)"
    }
}