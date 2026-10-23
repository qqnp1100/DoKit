package com.didichuxing.doraemonkit.kit.filemanager.action.file

import io.ktor.http.Headers
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadFileActionTest {
    @Test
    fun disposesUnsupportedBinaryPartAndReportsMissingFile() = runBlocking {
        var disposed = false
        val binaryPart = PartData.BinaryItem(
            provider = { ByteReadPacket.Empty },
            dispose = { disposed = true },
            partHeaders = Headers.Empty
        )
        val multipart = object : MultiPartData {
            private var consumed = false

            override suspend fun readPart(): PartData? {
                if (consumed) return null
                consumed = true
                return binaryPart
            }
        }

        val response = UploadFileAction.uploadFileRes(multipart)

        assertTrue(disposed)
        assertEquals(false, response["success"])
        assertEquals("filePart is null", response["message"])
    }
}
