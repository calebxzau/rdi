package calebxzhou.rdi.client

import calebxzau.rdi.client.RDIClient
import java.io.File

internal fun appConfigFile(): File =
    RDIClient.DIR.resolve("config.toml")
