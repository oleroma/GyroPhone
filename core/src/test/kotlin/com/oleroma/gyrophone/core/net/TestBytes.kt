package com.oleroma.gyrophone.core.net

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun ByteArray.hex(len: Int): String = copyOf(len).hex()

/** Deterministic "random" bytes for flow tests: 1, 2, 3, ... */
class CountingRandom(private var next: Int = 1) : (Int) -> ByteArray {
    override fun invoke(n: Int): ByteArray = ByteArray(n) { (next++).toByte() }
}
