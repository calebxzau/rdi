package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import calebxzau.rdi.client.ui.loadImageBitmap
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.runtimeLoaders

/**
 * calebxzhou @ 2026-01-29 18:44
 */


@Composable
fun McVersionCard(
    mcver: McVersion,
    highlight: Boolean = false,
    onClick: () -> Unit = {}
) {
    val iconBitmap = remember(mcver) {
        loadImageBitmap(mcver.icon).getOrThrow()
    }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlight) {
                    Modifier
                        .shadow(8.dp, shape, clip = false)
                        .border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = "MC ${mcver.mcVer}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "MC ${mcver.mcVer}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = mcver.runtimeLoaders.joinToString(" / ") { it.directorySlug },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
