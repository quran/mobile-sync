package com.quran.shared.syncv2.types

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.syncv2.Field
import com.quran.shared.syncv2.MergeItem
import com.quran.shared.syncv2.Merger
import com.quran.shared.syncv2.UniquenessRepair
import com.quran.shared.syncv2.get
import com.quran.shared.syncv2.inMemoryDatabase
import com.quran.shared.syncv2.stateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * Two remote collections whose names match once trimmed and lowercased, neither renamed here, leave only a suffix.
     * A collection's name is user text that must not reach the logs, so the repair names the item by its ids.
     */
    @Test
    fun `disambiguating a clash logs the ids and not the names`() {
        val newer = MergeItem(1, "r1", collection("Zq9x study", 10), collection("Zq9x study", 10), null, collection("Zq9x study", 10))
        val older = MergeItem(2, "r2", collection("Zq9x STUDY", 5), collection("Zq9x STUDY", 5), null, collection("Zq9x STUDY", 5))

        val lines = captureLogs { UniquenessRepair(type, Merger(type)) { _, _, _ -> }.repair(listOf(newer, older)) }

        assertEquals("Zq9x study", newer.working?.get(Field.string("name")), "the newer collection keeps its name")
        assertTrue(lines.any { "disambiguating" in it && "remoteId=r2 localId=2:" in it && "suffix 2" in it }, lines.toString())
        assertEquals(emptyList(), lines.filter { it.contains("zq9x", ignoreCase = true) })
    }

    private fun captureLogs(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val writer = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                lines += message
            }
        }
        val previousWriters = Logger.config.logWriterList
        val previousMinSeverity = Logger.config.minSeverity
        Logger.setMinSeverity(Severity.Verbose)
        Logger.addLogWriter(writer)
        try {
            block()
        } finally {
            try {
                Logger.setLogWriters(previousWriters)
            } finally {
                Logger.setMinSeverity(previousMinSeverity)
            }
        }
        return lines
    }
}
