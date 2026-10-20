package calebxzau.rdi.questviewer

import calebxzau.rdi.quests.ftb.model.FtbTask
import calebxzau.rdi.quests.ftb.model.FtbReward

internal sealed interface PreviewIcon {
    data class Item(val itemId: String) : PreviewIcon
    data object Checkmark : PreviewIcon
    data object Dice : PreviewIcon
}

internal fun FtbTask.previewIcon(): PreviewIcon? = (icon ?: item)?.let { PreviewIcon.Item(it.itemId) }
    ?: PreviewIcon.Checkmark.takeIf { type == "checkmark" || type == "ftbquests:checkmark" }

internal fun FtbReward.previewIcon(): PreviewIcon? = (icon ?: item)?.let { PreviewIcon.Item(it.itemId) }
    ?: when (type) {
        "xp_levels", "ftbquests:xp_levels" -> PreviewIcon.Item("minecraft:experience_bottle")
        "random", "ftbquests:random" -> PreviewIcon.Dice
        else -> null
    }
