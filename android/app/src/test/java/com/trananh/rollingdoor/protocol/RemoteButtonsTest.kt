package com.trananh.rollingdoor.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteButtonsTest {
    @Test
    fun parsesDefaultList() {
        val list = ButtonList.parse(hex("2a" + "010000" + "020100" + "030200" + "040300"))
        assertEquals(ButtonList.DEFAULT.buttons, list?.buttons)
        assertEquals(0x2a, list?.revision)
    }

    @Test
    fun parsesNamesInOrder() {
        // "Cổng" is 6 bytes of UTF-8.
        val value = hex("07" + "05" + "05" + "06" + "43e1bb956e67" + "02" + "01" + "00")
        val list = ButtonList.parse(value)
        assertEquals(
            listOf(RemoteButton(5, ButtonIcon.Gate, "Cổng"), RemoteButton(2, ButtonIcon.Down)),
            list?.buttons,
        )
    }

    @Test
    fun emptyListHasOnlyRevision() {
        assertEquals(ButtonList(3, emptyList()), ButtonList.parse(hex("03")))
    }

    @Test
    fun malformedListsAreRejected() {
        assertNull(ButtonList.parse(ByteArray(0)))
        assertNull(ButtonList.parse(hex("01" + "0100")))          // cut in the header
        assertNull(ButtonList.parse(hex("01" + "010003" + "41"))) // name shorter than its length
        assertNull(ButtonList.parse(hex("01" + "090000")))        // id 9
        assertNull(ButtonList.parse(hex("01" + "000000")))        // id 0
        assertNull(ButtonList.parse(hex("01" + "01ff00")))        // unknown icon
        assertNull(ButtonList.parse(hex("01" + "010000" + "010100"))) // same id twice
    }

    @Test
    fun setArgsCarryIdIconAndName() {
        assertArrayEquals(hex("0307" + "c490c3a86e"), ButtonList.setArgs(RemoteButton(3, ButtonIcon.Light, "Đèn")))
        assertArrayEquals(hex("0100"), ButtonList.setArgs(RemoteButton(1, ButtonIcon.Up)))
    }

    @Test
    fun setArgsFitTheCommandFrame() {
        val longest = RemoteButton(8, ButtonIcon.Bell, "a".repeat(ButtonList.MAX_NAME_BYTES))
        assertEquals(DoorProtocol.MAX_ARGS_LENGTH, ButtonList.setArgs(longest).size)
    }

    @Test
    fun fitNameCutsOnCharacterBoundaries() {
        // "ố" is 3 bytes: 30 ASCII bytes + one "ố" would be 33.
        val name = "a".repeat(30) + "ố"
        assertEquals("a".repeat(30), ButtonList.fitName(name))
        assertEquals("Cửa sau", ButtonList.fitName("Cửa sau"))
        assertEquals("Cửasau", ButtonList.fitName("Cửa\nsau"))
    }

    @Test
    fun freeIdIsTheLowestUnused() {
        val list = ButtonList(0, listOf(RemoteButton(1, ButtonIcon.Up), RemoteButton(3, ButtonIcon.Lock)))
        assertEquals(2, list.freeId())
        val full = ButtonList(0, (1..8).map { RemoteButton(it, ButtonIcon.Power) })
        assertNull(full.freeId())
    }

    @Test
    fun infoReportsLearnedButtonsAndRevision() {
        assertEquals(setOf(1, 4, 8), ButtonInfo.learned(hex("00ff8907")))
        assertEquals(7, ButtonInfo.revision(hex("00ff8907")))
        assertEquals(emptySet<Int>(), ButtonInfo.learned(hex("00ff00")))
        assertNull(ButtonInfo.revision(hex("00ff00")))
        assertNull(ButtonInfo.learned(hex("00ff")))
    }
}
