package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.master.service.ModpackService.isMcVer
import calebxzhou.rdi.master.service.ModpackService.requireAuthor
import calebxzhou.rdi.master.service.ModpackService.requireCanUploadVersion
import calebxzhou.rdi.master.service.ModpackService.requireCanManageVersion
import calebxzhou.rdi.master.service.modpack.ModpackQueryService.toDetailVo
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.canUploadVersion
import calebxzhou.rdi.master.service.ModpackService.validateVerName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.mongodb.MongoClientSettings
import org.bson.BsonDocument
import org.bson.BsonNull
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecRegistries.fromProviders
import org.bson.codecs.configuration.CodecRegistries.fromRegistries
import org.bson.codecs.pojo.PojoCodecProvider
import org.bson.BsonDocumentReader
import org.bson.BsonDocumentWriter
import org.bson.types.ObjectId
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject

class ModpackServicePureTest {
    @Test
    fun `version names accept two to four segments and trim surrounding whitespace`() {
        assertEquals("1.21", "  1.21  ".validate())
        assertEquals("release_1.2-beta.3", "release_1.2-beta.3".validate())
        assertEquals("v1.2.3.4", "v1.2.3.4".validate())
    }

    @Test
    fun `version names reject invalid segment counts and characters`() {
        listOf("", "   ", "1", "1.2.3.4.5", ".1", "1.", "1..2", "1. 2", "1/2", "测试.1", "1.2+beta")
            .forEach { name -> assertFailsWith<RequestError> { name.validate() } }
    }

    @Test
    fun `batch ids are distinct and preserve requested order`() {
        val a = org.bson.types.ObjectId()
        val b = org.bson.types.ObjectId()
        assertEquals(listOf(a, b), ModpackService.normalizeInfoBatchIds(listOf(a, b, a)))
        assertFailsWith<Exception> {
            ModpackService.normalizeInfoBatchIds(List(101) { org.bson.types.ObjectId() })
        }
        val first = ModpackServiceTestFixtures.modpack(a, "first")
        val second = ModpackServiceTestFixtures.modpack(b, "second")
        assertEquals(
            listOf(second._id, first._id),
            ModpackService.orderModpacksByIds(
                listOf(second._id, org.bson.types.ObjectId(), first._id),
                listOf(first, second)
            ).map { it._id }
        )
    }

    @Test
    fun `author guard allows owner and dav but rejects other player`() {
        val owner = ModpackServiceTestFixtures.account("owner")
        val other = ModpackServiceTestFixtures.account("other")
        val pack = ModpackServiceTestFixtures.modpack(owner._id)
        val context = ModpackContext(owner, pack, null)
        assertEquals(context, context.requireAuthor())
        assertFailsWith<RequestError> { ModpackContext(other, pack, null).requireAuthor() }
        val dav = other.copy(name = "davickk")
        assertEquals(pack, ModpackContext(dav, pack, null).requireAuthor().modpack)
    }

    @Test
    fun `version upload guard follows allow uploader states while preserving author and dav access`() {
        val owner = ModpackServiceTestFixtures.account("owner")
        val listed = ModpackServiceTestFixtures.account("listed")
        val other = ModpackServiceTestFixtures.account("other")
        val dav = other.copy(name = "davickk")

        assertEquals(owner, ModpackContext(owner, ModpackServiceTestFixtures.modpack(owner._id), null)
            .requireCanUploadVersion().player)
        assertEquals(listed, ModpackContext(listed, ModpackServiceTestFixtures.modpack(owner._id), null)
            .requireCanUploadVersion().player)
        assertEquals(owner, ModpackContext(
            owner,
            ModpackServiceTestFixtures.modpack(owner._id, allowUploaderIds = listOf(listed._id)),
            null,
        ).requireCanUploadVersion().player)
        assertEquals(owner, ModpackContext(
            owner,
            ModpackServiceTestFixtures.modpack(owner._id, allowUploaderIds = emptyList()),
            null,
        ).requireCanUploadVersion().player)
        assertFailsWith<RequestError> {
            ModpackContext(other, ModpackServiceTestFixtures.modpack(owner._id, allowUploaderIds = emptyList()), null)
                .requireCanUploadVersion()
        }
        assertEquals(listed, ModpackContext(
            listed,
            ModpackServiceTestFixtures.modpack(owner._id, allowUploaderIds = listOf(listed._id)),
            null,
        ).requireCanUploadVersion().player)
        assertFailsWith<RequestError> {
            ModpackContext(other, ModpackServiceTestFixtures.modpack(owner._id, allowUploaderIds = listOf(listed._id)), null)
                .requireCanUploadVersion()
        }
        assertEquals(dav, ModpackContext(
            dav,
            ModpackServiceTestFixtures.modpack(owner._id, allowUploaderIds = emptyList()),
            null,
        ).requireCanUploadVersion().player)
    }

