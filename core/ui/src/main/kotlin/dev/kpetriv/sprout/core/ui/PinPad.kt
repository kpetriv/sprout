package dev.kpetriv.sprout.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import kotlin.math.roundToInt

/*
 * PIN input without a text field: the digits never pass through an IME, a `String`, or saved state. The caller keeps
 * them in a `CharArray` (zeroed after use) and shows only how many were typed through [PinDots]. Used by the parent
 * lock (feature/lock) and the change-PIN and signing-key forms (feature/settings).
 */

/**
 * One dot per PIN digit, filled for each digit entered. Shakes sideways whenever [shakeKey] changes to a value
 * above zero (a rejected PIN). Announced as "3 of 6 digits entered", never the digits themselves.
 */
@Composable
fun PinDots(entered: Int, length: Int, shakeKey: Int, modifier: Modifier = Modifier) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey > 0) {
            for (target in SHAKE_STEPS) shake.animateTo(target, tween(durationMillis = SHAKE_STEP_MILLIS))
        }
    }
    val shakePx = with(LocalDensity.current) { SHAKE_DISTANCE.toPx() }
    val description = pluralStringResource(R.plurals.core_ui_pin_dots_description, length, entered, length)
    Row(
        modifier = modifier
            .offset { IntOffset((shake.value * shakePx).roundToInt(), 0) }
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val filled = MaterialTheme.colorScheme.primary
        val empty = MaterialTheme.colorScheme.outline
        repeat(length) { index ->
            val dot = Modifier.size(16.dp)
            Box(
                modifier = if (index < entered) {
                    dot.background(filled, CircleShape)
                } else {
                    dot.border(2.dp, empty, CircleShape)
                },
            )
        }
    }
}

/**
 * A phone-style numeric keypad: 1-9, then [bottomStart] (or a gap), 0, and backspace. Keys are [keySize] circles,
 * well above the 48dp minimum, so a parent can type fast without looking. Digits arrive one at a time through
 * [onDigit]; nothing is buffered here.
 */
@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keySize: Dp = PinPadDefaults.KeySize,
    bottomStart: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in DIGIT_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (digit in row) DigitKey(digit, onDigit, enabled, keySize)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(keySize), contentAlignment = Alignment.Center) { bottomStart?.invoke() }
            DigitKey('0', onDigit, enabled, keySize)
            Surface(
                onClick = onBackspace,
                enabled = enabled,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(keySize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.core_ui_ic_backspace),
                        contentDescription = stringResource(R.string.core_ui_pin_backspace),
                    )
                }
            }
        }
    }
}

/** Sizes for [PinPad]. */
object PinPadDefaults {
    /** Full-screen PIN entry. */
    val KeySize: Dp = 72.dp

    /** Dialogs and forms. */
    val CompactKeySize: Dp = 56.dp
}

@Composable
private fun DigitKey(digit: Char, onDigit: (Char) -> Unit, enabled: Boolean, keySize: Dp) {
    Surface(
        onClick = { onDigit(digit) },
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(keySize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = digit.toString(), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

private val DIGIT_ROWS = listOf("123", "456", "789")
private val SHAKE_STEPS = listOf(-1f, 1f, -0.6f, 0.6f, -0.3f, 0f)
private const val SHAKE_STEP_MILLIS = 45
private val SHAKE_DISTANCE = 14.dp

@Preview
@Composable
private fun PinPadPreview() {
    SproutTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PinDots(entered = 2, length = 6, shakeKey = 0)
            PinPad(onDigit = {}, onBackspace = {}, enabled = true)
        }
    }
}
