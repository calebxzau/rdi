package calebxzhou.rdi.client.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModpackArchiveUrlIdentityTest {
    @Test
    fun `modrinth candidate wins regardless of curseforge position`() {
        val identity = resolveArchiveModIdentity(
            listOf(
                "https://media.forgecdn.net/files/9/46/old.jar",
                "https://cdn.modrinth.com/data/project/versions/version/mod.JAR",
            ),
        )

        assertEquals(ArchiveUrlIdentity.Modrinth("project", "version"), identity)
    }

    @Test
    fun `modrinth root and subdomains are accepted and path components are decoded`() {
        assertEquals(
            ArchiveUrlIdentity.Modrinth("project id", "version"),
            resolveArchiveModIdentity(
                listOf("https://modrinth.com/data/project%20id/versions/version/file%2Ejar"),
            ),
        )
        assertEquals(
            ArchiveUrlIdentity.Modrinth("project", "version"),
            resolveArchiveModIdentity(
                listOf("https://cdn.modrinth.com/data/project/versions/version/file.jar"),
            ),
        )
    }

    @Test
    fun `forgecdn file ids use first group times one thousand plus second group`() {
        assertEquals(
            ArchiveUrlIdentity.CurseForge(9_046),
            resolveArchiveModIdentity(listOf("https://media.forgecdn.net/files/9/46/mod.jar")),
        )
        assertEquals(
            ArchiveUrlIdentity.CurseForge(9_000),
            resolveArchiveModIdentity(listOf("https://media.forgecdn.net/files/9/000/mod.jar")),
        )
    }

    @Test
    fun `pseudo domains and forgecdn root are rejected`() {
        assertNull(
            resolveArchiveModIdentity(
                listOf(
                    "https://notmodrinth.com/data/project/versions/version/mod.jar",
                    "https://modrinth.com.evil.test/data/project/versions/version/mod.jar",
                    "https://forgecdn.net/files/9/46/mod.jar",
                    "https://media.forgecdn.net.evil.test/files/9/46/mod.jar",
                ),
            ),
        )
    }

    @Test
    fun `non jar files malformed paths and overflowing ids are rejected`() {
        val invalidUrls = listOf(
            "https://cdn.modrinth.com/data/project/versions/version/mod.zip",
            "https://cdn.modrinth.com/data/project/versions/version/mod.jar/extra",
            "https://cdn.modrinth.com/data//versions/version/mod.jar",
            "https://cdn.modrinth.com/data/../versions/version/mod.jar",
            "https://media.forgecdn.net/files/9/1000/mod.jar",
            "https://media.forgecdn.net/files/9/not-a-number/mod.jar",
            "https://media.forgecdn.net/files/9/46/mod.zip",
            "https://media.forgecdn.net/files/999999999999999999999999/46/mod.jar",
            "not a uri",
        )

        invalidUrls.forEach { url ->
            assertNull(resolveArchiveModIdentity(listOf(url)), url)
        }
    }
}