    @Test
    fun `version management guard is limited to exact uploader author and dav`() {
        val author = ModpackServiceTestFixtures.account("author")
        val uploader = ModpackServiceTestFixtures.account("uploader")
        val listedButDifferent = ModpackServiceTestFixtures.account("listed")
        val otherVersionUploader = ModpackServiceTestFixtures.account("other-version")
        val pack = ModpackServiceTestFixtures.modpack(
            author._id,
            allowUploaderIds = listOf(listedButDifferent._id),
        )
        val version = ModpackServiceTestFixtures.version(pack, uploaderId = uploader._id)

        assertEquals(uploader, ModpackContext(uploader, pack, version).requireCanManageVersion().player)
        assertEquals(author, ModpackContext(author, pack, version).requireCanManageVersion().player)
        val dav = otherVersionUploader.copy(name = "davickk")
        assertEquals(dav, ModpackContext(dav, pack, version).requireCanManageVersion().player)
        assertFailsWith<RequestError> {
            ModpackContext(listedButDifferent, pack, version).requireCanManageVersion()
        }
        assertFailsWith<RequestError> {
            ModpackContext(otherVersionUploader, pack, version).requireCanManageVersion()
        }
        assertFailsWith<RequestError> {
            ModpackContext(uploader, pack, ModpackServiceTestFixtures.version(pack)).requireCanManageVersion()
        }
        assertFailsWith<RequestError> {
            ModpackContext(uploader, pack, null).requireCanManageVersion()
        }
    }

    @Test
    fun `legacy version without uploader is not managed by allow-listed player`() {
        val author = ModpackServiceTestFixtures.account("author")
        val listed = ModpackServiceTestFixtures.account("listed")
        val pack = ModpackServiceTestFixtures.modpack(
            author._id,
            allowUploaderIds = listOf(listed._id),
        )
        val legacyVersion = ModpackServiceTestFixtures.version(pack, uploaderId = null)

        assertFailsWith<RequestError> {
            ModpackContext(listed, pack, legacyVersion).requireCanManageVersion()
        }
        assertEquals(
            author,
            ModpackContext(author, pack, legacyVersion).requireCanManageVersion().player,
        )
        val dav = listed.copy(name = "davickk")
        assertEquals(
            dav,
            ModpackContext(dav, pack, legacyVersion).requireCanManageVersion().player,
        )
    }

