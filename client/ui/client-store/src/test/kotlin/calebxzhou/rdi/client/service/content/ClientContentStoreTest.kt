package calebxzhou.rdi.client.service.content

import calebxzhou.rdi.common.net.DownloadProgress
import calebxzhou.rdi.common.model.EXTRA_MOD_PREFIX
import calebxzhou.rdi.common.model.CurseForgeFile
import calebxzhou.rdi.common.model.Mod
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzhou.rdi.common.service.ModService
import calebxzhou.rdi.common.service.murmur2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClientContentStoreTest {
    @Test
    fun `client extra materialization reuses one cached blob at two placements`() = runBlocking {
        val bytes = "shared-extra".toByteArray()
        val hash = digest(bytes, "SHA-1")
        val cacheRoot = Files.createTempDirectory("rdi-extra-cache")
        Files.write(cacheRoot.resolve("$hash.sha1"), bytes)
        val targetRoot = Files.createTempDirectory("rdi-extra-target")
        val extras = listOf(
            Content(
                ContentPlatform.Modrinth,
                ContentType.ResPack,
                "p",
                "f",
                "shared",
                hash,
                "resourcepacks/shared.zip",
                ContentSide.Client,
            ),
            Content(
                ContentPlatform.Modrinth,
                ContentType.ShaderPack,
                "p",
                "f",
                "shared",
                hash,
                "shaderpacks/shared.zip",
                ContentSide.Client,
            ),
        )
        val paths = ClientContentStore(cacheRoot).materializeClientExtras(extras, targetRoot).getOrThrow()
        assertEquals(2, paths.size)
        assertContentEquals(bytes, Files.readAllBytes(targetRoot.resolve("resourcepacks/shared.zip")))
        assertContentEquals(bytes, Files.readAllBytes(targetRoot.resolve("shaderpacks/shared.zip")))
    }

    @Test
    fun `client extra materialization keeps an existing local winner without download`() = runBlocking {
        val targetRoot = Files.createTempDirectory("rdi-extra-local")
        val target = targetRoot.resolve("resourcepacks/local.zip")
        Files.createDirectories(target.parent)
        val localBytes = "local-override".toByteArray()
        Files.write(target, localBytes)
        val extra = Content(
            ContentPlatform.Modrinth,
            ContentType.ResPack,
            "p",
            "f",
            "local",
            "a".repeat(40),
            "resourcepacks/local.zip",
            ContentSide.Client,
        )
        val result = ClientContentStore(Files.createTempDirectory("rdi-extra-empty-cache"))
            .materializeClientExtras(listOf(extra), targetRoot)
            .getOrThrow()
        assertEquals(target.toAbsolutePath().normalize(), result.single())
        assertContentEquals(localBytes, Files.readAllBytes(target))
    }

    @Test
    fun `client extra materialization rejects unsafe target and symlink`() = runBlocking {
        val extra = Content(
            ContentPlatform.Modrinth,
            ContentType.ResPack,
            "p",
            "f",
            "bad",
            "a".repeat(40),
            "resourcepacks/bad.zip",
            ContentSide.Client,
        )
        assertFailsWith<IllegalArgumentException> {
            extra.toClientContentRequest("/resourcepacks/bad.zip")
        }
        val targetRoot = Files.createTempDirectory("rdi-extra-symlink")
        val target = targetRoot.resolve("resourcepacks/bad.zip")
        Files.createDirectories(target.parent)
        assumeSymbolicLink(target, targetRoot.resolve("elsewhere"))
        assertTrue(
            ClientContentStore(Files.createTempDirectory("rdi-extra-symlink-cache"))
                .materializeClientExtras(listOf(extra), targetRoot)
                .isFailure
        )
    }

    @Test
    fun `client extra materialization rejects contradictory placement and symlinked target root`() = runBlocking {
        val base = Content(
            ContentPlatform.Modrinth,
            ContentType.ResPack,
            "p",
            "f",
            "pack",
            "a".repeat(40),
            "resourcepacks/pack.zip",
            ContentSide.Client,
        )
        val store = ClientContentStore(Files.createTempDirectory("rdi-extra-conflict-cache"))
        val targetRoot = Files.createTempDirectory("rdi-extra-conflict-target")
        assertTrue(store.materializeClientExtras(listOf(base, base.copy(fileId = "other")), targetRoot).isFailure)

        val parent = Files.createTempDirectory("rdi-extra-link-parent")
        val actual = Files.createTempDirectory("rdi-extra-real-root")
        val link = parent.resolve("link")
        assumeSymbolicLink(link, actual)
        val linkedRoot = link.resolve("instance")
        assertTrue(store.materializeClientExtras(listOf(base), linkedRoot).isFailure)
    }

    @Test
    fun `client extra materialization rejects mismatched downloaded bytes`() = runBlocking {
        val payload = "expected-extra".toByteArray()
        val content = Content(
            ContentPlatform.Modrinth,
            ContentType.ResPack,
            "p",
            "f",
            "bad-digest",
            digest(payload, "SHA-1"),
            "resourcepacks/bad.zip",
            ContentSide.Client,
            downloadUrls = listOf("https://example.com/bad.zip"),
        )
        val store = ClientContentStore(Files.createTempDirectory("rdi-extra-bad-digest-cache"))
        val request = content.toClientContentRequest().copy(
            sources = listOf(ContentSource(downloader = { target, _ ->
                Files.write(target, "wrong-extra".toByteArray())
                Result.success(target)
            }))
        )
        assertTrue(store.materialize(listOf(request), Files.createTempDirectory("rdi-extra-bad-digest-target")).isFailure)
    }

    @Test
    fun `client extra requests retain root relative placement and validate identity`() {
        val content = Content(
            platform = ContentPlatform.Modrinth,
            type = ContentType.ShaderPack,
            projectId = "shader",
            fileId = "version",
            slug = "shader",
            hash = "a".repeat(40),
            path = "shaderpacks/shader.zip",
            side = ContentSide.Client,
            downloadUrls = listOf("https://example.com/shader.zip"),
        )
        val request = content.toClientContentRequest()
        assertEquals("shaderpacks/shader.zip", request.relativePath)
        assertEquals(ContentDigestAlgorithm.SHA1, request.digests.single().algorithm)
        assertTrue(request.id.endsWith(":shaderpacks/shader.zip"))
        assertFailsWith<IllegalArgumentException> {
            content.toClientContentRequest("/shaderpacks/shader.zip")
        }
    }

    @Test
    fun `mod content requests use platform hash contracts`() {
        val curseForge = testCurseForgeMod("101", "1001", hash = "123")
        val modrinth = Mod(
            platform = "mr",
            projectId = "project",
            slug = "modrinth",
            fileId = "version",
            hash = "a".repeat(40),
        )
        val github = Mod(
            platform = "github",
            projectId = "owner/repo",
            slug = "github",
            fileId = "tag/asset.jar",
            hash = "b".repeat(64),
        )
        val legacyGithub = github.copy(hash = "c".repeat(40))

        assertEquals(
            ContentDigestAlgorithm.MURMUR2,
            curseForge.toClientContentRequest().digests.single().algorithm,
        )
        assertEquals(
            ContentDigestAlgorithm.SHA1,
            modrinth.toClientContentRequest().digests.single().algorithm,
        )
        assertEquals(
            ContentDigestAlgorithm.SHA256,
            github.toClientContentRequest().digests.single().algorithm,
        )
        assertEquals(
            ContentDigestAlgorithm.SHA1,
            legacyGithub.toClientContentRequest().digests.single().algorithm,
        )
    }

    @Test
    fun `github sha1 extra mod cache materializes without downloading`() = runBlocking {
        val payload = "github-sha1-extra-mod".toByteArray()
        val hash = digest(payload, "SHA-1")
        val mod = Mod(
            platform = "github",
            projectId = "owner/repo",
            slug = "extra-mod",
            fileId = "tag/asset.jar",
            hash = hash,
        )
        val cacheRoot = Files.createTempDirectory("rdi-content-github-sha1-cache")
        Files.write(cacheRoot.resolve("$hash.sha1"), payload)
        val targetRoot = Files.createTempDirectory("rdi-content-github-sha1-target")
        val targetRelativePath = EXTRA_MOD_PREFIX + mod.fileName
        val downloads = AtomicInteger()
        val request = mod.toClientContentRequest(targetRelativePath).copy(
            sources = listOf(ContentSource(downloader = { target, _ ->
                downloads.incrementAndGet()
                Files.write(target, payload)
                Result.success(target)
            }))
        )

        val target = ClientContentStore(cacheRoot)
            .materialize(listOf(request), targetRoot)
            .getOrThrow()
            .single()

        assertEquals(targetRoot.resolve(targetRelativePath), target)
        assertEquals(0, downloads.get())
        assertContentEquals(payload, Files.readAllBytes(target))
    }

    @Test
    fun `existing github sha1 extra mod target is reused without downloading`() = runBlocking {
        val payload = "github-sha1-existing-target".toByteArray()
        val hash = digest(payload, "SHA-1")
        val mod = Mod(
            platform = "github",
            projectId = "owner/repo",
            slug = "extra-mod",
            fileId = "tag/asset.jar",
            hash = hash,
        )
        val cacheRoot = Files.createTempDirectory("rdi-content-github-sha1-target-cache")
        val targetRoot = Files.createTempDirectory("rdi-content-github-sha1-existing-target")
        val targetRelativePath = EXTRA_MOD_PREFIX + mod.fileName
        val target = targetRoot.resolve(targetRelativePath)
        Files.write(target, payload)
        val downloads = AtomicInteger()
        val request = mod.toClientContentRequest(targetRelativePath).copy(
            sources = listOf(ContentSource(downloader = { targetPath, _ ->
                downloads.incrementAndGet()
                Files.write(targetPath, payload)
                Result.success(targetPath)
            }))
        )

        val materialized = ClientContentStore(cacheRoot)
            .materialize(listOf(request), targetRoot)
            .getOrThrow()
            .single()

        assertEquals(target, materialized)
        assertEquals(0, downloads.get())
        assertContentEquals(payload, Files.readAllBytes(target))
    }

    @Test
    fun `symbolic link target is replaced with cached hard link`() = runBlocking {
        val payload = "cached-symbolic-link-target".toByteArray()
        val request = request(
            id = "symbolic-link-target",
            relativePath = "mods/example.jar",
            payload = payload,
            source = ContentSource(downloader = { _, _ ->
                Result.failure(AssertionError("cached content should not download"))
            }),
        )
        val cacheRoot = Files.createTempDirectory("rdi-content-symbolic-link-cache")
        val cachePath = cacheRoot.resolve("${request.digests.first().normalizedValue}.sha1")
        Files.write(cachePath, payload)
        val targetRoot = Files.createTempDirectory("rdi-content-symbolic-link-target")
        val target = targetRoot.resolve(request.relativePath)
        Files.createDirectories(target.parent)
        val otherTarget = Files.createTempFile("rdi-content-symbolic-link-other", ".jar")
        Files.write(otherTarget, "other-content".toByteArray())
        assumeSymbolicLink(target, otherTarget)

        ClientContentStore(cacheRoot).materialize(listOf(request), targetRoot).getOrThrow()

        assertTrue(!Files.isSymbolicLink(target))
        assertTrue(Files.isSameFile(cachePath, target))
        assertContentEquals(payload, Files.readAllBytes(target))
    }

    @Test
    fun `broken symbolic link target is replaced with cached hard link`() = runBlocking {
        val payload = "cached-broken-symbolic-link-target".toByteArray()
        val request = request(
            id = "broken-symbolic-link-target",
            relativePath = "mods/example.jar",
            payload = payload,
            source = ContentSource(downloader = { _, _ ->
                Result.failure(AssertionError("cached content should not download"))
            }),
        )
        val cacheRoot = Files.createTempDirectory("rdi-content-broken-symbolic-link-cache")
        val cachePath = cacheRoot.resolve("${request.digests.first().normalizedValue}.sha1")
        Files.write(cachePath, payload)
        val targetRoot = Files.createTempDirectory("rdi-content-broken-symbolic-link-target")
        val target = targetRoot.resolve(request.relativePath)
        Files.createDirectories(target.parent)
        val missingTarget = targetRoot.resolve("missing.jar")
        assumeSymbolicLink(target, missingTarget)

        ClientContentStore(cacheRoot).materialize(listOf(request), targetRoot).getOrThrow()

        assertTrue(!Files.isSymbolicLink(target))
        assertTrue(Files.isSameFile(cachePath, target))
        assertContentEquals(payload, Files.readAllBytes(target))
    }

    @Test
    fun `existing ordinary target with different content is preserved`() = runBlocking {
        val payload = "cached-content".toByteArray()
        val original = "player-modified-content".toByteArray()
        val request = request(
            id = "different-ordinary-target",
            relativePath = "mods/example.jar",
            payload = payload,
            source = ContentSource(downloader = { _, _ ->
                Result.failure(AssertionError("cached content should not download"))
            }),
        )
        val cacheRoot = Files.createTempDirectory("rdi-content-different-target-cache")
        Files.write(cacheRoot.resolve("${request.digests.first().normalizedValue}.sha1"), payload)
        val targetRoot = Files.createTempDirectory("rdi-content-different-target")
        val target = targetRoot.resolve(request.relativePath)
        Files.createDirectories(target.parent)
        Files.write(target, original)

        val result = ClientContentStore(cacheRoot).materialize(listOf(request), targetRoot)

        assertTrue(result.isFailure)
        assertContentEquals(original, Files.readAllBytes(target))
    }

    @Test
    fun `github sha256 validation reads the historical sha1 filename alias`() {
        val targetDir = Files.createTempDirectory("rdi-github-legacy-mod-cache")
        val payload = "github-legacy-cache".toByteArray()
        val legacyHash = digest(payload, "SHA-1")
        val currentHash = digest(payload, "SHA-256")
        val mod = Mod(
            platform = "github",
            projectId = "owner/repo",
            slug = "example-mod",
            fileId = "tag/asset.jar",
            hash = currentHash,
        )
        Files.write(targetDir.resolve("example-mod_github_${legacyHash}.jar"), payload)

        assertTrue(ModService.isDownloadedModFileValid(mod, targetDir.toFile()))
        assertTrue(Files.isRegularFile(targetDir.resolve(mod.fileName)))
    }

    @Test
    fun `github sha1 records validate and copy the legacy named jar`() {
        val targetDir = Files.createTempDirectory("rdi-github-sha1-legacy-mod-cache")
        val payload = "github-sha1-legacy-cache".toByteArray()
        val hash = digest(payload, "SHA-1")
        val mod = Mod(
            platform = "github",
            projectId = "owner/repo",
            slug = "true-ending",
            fileId = "tag/asset.jar",
            hash = hash,
        )
        val legacyPath = targetDir.resolve("true-ending_github_${hash}.jar")
        Files.write(legacyPath, payload)

        assertTrue(ModService.isDownloadedModFileValid(mod, targetDir.toFile()))
        assertTrue(Files.isRegularFile(targetDir.resolve(mod.fileName)))
        assertContentEquals(payload, Files.readAllBytes(targetDir.resolve(mod.fileName)))
    }

    @Test
    fun `curseforge batch preparation makes one lookup and skips trusted urls`() = runBlocking {
        val calls = mutableListOf<List<Int>>()
        val unresolved = listOf(
            testCurseForgeMod("101", "1001"),
            testCurseForgeMod("102", "1002"),
            testCurseForgeMod("103", "1003"),
        )
        val fetched = ModService.prepareCurseForgeFileInfos(unresolved) { ids ->
            calls += ids
            ids.map { id -> CurseForgeFile(id = id, fileFingerprint = id.toLong()) }
        }

        assertEquals(listOf(listOf(1001, 1002, 1003)), calls)
        assertEquals(setOf(1001, 1002, 1003), fetched.keys)

        calls.clear()
        val trusted = unresolved.map { it.copy(downloadUrls = listOf("https://cdn.example.test/${it.fileId}.jar")) }
        assertTrue(ModService.prepareCurseForgeFileInfos(trusted) { ids ->
            calls += ids
            emptyList()
        }.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `content batch preparation skips cached CF content and looks up misses once`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-content-cf-batch-cache")
        val payload = "cached-curseforge-content".toByteArray()
        val payloadFile = Files.createTempFile("rdi-content-cf-payload", ".jar")
        Files.write(payloadFile, payload)
        val murmur = payloadFile.murmur2
        Files.write(cacheRoot.resolve("$murmur.murmur2"), payload)

        val cached = testCurseForgeMod("201", "2001", hash = murmur.toString())
        val missOne = testCurseForgeMod("202", "2002")
        val missTwo = testCurseForgeMod("203", "2003")
        val requests = listOf(cached, missOne, missTwo).map(Mod::toClientContentRequest)
        val calls = mutableListOf<List<Int>>()
        val store = ClientContentStore(cacheRoot) { ids ->
            calls += ids
            ids.map { id -> CurseForgeFile(id = id, fileFingerprint = id.toLong()) }
        }

        store.prepareBatchSourcesForTest(requests)

        assertEquals(listOf(listOf(2002, 2003)), calls)
    }

    @Test
    fun `offline CF request keeps its local source without metadata lookup`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-content-offline-cf-cache")
        val staged = Files.createTempFile("rdi-content-offline-cf-stage", ".jar")
        val payload = "offline-curseforge-content".toByteArray()
        Files.write(staged, payload)
        val fingerprint = staged.murmur2
        val mod = testCurseForgeMod("301", "3001", fingerprint.toString())
        val localDownloads = AtomicInteger()
        val calls = mutableListOf<List<Int>>()
        val request = mod.toClientContentRequest().copy(
            allowNetwork = false,
            sources = listOf(
                ContentSource(
                    localOnly = true,
                    downloader = { target, _ ->
                        localDownloads.incrementAndGet()
                        Files.copy(staged, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        Result.success(target)
                    },
                )
            ),
        )

        val result = ClientContentStore(cacheRoot) { ids ->
            calls += ids
            emptyList()
        }.use(listOf(request)) { }

        assertTrue(result.isSuccess)
        assertEquals(1, localDownloads.get())
        assertTrue(calls.isEmpty())
        assertTrue(Files.isRegularFile(cacheRoot.resolve("${fingerprint}.murmur2")))
    }

    @Test
    fun `offline uncached CF preparation leaves request unchanged`() = runBlocking {
        val mod = testCurseForgeMod("302", "3002")
        val calls = mutableListOf<List<Int>>()
        val request = mod.toClientContentRequest().copy(allowNetwork = false)
        val prepared = ClientContentStore(Files.createTempDirectory("rdi-content-offline-cf-prep")) { ids ->
            calls += ids
            emptyList()
        }.prepareBatchSourcesForTest(listOf(request)).single()

        assertEquals(request, prepared)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `cache-only batch returns hits and omits misses without downloading`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-content-cache-only-batch")
        val downloads = AtomicInteger()
        val hitOne = request(
            id = "cache-hit-one",
            relativePath = "mods/one.jar",
            payload = "cache-hit-one".toByteArray(),
            source = ContentSource(localOnly = true, downloader = { target, _ ->
                downloads.incrementAndGet()
                Files.write(target, "cache-hit-one".toByteArray())
                Result.success(target)
            }),
        )
        val hitTwo = request(
            id = "cache-hit-two",
            relativePath = "mods/two.jar",
            payload = "cache-hit-two".toByteArray(),
            source = ContentSource(localOnly = true, downloader = { target, _ ->
                downloads.incrementAndGet()
                Files.write(target, "cache-hit-two".toByteArray())
                Result.success(target)
            }),
        )
        val miss = request(
            id = "cache-miss",
            relativePath = "mods/miss.jar",
            payload = "cache-miss".toByteArray(),
            source = ContentSource(localOnly = true, downloader = { target, _ ->
                downloads.incrementAndGet()
                Files.write(target, "cache-miss".toByteArray())
                Result.success(target)
            }),
        )
        listOf(hitOne, hitTwo).forEach { cached ->
            Files.write(
                cacheRoot.resolve("${cached.digests.first().normalizedValue}.sha1"),
                when (cached) {
                    hitOne -> "cache-hit-one".toByteArray()
                    else -> "cache-hit-two".toByteArray()
                },
            )
        }

        val paths = ClientContentStore(cacheRoot).useCached(listOf(hitOne, hitTwo, miss)) { it }
            .getOrThrow()

        assertEquals(setOf(hitOne.id, hitTwo.id), paths.keys)
        assertContentEquals(
            "cache-hit-one".toByteArray(),
            Files.readAllBytes(paths.getValue(hitOne.id)),
        )
        assertContentEquals(
            "cache-hit-two".toByteArray(),
            Files.readAllBytes(paths.getValue(hitTwo.id)),
        )
        assertEquals(0, downloads.get())
    }

    @Test
    fun `cache-only batch reports exact names and item progress for hits and misses`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-content-cache-only-progress")
        val hit = request(
            id = "progress-hit",
            relativePath = "mods/hit.jar",
            payload = "progress-hit".toByteArray(),
            source = null,
        ).copy(displayName = "精确Mod")
        val miss = request(
            id = "progress-miss",
            relativePath = "mods/miss.jar",
            payload = "progress-miss".toByteArray(),
            source = ContentSource(localOnly = true, downloader = { _, _ ->
                error("cache-only lookup must not download")
            }),
        ).copy(displayName = "missing-mod")
        Files.write(
            cacheRoot.resolve("${hit.digests.first().normalizedValue}.sha1"),
            "progress-hit".toByteArray(),
        )
        val progress = mutableListOf<calebxzhou.rdi.common.model.Task2Progress>()

        ClientContentStore(cacheRoot).useCached(
            requests = listOf(hit, miss),
            onProgress = progress::add,
        ) { it }.getOrThrow()

        assertTrue(progress.isNotEmpty())
        assertEquals(setOf("精确Mod", "missing-mod"), progress.map { it.message }.toSet())
        assertTrue(progress.all { it.totalItems == 2 })
        assertTrue(progress.zipWithNext().all { (before, after) ->
            (after.completedItems ?: 0) >= (before.completedItems ?: 0)
        })
        assertEquals(2, progress.last().completedItems)
        assertEquals(2, progress.last().totalItems)
    }

    @Test
    fun `cache-only batch with no requests invokes block with empty map`() = runBlocking {
        var received: Map<String, java.nio.file.Path>? = null

        ClientContentStore(Files.createTempDirectory("rdi-content-cache-only-empty"))
            .useCached(emptyList()) {
                received = it
            }
            .getOrThrow()

        assertEquals(emptyMap(), received)
    }

    @Test
    fun `parallel resolution failure releases cache lease for later cleanup`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-content-lease-failure-cache")
        val payload = "leased-cache-content".toByteArray()
        val hit = request("lease-hit", "mods/hit.jar", payload, source = null)
        val cachePath = cacheRoot.resolve("${hit.digests.first().normalizedValue}.sha1")
        Files.write(cachePath, payload)
        val leaseReady = CompletableDeferred<Unit>()
        val failed = ContentRequest(
            id = "lease-fail",
            relativePath = "mods/fail.jar",
            sources = listOf(ContentSource(downloader = { _, _ ->
                leaseReady.await()
                Result.failure(IllegalStateException("expected failure"))
            })),
        )

        val result = ClientContentStore(cacheRoot).use(
            listOf(hit, failed),
            onProgress = { progress ->
                if (progress.completedItems == 1 && !leaseReady.isCompleted) leaseReady.complete(Unit)
            },
        ) { }

        assertTrue(result.isFailure)
        val cleaned = DownloadCacheCleanupService(
            cacheRoot = cacheRoot,
            versionsRoot = Files.createTempDirectory("rdi-content-lease-failure-versions"),
        ).cleanup().getOrThrow()
        assertEquals(1, cleaned.deletedFiles)
        assertTrue(!Files.exists(cachePath))
    }

    @Test
    fun `active use lease causes cleanup to report busy and retain entry`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-content-active-lease-cache")
        val payload = "active-cache-content".toByteArray()
        val request = request("active-hit", "mods/active.jar", payload, source = null)
        val cachePath = cacheRoot.resolve("${request.digests.first().normalizedValue}.sha1")
        Files.write(cachePath, payload)
        val callbackEntered = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val useJob = async {
            ClientContentStore(cacheRoot).use(listOf(request)) {
                callbackEntered.complete(Unit)
                releaseCallback.await()
            }
        }

        callbackEntered.await()
        val cleanup = DownloadCacheCleanupService(
            cacheRoot = cacheRoot,
            versionsRoot = Files.createTempDirectory("rdi-content-active-lease-versions"),
        ).cleanup().getOrThrow()

        assertEquals(0, cleanup.deletedFiles)
        assertEquals(1, cleanup.busyFiles)
        assertTrue(Files.exists(cachePath))
        releaseCallback.complete(Unit)
        assertTrue(useJob.await().isSuccess)
    }

    @Test
    fun `verified content uses cache and avoids a second download`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-content-cache")
        val payload = "content-store-payload\nwith whitespace".toByteArray()
        val downloads = AtomicInteger()
        val source = ContentSource(downloader = { target, progress ->
            downloads.incrementAndGet()
            Files.write(target, payload)
            progress(DownloadProgress(payload.size.toLong(), payload.size.toLong(), 0.0))
            Result.success(target)
        })
        val networkRequest = request("network", "mods/example.jar", payload, source)
        val networkStore = ClientContentStore(cacheRoot)
        val firstTargetRoot = Files.createTempDirectory("rdi-content-target-first")
        networkStore.materialize(listOf(networkRequest), firstTargetRoot).getOrThrow()
        Files.delete(firstTargetRoot.resolve("mods/example.jar"))

        val secondTargetRoot = Files.createTempDirectory("rdi-content-target-second")
        networkStore.materialize(listOf(networkRequest), secondTargetRoot).getOrThrow()

        assertEquals(1, downloads.get())
        assertTrue(Files.isRegularFile(cacheRoot.resolve("${networkRequest.digests[0].value}.sha1")))
        assertTrue(Files.isRegularFile(cacheRoot.resolve("${networkRequest.digests[1].value}.sha256")))
        assertTrue(Files.isRegularFile(cacheRoot.resolve("${networkRequest.digests[2].value}.murmur2")))
    }

    @Test
    fun `offline local downloader is verified and committed to content cache`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-content-local-cache")
        val staged = Files.createTempFile("rdi-content-local-stage", ".jar")
        val payload = "staged-embedded-mod".toByteArray()
        Files.write(staged, payload)
        val request = request("local", "mods/staged.jar", payload, source = null).copy(
            sources = listOf(
                ContentSource(
                    knownSize = payload.size.toLong(),
                    localOnly = true,
                    downloader = { target, _ ->
                        runCatching {
                            Files.copy(staged, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                )
            ),
            allowNetwork = false,
        )

        ClientContentStore(cacheRoot).use(listOf(request)) { }

        assertTrue(Files.isRegularFile(cacheRoot.resolve("${request.digests[0].value}.sha1")))
    }

    @Test
    fun `local imported content keeps its filename in transaction work root`() = runBlocking {
        val payload = "imported-client-pack".toByteArray()
        val staged = Files.createTempFile("rdi-content-import-stage", ".tar.zst")
        Files.write(staged, payload)
        val request = request(
            id = "imported-pack",
            relativePath = "0123456789abcdef01234567_version.tar.zst",
            payload = payload,
            source = ContentSource(
                knownSize = payload.size.toLong(),
                localOnly = true,
                downloader = { target, _ ->
                    runCatching {
                        Files.copy(staged, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                },
            ),
        ).copy(allowNetwork = false)
        val transactionRoot = Files.createTempDirectory("rdi-content-import-transaction")
        val cacheRoot = Files.createTempDirectory("rdi-content-import-cache")

        val target = ClientContentStore(cacheRoot)
            .materialize(listOf(request), transactionRoot)
            .getOrThrow()
            .single()

        assertEquals(
            transactionRoot.resolve("0123456789abcdef01234567_version.tar.zst"),
            target,
        )
        assertContentEquals(payload, Files.readAllBytes(target))
        assertTrue(Files.isRegularFile(cacheRoot.resolve("${request.digests[0].value}.sha1")))
    }

    @Test
    fun `target is preflighted and cache write failure still installs content`() = runBlocking {
        val payload = "direct-install".toByteArray()
        val downloads = AtomicInteger()
        val source = ContentSource(downloader = { target, _ ->
            downloads.incrementAndGet()
            Files.write(target, payload)
            Result.success(target)
        })
        val request = request("direct", "mods/direct.jar", payload, source)

        val targetFile = Files.createTempFile("rdi-content-target-file", ".tmp")
        val preflightStore = ClientContentStore(Files.createTempDirectory("rdi-content-preflight-cache"))
        val preflight = preflightStore.materialize(listOf(request), targetFile)
        assertTrue(preflight.isFailure)
        assertEquals(0, downloads.get())

        val brokenCache = Files.createTempFile("rdi-content-cache-file", ".tmp")
        val targetRoot = Files.createTempDirectory("rdi-content-direct-target")
        val directStore = ClientContentStore(brokenCache)
        val target = directStore.materialize(listOf(request), targetRoot).getOrThrow().single()
        assertContentEquals(payload, Files.readAllBytes(target))
        assertEquals(1, downloads.get())
        assertTrue(Files.isRegularFile(brokenCache))
    }

    @Test
    fun `cancellation is propagated instead of converted to failure`() = runBlocking {
        val request = request(
            id = "cancel",
            relativePath = "mods/cancel.jar",
            payload = byteArrayOf(1),
            source = ContentSource(downloader = { _, _ ->
                throw CancellationException("cancelled")
            }),
        )
        val store = ClientContentStore(Files.createTempDirectory("rdi-content-cancel-cache"))
        assertFailsWith<CancellationException> {
            store.materialize(listOf(request), Files.createTempDirectory("rdi-content-cancel-target"))
        }
    }

    private fun request(
        id: String,
        relativePath: String,
        payload: ByteArray,
        source: ContentSource?,
    ): ContentRequest {
        val digestSource = Files.createTempFile("rdi-content-digest", ".bin")
        Files.write(digestSource, payload)
        val sha1 = digest(payload, "SHA-1")
        val sha256 = digest(payload, "SHA-256")
        val murmur = digestSource.murmur2.toULong().toString()
        Files.deleteIfExists(digestSource)
        return ContentRequest(
            id = id,
            relativePath = relativePath,
            size = payload.size.toLong(),
            digests = listOf(
                ContentDigest(ContentDigestAlgorithm.SHA1, sha1),
                ContentDigest(ContentDigestAlgorithm.SHA256, sha256),
                ContentDigest(ContentDigestAlgorithm.MURMUR2, murmur),
            ),
            sources = listOfNotNull(source),
        )
    }

    private fun digest(payload: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(payload).joinToString("") { "%02x".format(it) }

    private fun assumeSymbolicLink(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: FileSystemException) {
            assumeTrue(false, "symbolic links are not available")
        }
    }

    private fun testCurseForgeMod(projectId: String, fileId: String, hash: String = fileId) = Mod(
        platform = "cf",
        projectId = projectId,
        slug = "cf-$fileId",
        fileId = fileId,
        hash = hash,
    )
}
