package com.vagujhelyigergely.calculatorm3.camera

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vagujhelyigergely.calculatorm3.ui.theme.CalculatorM3Theme

// Debug-only previews so the redesigned AI surfaces can be eyeballed in Android Studio
// without running on-device inference. They exercise the reusable building blocks from
// ScanComponents.kt; the shared-element modifiers no-op outside a SharedTransitionLayout,
// so each block renders standalone.

@Preview(name = "Loading", showBackground = true)
@Preview(name = "Loading · dark", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LoadingPreview() {
    CalculatorM3Theme {
        Surface {
            Box(Modifier.fillMaxSize()) {
                AiHero(
                    title = "Loading AI model…",
                    subtitle = "Loading the vision model into memory. This may take up to a minute on first use.",
                    loading = true,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            }
        }
    }
}

@Preview(name = "Processing", showBackground = true)
@Composable
private fun ProcessingPreview() {
    CalculatorM3Theme {
        Surface {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                AiHero(title = "Solving…", icon = Icons.Default.Psychology, pulsing = true)
                AiCard(Modifier.fillMaxWidth()) {
                    Text(
                        "To solve 3x + 6 = 21, subtract 6: 3x = 15, then divide by 3 → x = 5.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Preview(name = "Success", showBackground = true)
@Preview(name = "Success · dark", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun SuccessPreview() {
    CalculatorM3Theme {
        Surface {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                AiHero(title = "Answer found", icon = Icons.Default.CheckCircle)
                AiCard(Modifier.fillMaxWidth()) {
                    Text(
                        "x = 5",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun ErrorPreview() {
    CalculatorM3Theme {
        Surface {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AiHero(
                    title = "Couldn't read that",
                    subtitle = "The model couldn't find an expression in the photo.",
                    icon = Icons.Default.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error
                )
                AiCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Text("raw model output…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Preview(name = "Downloading", showBackground = true)
@Composable
private fun DownloadingPreview() {
    CalculatorM3Theme {
        Surface {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AiHero(title = "Downloading AI model", icon = Icons.Default.CloudDownload, pulsing = true)
                AiDownloadBar(progress = 0.42f, modifier = Modifier.fillMaxWidth())
                Text("1.3 GB / 3.1 GB (42%)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Preview(name = "Shimmer skeleton", showBackground = true)
@Composable
private fun ShimmerPreview() {
    CalculatorM3Theme {
        Surface {
            AiCard(Modifier.fillMaxWidth().padding(24.dp)) {
                ShimmerLines(Modifier.padding(16.dp))
            }
        }
    }
}
