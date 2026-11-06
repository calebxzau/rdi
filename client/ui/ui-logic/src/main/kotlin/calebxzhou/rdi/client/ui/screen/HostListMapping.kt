package calebxzhou.rdi.client.ui.screen

fun allHostSourcesEnded(
    legacyAvailableEnded: Boolean,
    legacyUnavailableEnded: Boolean,
): Boolean = legacyAvailableEnded && legacyUnavailableEnded

fun groupUnifiedHosts(hosts: List<UnifiedHostBrief>): Pair<List<UnifiedHostBrief>, List<UnifiedHostBrief>> =
    hosts.partition { it.playable }

fun applyDavPlayableOverride(
    hosts: List<UnifiedHostBrief>,
    isDav: Boolean,
): List<UnifiedHostBrief> = if (isDav) hosts.map { it.copy(playable = true) } else hosts

/** 按原始可玩状态决定可见性，再应用特殊账号的游玩权限。 */
fun selectShownHosts(
    hosts: List<UnifiedHostBrief>,
    showOtherHosts: Boolean,
    isDav: Boolean,
): List<UnifiedHostBrief> {
    val (playable, nonPlayable) = groupUnifiedHosts(hosts)
    return applyDavPlayableOverride(if (showOtherHosts) playable + nonPlayable else playable, isDav)
}

/** Keeps the two server pages in one stable UI list without coercing either id type. */
fun mergeUnifiedHosts(
    current: List<UnifiedHostBrief>,
    incoming: List<UnifiedHostBrief>,
): List<UnifiedHostBrief> = (current + incoming)
    .distinctBy { it.target }
    .sortedWith(compareByDescending<UnifiedHostBrief> { it.target.id }.thenBy { it.target.kind })
