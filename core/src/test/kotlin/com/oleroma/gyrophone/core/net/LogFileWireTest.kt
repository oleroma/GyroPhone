package com.oleroma.gyrophone.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Diagnostics file fetch's vectors, shared with the Windows companion (protocol/v1/logfile-v1.json). */
class LogFileWireTest {
    private val v: JsonObject = run {
        val text = javaClass.getResourceAsStream("/protocol/v1/logfile-v1.json")!!.readBytes().toString(Charsets.UTF_8)
        Json.parseToJsonElement(text).jsonObject
    }
    private val sid = v["sessionId"]!!.jsonPrimitive.long.toInt()
    private val key = LogFileWire.logKey(hex(v.str("kPH")), sid)
    private val reqId = hex(v.str("reqId"))

    @Test fun keyRequestAndHeadMatchVectors() {
        assertEquals(v.str("logKey"), key.hex())
        val req = LogFileWire.encodeRequest(key, LogFileWire.Request(LogFileWire.KIND_NEWEST, v["maxAgeS"]!!.jsonPrimitive.int, sid, reqId))
        assertEquals(v.str("request"), req.hex())
        assertEquals(sid, LogFileWire.requestSessionId(req))
        val back = LogFileWire.decodeRequest(key, req)!!
        assertEquals(30, back.maxAgeS)
        assertArrayEquals(reqId, back.reqId)
        assertEquals(v.str("head"), LogFileWire.head(LogFileWire.OK).hex())
        assertEquals(LogFileWire.OK, LogFileWire.headStatus(hex(v.str("head"))))
    }

    @Test fun framesMatchVectors() {
        val meta = LogFileWire.meta(v["metaRaw"]!!.jsonPrimitive.int, v["metaZip"]!!.jsonPrimitive.int, v.str("metaName"))
        assertEquals(v.str("meta"), meta.hex())
        assertEquals(v.str("frame0"), LogFileWire.sealFrame(key, reqId, 0, meta).hex())
        assertEquals(v.str("frame1"), LogFileWire.sealFrame(key, reqId, 1, hex(v.str("data"))).hex())
        assertEquals(v.str("frame2"), LogFileWire.sealFrame(key, reqId, 2, ByteArray(0)).hex())
        val opened = LogFileWire.openFrame(key, reqId, 0, hex(v.str("frame0")).copyOfRange(4, hex(v.str("frame0")).size))!!
        val m = LogFileWire.decodeMeta(opened)!!
        assertEquals("phone-20261121-211736.txt", m.name)
        assertEquals(5L, m.rawSize)
    }

    @Test fun aWrongKeyOrIndexIsRefused() {
        val req = LogFileWire.encodeRequest(key, LogFileWire.Request(LogFileWire.KIND_NEWEST, 30, sid, reqId))
        assertNull(LogFileWire.decodeRequest(ByteArray(16), req))
        req[20] = 1
        assertNull(LogFileWire.decodeRequest(key, req))
        val f1 = hex(v.str("frame1"))
        assertNull(LogFileWire.openFrame(key, reqId, 2, f1.copyOfRange(4, f1.size)))
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
}
