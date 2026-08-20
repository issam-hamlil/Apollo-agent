# Module Spec: UserService

| Property | Value |
|----------|-------|
| **Package** | `com.example.legacy` |
| **Source** | `D:\Projects\Apollo-agent\sample-legacy\src\main\java\com\example\legacy\UserService.java` |
| **Depends On** | `User` |

## Fields
- `List<User> userList`

## Methods
- `public void addUser(User user)`
- `public User findById(String id)`
- `public List<User> filterAdults()`
- `public String formatUserSummary(User user)`

## Business Logic Summary

1. **Purpose**  
   The `UserService` class is responsible for managing a list of users, providing operations to add users, find users by ID, filter adult users, and format user summaries.

2. **Core Logic**  
   - Adds users to an internal list after validating that the user and their ID are not null or empty.
   - Searches for a user by ID in the list and returns the user if found.
   - Filters the list of users to include only those who are 18 years old or older.
   - Formats a summary string for a given user, handling potential null values for username and email.

3. **Migration Notes**  
   - Use Kotlin's `filter` and `map` functions for more concise collection operations.
   - Utilize Kotlin's `let` or `apply` for safer null checks and object manipulations.
   - Replace Java-style exception throwing with Kotlin's idiomatic approach using `requireNotNull`.

4. **Dependencies**  
   - Relies on the `User` class from the same repository, which is used to store user data.
