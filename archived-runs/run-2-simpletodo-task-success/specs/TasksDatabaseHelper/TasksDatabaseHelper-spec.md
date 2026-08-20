# Module Spec: TasksDatabaseHelper

| Property | Value |
|----------|-------|
| **Package** | `com.clinton.simpletodo.utils` |
| **Source** | `D:\Projects\projects-to-test-on\SimpleToDo\app\src\main\java\com\clinton\simpletodo\utils\TasksDatabaseHelper.java` |
| **Depends On** | `Task`, `TaskContract` |

## Fields
- `String TAG`
- `String DATABASE_NAME`
- `int DATABASE_VERSION`

## Methods
- `public void onConfigure(SQLiteDatabase db)`
- `public void onCreate(SQLiteDatabase db)`
- `public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)`
- `public void addTask(String task)`
- `public long addOrUpdateTask(Task task)`
- `public ArrayList<String> getAllTasks()`
- `public void deleteAllTasks()`

## Business Logic Summary

1. **Purpose** — The `TasksDatabaseHelper` class is responsible for managing a SQLite database in an Android application, providing methods to add, retrieve, update, and delete tasks.

2. **Core Logic** 
   - Manages the lifecycle of the database, including creation (`onCreate`) and upgrade (`onUpgrade`).
   - Provides methods to add tasks (`addTask`, `addOrUpdateTask`), retrieve all tasks (`getAllTasks`), and delete all tasks (`deleteAllTasks`).

3. **Migration Notes** 
   - Use Kotlin's data classes for the `Task` model to improve immutability and reduce boilerplate.
   - Utilize Kotlin coroutines for database operations to handle them asynchronously, improving app responsiveness.
   - Leverage Kotlin extensions for SQLite operations to make the code more concise and readable.

4. **Dependencies** 
   - Relies on the `Task` class for task data representation.
   - Depends on the `TaskContract` class for database schema definitions.