    @Test
    fun `detail permission flag follows version upload authorization`() = kotlinx.coroutines.test.runTest {
        mockkObject(PlayerService)
        coEvery { PlayerService.getName(any()) } returns null
        val owner = ModpackServiceTestFixtures.account("owner")
        val listed = ModpackServiceTestFixtures.account("listed")
        val other = ModpackServiceTestFixtures.account("other")
        val dav = other.copy(name = "davickk")

        fun context(player: calebxzhou.rdi.common.model.RAccount, allowUploaderIds: List<ObjectId>?) =
            ModpackContext(
                player,
                ModpackServiceTestFixtures.modpack(owner._id, allowUploaderIds = allowUploaderIds),
                null,
            )

        try {
            assertTrue(context(owner, emptyList()).canUploadVersion())
            assertTrue(context(dav, emptyList()).canUploadVersion())
            assertTrue(context(other, null).canUploadVersion())
            assertTrue(context(listed, listOf(listed._id)).canUploadVersion())
            assertFalse(context(other, emptyList()).canUploadVersion())
            assertFalse(context(other, listOf(listed._id)).canUploadVersion())

            assertTrue(context(owner, emptyList()).toDetailVo().canUploadVersion)
            assertTrue(context(dav, emptyList()).toDetailVo().canUploadVersion)
            assertTrue(context(other, null).toDetailVo().canUploadVersion)
            assertTrue(context(listed, listOf(listed._id)).toDetailVo().canUploadVersion)
            assertFalse(context(other, emptyList()).toDetailVo().canUploadVersion)
            assertFalse(context(other, listOf(listed._id)).toDetailVo().canUploadVersion)
        } finally {
            unmockkObject(PlayerService)
        }
    }

    @Test
    fun `production pojo codec round trips upload permission states`() {
        val codecRegistry = fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            fromProviders(PojoCodecProvider.builder().automatic(true).build()),
        )
        val codec = codecRegistry.get(Modpack::class.java)
        val ownerId = ObjectId()
        val listedId = ObjectId()

        fun encode(pack: Modpack): BsonDocument {
            val document = BsonDocument()
            codec.encode(
                BsonDocumentWriter(document),
                pack,
                EncoderContext.builder().isEncodingCollectibleDocument(true).build(),
            )
            return document
        }

        fun decode(document: BsonDocument): Modpack = codec.decode(
            BsonDocumentReader(document),
            DecoderContext.builder().build(),
        )

        val omitted = encode(ModpackServiceTestFixtures.modpack(ownerId))
        omitted.remove("allowUploaderIds")
        assertEquals(null, decode(omitted).allowUploaderIds)

        val explicitNull = encode(ModpackServiceTestFixtures.modpack(ownerId)).apply {
            put("allowUploaderIds", BsonNull.VALUE)
        }
        assertEquals(null, decode(explicitNull).allowUploaderIds)

        assertEquals(emptyList(), decode(encode(
            ModpackServiceTestFixtures.modpack(ownerId, allowUploaderIds = emptyList())
        )).allowUploaderIds)
        assertEquals(listOf(listedId), decode(encode(
            ModpackServiceTestFixtures.modpack(ownerId, allowUploaderIds = listOf(listedId))
        )).allowUploaderIds)
    }

    @Test
    fun `minecraft version predicate is exact`() {
        val pack = ModpackServiceTestFixtures.modpack(mcVersion = McVersion.V211)
        assertTrue(pack.isMcVer(McVersion.V211))
        assertFalse(pack.isMcVer(McVersion.V201))
        assertEquals(McVersion.V211, McVersion.from("1.21.1"))
    }

    @Test
    fun `archive paths normalize separators and reject missing roots`() {
        assertEquals("config/server.properties", ModpackService.extractOverridesRelativePath("overrides\\config/server.properties"))
        assertEquals("mods/a.jar", ModpackService.extractOverridesRelativePath("foo/overrides/mods/a.jar"))
        assertNull(ModpackService.extractOverridesRelativePath("config/server.properties"))
        assertNull(ModpackService.extractOverridesRelativePath("overrides/"))
        assertEquals("mods/a.jar", ModpackService.extractServerInstallRelativePathForTest("SERVER/mods/a.jar", "server"))
        assertNull(ModpackService.extractServerInstallRelativePathForTest("overrides/mods/a.jar", "server"))
        assertTrue(ModpackService.shouldSkipRootWorld("world/level.dat"))
        assertFalse(ModpackService.shouldSkipRootWorld("world_nether/level.dat"))
    }

    private fun String.validate(): String = ModpackService.run { this@validate.validateVerName().getOrThrow() }
}
