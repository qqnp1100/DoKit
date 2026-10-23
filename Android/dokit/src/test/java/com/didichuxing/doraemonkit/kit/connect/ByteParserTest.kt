package com.didichuxing.doraemonkit.kit.connect

import com.didichuxing.doraemonkit.kit.connect.data.BytePackage
import com.didichuxing.doraemonkit.kit.connect.data.PackageType
import com.didichuxing.doraemonkit.kit.connect.data.TextPackage
import com.didichuxing.doraemonkit.kit.connect.parser.ByteParser
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class ByteParserTest {
    private fun textPackage() = TextPackage(
        pid = "packet-fixture",
        type = PackageType.AUTOTEST,
        data = """{"caseId":"fixture-case","image":"截图.png","type":"jpeg"}""",
        connectSerial = "fixture-connection",
        contentType = "image"
    )

    @Test fun roundTripPreservesHeaderAndBinaryPayload() {
        val payload = ByteArray(256) { it.toByte() }
        val header = textPackage()
        val encoded = ByteParser.toByteString(header, payload)
        val buffer = encoded.asByteBuffer()
        val magic = ByteArray(5).also(buffer::get)
        val headerLength = buffer.int
        val payloadLength = buffer.long
        assertArrayEquals("DoKit".toByteArray(Charsets.US_ASCII), magic)
        assertTrue(headerLength > 0)
        assertEquals(payload.size.toLong(), payloadLength)
        assertEquals(17 + headerLength + payload.size, encoded.size)

        val decoded = ByteParser.parse(encoded)
        assertEquals(headerLength, decoded.headLength)
        assertEquals(payloadLength, decoded.dataLength)
        assertEquals(header, decoded.textPackage)
        assertArrayEquals(payload, decoded.data)
    }

    @Test fun emptyBinaryPayloadRoundTrips() {
        val header = textPackage()
        val encoded = ByteParser.toByteString(BytePackage(textPackage = header, data = byteArrayOf()))
        val decoded = ByteParser.parse(encoded)
        assertEquals(header, decoded.textPackage)
        assertEquals(0L, decoded.dataLength)
        assertArrayEquals(byteArrayOf(), decoded.data)
        assertEquals(17 + decoded.headLength, encoded.size)
    }

    fun getByteString(): ByteString = ByteParser.toByteString(textPackage(), "binary fixture".toByteArray())
}
