package com.example.legacy

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

object AsyncDataLoader {
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    suspend fun loadUserData(userId: String): Result<com.example.legacy.User> {
        if (userId.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("User ID cannot be null or empty"))
        }

        try {
            val user = com.example.legacy.User(userId, "User_$userId", "$userId@example.com", 30)
            return Result.success(user)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}

data class User(
    val id: String,
    val username: String,
    val email: String,
    val age: Int
)