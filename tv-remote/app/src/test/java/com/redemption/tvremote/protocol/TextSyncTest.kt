package com.redemption.tvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSyncTest {

    /** A TV text field with the cursor at the end. Echoes are delivered when [deliver] runs. */
    private class FakeField(
        var replaces: Boolean = false,
        val echoes: Boolean = true,
        var structured0: Boolean = true,
    ) : TextSync.Output {
        var value = ""
        val log = mutableListOf<String>()
        val pendingEchoes = ArrayDeque<String>()
        override val structured get() = structured0
        override fun insert(text: String) {
            log += "insert:$text"
            value = if (replaces) text else value + text
            if (echoes) pendingEchoes.addLast(value)
        }
        override fun deleteBack(count: Int) {
            log += "del:$count"
            repeat(count) {
                value = value.dropLast(1)
                if (echoes) pendingEchoes.addLast(value)
            }
        }
        override fun typeKeys(text: String) { log += "keys:$text"; value += text }
    }

    private fun deliver(f: FakeField, sync: TextSync) {
        var guard = 0
        while (f.pendingEchoes.isNotEmpty() && guard++ < 100) sync.onTvValue(f.pendingEchoes.removeFirst())
    }

    /** Types [text] the way a keyboard does: one character at a time. */
    private fun typeLive(sync: TextSync, f: FakeField, start: String, text: String, echoEach: Boolean = true) {
        var cur = start
        for (ch in text) {
            cur += ch
            sync.onUserText(cur)
            if (echoEach) deliver(f, sync)
        }
    }

    @Test fun insertFieldTypesLiveWithSmallEdits() {
        val f = FakeField()
        val sync = TextSync(f).apply { reset("") }
        typeLive(sync, f, "", "stranger")
        assertEquals("stranger", f.value)
        assertEquals(8, f.log.count { it.startsWith("insert:") })
        assertTrue(f.log.all { it.length == "insert:x".length })
    }

    @Test fun backspaceAndEditInTheMiddle() {
        val f = FakeField()
        val sync = TextSync(f).apply { reset("") }
        typeLive(sync, f, "", "hello")
        sync.onUserText("hell"); deliver(f, sync)
        assertEquals("hell", f.value)
        sync.onUserText("help me"); deliver(f, sync)
        assertEquals("help me", f.value)
        sync.onUserText(""); deliver(f, sync)
        assertEquals("", f.value)
    }

    @Test fun keepsExistingTextTheTvAlreadyHad() {
        val f = FakeField().apply { value = "stranger" }
        val sync = TextSync(f).apply { reset("stranger") }
        typeLive(sync, f, "stranger", " things")
        assertEquals("stranger things", f.value) // not "strangerstranger things" (androidtvremote2 #51)
    }

    @Test fun detectsFieldsThatReplaceAndFixesThem() {
        val f = FakeField(replaces = true)
        val sync = TextSync(f).apply { reset("") }
        typeLive(sync, f, "", "spotify")
        assertEquals("spotify", f.value)
        assertTrue(sync.replaceMode)
    }

    @Test fun fastTypingIsBatchedWhileTheTvCatchesUp() {
        val f = FakeField()
        val sync = TextSync(f).apply { reset("") }
        sync.onUserText("a"); deliver(f, sync)  // TV shown to echo
        typeLive(sync, f, "a", "bcdef", echoEach = false)
        deliver(f, sync)
        deliver(f, sync)
        assertEquals("abcdef", f.value)
        assertTrue("later keystrokes are merged into one insert", f.log.size < 6)
    }

    @Test fun worksWhenTheTvNeverAnswers() {
        val f = FakeField(echoes = false)
        val sync = TextSync(f).apply { reset("") }
        var cur = ""
        for (ch in "netflix") { cur += ch; if (sync.onUserText(cur)) sync.onTimeout() }
        sync.onUserText("net"); sync.onTimeout()
        assertEquals("net", f.value)
    }

    @Test fun appsWithoutFieldInfoGetKeyPresses() {
        val f = FakeField(structured0 = false)
        val sync = TextSync(f).apply { reset("") }
        sync.onUserText("cat"); sync.onUserText("ca"); sync.onUserText("car")
        assertEquals(listOf("keys:cat", "del:1", "del:1", "keys:r"), f.log.let { listOf(it[0], it[1], it[1], it.last()) })
        assertEquals("car", f.value)
    }
}
