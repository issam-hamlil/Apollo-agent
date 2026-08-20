# Module Spec: SplashActivity

| Property | Value |
|----------|-------|
| **Package** | `com.clinton.simpletodo.activities` |
| **Source** | `D:\Projects\projects-to-test-on\SimpleToDo\app\src\main\java\com\clinton\simpletodo\activities\SplashActivity.java` |
| **Depends On** | `MainActivity` |

## Fields
*(none)*

## Methods
- `void onCreate(Bundle savedInstanceState)`

## Business Logic Summary

1. **Purpose** — The `SplashActivity` is responsible for displaying a splash screen and immediately transitioning to the `MainActivity`.

2. **Core Logic** — Upon creation, it starts an intent to launch the `MainActivity` and then finishes itself.

3. **Migration Notes** — In Kotlin, the class can be simplified using extension functions and null safety features. The `onCreate` method can be made more concise by leveraging Kotlin's syntax for starting activities.

4. **Dependencies** — It relies on the `MainActivity` from the same repository to handle the main application logic after the splash screen is displayed.
