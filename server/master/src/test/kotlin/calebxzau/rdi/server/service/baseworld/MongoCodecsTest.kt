package calebxzau.rdi.server.service.baseworld

import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.McVersion
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzhou.rdi.model.Role
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.server.infra.productionMongoCodecRegistry
import kotlinx.serialization.encodeToString
import org.bson.BsonDocument
import org.bson.BsonDocumentReader
import org.bson.BsonDocumentWriter
import org.bson.UuidRepresentation
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.types.ObjectId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MongoCodecsTest {
    @Test
    fun `production Mongo codecs preserve version client extras and missing field defaults`() {
        val pack = Modpack(
            _id = ObjectId("00112233445566778899aabb"),
            name = "Extras codec",
            authorId = ObjectId("aabbccddeeff001122334455"),
            modloader = ModLoader.neoforge,
            mcVer = McVersion.V211,
            versions = arrayListOf(
                Modpack.Version(
                    time = 1L,
                    modpackId = ObjectId("00112233445566778899aabb"),
                    name = "1.0",
                    changelog = "test",
                    status = Modpack.Status.WAIT,
                    clientExtras = arrayListOf(
                        Content(
                            platform = ContentPlatform.Modrinth,
                            type = ContentType.ResPack,
                            projectId = "p",
                            fileId = "f",
                            slug = "pack",
                            hash = "a".repeat(40),
                            path = "resourcepacks/pack.zip",
                            side = ContentSide.Client,
                        )
                    ),
                )
            ),
        )
        val codec = productionMongoCodecRegistry().get(Modpack::class.java)
        val encoded = BsonDocument().also { document ->
            codec.encode(BsonDocumentWriter(document), pack, EncoderContext.builder().isEncodingCollectibleDocument(true).build())
        }
        assertEquals(1, encoded["versions"]!!.asArray().single().asDocument()["clientExtras"]!!.asArray().size)
        val decoded = codec.decode(BsonDocumentReader(encoded), DecoderContext.builder().build())
        assertEquals(pack._id, decoded._id)
        assertEquals(pack.versions.single().clientExtras, decoded.versions.single().clientExtras)
        assertEquals(pack.versions.single().modpackId, decoded.versions.single().modpackId)
        val missing = BsonDocument().apply {
            putAll(encoded)
            put("versions", encoded["versions"]!!.asArray().map { value ->
                BsonDocument().apply {
                    putAll(value.asDocument())
                    remove("clientExtras")
                }
            }.let { org.bson.BsonArray(it) })
        }
        assertEquals(emptyList(), codec.decode(BsonDocumentReader(missing), DecoderContext.builder().build()).versions.single().clientExtras)
    }

    @Test
    fun `production Mongo codecs preserve UUID BSON and ObjectId fields`() {
        val baseWorldId = UUID.fromString("018f0c44-2d1f-7abc-8def-1234567890ab")
        val host = Host(
            _id = ObjectId("00112233445566778899aabb"),
            name = "Codec test",
            ownerId = ObjectId("aabbccddeeff001122334455"),
            modpackId = ObjectId("11223344556677889900aabb"),
            port = 25565,
            difficulty = 2,
            gameMode = 0,
            levelType = "normal",
            members = listOf(Host.Member(ObjectId("223344556677889900aabbcc"), Role.ADMIN)),
            banlist = listOf(ObjectId("3344556677889900aabbccdd")),
            baseWorldId = baseWorldId,
        )
        val codec = productionMongoCodecRegistry().get(Host::class.java)
        val encoded = BsonDocument().also { document ->
            codec.encode(
                BsonDocumentWriter(document),
                host,
                EncoderContext.builder().isEncodingCollectibleDocument(true).build(),
            )
        }

        assertEquals(baseWorldId, encoded["baseWorldId"]!!.asBinary().asUuid(UuidRepresentation.STANDARD))
        assertEquals(host._id, encoded["_id"]!!.asObjectId().value)
        assertEquals(host.ownerId, encoded["ownerId"]!!.asObjectId().value)
        assertEquals(host.modpackId, encoded["modpackId"]!!.asObjectId().value)
        assertEquals(
            host.members.single().id,
            encoded["members"]!!.asArray().single().asDocument()["id"]!!.asObjectId().value,
        )
        assertEquals(
            host.banlist.single(),
            encoded["banlist"]!!.asArray().single().asObjectId().value,
        )
        val decoded = codec.decode(BsonDocumentReader(encoded), DecoderContext.builder().build())
        assertEquals(host, decoded)

        val explicitNull = BsonDocument().apply {
            putAll(encoded)
            put("baseWorldId", org.bson.BsonNull.VALUE)
        }
        assertNull(codec.decode(BsonDocumentReader(explicitNull), DecoderContext.builder().build()).baseWorldId)
        val missing = BsonDocument().apply {
            putAll(encoded)
            remove("baseWorldId")
        }
        assertNull(codec.decode(BsonDocumentReader(missing), DecoderContext.builder().build()).baseWorldId)
    }

    @Test
    fun `Host CreateDto JSON keeps UUID optional compatibility`() {
        val id = UUID.fromString("018f0c44-2d1f-7abc-8def-1234567890ab")
        val dto = Host.CreateDto(
            name = "Codec test",
            modpackId = ObjectId("00112233445566778899aabb"),
            packVer = "latest",
            difficulty = 2,
            gameMode = 0,
            levelType = "normal",
            allowCheats = false,
            whitelist = false,
            gameRules = mutableMapOf(),
            baseWorldId = id,
        )
        val encoded = serdesJson.encodeToString(dto)
        assertEquals(id, serdesJson.decodeFromString<Host.CreateDto>(encoded).baseWorldId)
        assertNull(serdesJson.decodeFromString<Host.CreateDto>(encoded.replace(",\"baseWorldId\":\"$id\"", "")).baseWorldId)
        assertNull(serdesJson.decodeFromString<Host.CreateDto>(encoded.replace("\"baseWorldId\":\"$id\"", "\"baseWorldId\":null")).baseWorldId)
    }
}
