package com.m3calculator

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CalcButton(
    val label: String,
    val type: ButtonType
)

enum class ButtonType {
    NUMBER, OPERATOR, FUNCTION, EQUALS, BACKSPACE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(viewModel: CalculatorViewModel) {
    val colorScheme = MaterialTheme.colorScheme

    val buttons = listOf(
        listOf(
            CalcButton("AC", ButtonType.FUNCTION),
            CalcButton("()", ButtonType.FUNCTION),
            CalcButton("%", ButtonType.FUNCTION),
            CalcButton("÷", ButtonType.OPERATOR),
        ),
        listOf(
            CalcButton("7", ButtonType.NUMBER),
            CalcButton("8", ButtonType.NUMBER),
            CalcButton("9", ButtonType.NUMBER),
            CalcButton("×", ButtonType.OPERATOR),
        ),
        listOf(
            CalcButton("4", ButtonType.NUMBER),
            CalcButton("5", ButtonType.NUMBER),
            CalcButton("6", ButtonType.NUMBER),
            CalcButton("−", ButtonType.OPERATOR),
        ),
        listOf(
            CalcButton("1", ButtonType.NUMBER),
            CalcButton("2", ButtonType.NUMBER),
            CalcButton("3", ButtonType.NUMBER),
            CalcButton("+", ButtonType.OPERATOR),
        ),
        listOf(
            CalcButton("+/−", ButtonType.FUNCTION),
            CalcButton("0", ButtonType.NUMBER),
            CalcButton(".", ButtonType.NUMBER),
            CalcButton("=", ButtonType.EQUALS),
        ),
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Display area
            DisplaySection(
                expression = viewModel.expression,
                result = viewModel.result,
                history = viewModel.history,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            // Divider
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                thickness = 0.5.dp,
                color = colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Backspace row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                CalcButtonView(
                    button = CalcButton("⌫", ButtonType.BACKSPACE),
                    onClick = { viewModel.onButtonPress("⌫") },
                    modifier = Modifier.size(width = 72.dp, height = 40.dp)
                )
            }

            // Button grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                buttons.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { button ->
                            CalcButtonView(
                                button = button,
                                onClick = { viewModel.onButtonPress(button.label) },
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.15f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DisplaySection(
    expression: String,
    result: String,
    history: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End
    ) {
        // History
        AnimatedVisibility(
            visible = history.isNotEmpty(),
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut()
        ) {
            Text(
                text = history,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.outline,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Expression
        val fontSize = when {
            expression.length > 16 -> 32.sp
            expression.length > 12 -> 38.sp
            expression.length > 8 -> 46.sp
            else -> 56.sp
        }

        val animatedFontSize by animateFloatAsState(
            targetValue = fontSize.value,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "fontSize"
        )

        Text(
            text = formatExpression(expression).ifEmpty { "0" },
            fontSize = animatedFontSize.sp,
            fontWeight = FontWeight.Light,
            color = if (expression.isEmpty()) colorScheme.outlineVariant else colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = (animatedFontSize * 1.15).sp,
            modifier = Modifier.fillMaxWidth(),
            letterSpacing = (-0.5).sp
        )

        // Live preview
        val showPreview = result.isNotEmpty() && expression != result && expression.isNotEmpty()
        AnimatedVisibility(
            visible = showPreview,
            enter = fadeIn(animationSpec = tween(200)) +
                    slideInVertically(animationSpec = tween(200)) { it / 3 },
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Text(
                text = "= $result",
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.primary.copy(alpha = 0.65f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun CalcButtonView(
    button: CalcButton,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )

    val shape = when (button.type) {
        ButtonType.EQUALS -> RoundedCornerShape(28.dp)
        else -> RoundedCornerShape(24.dp)
    }

    val containerColor = when (button.type) {
        ButtonType.NUMBER -> colorScheme.surfaceContainerHigh
        ButtonType.OPERATOR -> colorScheme.secondaryContainer
        ButtonType.FUNCTION -> colorScheme.surfaceContainer
        ButtonType.EQUALS -> colorScheme.primary
        ButtonType.BACKSPACE -> Color.Transparent
    }

    val contentColor = when (button.type) {
        ButtonType.NUMBER -> colorScheme.onSurface
        ButtonType.OPERATOR -> colorScheme.onSecondaryContainer
        ButtonType.FUNCTION -> colorScheme.primary
        ButtonType.EQUALS -> colorScheme.onPrimary
        ButtonType.BACKSPACE -> colorScheme.onSurfaceVariant
    }

    val fontSize = when (button.type) {
        ButtonType.EQUALS -> 28.sp
        ButtonType.OPERATOR -> 24.sp
        ButtonType.NUMBER -> 22.sp
        ButtonType.BACKSPACE -> 20.sp
        else -> 18.sp
    }

    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier
            .then(
                Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            ),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (button.label == "⌫") {
                // Backspace icon drawn with shapes
                Text(
                    text = "⌫",
                    fontSize = fontSize,
                    fontWeight = FontWeight.Normal,
                    color = contentColor,
                    letterSpacing = 0.sp
                )
            } else {
                Text(
                    text = button.label,
                    fontSize = fontSize,
                    fontWeight = when (button.type) {
                        ButtonType.NUMBER -> FontWeight.Medium
                        ButtonType.EQUALS -> FontWeight.SemiBold
                        else -> FontWeight.SemiBold
                    },
                    color = contentColor,
                    letterSpacing = if (button.label == "+/−") (-0.5).sp else 0.2.sp
                )
            }
        }
    }
}

private fun formatExpression(expr: String): String {
    return expr.replace(Regex("([+\\-×÷])")) { " ${it.value} " }
}
