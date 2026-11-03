package calebxzau.rdi.client.service

private const val DM_MASTER_PORT = 15566

fun dmMasterUrl(serverHost: String): String = "http://${serverHost}:${DM_MASTER_PORT}"

/** The game's DM entry stays off for everyone except the test account until DM opens to players. */
fun dmGameJvmArgs(isTestAccount: Boolean, serverHost: String): List<String> =
    if (isTestAccount) listOf("-Drdi.dm.server=${dmMasterUrl(serverHost)}") else emptyList()
