package com.quran.shared.pipeline

/**
 * A local item that does not sync, as [QuranDataService.heldItems] lists it: held because the server would refuse it
 * (or did), or waiting on a held item. It stays on the device and syncs again once the user edits it, the hold
 * expires, or the app is updated.
 *
 * It never carries user data or server messages: only the item's id, and for [HeldReason.REJECTED_BY_SERVER] the
 * HTTP status and the server's error code.
 *
 * @param type the kind of item.
 * @param id the item's id as the public models carry it: [com.quran.shared.persistence.model.Note.id],
 *   [com.quran.shared.persistence.model.Collection.id], [com.quran.shared.persistence.model.ReadingSession.id],
 *   [com.quran.shared.persistence.model.ReadingBookmark.id], or for a [SyncResourceType.COLLECTION_BOOKMARK] the
 *   [com.quran.shared.persistence.model.CollectionAyahBookmark.bookmarkId] (`sura:ayah`). An item held while it was
 *   being deleted is no longer in the public models.
 * @param collectionId for a [SyncResourceType.COLLECTION_BOOKMARK], the id of its collection (for a highlight, a
 *   hidden highlight collection); otherwise `null`.
 * @param reason why it does not sync.
 * @param waitingOn for [HeldReason.WAITING_ON_PARENT] and [HeldReason.WAITING_ON_OTHER_ITEM], the held item it waits
 *   on; otherwise `null`.
 * @param httpStatus for [HeldReason.REJECTED_BY_SERVER], the HTTP status the server refused it with.
 * @param serverCode for [HeldReason.REJECTED_BY_SERVER], the server's error code, such as `UniqueViolationError`.
 */
data class HeldSyncItem(
    val type: SyncResourceType,
    val id: String,
    val collectionId: String?,
    val reason: HeldReason,
    val waitingOn: HeldSyncItem?,
    val httpStatus: Int?,
    val serverCode: String?
)

/** The kinds of synced items. */
enum class SyncResourceType {
    NOTE,
    COLLECTION,

    /** An ayah bookmark in a collection: Favorites, a user collection, or a highlight. */
    COLLECTION_BOOKMARK,
    READING_SESSION,
    READING_BOOKMARK
}

/** Why a [HeldSyncItem] does not sync. */
enum class HeldReason {
    /** The text (a note body or collection name) is shorter than the server accepts. */
    TOO_SHORT,

    /** The text (a note body, collection name, or reading bookmark name) is longer than the server accepts. */
    TOO_LONG,

    /** A note's verse range doesn't exist or runs backwards. */
    INVALID_RANGE,

    /** The verse doesn't exist. */
    INVALID_VERSE,

    /** A reading bookmark's location doesn't exist. */
    INVALID_LOCATION,

    /** A reading bookmark's slot isn't one the server knows. */
    INVALID_SLOT,

    /** A reading bookmark carries more metadata than the server accepts. */
    TOO_MUCH_METADATA,

    /** A collection name is reserved by the server. */
    RESERVED_NAME,

    /** The collection can't be renamed or deleted. */
    READ_ONLY,

    /**
     * The server refused it ([HeldSyncItem.httpStatus], [HeldSyncItem.serverCode]). Also reported, with neither, for
     * a reason this library version doesn't know.
     */
    REJECTED_BY_SERVER,

    /** Its collection is held and the server never received it, so it waits for the collection. */
    WAITING_ON_PARENT,

    /** It takes a name (or slot) that a held item still has on the server, so it waits for that item. */
    WAITING_ON_OTHER_ITEM,

    /** Only a sync can link it to the server's copy (Favorites), and none has brought that copy yet. */
    NOT_YET_LINKED
}
