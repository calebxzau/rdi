package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.client.model.UiMod
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.modpacktest.ModpackTestStatus
import calebxzhou.rdi.client.service.toUiMods
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Task2Entry
import calebxzhou.rdi.common.service.validateIconUrlAddress
import calebxzau.rdi.client.packproc.LoadedLocalModpack
import calebxzau.rdi.client.packproc.LoadedServerPackResult
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.service.murmur2
import calebxzau.rdi.client.packproc.LocalModpackSourceType
import calebxzhou.rdi.client.service.content.ClientContentStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import java.io.File
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModpackUploadViewModelTest {
    @Test
    fun `same content client override explicitly present on server becomes shared`() = runBlocking {
        val client = Mod("mr", "project", "example", "file", "a".repeat(40), Mod.Side.CLIENT, clientOnlyOverride = true)
        val server = client.copy(side = Mod.Side.SERVER, clientOnlyOverride = false)
        val merged = mergeAsBoth(UiMod(client), UiMod(server))
        assertEquals(Mod.Side.BOTH, merged.side)
        assertEquals(false, merged.mod.clientOnlyOverride)
    }

    @Test
    fun `different client override and selected server versions remain separate`() = runBlocking {
        // Invalid cache digests deliberately keep this pure merge test away from content stores.
        val client = Mod("mr", "project", "example", "client-file", "client-hash", Mod.Side.CLIENT, clientOnlyOverride = true)
        val server = client.copy(fileId = "server-file", hash = "server-hash", side = Mod.Side.SERVER, clientOnlyOverride = false)
        val merged = mergeClientAndServerMods(listOf(UiMod(client)), listOf(UiMod(server)))
        assertEquals(2, merged.size)
        assertEquals(Mod.Side.CLIENT, merged.first { it.hash == "client-hash" }.side)
        assertEquals(Mod.Side.SERVER, merged.first { it.hash == "server-hash" }.side)
    }

    @Test
    fun `merge keeps URLs separate for different local content`() = runBlocking {
        val root = Files.createTempDirectory("rdi-merge-content-mismatch").toFile()
        try {
            val clientFile = root.resolve("client.jar").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val serverFile = root.resolve("server.jar").also { it.writeBytes(byteArrayOf(4, 5, 6)) }
            val client = Mod(
                "cf", "project", "mod", "client", clientFile.murmur2.toULong().toString(),
                downloadUrls = listOf("client-url")
            )
            val server = Mod(
                "mr", "project", "mod", "server", serverFile.sha1,
                downloadUrls = listOf("server-url")
            )

            val merged = mergeAsBoth(UiMod(client, file = clientFile), UiMod(server, file = serverFile))

            assertEquals(listOf("client-url"), merged.mod.downloadUrls)
            assertEquals(clientFile, merged.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `merge combines URLs for matching metadata content`() = runBlocking {
        val client = Mod("cf", "project", "mod", "client", "1805207383", downloadUrls = listOf("client-url"))
        val server = Mod("cf", "project", "mod", "server", "1805207383", downloadUrls = listOf("server-url"))

        val merged = mergeAsBoth(UiMod(client), UiMod(server))

        assertEquals(listOf("client-url", "server-url"), merged.mod.downloadUrls)
    }

    @Test
    fun `merge does not inherit a server file when its bytes fail the client digest`() = runBlocking {
        val root = Files.createTempDirectory("rdi-merge-file-mismatch").toFile()
        try {
            val serverFile = root.resolve("server.jar").also { it.writeBytes(byteArrayOf(4, 5, 6)) }
            val client = Mod("mr", "project", "mod", "client", "a".repeat(40))
            val server = client.copy(downloadUrls = listOf("server-url"))

            val merged = mergeAsBoth(UiMod(client), UiMod(server, file = serverFile))

            assertEquals(listOf("server-url"), merged.mod.downloadUrls)
            assertNull(merged.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `merge can confirm different platform metadata from identical local bytes`() = runBlocking {
        val root = Files.createTempDirectory("rdi-merge-cross-platform").toFile()
        try {
            val clientFile = root.resolve("client.jar").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val serverFile = root.resolve("server.jar").also { it.writeBytes(clientFile.readBytes()) }
            val client = Mod(
                "cf", "project", "mod", "client", clientFile.murmur2.toULong().toString(),
                downloadUrls = listOf("client-url")
            )
            val server = Mod(
                "mr", "project", "mod", "server", serverFile.sha1,
                downloadUrls = listOf("server-url")
            )

            val merged = mergeAsBoth(UiMod(client, file = clientFile), UiMod(server, file = serverFile))

            assertEquals(listOf("client-url", "server-url"), merged.mod.downloadUrls)
            assertEquals(clientFile, merged.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `server pack rejects Bukkit family root marker files`() = runBlocking {
        val gateway = RdiModpackUploadGateway(noCallModCatalog())
        val markerNames = listOf("spigot.yml", "arclight.conf", "bukkit.yml")

        markerNames.forEach { markerName ->
            val directory = Files.createTempDirectory("rdi-upload-invalid-server").toFile()
            directory.resolve(markerName).writeText("")

            val result = gateway.prepareServerPack(
                directory = directory,
                clientUiMods = emptyList(),
                onProgress = { error("invalid server marker should reject before progress") },
            )

            val exception = assertIs<ModpackError>(result.exceptionOrNull())
            assertEquals("请选择正确安装了模组载入器的服务端", exception.message)
        }
    }

    @Test
    fun `nested or directory marker does not trigger Bukkit family root validation`() = runBlocking {
        val gateway = RdiModpackUploadGateway(noCallModCatalog())
        val nestedMarkerDirectory = Files.createTempDirectory("rdi-upload-nested-marker").toFile()
        nestedMarkerDirectory.resolve("nested").mkdirs()
        nestedMarkerDirectory.resolve("nested/spigot.yml").writeText("")
        val directoryMarkerDirectory = Files.createTempDirectory("rdi-upload-directory-marker").toFile()
        directoryMarkerDirectory.resolve("bukkit.yml").mkdirs()

        listOf(nestedMarkerDirectory, directoryMarkerDirectory).forEach { directory ->
            val result = gateway.prepareServerPack(directory, emptyList(), {})

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message != "请选择正确安装了模组载入器的服务端")
        }
    }

    @Test
    fun `batched cached mod id reads tolerate same filename for different identities`() = runBlocking {
        val jarBytes = ByteArrayOutputStream().use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/mods.toml"))
                jar.write("[[mods]]\nmodId=\"shared-id\"\n".toByteArray())
                jar.closeEntry()
            }
            output.toByteArray()
        }
        val hash = jarBytes.sha1
        val cacheRoot = Files.createTempDirectory("rdi-upload-mod-id-batch")
        Files.write(cacheRoot.resolve("$hash.sha1"), jarBytes)
        val first = Mod("mr", "first-project", "same-name", "first-file", hash)
        val second = Mod("mr", "second-project", "same-name", "second-file", hash)

        assertEquals(first.fileName, second.fileName)
        val ids = readInstalledModIdsForTest(
            listOf(UiMod(first), UiMod(second)),
            ClientContentStore(cacheRoot),
        )

        assertEquals(2, ids.size)
        assertEquals("shared-id", ids["mr:first-project:first-file:$hash"])
        assertEquals("shared-id", ids["mr:second-project:second-file:$hash"])
    }

    @Test
    fun `icon address rejects unsupported bare host`() {
        val error = assertFailsWith<RequestError> {
            validateIconUrlAddress("http://abcabcba").getOrThrow()
        }

        assertEquals("只接受整合包发布网页的图标链接", error.message)
    }

    @Test
    fun `create draft immediately surfaces shared icon address error`() {
        var fullValidationCount = 0
        val viewModel = ModpackUploadViewModel(FakeModpackUploadGateway()) {
            fullValidationCount++
            Result.success(Unit)
        }

        viewModel.updateDraft(validCreateDraft().copy(iconUrl = "http://abcabcba"))

        assertEquals("只接受整合包发布网页的图标链接", viewModel.uiState.value.draftErrors.iconUrl)
        assertEquals(0, fullValidationCount)
    }

    @Test
    fun `icon address accepts allowed host and subdomain`() {
        listOf(
            "https://modrinth.com/icon.png",
            "https://cdn.modrinth.com/icon.png",
        ).forEach { url ->
            validateIconUrlAddress(url).getOrThrow()
        }
    }

    @Test
    fun `icon address rejects spoofed allowed domain`() {
        val error = assertFailsWith<RequestError> {
            validateIconUrlAddress("https://evilforgecdn.net/icon.png").getOrThrow()
        }

        assertEquals("只接受整合包发布网页的图标链接", error.message)
    }

    @Test
    fun `icon address only accepts http and https`() {
        val error = assertFailsWith<RequestError> {
            validateIconUrlAddress("ftp://cdn.modrinth.com/icon.png").getOrThrow()
        }

        assertEquals("链接必须以http或https开头", error.message)
    }

    @Test
    fun `loading client pack enters edit mode with normalized draft`() = runBlocking {
        val prepared = PreparedClientPack(testPack(), listOf(UiMod(testMod())))
        val gateway = FakeModpackUploadGateway(preparedClientPack = prepared)
        val viewModel = ModpackUploadViewModel(gateway)

        viewModel.loadClientPack(File("client-pack.mrpack"))

        val event = assertIs<ModpackUploadEvent.ClientPackLoaded>(viewModel.events.first())
        val state = viewModel.uiState.filter { !it.loading && !it.uiModsLoading && it.editMode }.first()
        assertEquals(0, event.initialTab)
        assertEquals("测试包", state.draft.name)
        assertEquals("1_0", state.draft.versionName)
        assertEquals("1.21.1", state.mcVersionText)
        assertEquals("neoforge", state.modloaderText)
        assertEquals(1, state.uiMods.size)
        assertEquals(ModpackTestStatus.NOT_RUN, state.clientTestStatus)
        assertEquals(ModpackTestStatus.NOT_RUN, state.serverTestStatus)
    }

    @Test
    fun `excluded mca detection shows dismissible warning`() = runBlocking {
        val prepared = PreparedClientPack(
            pack = testPack().copy(containsExcludedMcaFiles = true),
            uiMods = listOf(UiMod(testMod())),
        )
        val viewModel = ModpackUploadViewModel(FakeModpackUploadGateway(preparedClientPack = prepared))

        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.loading && !it.uiModsLoading && it.editMode }.first()

        assertEquals(MODPACK_UPLOAD_MCA_WARNING, viewModel.uiState.value.warningMessage)
        viewModel.clearWarningMessage()
        assertNull(viewModel.uiState.value.warningMessage)
    }

    @Test
    fun `starting a new client selection clears stale mca warning`() = runBlocking {
        val prepared = PreparedClientPack(
            pack = testPack().copy(containsExcludedMcaFiles = true),
            uiMods = listOf(UiMod(testMod())),
        )
        val viewModel = ModpackUploadViewModel(FakeModpackUploadGateway(preparedClientPack = prepared))

        viewModel.loadClientPack(File("first.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.loading && !it.uiModsLoading && it.editMode }.first()
        assertEquals(MODPACK_UPLOAD_MCA_WARNING, viewModel.uiState.value.warningMessage)

        viewModel.loadClientPack(File("second.mrpack"))
        assertNull(viewModel.uiState.value.warningMessage)
    }

    @Test
    fun `version draft replaces spaces with underscores`() {
        val viewModel = ModpackUploadViewModel(FakeModpackUploadGateway())

        viewModel.updateVersionName("release candidate 1")

        assertEquals("release_candidate_1", viewModel.uiState.value.draft.versionName)
    }

    @Test
    fun `submitting before client pack reports error without queueing`() = runBlocking {
        val gateway = FakeModpackUploadGateway()
        val viewModel = ModpackUploadViewModel(gateway)
        awaitInitialization(viewModel)
        assertTrue(viewModel.uiState.value.uploadInitializationReady)

        viewModel.submitUpload()

        val state = viewModel.uiState.filter { it.errorMessage != null }.first()
        assertEquals("请先选择整合包文件", state.errorMessage)
        assertNull(gateway.submission)
    }

    @Test
    fun `submitting while initialization is pending does not queue`() = runBlocking {
        val audioReadyGate = CompletableDeferred<Result<Unit>>()
        val gateway = FakeModpackUploadGateway(audioReadyGate = audioReadyGate)
        val viewModel = ModpackUploadViewModel(gateway)

        viewModel.submitUpload()

        assertEquals("正在准备上传", viewModel.uiState.value.errorMessage)
        assertEquals(0, gateway.preflightCount)
        assertNull(gateway.submission)

        audioReadyGate.complete(Result.success(Unit))
        awaitInitialization(viewModel)
    }

    @Test
    fun `valid upload emits submitted task id`() = runBlocking {
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            ignoreModpackTestInitially = true,
            uploadRunId = "upload-9",
        )
        val viewModel = ModpackUploadViewModel(gateway) { Result.success(Unit) }
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()
        viewModel.updateDraft(
            viewModel.uiState.value.draft.copy(
                name = "新整合包",
                versionName = "2.0",
                iconUrl = "https://cdn.modrinth.com/icon.png",
                info = "这是一个有效的整合包简介",
                categories = listOf(Modpack.Category.ADVENTURE),
            )
        )

        viewModel.submitUpload()

        val event = assertIs<ModpackUploadEvent.UploadSubmitted>(viewModel.events.first())
        assertEquals("upload-9", event.runId)
        assertEquals("新整合包", gateway.submission?.draft?.name)
        assertEquals("2.0", gateway.submission?.draft?.versionName)
    }

    @Test
    fun `create icon validation failure prevents upload submission`() = runBlocking {
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            ignoreModpackTestInitially = true,
        )
        val viewModel = ModpackUploadViewModel(gateway) {
            Result.failure(IllegalArgumentException("图标链接不是图片"))
        }
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()
        viewModel.updateDraft(validCreateDraft())

        viewModel.submitUpload()

        val state = viewModel.uiState.filter { it.errorMessage != null }.first()
        assertEquals("图标链接不是图片", state.errorMessage)
        assertNull(gateway.submission)
    }

    @Test
    fun `duplicate create submission is ignored while icon validation runs`() = runBlocking {
        val validationResult = CompletableDeferred<Result<Unit>>()
        val validationStarted = CompletableDeferred<Unit>()
        var validationCount = 0
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            ignoreModpackTestInitially = true,
        )
        val viewModel = ModpackUploadViewModel(gateway) {
            validationCount++
            validationStarted.complete(Unit)
            validationResult.await()
        }
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()
        viewModel.updateDraft(validCreateDraft())

        viewModel.submitUpload()
        validationStarted.await()
        viewModel.submitUpload()

        assertEquals(1, validationCount)
        assertNull(gateway.submission)
        validationResult.complete(Result.success(Unit))
        assertIs<ModpackUploadEvent.UploadSubmitted>(viewModel.events.first())
    }

    @Test
    fun `changing create draft while icon validation runs prevents stale submission`() = runBlocking {
        val validationResult = CompletableDeferred<Result<Unit>>()
        val validationStarted = CompletableDeferred<Unit>()
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            ignoreModpackTestInitially = true,
        )
        val viewModel = ModpackUploadViewModel(gateway) {
            validationStarted.complete(Unit)
            validationResult.await()
        }
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()
        viewModel.updateDraft(validCreateDraft())
        viewModel.submitUpload()
        validationStarted.await()

        viewModel.updateDraft(
            validCreateDraft().copy(iconUrl = "https://modrinth.com/changed.png")
        )
        validationResult.complete(Result.success(Unit))

        val state = viewModel.uiState
            .filter { it.errorMessage == "上传信息已更改，请重新提交" }
            .first()
        assertNull(gateway.submission)
        assertTrue(!state.iconValidationRunning)
    }

    @Test
    fun `route-fixed create mode cannot switch to update`() = runBlocking {
        val viewModel = ModpackUploadViewModel(FakeModpackUploadGateway())
        awaitInitialization(viewModel)

        viewModel.chooseUpdateMode(updateTarget())
        viewModel.requestUpdateModeSelection()
        viewModel.requestUploadModeDialog()

        val state = viewModel.uiState.value
        assertEquals(ModpackUploadMode.CREATE, state.uploadMode)
        assertNull(state.selectedUpdateTarget)
    }

    @Test
    fun `route-fixed update mode survives local pack loading and selector calls`() = runBlocking {
        val target = updateTarget()
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            updateTarget = target,
        )
        val viewModel = ModpackUploadViewModel(gateway, modpackId = target.id.toHexString())

        awaitInitialization(viewModel)
        viewModel.chooseCreateMode()
        viewModel.chooseUpdateMode(updateTarget().copy(name = "其他包"))
        viewModel.loadClientPack(File("client-pack.mrpack"))
        awaitClientPackLoaded(viewModel)
        val state = viewModel.uiState.value

        assertEquals(ModpackUploadMode.UPDATE, state.uploadMode)
        assertEquals(target, state.selectedUpdateTarget)
        assertEquals(target.name, state.draft.name)
    }

    @Test
    fun `target initialization failure blocks selection and submission`() = runBlocking {
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            updateTargetResult = Result.failure(IllegalStateException("forbidden")),
        )
        val viewModel = ModpackUploadViewModel(
            gateway = gateway,
            modpackId = updateTarget().id.toHexString(),
        )

        awaitInitialization(viewModel)
        assertEquals("无法加载要更新的整合包，请返回后重试", viewModel.uiState.value.errorMessage)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.submitUpload()

        assertNull(viewModel.uiState.value.loadedModpack)
        assertEquals("无法加载要更新的整合包，请返回后重试", viewModel.uiState.value.errorMessage)
        assertNull(gateway.submission)
    }

    @Test
    fun `audio initialization failure blocks create upload`() = runBlocking {
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            audioReadyResult = Result.failure(IllegalStateException("audio unavailable")),
        )
        val viewModel = ModpackUploadViewModel(gateway)

        awaitInitialization(viewModel)
        assertEquals("audio unavailable", viewModel.uiState.value.errorMessage)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.submitUpload()

        assertNull(viewModel.uiState.value.loadedModpack)
        assertEquals("audio unavailable", viewModel.uiState.value.errorMessage)
        assertNull(gateway.submission)
    }

    @Test
    fun `audio initialization failure blocks update before target load`() = runBlocking {
        val gateway = FakeModpackUploadGateway(
            audioReadyResult = Result.failure(IllegalStateException("audio unavailable")),
        )
        val viewModel = ModpackUploadViewModel(
            gateway = gateway,
            modpackId = updateTarget().id.toHexString(),
        )

        awaitInitialization(viewModel)
        assertEquals("audio unavailable", viewModel.uiState.value.errorMessage)
        assertEquals(0, gateway.updateTargetLoadCount)
        viewModel.submitUpload()

        assertEquals("audio unavailable", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `double update submit while preflight runs queues one upload after success`() = runBlocking {
        val preflightResult = CompletableDeferred<Result<Unit>>()
        val preflightStarted = CompletableDeferred<Unit>()
        val target = updateTarget()
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            updateTarget = target,
            ignoreModpackTestInitially = true,
            preflightGate = preflightResult,
            onPreflightStarted = { preflightStarted.complete(Unit) },
        )
        val viewModel = ModpackUploadViewModel(
            gateway = gateway,
            modpackId = target.id.toHexString(),
        )
        awaitInitialization(viewModel)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        awaitClientPackLoaded(viewModel)
        viewModel.updateVersionName("2.0")

        viewModel.submitUpload()
        withTimeout(5_000) { preflightStarted.await() }
        viewModel.submitUpload()

        assertEquals(1, gateway.preflightCount)
        assertNull(gateway.submission)
        preflightResult.complete(Result.success(Unit))

        assertIs<ModpackUploadEvent.UploadSubmitted>(awaitUploadSubmitted(viewModel))
        assertEquals(1, gateway.preflightCount)
        assertEquals("2.0", gateway.submission?.draft?.versionName)
        assertTrue(!viewModel.uiState.value.iconValidationRunning)
    }

    @Test
    fun `changing update draft while preflight runs prevents stale submission`() = runBlocking {
        val preflightResult = CompletableDeferred<Result<Unit>>()
        val preflightStarted = CompletableDeferred<Unit>()
        val target = updateTarget()
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            updateTarget = target,
            ignoreModpackTestInitially = true,
            preflightGate = preflightResult,
            onPreflightStarted = { preflightStarted.complete(Unit) },
        )
        val viewModel = ModpackUploadViewModel(
            gateway = gateway,
            modpackId = target.id.toHexString(),
        )
        awaitInitialization(viewModel)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        awaitClientPackLoaded(viewModel)
        viewModel.updateVersionName("2.0")

        viewModel.submitUpload()
        withTimeout(5_000) { preflightStarted.await() }
        viewModel.updateVersionName("3.0")
        preflightResult.complete(Result.success(Unit))

        val state = withTimeout(5_000) {
            viewModel.uiState
                .filter { it.errorMessage == "上传信息已更改，请重新提交" }
                .first()
        }
        assertEquals(1, gateway.preflightCount)
        assertNull(gateway.submission)
        assertTrue(!state.iconValidationRunning)
    }

    @Test
    fun `update submission skips create icon validation`() = runBlocking {
        var validationCount = 0
        val target = Modpack.BriefVo(
            id = ObjectId("66a000000000000000000001"),
            name = "已有包",
            icon = "http://abcabcba",
            info = "这是一个已有整合包简介",
            categories = listOf(Modpack.Category.TECH),
        )
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            updateTarget = target,
            ignoreModpackTestInitially = true,
        )
        val viewModel = ModpackUploadViewModel(
            gateway = gateway,
            modpackId = target.id.toHexString(),
            validateFullIconUrl = {
                validationCount++
                Result.failure(IllegalStateException("UPDATE不应验证图标"))
            },
        )
        awaitInitialization(viewModel)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        awaitClientPackLoaded(viewModel)

        viewModel.submitUpload()

        assertIs<ModpackUploadEvent.UploadSubmitted>(awaitUploadSubmitted(viewModel))
        assertEquals(0, validationCount)
        assertEquals(1, gateway.preflightCount)
        assertEquals(target.id, gateway.submission?.updateModpackId)
    }

    @Test
    fun `preflight failure prevents upload task submission`() = runBlocking {
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            ignoreModpackTestInitially = true,
            preflightResult = Result.failure(RequestError("同名整合包已存在")),
        )
        val viewModel = ModpackUploadViewModel(gateway) { Result.success(Unit) }
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()
        viewModel.updateDraft(validCreateDraft())

        viewModel.submitUpload()

        val state = viewModel.uiState.filter { it.errorMessage != null }.first()
        assertEquals("上传检查失败：同名整合包已存在", state.errorMessage)
        assertNull(gateway.submission)
    }

    @Test
    fun `new upload requires intro icon and category`() = runBlocking {
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            ignoreModpackTestInitially = true,
        )
        val viewModel = ModpackUploadViewModel(gateway)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()
        viewModel.updateDraft(
            viewModel.uiState.value.draft.copy(
                name = "新整合包",
                versionName = "2.0",
            )
        )

        viewModel.submitUpload()

        val state = viewModel.uiState.value
        assertEquals("简介不能为空", state.draftErrors.info)
        assertEquals("图标链接不能为空", state.draftErrors.iconUrl)
        assertEquals("至少选择1个分类", state.draftErrors.categories)
        assertEquals("简介不能为空", state.uploadDisabledReason)
        assertNull(gateway.submission)
    }

    @Test
    fun `upload disabled reason reports mod hydration`() {
        val state = ModpackUploadUiState(
            draft = ModpackUploadDraft(
                iconUrl = "https://cdn.modrinth.com/icon.png",
                info = "这是一个有效的整合包简介",
                categories = listOf(Modpack.Category.ADVENTURE),
            ),
            uiModsLoading = true,
        )

        assertEquals("正在补充Mod详细信息", state.uploadDisabledReason)
        assertTrue(!state.canSubmitUpload)
    }

    @Test
    fun `missing mods require confirmation before client test`() = runBlocking {
        val missing = testMod()
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), listOf(UiMod(missing))),
            missingMods = listOf(missing),
        )
        val viewModel = ModpackUploadViewModel(gateway)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()

        viewModel.startClientTest()

        val state = viewModel.uiState.filter { it.pendingMissingModDownload != null }.first()
        assertEquals("客户端测试", state.pendingMissingModDownload?.usage)
        assertEquals(listOf(missing), state.pendingMissingModDownload?.mods)
        assertEquals(ModpackTestStatus.NOT_RUN, state.clientTestStatus)
    }

    @Test
    fun `route loads exact update target and copies its metadata`() = runBlocking {
        val target = updateTarget()
        val gateway = FakeModpackUploadGateway(updateTarget = target)
        val viewModel = ModpackUploadViewModel(gateway, modpackId = target.id.toHexString())

        awaitInitialization(viewModel)
        val state = viewModel.uiState.value
        assertEquals(ModpackUploadMode.UPDATE, state.uploadMode)
        assertEquals(target, state.selectedUpdateTarget)
        assertEquals("已有包", state.draft.name)
        assertEquals(listOf(Modpack.Category.TECH), state.draft.categories)
        assertEquals(target.icon, state.draft.iconUrl)
        assertEquals(target.info, state.draft.info)
        assertEquals(1, gateway.updateTargetLoadCount)
    }

    @Test
    fun `loading server pack updates merged mods and summary`() = runBlocking {
        val mergedMod = testMod().copy(side = Mod.Side.BOTH)
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            preparedServerPack = PreparedServerPack(
                pack = LoadedServerPackResult(
                    mods = listOf(mergedMod),
                    serverExtraFiles = emptyList(),
                ),
                uiMods = listOf(UiMod(mergedMod)),
            ),
        )
        val viewModel = ModpackUploadViewModel(gateway)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()

        viewModel.loadServerPack(File("server"))

        val state = viewModel.uiState.filter { !it.loading && it.serverPackName != null }.first()
        assertEquals(Mod.Side.BOTH, state.uiMods.single().side)
        assertEquals("已选择服务端(1个mod)", state.serverPackName)
    }

    @Test
    fun `detected server mca exclusions show the same warning`() = runBlocking {
        val mergedMod = testMod().copy(side = Mod.Side.BOTH)
        val gateway = FakeModpackUploadGateway(
            preparedClientPack = PreparedClientPack(testPack(), testPack().mods.toUiMods()),
            preparedServerPack = PreparedServerPack(
                pack = LoadedServerPackResult(
                    mods = listOf(mergedMod),
                    serverExtraFiles = emptyList(),
                    containsExcludedMcaFiles = true,
                ),
                uiMods = listOf(UiMod(mergedMod)),
            ),
        )
        val viewModel = ModpackUploadViewModel(gateway)
        viewModel.loadClientPack(File("client-pack.mrpack"))
        viewModel.events.first()
        viewModel.uiState.filter { !it.uiModsLoading }.first()

        viewModel.loadServerPack(File("server"))

        val state = viewModel.uiState.filter { !it.loading && it.serverPackName != null }.first()
        assertEquals(MODPACK_UPLOAD_MCA_WARNING, state.warningMessage)
    }

    @Test
    fun `closing screen cleans workspace before navigation`() = runBlocking {
        val gateway = FakeModpackUploadGateway()
        val viewModel = ModpackUploadViewModel(gateway)

        viewModel.closeScreen()

        assertIs<ModpackUploadEvent.NavigateBack>(viewModel.events.first())
        assertTrue(gateway.cleanupCalled)
    }

    @Suppress("UNCHECKED_CAST")
    private fun noCallModCatalog(): ModCatalog = Proxy.newProxyInstance(
        ModCatalog::class.java.classLoader,
        arrayOf(ModCatalog::class.java),
    ) { _, method, _ ->
        error("catalog should not be called: ${method.name}")
    } as ModCatalog

    private class FakeModpackUploadGateway(
        private val preparedClientPack: PreparedClientPack? = null,
        private val preparedServerPack: PreparedServerPack? = null,
        private val missingMods: List<Mod> = emptyList(),
        private val updateTarget: Modpack.BriefVo? = null,
        private val audioReadyResult: Result<Unit> = Result.success(Unit),
        private val audioReadyGate: CompletableDeferred<Result<Unit>>? = null,
        private val updateTargetResult: Result<Modpack.BriefVo>? = null,
        override val ignoreModpackTestInitially: Boolean = false,
        private val uploadRunId: String = "upload-1",
        private val preflightResult: Result<Unit> = Result.success(Unit),
        private val preflightGate: CompletableDeferred<Result<Unit>>? = null,
        private val onPreflightStarted: (() -> Unit)? = null,
    ) : ModpackUploadGateway {
        override val taskEntries: StateFlow<List<Task2Entry>> = MutableStateFlow(emptyList())
        var submission: ModpackUploadSubmission? = null
        var preflightCount = 0
        var cleanupCalled = false
        var updateTargetLoadCount = 0

        override suspend fun ensureAudioReady(): Result<Unit> = audioReadyGate?.await() ?: audioReadyResult

        override suspend fun loadUpdateTarget(modpackId: String): Result<Modpack.BriefVo> {
            updateTargetLoadCount++
            return updateTargetResult
                ?: updateTarget?.takeIf { it.id.toHexString() == modpackId }?.let(Result.Companion::success)
                ?: Result.failure(IllegalArgumentException("unexpected target $modpackId"))
        }

        override suspend fun loadUploadedModpacks(): Result<List<Modpack.BriefVo>> =
            Result.success(emptyList())

        override suspend fun preflightUpload(submission: ModpackUploadSubmission): Result<Unit> {
            preflightCount++
            onPreflightStarted?.invoke()
            return preflightGate?.await() ?: preflightResult
        }

        override suspend fun prepareClientPack(
            file: File,
            onProgress: (calebxzhou.rdi.common.model.LoadProgress) -> Unit,
        ): Result<PreparedClientPack> = preparedClientPack?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("missing prepared pack"))

        override fun hydrateMods(mods: List<UiMod>) = flowOf(mods)

        override suspend fun prepareServerPack(
            directory: File,
            clientUiMods: List<UiMod>,
            onProgress: (calebxzhou.rdi.common.model.LoadProgress) -> Unit,
        ): Result<PreparedServerPack> = preparedServerPack?.let { Result.success(it) }
            ?: Result.failure(UnsupportedOperationException())

        override suspend fun validateRuntime(mcVersion: McVersion): Result<Unit> = Result.success(Unit)

        override suspend fun findMissingMods(mods: List<Mod>): Result<List<Mod>> =
            Result.success(missingMods)

        override fun queueMissingModDownload(mods: List<Mod>): Result<String> = Result.success("download-1")

        override fun queueTestServer(pack: LoadedLocalModpack): Result<String> = Result.success("server-1")

        override fun queueUpload(submission: ModpackUploadSubmission): Result<String> {
            this.submission = submission
            return Result.success(uploadRunId)
        }

        override fun enableIgnoreModpackTest(): Result<Unit> = Result.success(Unit)

        override suspend fun cleanup(): Result<Unit> {
            cleanupCalled = true
            return Result.success(Unit)
        }
    }

    private companion object {
        suspend fun awaitInitialization(viewModel: ModpackUploadViewModel) {
            withTimeout(5_000) {
                viewModel.uiState.filter { !it.loading }.first()
            }
        }

        suspend fun awaitClientPackLoaded(viewModel: ModpackUploadViewModel) {
            withTimeout(5_000) {
                viewModel.events.first { it is ModpackUploadEvent.ClientPackLoaded }
            }
            withTimeout(5_000) {
                viewModel.uiState.filter { !it.loading && !it.uiModsLoading && it.editMode }.first()
            }
        }

        suspend fun awaitUploadSubmitted(viewModel: ModpackUploadViewModel): ModpackUploadEvent {
            return withTimeout(5_000) {
                viewModel.events.first { it is ModpackUploadEvent.UploadSubmitted }
            }
        }

        fun updateTarget() = Modpack.BriefVo(
            id = ObjectId("66a000000000000000000001"),
            name = "已有包",
            icon = "https://cdn.modrinth.com/icon.png",
            info = "这是一个已有整合包简介",
            categories = listOf(Modpack.Category.TECH),
        )

        fun validCreateDraft() = ModpackUploadDraft(
            name = "新整合包",
            versionName = "2.0",
            iconUrl = "https://cdn.modrinth.com/icon.png",
            info = "这是一个有效的整合包简介",
            categories = listOf(Modpack.Category.ADVENTURE),
        )

        fun testPack() = LoadedLocalModpack(
            sourceType = LocalModpackSourceType.MODRINTH,
            sourceDir = File("source"),
            packName = "测试包",
            packVersion = "1 0",
            mcVersion = McVersion.V211,
            modloader = ModLoader.neoforge,
            mods = listOf(testMod()),
        )

        fun testMod() = Mod(
            platform = "mr",
            projectId = "project",
            slug = "example-mod",
            fileId = "file",
            hash = "abc",
        )
    }
}
