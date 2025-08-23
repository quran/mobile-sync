# SyncEngine Architecture & Conflict Resolution

## Overview

The SyncEngine package provides a robust synchronization system for page bookmarks across multiple devices. It handles bidirectional synchronization between local and remote data stores, with sophisticated conflict detection and resolution mechanisms.

## Core Components

### 1. SynchronizationClient Interface

The main entry point for synchronization operations:

```kotlin
interface SynchronizationClient {
    fun localDataUpdated()      // Triggered when local data changes
    fun applicationStarted()    // Triggered when app starts
}
```

**Key Features:**
- Provides a simple interface for triggering sync operations
- Handles authentication and network communication
- Orchestrates the complete synchronization pipeline

### 2. PageBookmarksSynchronizationExecutor

The core business logic executor that contains no external dependencies. This should ease testing the whole synchronization pipeline with unit tests.

```kotlin
class PageBookmarksSynchronizationExecutor {
    suspend fun executePipeline(
        fetchLocal: suspend () -> PipelineInitData,
        fetchRemote: suspend (Long) -> FetchedRemoteData,
        checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>,
        pushLocal: suspend (List<LocalModelMutation<PageBookmark>>, Long) -> PushResultData
    ): PipelineResult
}
```

**Pipeline Steps:**
1. **Initialize**: Fetch local mutations and last modification date
2. **Preprocess Local**: Validate and transform local mutations
3. **Fetch Remote**: Get remote mutations since last sync
4. **Preprocess Remote**: Filter and transform remote mutations
5. **Detect Conflicts**: Identify conflicting mutations
6. **Resolve Conflicts**: Apply conflict resolution rules
7. **Push Local**: Send non-conflicting local mutations
8. **Combine Results**: Merge all remote mutations for persistence

## Conflict Resolution System

### Conflict Detection

The `ConflictDetector` identifies conflicts between local and remote mutations:

#### Conflict Types

1. **Page-Level Conflicts**: Multiple mutations for the same page
2. **Resource-Level Conflicts**: Mutations for the same resource ID
3. **Cross-Reference Conflicts**: Local mutations referencing remote resources

### Conflict Resolution

The `ConflictResolver` applies business rules to resolve detected conflicts:

#### Resolution Rules

1. **Illogical Scenarios** (throw exceptions):
   - Local creation vs Remote deletion
   - Local deletion vs Remote creation

2. **Same Operation Conflicts**:
   - Both sides created → Accept remote
   - Both sides deleted → Accept remote

3. **Mixed Operation Conflicts**:
   - Local deletion + Remote creation → Accept remote, push local creation
   - Other combinations → Accept remote

## Data Flow

### Synchronization Pipeline

```
┌─────────────────┐    ┌──────────────────┐
│   Local Data    │    │  Remote Server   │
└─────────────────┘    └──────────────────┘
         │                       │
         ▼                       ▼
┌─────────────────┐    ┌──────────────────┐
│ 1. Fetch Local  │    │ 2. Fetch Remote  │
│   Mutations     │    │   Mutations      │
└─────────────────┘    └──────────────────┘
         │                       │
         ▼                       ▼
┌─────────────────┐    ┌──────────────────┐
│ 3. Preprocess   │    │ 4. Preprocess    │
│   Local Data    │    │   Remote Data    │
└─────────────────┘    └──────────────────┘
         │                       │
         └───────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ 5. Detect Conflicts     │
                    │   (Input: Preprocessed  │
                    │    Local + Remote)      │
                    └─────────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ 6. Resolve Conflicts    │
                    └─────────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ 7. Push Non-Conflicting │
                    │    Local Mutations      │
                    └─────────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ 8. Combine & Return     │
                    │   (Non-conflicting      │
                    │    Remote + Resolved    │
                    │    Remote + Pushed      │
                    │    Remote)              │
                    └─────────────────────────┘
```

### Mutation Types

The system handles three types of mutations:

1. **CREATE**: New resource creation
2. **UPDATE**: Existing resource modification
3. **DELETE**: Resource deletion

**Important Note**: UPDATE mutations are converted to CREATE mutations during preprocessing for page bookmarks, as they're not expected for them. 

## Preprocessing Logic

### Local Mutations Preprocessor

Validates local mutations and ensures logical consistency:

```kotlin
fun preprocess(localMutations: List<LocalModelMutation<PageBookmark>>): List<LocalModelMutation<PageBookmark>> {
    // Convert MODIFIED to CREATED
    val transformedModifiedMutations = modifiedMutations.map { 
        it.copy(mutation = Mutation.CREATED) 
    }
    
    // Validate logical constraints
    // - Max 2 mutations per page
    // - Max 1 deletion per page
    // - Max 1 creation per page
    // - Deletions must have remote IDs
    
    return processedMutations
}
```

### Remote Mutations Preprocessor

Filters and transforms remote mutations:

```kotlin
suspend fun preprocess(remoteMutations: List<RemoteModelMutation<PageBookmark>>): List<RemoteModelMutation<PageBookmark>> {
    // Filter DELETE mutations for non-existent local resources
    val filteredDeletedMutations = deletedMutations.filter { 
        checkLocalExistence(listOf(it.remoteID))[it.remoteID] ?: false 
    }
    
    // Convert MODIFIED to CREATED
    val transformedModifiedMutations = modifiedMutations.map { 
        it.copy(mutation = Mutation.CREATED) 
    }
    
    return createdMutations + filteredDeletedMutations + transformedModifiedMutations
}
```

## Network Layer

### Request/Response Pattern

The network layer uses a clean request/response pattern. 

## Configuration and Dependencies

### Required Dependencies

The sync engine requires three main dependencies to function:

- **LocalDataFetcher**: Provides access to local mutations and existence checks
- **ResultNotifier**: Handles sync success/failure callbacks with results
- **LocalModificationDateFetcher**: Tracks the last local modification timestamp
- **AuthenticationDataFetcher**: Supplies authentication headers for API requests

### Environment Configuration

The sync engine needs the remote server endpoint URL to establish network communication.
