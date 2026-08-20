# Module Spec: MainActivity

| Property | Value |
|----------|-------|
| **Package** | `com.clinton.simpletodo.activities` |
| **Source** | `D:\Projects\projects-to-test-on\SimpleToDo\app\src\main\java\com\clinton\simpletodo\activities\MainActivity.java` |
| **Depends On** | `EditItemActivity`, `Task`, `TaskContract`, `TasksDatabaseHelper` |

## Fields
- `int REQUEST_CODE`
- `String TAG`
- `ArrayList<String> todoItems`
- `ArrayAdapter<String> aToDoAdapter`
- `ListView lvItems`
- `EditText etEditText`
- `TasksDatabaseHelper databaseHelper`

## Methods
- `void onCreate(Bundle savedInstanceState)`
- `public void launchEditView(String todoItemText, int position)`
- `public void launchEditDialog(String todoItemText, int position)`
- `void onActivityResult(int requestCode, int resultCode, Intent data)`
- `public void populateArrayItems()`
- `public void populateArrayItemsFromDb()`
- `public void onAddItem(View view)`
- `private void readItems()`
- `private void writeItems()`
- `public boolean onCreateOptionsMenu(Menu menu)`
- `public boolean onOptionsItemSelected(MenuItem item)`
- `public static String preferredCase(String original)`
- `public static void storeArrayVal(ArrayList inArrayList, Context context)`
- `public static ArrayList getArrayVal(Context acontext)`
- `public void removeElement(String selectedItem, int position)`

## Business Logic Summary

### Purpose
The `MainActivity` class is responsible for managing the main user interface of a simple to-do application, allowing users to add, edit, and delete tasks. It interacts with a database helper to persist task data.

### Core Logic
- **Task Management**: Users can add new tasks through an EditText field and view them in a ListView.
- **Edit Functionality**: Tasks can be edited either by launching a separate activity or via a dialog box.
- **Persistence**: Tasks are stored in a SQLite database using `TasksDatabaseHelper`.
- **Deletion**: Long-clicking on a task removes it from the list and updates the database.

### Migration Notes
- Use Kotlin's coroutines for asynchronous database operations to improve performance and readability.
- Replace Java collections with Kotlin's more idiomatic data structures, such as `mutableListOf` for `todoItems`.
- Utilize Kotlin's extension functions to simplify repetitive tasks like reading and writing items.

### Dependencies
- **EditItemActivity**: Used for launching a separate activity to edit tasks.
- **Task**: Represents individual task objects (though not directly used in the provided code).
- **TaskContract**: Defines database schema contracts (not explicitly used but implied by `TasksDatabaseHelper`).
- **TasksDatabaseHelper**: Manages SQLite database operations for storing and retrieving tasks.
