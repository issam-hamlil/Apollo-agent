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

2. **Core Logic** — Upon creation, it launches an intent to start the `MainActivity` and then finishes itself.

3. **Migration Notes** — In Kotlin, the class can be simplified using extension functions and more concise syntax. For example, `startActivity(intent)` can be replaced with `startActivity<MainActivity>()`.

4. **Dependencies** — The class depends on the `MainActivity` for navigation after the splash screen is displayed.
