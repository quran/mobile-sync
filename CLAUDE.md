# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

The mobile-sync project (internally named "quransync") is a Kotlin Multiplatform Mobile (KMM) library for synchronizing Quran.com user data across Android and iOS. It implements bidirectional synchronization with conflict resolution for page bookmarks.

## Development Commands

### Building and Testing
```bash
# Clean and build entire project
./gradlew clean build

# Run all tests across all modules  
./gradlew allTests

# Run tests for specific module
./gradlew :syncengine:test
./gradlew :persistence:test

# Build iOS framework
./gradlew :umbrella:assembleXCFramework

# Run Android demo
./gradlew :demo:android:installDebug
```

### Module-Specific Commands
```bash
# Test individual components
./gradlew :syncengine:testDebugUnitTest
./gradlew :syncengine:testReleaseUnitTest

# Check for dependency updates
./gradlew dependencyUpdates
```

## Architecture Overview

The project follows a layered architecture with clear separation of concerns:

### Module Dependencies
```
mutations-definitions (foundational)
         ↑
    ┌────┴────┬────────────┐
    │         │            │
persistence  syncengine    │
    └────┬────┴────────┐   │
         │             │   │
    sync-pipelines     │   │
         ↑             │   │
         │             │   │
      umbrella    demo:android
```

### Core Modules

**mutations-definitions**: Foundation module defining `Mutation`, `LocalModelMutation<Model>`, and `RemoteModelMutation<Model>` types used across the entire sync system.

**persistence**: SQLDelight-based data layer with `PageBookmarksRepository` and change tracking. Handles cross-platform database operations and mutation state persistence.

**syncengine**: Pure business logic module containing `SynchronizationClient`, `PageBookmarksSynchronizationExecutor`, conflict resolution system, network layer (Ktor), and scheduling system. Contains no external dependencies in core executor logic.

**sync-pipelines**: Integration layer with `SyncEnginePipeline` that bridges syncengine and persistence. Provides the main high-level API via `RepositoryDataFetcher` and `ResultReceiver`.

**umbrella**: iOS framework packaging module that exports all public APIs as "Shared" framework.

**demo:android**: Sample Android app demonstrating library usage.

### Key Architectural Patterns

- **Dependency Inversion**: syncengine defines interfaces implemented by persistence
- **Adapter Pattern**: sync-pipelines acts as adapter between layers  
- **Pure Business Logic**: Core sync logic has no external dependencies
- **Conflict Resolution Pipeline**: Sophisticated preprocessing and conflict handling

## Technology Stack

- **Language**: Kotlin Multiplatform (targeting iOS and Android)
- **Database**: SQLDelight for cross-platform SQL operations
- **Networking**: Ktor HTTP client with platform-specific implementations
- **Serialization**: kotlinx.serialization
- **Async**: kotlinx.coroutines 
- **Testing**: kotlin.test with kotlinx.coroutines.test
- **Build**: Gradle with Kotlin DSL
- **iOS Distribution**: XCFramework via umbrella module

## Development Guidelines

### Module Boundaries
- Keep syncengine pure (no external persistence dependencies)
- Use sync-pipelines for integration between layers
- Maintain clear interfaces between modules

### Testing Strategy  
- syncengine has comprehensive unit tests with timing-sensitive scheduling tests
- Use `kotlinx.coroutines.test` for coroutine testing
- Test timing with tolerance values (typically 100ms tolerance)

### Synchronization Architecture
The sync system implements a sophisticated bidirectional flow:

1. **Local Changes**: `PageBookmarksRepository` tracks mutations
2. **Sync Trigger**: Events fire through `SynchronizationClient` 
3. **Pipeline Execution**: `PageBookmarksSynchronizationExecutor` orchestrates:
   - Fetch local/remote mutations
   - Conflict detection and resolution
   - Push/pull data exchange
4. **Result Persistence**: Coordinated by sync-pipelines

### Scheduling System
The recently added `Scheduler` in syncengine manages sync timing with:
- `APP_START` trigger (30s delay)
- `LOCAL_DATA_MODIFIED` trigger (5s delay)  
- `IMMEDIATE` trigger (0ms delay)
- Exponential backoff retry logic (200ms base, 2.5x multiplier, max 5 retries)
- State machine tracking scheduler lifecycle

**Error Handling**: The Scheduler uses exception-based error handling:
- Task functions should throw exceptions to indicate failure
- Scheduler catches exceptions and applies retry logic automatically
- After maximum retries, the final exception is reported to the failure callback
- Success is indicated by task function completing without throwing

### Platform-Specific Considerations
- HTTP clients: OkHttp (Android), Darwin (iOS)
- Database drivers: Android SQLite, iOS native
- Framework packaging: XCFramework for iOS consumption

## Common Development Tasks

### Adding New Mutation Types
1. Define in mutations-definitions module
2. Update persistence layer with SQL schema changes
3. Add syncengine business logic
4. Wire through sync-pipelines

### Extending Sync Logic
- Add new preprocessing steps to `LocalMutationsPreprocessor`/`RemoteMutationsPreprocessor`
- Extend conflict resolution in `ConflictDetector`/`ConflictResolver`  
- Update `PageBookmarksSynchronizationExecutor` pipeline

### Testing Sync Behavior
- Use test timings in `SchedulerTest.kt` as reference
- Mock network layer for integration tests
- Test conflict scenarios with controlled data states