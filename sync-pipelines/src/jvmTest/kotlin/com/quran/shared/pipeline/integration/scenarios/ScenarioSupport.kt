package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.CollectionWithAyahBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.pipeline.integration.SyncDevice
import com.quran.shared.pipeline.integration.SyncRun
import com.quran.shared.pipeline.integration.SyncScenario
import com.quran.shared.pipeline.integration.UserDataSnapshot
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.FakeSyncServer
import com.quran.shared.pipeline.integration.server.RequestRecord
import com.quran.shared.pipeline.integration.server.ServerSession
import com.quran.shared.pipeline.integration.server.ServerState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.Instant

// Everything here reads public flows, return values, or the fake server's state and request log;
// nothing reads engine tables or the sync token.

internal const val ALICE = "alice"
internal const val BOB = "bob"

/** A fixed base for explicit client timestamps; tests add offsets to order writes. */
internal const val T0 = 1_760_000_000_000L

/** A client timestamp [offsetMillis] after [T0]. */
internal fun at(offsetMillis: Long): Instant = Instant.fromEpochMilliseconds(T0 + offsetMillis)

internal val EMPTY_SNAPSHOT = UserDataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

/** New devices named [names], each signed in to [account] and synced once. */
internal suspend fun SyncScenario.signedIn(vararg names: String, account: String = ALICE): List<SyncDevice> =
    names.map { name -> device(name).also { assertSucceeded(it.login(account)) } }

// region Device reads
internal suspend fun SyncDevice.favorites(): CollectionWithAyahBookmarks =
    data.collectionsWithBookmarks.first().single { it.collection.isDefault }

internal suspend fun SyncDevice.favoritesId(): String = favorites().collection.id

internal suspend fun SyncDevice.favoriteVerses(): List<String> = favorites().bookmarks.map { "${it.sura}:${it.ayah}" }.sorted()

/** The user collection named [name], or null. Favorites and highlight collections are not listed. */
internal suspend fun SyncDevice.collection(name: String): CollectionWithAyahBookmarks? =
    data.collectionsWithBookmarks.first().firstOrNull { !it.collection.isDefault && it.collection.name == name }

internal suspend fun SyncDevice.collectionId(collectionName: String): String =
    checkNotNull(collection(collectionName)) { "$name has no collection $collectionName" }.collection.id

internal suspend fun SyncDevice.collectionNames(): List<String> =
    data.collectionsWithBookmarks.first().filter { !it.collection.isDefault }.map { it.collection.name }.sorted()

internal suspend fun SyncDevice.versesIn(name: String): List<String>? =
    collection(name)?.bookmarks?.map { "${it.sura}:${it.ayah}" }?.sorted()

internal suspend fun SyncDevice.noteBodies(): List<String> = data.notes.first().map { it.body }.sorted()

internal suspend fun SyncDevice.note(body: String): Note = data.notes.first().single { it.body == body }

internal suspend fun SyncDevice.sessionVerses(): List<String> = data.readingSessions.first().map { "${it.sura}:${it.ayah}" }.sorted()

internal suspend fun SyncDevice.highlightList(): List<String> =
    data.highlights.first().map { "${it.sura}:${it.ayah} ${it.color.name.lowercase()}" }.sorted()
// endregion

// region Device writes
internal suspend fun SyncDevice.addToFavorites(sura: Int, ayah: Int) {
    data.addAyahBookmarkToCollection(favoritesId(), sura, ayah)
}

internal suspend fun SyncDevice.removeFromFavorites(sura: Int, ayah: Int): Boolean = removeFrom(favorites(), sura, ayah)

internal suspend fun SyncDevice.removeFromCollection(name: String, sura: Int, ayah: Int): Boolean =
    removeFrom(checkNotNull(collection(name)) { "No collection $name" }, sura, ayah)

private suspend fun SyncDevice.removeFrom(collection: CollectionWithAyahBookmarks, sura: Int, ayah: Int): Boolean {
    val bookmark = collection.bookmarks.single { it.sura == sura && it.ayah == ayah }
    return data.removeAyahBookmarkFromCollection(collection.collection.id, bookmark.bookmarkId)
}
// endregion

// region Server reads
/** A server session's verse, "sura:ayah". */
internal val ServerSession.verseKey: String get() = "$chapter:$verse"

/** The server's session verses, sorted. */
internal val ServerState.sessionVerses: List<String> get() = sessions.map { it.verseKey }.sorted()
// endregion

// region Request log
internal val RequestRecord.isPost: Boolean get() = method == "POST"

/** Mutations in this run's POSTs that the server accepted. */
internal val SyncRun.accepted: List<JsonObject> get() = requests.filter { it.isPost && it.status == 200 }.flatMap { it.mutations }

/** Every POST [device] made, in order. */
internal fun FakeSyncServer.posts(device: String): List<RequestRecord> = requests(device).filter { it.isPost }

/** Mutations in [device]'s POSTs that the server accepted. */
internal fun FakeSyncServer.accepted(device: String): List<JsonObject> = posts(device).filter { it.status == 200 }.flatMap { it.mutations }

internal val JsonObject.resource: String? get() = (this["resource"] as? JsonPrimitive)?.contentOrNull
internal val JsonObject.type: String? get() = (this["type"] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.data(key: String): String? = (this["data"] as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }

internal fun List<JsonObject>.of(resource: String, type: String? = null) = filter { it.resource == resource && (type == null || it.type == type) }

/** Every mutation this run's successful GET pages delivered, in page order. */
internal val SyncRun.received: List<JsonObject>
    get() = requests.filter { it.method == "GET" && it.status == 200 }.flatMap { get ->
        (Json.parseToJsonElement(checkNotNull(get.response)).jsonObject["data"]!!.jsonObject["mutations"] as JsonArray).map { it as JsonObject }
    }

/** The `lastMutationAt` a successful POST's response carried. */
internal fun RequestRecord.responseHead(): Long =
    Json.parseToJsonElement(checkNotNull(response)).jsonObject["data"]!!.jsonObject["lastMutationAt"]!!.jsonPrimitive.long
// endregion

// region Known-issue pins
/** Accepts a failure whose message contains every fragment. */
internal fun failureMentions(vararg fragments: String): (AssertionError) -> Boolean = { failure ->
    fragments.all { it in failure.message.orEmpty() }
}
// endregion
