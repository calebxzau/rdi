package calebxzhou.rdi.master.service

import calebxzau.rdi.server.service.upload.ChunkedUploadService
import calebxzhou.rdi.common.exception.ChunkedUploadErrorCodes
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzhou.rdi.common.model.ModpackUploadSessionVo
import calebxzhou.rdi.common.util.sha1
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModpackParallelUploadServiceTest {
    @Test
    fun `parts can upload out of order and completed file is consumed once`() = runTest {
        val root = createTempDirectory("modpack-parallel-upload-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = "parallel-upload".encodeToByteArray()
            val service = service(root)
            val session = service.create(
                ownerId,
                ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            ).getOrThrow()

            assertEquals(7, session.id.version())
            assertEquals(4, session.partSize)
            assertEquals(4, session.partCount)
            assertEquals(8, session.maxParallelParts)

            listOf(3, 1, 0, 2).forEach { index ->
                val part = content.part(index, session.partSize)
                service.uploadPart(
                    ownerId = ownerId,
                    id = session.id,
                    index = index,
                    declaredLength = part.size.toLong(),
                    expectedSha1 = part.sha1,
                    source = ByteReadChannel(part)
                ).getOrThrow()
            }

            val completed = service.complete(ownerId, session.id).getOrThrow()
            assertTrue(completed.ready)
            assertEquals(completed, service.complete(ownerId, session.id).getOrThrow())
            val consumed = service.withReadyUpload(ownerId, session.id) { it.readBytes() }.getOrThrow()
            assertContentEquals(content, consumed)
            assertTrue(service.status(ownerId, session.id).isFailure)
            assertFalse(root.resolve(session.id.toString()).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `complete rejects missing parts before checking the whole file`() = runTest {
        val root = createTempDirectory("modpack-parallel-missing-part-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = "missing-part".encodeToByteArray()
            val service = service(root)
            val session = service.create(
                ownerId,
                ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            ).getOrThrow()

            val firstPart = content.part(0, session.partSize)
            service.uploadPart(
                ownerId,
                session.id,
                0,
                firstPart.size.toLong(),
                firstPart.sha1,
                ByteReadChannel(firstPart)
            ).getOrThrow()

            assertTrue(service.complete(ownerId, session.id).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancel rejects a ready upload while it is being consumed`() = runTest {
        val root = createTempDirectory("modpack-parallel-finalizing-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = "finalizing-upload".encodeToByteArray()
            val service = service(root)
            val session = service.create(
                ownerId,
                ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            ).getOrThrow()
            (0 until session.partCount).forEach { index ->
                val part = content.part(index, session.partSize)
                service.uploadPart(
                    ownerId,
                    session.id,
                    index,
                    part.size.toLong(),
                    part.sha1,
                    ByteReadChannel(part)
                ).getOrThrow()
            }
            service.complete(ownerId, session.id).getOrThrow()

            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val consuming = async {
                service.withReadyUpload(ownerId, session.id) {
                    started.complete(Unit)
                    release.await()
                    it.readBytes()
                }.getOrThrow()
            }
            started.await()

            assertTrue(service.cancel(ownerId, session.id).isFailure)
            release.complete(Unit)
            assertEquals(content.toList(), consuming.await().toList())
            assertTrue(service.status(ownerId, session.id).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `matching create resumes uploaded parts and rejects wrong sha1`() = runTest {
        val root = createTempDirectory("modpack-parallel-resume-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = "resume-me".encodeToByteArray()
            val dto = ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            val service = service(root)
            val first = service.create(ownerId, dto).getOrThrow()
            val part = content.part(0, first.partSize)

            val wrongHashResult = service.uploadPart(
                ownerId,
                first.id,
                0,
                part.size.toLong(),
                ByteArray(20).sha1,
                ByteReadChannel(part)
            )
            assertTrue(wrongHashResult.isFailure)
            service.uploadPart(
                ownerId,
                first.id,
                0,
                part.size.toLong(),
                part.sha1,
                ByteReadChannel(part)
            ).getOrThrow()

            val resumedService = service(root)
            val resumed = resumedService.create(ownerId, dto).getOrThrow()
            assertEquals(first.id, resumed.id)
            assertEquals(listOf(0), resumed.uploadedParts)
            assertTrue(resumedService.status(ObjectId(), first.id).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `parallel receives are capped and cancellation releases a slot`() = runBlocking {
        val root = createTempDirectory("modpack-parallel-cap-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = ByteArray(9) { it.toByte() }
            val service = service(root, partSize = 1)
            val session = service.create(
                ownerId,
                ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            ).getOrThrow()
            val channels = List(8) { ByteChannel(autoFlush = true) }
            val receiving = List(8) { CompletableDeferred<Unit>() }
            val uploads = channels.indices.map { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    service.uploadPart(
                        ownerId,
                        session.id,
                        index,
                        1,
                        byteArrayOf(content[index]).sha1,
                        blockedChannel(channels[index], receiving[index]),
                    )
                }
            }
            receiving.forEach { withTimeout(5_000) { it.await() } }
            val ninth = service.uploadPart(
                ownerId,
                session.id,
                8,
                1,
                byteArrayOf(content[8]).sha1,
                ByteReadChannel(byteArrayOf(content[8])),
            ).exceptionOrNull()
            assertIs<RequestError>(ninth)
            assertEquals(ChunkedUploadErrorCodes.PART_RETRYABLE, ninth.errorCode)

            uploads[0].cancel()
            uploads[0].join()
            channels[0].close()
            assertTrue(
                service.uploadPart(
                    ownerId,
                    session.id,
                    8,
                    1,
                    byteArrayOf(content[8]).sha1,
                    ByteReadChannel(byteArrayOf(content[8])),
                ).isSuccess,
            )
            channels.drop(1).forEachIndexed { offset, channel ->
                val index = offset + 1
                channel.writeFully(byteArrayOf(content[index]))
                channel.close()
            }
            assertTrue(uploads.drop(1).all { it.await().isSuccess })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retried part takes over a stalled request which can no longer commit`() = runBlocking {
        val root = createTempDirectory("modpack-parallel-takeover-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = byteArrayOf(1, 2)
            val service = service(root, partSize = 1)
            val session = service.create(
                ownerId,
                ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            ).getOrThrow()
            val stalledChannel = ByteChannel(autoFlush = true)
            val receiving = CompletableDeferred<Unit>()
            val stalled = async(start = CoroutineStart.UNDISPATCHED) {
                service.uploadPart(
                    ownerId,
                    session.id,
                    0,
                    1,
                    byteArrayOf(content[0]).sha1,
                    blockedChannel(stalledChannel, receiving),
                )
            }
            withTimeout(5_000) { receiving.await() }

            service.uploadPart(ownerId, session.id, 0, 1, byteArrayOf(content[0]).sha1, ByteReadChannel(byteArrayOf(content[0])))
                .getOrThrow()
            stalledChannel.writeFully(byteArrayOf(content[0]))
            stalledChannel.close()
            assertEquals("该分片已由新的请求接管", stalled.await().exceptionOrNull()?.message)

            service.uploadPart(ownerId, session.id, 1, 1, byteArrayOf(content[1]).sha1, ByteReadChannel(byteArrayOf(content[1])))
                .getOrThrow()
            assertTrue(service.complete(ownerId, session.id).getOrThrow().ready)
            assertContentEquals(content, session.dir(root).resolve("upload.data").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stalled receive times out as retryable and releases the part`() = runBlocking {
        val root = createTempDirectory("modpack-parallel-idle-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = byteArrayOf(1)
            val service = ModpackParallelUploadService(
                sessionsDir = root,
                maxFileSize = 1024,
                clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
                partSize = 1,
                chunkedUploadService = ChunkedUploadService(idleTimeoutMillis = 100),
            )
            val session = service.create(
                ownerId,
                ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            ).getOrThrow()

            val failure = withTimeout(5_000) {
                service.uploadPart(ownerId, session.id, 0, 1, content.sha1, ByteChannel(autoFlush = true))
            }.exceptionOrNull()
            assertIs<RequestError>(failure)
            assertEquals(ChunkedUploadErrorCodes.PART_RETRYABLE, failure.errorCode)
            assertEquals("分片数据接收超时", failure.message)

            service.cancel(ownerId, session.id).getOrThrow()
            assertFalse(session.dir(root).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid part never overwrites an acknowledged part`() = runTest {
        val root = createTempDirectory("modpack-parallel-invalid-part-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = "stable-content".encodeToByteArray()
            val service = service(root)
            val session = service.create(
                ownerId,
                ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            ).getOrThrow()
            val part = content.part(0, session.partSize)
            service.uploadPart(ownerId, session.id, 0, part.size.toLong(), part.sha1, ByteReadChannel(part)).getOrThrow()
            val acceptedBefore = session.dir(root).resolve("upload.data").readBytes()
            assertTrue(
                service.uploadPart(
                    ownerId,
                    session.id,
                    0,
                    part.size.toLong(),
                    ByteArray(20).sha1,
                    ByteReadChannel(ByteArray(part.size) { 7 }),
                ).isFailure,
            )
            assertContentEquals(acceptedBefore, session.dir(root).resolve("upload.data").readBytes())
            assertEquals(listOf(0), service.status(ownerId, session.id).getOrThrow().uploadedParts)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `startup removes only scoped part upload temporaries`() = runTest {
        val root = createTempDirectory("modpack-parallel-orphan-test").toFile()
        try {
            val ownerId = ObjectId()
            val content = "orphan-test".encodeToByteArray()
            val first = service(root)
            val session = first.create(
                ownerId,
                ModpackUploadSessionCreateDto("pack.zip", content.size.toLong(), content.sha1)
            ).getOrThrow()
            val orphan = session.dir(root).resolve(".part-upload-orphan.tmp").apply { writeText("stale") }
            val sentinel = session.dir(root).resolve("keep.me").apply { writeText("keep") }
            service(root)
            assertTrue(!orphan.exists())
            assertTrue(sentinel.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun service(root: java.io.File, partSize: Int = 4) = ModpackParallelUploadService(
        sessionsDir = root,
        maxFileSize = 1024,
        clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
        partSize = partSize
    )

    private fun blockedChannel(
        channel: ByteChannel,
        receiving: CompletableDeferred<Unit>,
    ): io.ktor.utils.io.ByteReadChannel = object : io.ktor.utils.io.ByteReadChannel by channel {
        override suspend fun awaitContent(min: Int): Boolean {
            receiving.complete(Unit)
            return channel.awaitContent(min)
        }
    }

    private fun ModpackUploadSessionVo.dir(root: java.io.File): java.io.File =
        root.resolve(id.toString())

    private fun ByteArray.part(index: Int, partSize: Int): ByteArray {
        val start = index * partSize
        return copyOfRange(start, minOf(start + partSize, size))
    }

}
