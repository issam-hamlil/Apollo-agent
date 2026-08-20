# Apollo — Stage 1: Module Dependency Report

Generated: 2026-08-11T15:16:00.727654700Z

## Topologically-Sorted Migration Order

Every dependency appears **before** the classes that use it.

1. `Task`
2. `TaskContract`
3. `TasksDatabaseHelper`
4. `EditItemActivity`
5. `MainActivity`
6. `SplashActivity`

## Dependency Graph

| Module | Depends On |
|--------|------------|
| `Task` |  |
| `TaskContract` |  |
| `TasksDatabaseHelper` | `Task`, `TaskContract` |
| `EditItemActivity` | `MainActivity` |
| `MainActivity` | `EditItemActivity`, `Task`, `TaskContract`, `TasksDatabaseHelper` |
| `SplashActivity` | `MainActivity` |
