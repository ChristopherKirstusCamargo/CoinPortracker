package com.coinportracker.core

import org.junit.Assert.*
import org.junit.Test

class BoundedReadTest {
    @Test fun readsUnderLimit() { assertEquals("abc", BoundedRead.text("abc".byteInputStream(), 3)) }
    @Test fun failsOverLimit() { assertThrows(TooLargeException::class.java) { BoundedRead.bytes(ByteArray(2_000_000).inputStream(), 1_000_000) } }
}
