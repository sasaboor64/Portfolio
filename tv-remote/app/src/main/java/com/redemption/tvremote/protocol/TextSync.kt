package com.redemption.tvremote.protocol

/**
 * Keeps a TV text field in step with a text box on the phone, as the user types.
 *
 * TVs differ in how they apply an inserted string: most insert it at the cursor, but some
 * apps replace the whole field. This sends the smallest edit (deletes + insert), then uses
 * the field value the TV reports back to confirm it, switch to "replace" mode, or
 * re-synchronise. Without field reports from the TV, it simply types and deletes with keys.
 */
class TextSync(private val out: Output) {

    interface Output {
        /** True when the TV shares its text field, so [insert] can be used. */
        val structured: Boolean
        fun insert(text: String)
        fun deleteBack(count: Int)
        fun typeKeys(text: String)
    }

    /** What we believe the TV field contains. */
    var synced: String = ""
        private set

    /** The text the user wants on the TV. */
    private var desired: String = ""

    /** True once this field is known to replace its contents instead of inserting. */
    var replaceMode = false
        private set

    /** Field states we expect the TV to report, in order, for edits already sent. */
    private val expected = ArrayDeque<String>()
    private var echoesSeen = false
    private var corrections = 0

    /** A new field was focused on the TV, holding [tvValue]. */
    fun reset(tvValue: String) {
        synced = tvValue; desired = tvValue
        replaceMode = false; expected.clear(); echoesSeen = false; corrections = 0
    }

    /**
     * The user changed the phone text box. Returns true if edits are in flight and the
     * caller should call [onTimeout] shortly in case the TV never answers.
     */
    fun onUserText(text: String): Boolean {
        desired = text
        corrections = 0
        return flush()
    }

    /** The TV reported its field value (after an edit, or on its own). */
    fun onTvValue(value: String): Boolean {
        echoesSeen = true
        val idx = expected.indexOf(value)
        if (idx >= 0) {
            repeat(idx + 1) { expected.removeFirst() }
            synced = value
            return if (expected.isEmpty()) flush() else true
        }
        // Not a state we predicted. If the TV holds only the last inserted chunk, the field
        // replaces instead of inserting; otherwise adopt the TV's value and re-sync from it.
        val lastInsert = lastInserted
        if (!replaceMode && lastInsert != null && value == lastInsert && value != expected.lastOrNull()) {
            replaceMode = true
        }
        expected.clear()
        synced = value
        if (++corrections > MAX_CORRECTIONS) { desired = value; return false }
        return flush()
    }

    /** No answer from the TV: assume the edits landed and continue. */
    fun onTimeout(): Boolean {
        if (expected.isNotEmpty()) {
            synced = expected.last()
            expected.clear()
        }
        return flush()
    }

    private var lastInserted: String? = null

    private fun flush(): Boolean {
        // While the TV is still answering earlier edits, wait so counters stay current.
        if (expected.isNotEmpty() && echoesSeen) return true
        expected.clear()
        if (desired == synced) return false

        if (!out.structured) {
            val p = commonPrefix(synced, desired)
            if (synced.length > p) out.deleteBack(synced.length - p)
            if (desired.length > p) out.typeKeys(desired.substring(p))
            synced = desired
            return false
        }

        if (replaceMode) {
            if (desired.isEmpty()) out.deleteBack(synced.length) else out.insert(desired)
            lastInserted = desired
            expected.addLast(desired)
            return true
        }

        val p = commonPrefix(synced, desired)
        val deletes = synced.length - p
        if (deletes > 0) {
            out.deleteBack(deletes)
            for (i in 1..deletes) expected.addLast(synced.substring(0, synced.length - i))
        }
        val add = desired.substring(p)
        if (add.isNotEmpty()) {
            out.insert(add)
            lastInserted = add
            expected.addLast(desired)
        }
        return true
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private companion object { const val MAX_CORRECTIONS = 3 }
}
