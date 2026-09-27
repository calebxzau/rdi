package calebxzau.rdi.server.service.baseworld

import calebxzhou.rdi.master.PostgresConfig
import calebxzhou.rdi.master.infra.postgres.DatabaseProvider
import calebxzau.rdi.server.account.AccountRecord
import calebxzau.rdi.server.account.InsertIfMissingResult
import calebxzau.rdi.server.account.PgAccountRepo
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class BaseWorldServiceIntegrationTest {
    private lateinit var database: DatabaseProvider
    private val accounts = PgAccountRepo()
    private val repository = PgBaseWorldRepo()
    private lateinit var service: BaseWorldService

    @BeforeTest
    fun setUp() {
        database = DatabaseProvider(
            PostgresConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maximumPoolSize = 4,
            )
        )
        service = BaseWorldService(database, repository)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create round trips fields and database generates UUIDv7`() = runTest {
        val ownerId = createAccount()

        val created = service.create(
            ownerId,
            "Skyblock",
            "skyblockbuilder:skyblock",
            generatorSettings = null,
            size = 1234,,
        ).getOrThrow()

        assertEquals(7, created.id.version())
        assertEquals(ownerId, created.ownerId)
        assertEquals("Skyblock", created.name)
        assertEquals("skyblockbuilder:skyblock", created.levelType)
        assertEquals(null, created.generatorSettings)
        assertEquals(1234, created.size)
        assertEquals(created, service.findById(ownerId, created.id).getOrThrow())

        val configured = service.create(
            ownerId,
            "Configured",
            "custom",
            generatorSettings = "{\"seed\":123}",
            size = 5,,
        ).getOrThrow()
        assertEquals("{\"seed\":123}", configured.generatorSettings)
        assertEquals(configured, service.findById(ownerId, configured.id).getOrThrow())

        val otherOwner = createAccount()
        assertNull(database.transaction { repository.updateSize(otherOwner, created.id, 999) })
        assertEquals(1234, service.findById(ownerId, created.id).getOrThrow()?.size)
        val resized = database.transaction { repository.updateSize(ownerId, created.id, 2048) }
        assertEquals(2048, resized?.size)
        assertEquals(2048, service.findById(ownerId, created.id).getOrThrow()?.size)
    }

    @Test
    fun `find list and delete remain owner scoped`() = runTest {
        val ownerId = createAccount()
        val otherOwnerId = createAccount()
        val first = service.create(ownerId, "First", "normal", null, 1,).getOrThrow()
        val second = service.create(ownerId, "Second", "flat", "", 2,).getOrThrow()
        val other = service.create(otherOwnerId, "Other", "normal", null, 3,).getOrThrow()

        assertNull(service.findById(otherOwnerId, first.id).getOrThrow())
        val worlds = service.listByOwner(ownerId).getOrThrow()
        assertEquals(listOf(second.id, first.id), worlds.map { it.id })
        assertEquals(setOf(first.id, second.id), worlds.map { it.id }.toSet())
        assertEquals("", worlds.single { it.id == second.id }.generatorSettings)
        assertTrue(service.listByOwner(otherOwnerId).getOrThrow().single().id == other.id)

        assertFalse(service.delete(otherOwnerId, first.id).getOrThrow())
        assertTrue(service.findById(ownerId, first.id).getOrThrow() != null)
        assertTrue(service.delete(ownerId, first.id).getOrThrow())
        assertFalse(service.delete(ownerId, first.id).getOrThrow())
        assertNull(service.findById(ownerId, first.id).getOrThrow())
    }

    @Test
    fun `empty owner list and database constraints are surfaced`() = runTest {
        val ownerId = createAccount()
        assertTrue(service.listByOwner(ownerId).getOrThrow().isEmpty())

        assertTrue(
            service.create(UUID.randomUUID(), "Missing owner", "normal", null, 1,).isFailure
        )
        assertTrue(service.create(ownerId, "Negative", "normal", null, -1,).isFailure)
    }

    @Test
    fun `owner quota is transactional across service instances and deletion frees a slot`() = runTest {
        val ownerId = createAccount()
        service.create(ownerId, "One", "normal", null, 1,).getOrThrow()
        service.create(ownerId, "Two", "normal", null, 1,).getOrThrow()
        val secondService = BaseWorldService(database, repository, accounts = accounts)
        val results = listOf(service, secondService).mapIndexed { index, instance ->
            async(Dispatchers.IO) { instance.create(ownerId, "Concurrent$index", "normal", null, 1,) }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(3, service.listByOwner(ownerId).getOrThrow().size)

        val deleted = service.listByOwner(ownerId).getOrThrow().first()
        assertTrue(service.delete(ownerId, deleted.id).getOrThrow())
        assertTrue(service.create(ownerId, "AfterDelete", "normal", null, 1,).isSuccess)
        assertTrue(service.create(createAccount(), "OtherOwner", "normal", null, 1,).isSuccess)
    }

    private suspend fun createAccount(): UUID {
        val id = UUID.randomUUID()
        val result = database.transaction {
            accounts.insertIfMissing(
                AccountRecord(
                    id = id,
                    name = "base-world-${id.toString().take(8)}",
                    pwd = "123456",
                    qq = accountSequence.incrementAndGet().toString(),
                    msid = null,
                    isSlim = true,
                    skin = "https://example.com/skin.png",
                    cape = null,
                )
            )
        }
        check(result == InsertIfMissingResult.INSERTED)
        return id
    }

    companion object {
        private val accountSequence = AtomicInteger(10000)

        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18")
            .withDatabaseName("rdi")
            .withUsername("rdi")
            .withPassword("rdi")
    }
}
