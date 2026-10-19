package calebxzau.rdi.client.service

import calebxzhou.rdi.client.service.ClientDirs
import calebxzhou.rdi.client.service.content.ClientContentStore

object ClientContentStores {
    val shared: ClientContentStore by lazy {
        ClientContentStore(ClientDirs.dlcDir.toPath())
    }
}
