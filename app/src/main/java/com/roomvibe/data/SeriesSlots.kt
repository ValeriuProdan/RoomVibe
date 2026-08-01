package com.roomvibe.data

/**
 * Assigns each device a stable colour slot for the compare chart.
 *
 * The point is stability: a device's colour must not change because another
 * device was added, renamed (the sensor list is sorted by name), hidden, or
 * because the user unchecked something. So slots are allocated once, per device
 * address, and only released when that device is deleted.
 */
object SeriesSlots {

    /**
     * Returns the slot map for [addresses], keeping every assignment already in
     * [existing] and giving each new address the lowest slot nobody holds.
     * Addresses that are gone drop out, freeing their slot for the next device.
     */
    fun assign(existing: Map<String, Int>, addresses: List<String>): Map<String, Int> {
        val present = addresses.toSet()
        val kept = existing.filterKeys { it in present }
        val taken = kept.values.toMutableSet()
        val out = LinkedHashMap(kept)
        // Allocate in a stable order so the result doesn't depend on how the
        // caller happened to sort the sensor list.
        for (address in addresses.sorted()) {
            if (address in out) continue
            var slot = 0
            while (slot in taken) slot++
            taken.add(slot)
            out[address] = slot
        }
        return out
    }

    // ── Persistence ──────────────────────────────────────────────────────────
    // Stored as "AA:BB:CC=0|DD:EE:FF=3". MAC addresses contain ':' but never
    // '=' or '|', so no escaping is needed.

    fun encode(slots: Map<String, Int>): String =
        slots.entries.joinToString("|") { "${it.key}=${it.value}" }

    fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (entry in raw.split('|')) {
            val sep = entry.lastIndexOf('=')
            if (sep <= 0) continue
            val slot = entry.substring(sep + 1).toIntOrNull() ?: continue
            out[entry.substring(0, sep)] = slot
        }
        return out
    }
}
