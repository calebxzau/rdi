package calebxzau.rdi.client.logic

import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.client.account.OfflineGameAccount
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LocalPackPlayTest {
    @Test
    fun `local play preserves the offline account and disables RDI runtime`() {
        val info = LocalModpackInfo("019a55b0-0000-7000-8000-000000000001", "本地整合包",
            mcVersion = McVersion.V211, modLoader = ModLoader.neoforge, createdAt = 1, source = LocalModpackSourceKind.Empty)
        val pack = ModpackLocalDir(File("versions/local_${info.id}"), "1.21.1", null, 1, info)
        val account = OfflineGameAccount.create("TestPlayer").getOrThrow()
        val args = localPackPlayArgs(pack, account)
        assertFalse(args.withRdiCore)
        assertEquals(account, args.account)
        assertEquals(pack.versionId, args.versionId)
        assertEquals(pack.dir.absolutePath, args.versionDir)
        assertEquals("", args.playArg)
    }

    @Test
    fun `offline preparation persists the chosen identity without initializing Microsoft login`(): Unit = runBlocking {
        val root = Files.createTempDirectory("u4a-local-play").toFile()
        val info = LocalModpackInfo("019a55b0-0000-7000-8000-000000000001", "测试包",
            mcVersion = McVersion.V211, modLoader = ModLoader.neoforge, createdAt = 1, source = LocalModpackSourceKind.Empty)
        LocalModpackStore.write(root, info).getOrThrow()
        val pack = ModpackLocalDir(root, "1.21.1", null, 1, info)
        val launcher = LocalPackPlay(lazy { error("Offline play must not initialize Microsoft login") })
        val args = launcher.prepare(pack, LocalPlayAccountMode.Offline, "CustomPlayer") { error("No device code offline") }
        assertEquals("CustomPlayer", args.account!!.name)
        assertFalse(args.withRdiCore)
        val saved = LocalModpackStore.read(root).getOrThrow()
        assertEquals(LocalPlayAccountMode.Offline, saved.lastAccountMode)
        assertEquals("CustomPlayer", saved.lastOfflineName)
        assertFailsWith<IllegalArgumentException> {
            launcher.prepare(pack, LocalPlayAccountMode.Offline, "") { error("No device code offline") }
        }
        assertEquals(saved, LocalModpackStore.read(root).getOrThrow())
    }
}
