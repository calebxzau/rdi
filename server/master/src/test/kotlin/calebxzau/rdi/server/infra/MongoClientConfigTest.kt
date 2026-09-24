package calebxzau.rdi.server.infra

import calebxzhou.rdi.master.AppConfig
import calebxzhou.rdi.master.DatabaseConfig
import net.peanuuutz.tomlkt.Toml
import org.bson.UuidRepresentation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MongoClientConfigTest {
    @Test
    fun `old TOML database config uses authentication defaults`() {
        val config = Toml.decodeFromString(
            AppConfig.serializer(),
            """
            [database]
            host = "db.example"
            port = 27018
            name = "rdi"
            """.trimIndent(),
        )

        assertEquals("db.example", config.database.host)
        assertEquals(27018, config.database.port)
        assertEquals("rdi", config.database.name)
        assertEquals("", config.database.username)
        assertEquals("", config.database.password)
        assertEquals("admin", config.database.authSource)
    }

    @Test
    fun `empty credentials leave settings unauthenticated`() {
        val settings = mongoClientSettings(DatabaseConfig())

        assertNull(settings.credential)
        assertEquals(UuidRepresentation.STANDARD, settings.uuidRepresentation)
    }

    @Test
    fun `credentials preserve username password and auth source`() {
        val password = " p@ss word\t"
        val settings = mongoClientSettings(
            DatabaseConfig(
                username = "mongo-user",
                password = password,
                authSource = "users",
            )
        )

        val credential = settings.credential ?: error("Expected MongoDB credentials")
        assertEquals("mongo-user", credential.userName)
        assertEquals("users", credential.source)
        assertContentEquals(password.toCharArray(), credential.password)
        assertEquals(UuidRepresentation.STANDARD, settings.uuidRepresentation)
    }

    @Test
    fun `partial credentials are rejected without exposing password`() {
        val cases = listOf(
            DatabaseConfig(username = "mongo-user"),
            DatabaseConfig(password = "secret"),
            DatabaseConfig(username = " ", password = "secret"),
            DatabaseConfig(username = "mongo-user", password = "secret", authSource = " "),
        )

        cases.forEach { config ->
            val exception = assertFailsWith<IllegalArgumentException> {
                mongoClientSettings(config)
            }
            assertFalse(exception.message.orEmpty().contains("secret"))
        }
    }
}
