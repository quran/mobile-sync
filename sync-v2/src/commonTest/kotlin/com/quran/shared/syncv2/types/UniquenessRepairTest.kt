package com.quran.shared.syncv2.types

import com.quran.shared.syncv2.Field
import com.quran.shared.syncv2.MergeItem
import com.quran.shared.syncv2.Merger
import com.quran.shared.syncv2.UniquenessRepair
import com.quran.shared.syncv2.inMemoryDatabase
import com.quran.shared.syncv2.stateOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** Uniqueness repair on its own, with the real collection type: it must always converge, never throw. */
class UniquenessRepairTest {
    private val type = CollectionsType(inMemoryDatabase())

    private fun collection(name: String, at: Long) =
        stateOf(Field.string("name")(name), Field.bool("system")(false), Field.long("updatedAt")(at))

    /**
     * Ten collections shifted up one name locally (n9 -> n10, ..., n0 -> n1) while another device created "n10".
     * Each revert uncovers the next clash, one per pass, which is more than the passes allowed.
     */
    @Test
    fun `a revert chain longer than the passes falls back to disambiguating`() {
        val items = (0 until 10).map { i ->
            MergeItem(i.toLong(), "c$i", collection("n$i", 0), collection("n${i + 1}", 10L + i), null, collection("n${i + 1}", 10L + i))
        } + MergeItem(null, "r", collection("n10", 5), collection("n10", 5), null, null)

        UniquenessRepair(type, Merger(type)) { _, _, _ -> }.repair(items)

        val names = items.mapNotNull { it.working?.let(type::naturalKey) }
        assertEquals(names.size, names.toSet().size, "no clash is left: $names")
        assertEquals(11, names.size, "nothing is dropped")
        assertEquals("n10", items.last().working?.let(type::naturalKey), "the remote collection keeps its name")
    }
}
