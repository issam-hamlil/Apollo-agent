package com.example.legacy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Self-Healed by Apollo Agent (Stage 5 Fixer)
 * Pattern: Callback Interface -> Kotlin Suspending Function
 */
class AsyncDataLoader {

    suspend fun loadUserData(userId: String?): User? = withContext(Dispatchers.IO) {
        if (userId.isNullOrBlank()) return@withContext null
        User(
            id = userId,
            username = "User_$userId",
            email = "$userId@example.com",
            age = 30
        )
    }

    fun shutdown() {}
}