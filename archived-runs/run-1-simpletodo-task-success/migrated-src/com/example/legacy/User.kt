package com.example.legacy

data class User(
    var id: String? = null,
    var username: String? = null,
    var email: String? = null,
    var age: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as User
        return id == other.id && username == other.username && email == other.email && age == other.age
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (email?.hashCode() ?: 0)
        result = 31 * result + age
        return result
    }

    override fun toString(): String {
        return "User{id='$id', username='$username', email='$email', age=$age}"
    }
}