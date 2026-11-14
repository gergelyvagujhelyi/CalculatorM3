@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.vagujhelyigergely.calculatorm3.R

/** What the AI window is opened for, picked in the AI menu. */
enum class AiEntry { CAMERA, GALLERY, WRITE, TYPE, SETTINGS }

/**
 * The AI button on the calculator. Tapping it opens a small menu, anchored to the button,
 * with one tile per input source and a settings gear. Nothing is preselected or remembered:
 * every choice is always one tap away. [onSelect] gets the chosen [AiEntry].
 */
@Composable
fun AiMenuButton(
    onSelect: (AiEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the menu is open, owned by the caller (so it can open it after the intro). */
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    /** Tapping the button: the caller decides (the first-tap intro, or just open the menu). */
    onClick: () -> Unit = { onExpandedChange(true) },
) {
    Box(modifier) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.Assistant,
                contentDescription = stringResource(R.string.ai_menu_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            AiMenuContent(onSelect = { entry ->
                onExpandedChange(false)
                onSelect(entry)
            })
        }
    }
}

/**
 * Shown on the first tap of the AI button by someone who hasn't used AI yet (no model): it's
 * private and runs on the phone, needs a one-time download, and the button can be hidden.
 * [onContinue] opens the menu; [onHide] hides the button; [onDismiss] (outside, back) just closes,
 * so it shows again next time.
 */
@Composable
internal fun AiIntroDialog(
    onContinue: (answerInAppLanguage: Boolean) -> Unit,
    onHide: (answerInAppLanguage: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** The app's language as its speakers write it, to offer as the answer language; null for English. */
    appLanguageName: String? = null,
    /** The translation's one-time download, for the language option; null if it's on the phone. */
    translationSize: Long? = null,
) {
    // English until the user picks their language here (English gives the best results).
    var inAppLanguage by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.ai_intro_title), textAlign = TextAlign.Center) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IntroPoint(Icons.Filled.PhoneAndroid, stringResource(R.string.ai_intro_local))
                IntroPoint(Icons.Filled.CloudDownload, stringResource(R.string.ai_intro_download))
                if (appLanguageName != null) {
                    Text(stringResource(R.string.ai_settings_answer_language), style = MaterialTheme.typography.titleSmall)
                    AnswerLanguageSetting(inAppLanguage, appLanguageName, { inAppLanguage = it }, downloadSize = translationSize)
                }
                IntroPoint(Icons.Filled.VisibilityOff, stringResource(R.string.ai_intro_hide_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = { onContinue(inAppLanguage) }) { Text(stringResource(R.string.ai_intro_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { onHide(inAppLanguage) }) { Text(stringResource(R.string.ai_intro_hide)) }
        }
    )
}

/**
 * Asks once for the answer language, for someone who already set up the AI before it was
 * offered (so never saw the intro). English is preselected; [onChoose] gets the choice.
 */
@Composable
internal fun AnswerLanguageDialog(
    appLanguageName: String,
    onChoose: (answerInAppLanguage: Boolean) -> Unit,
    onDismiss: () -> Unit,
    translationSize: Long? = null,
) {
    var inAppLanguage by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_settings_answer_language)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnswerLanguageSetting(inAppLanguage, appLanguageName, { inAppLanguage = it }, downloadSize = translationSize)
                Text(
                    stringResource(R.string.ai_answer_language_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(inAppLanguage) }) { Text(stringResource(R.string.ai_intro_continue)) }
        }
    )
}

@Composable
private fun IntroPoint(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The menu's contents: a header with the settings gear, then the source tiles. */
@Composable
internal fun AiMenuContent(onSelect: (AiEntry) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ai_menu_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp).weight(1f)
            )
            IconButton(onClick = { onSelect(AiEntry.SETTINGS) }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.ai_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Pictures on the first row, entering it yourself on the second.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiMenuTile(
                label = stringResource(R.string.ai_menu_camera),
                icon = Icons.Filled.PhotoCamera,
                shape = MaterialShapes.Cookie9Sided,
                onClick = { onSelect(AiEntry.CAMERA) }
            )
            AiMenuTile(
                label = stringResource(R.string.ai_menu_gallery),
                icon = Icons.Filled.PhotoLibrary,
                shape = MaterialShapes.Clover4Leaf,
                onClick = { onSelect(AiEntry.GALLERY) }
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            AiMenuTile(
                label = stringResource(R.string.ai_menu_write),
                icon = Icons.Filled.Draw,
                shape = MaterialShapes.Pill,
                onClick = { onSelect(AiEntry.WRITE) }
            )
            AiMenuTile(
                label = stringResource(R.string.ai_menu_type),
                icon = Icons.Filled.Keyboard,
                shape = MaterialShapes.Sunny,
                onClick = { onSelect(AiEntry.TYPE) }
            )
        }
    }
}

/**
 * A square-ish tile: the icon on an expressive [shape] above its label. Pressing it springs the
 * tile down and turns the shape a little, with a haptic tick, like the calculator keys.
 */
@Composable
private fun AiMenuTile(
    label: String,
    icon: ImageVector,
    shape: RoundedPolygon,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(isPressed) {
        if (isPressed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val bouncy = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    val scale by animateFloatAsState(if (isPressed) 0.94f else 1f, bouncy, label = "tileScale")
    val turn by animateFloatAsState(if (isPressed) 30f else 0f, bouncy, label = "tileTurn")

    Column(
        modifier = Modifier
            .width(112.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer { rotationZ = turn }
                    .background(MaterialTheme.colorScheme.primaryContainer, shape.toShape())
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
