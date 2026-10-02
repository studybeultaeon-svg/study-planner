package com.phonelock.desktop.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 라벨을 값 위에 작게 고정한 입력 칸(96차 사용자 시안 — M3 OutlinedTextField의 떠 있는 라벨 대신).
 * 147차: 테두리 상자 → [LedgerInputFrame](라벨 + 값 + 아래 가는 선). [label]을 안 주면(위 문맥 캡션으로 이미
 * 설명되는 시간 필드 등) 값만 보여준다. 값 부분은 BasicTextField라 탭하면 바로 키보드로 직접 입력할 수 있다.
 */
@Composable
fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    centerValue: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LedgerInputFrame(modifier, label = label, focused = focused, centerLabel = centerValue) {
        // 146차: 이모지(🕐·🔍) 대신 벡터 아이콘 — 기기마다 그림·기준선이 달라지지 않게.
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    placeholder,
                    style = calcFieldTextStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = if (centerValue) TextAlign.Center else TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = calcFieldTextStyle().copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = if (centerValue) TextAlign.Center else TextAlign.Start
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * "− 값 +" 숫자 칸(96차 사용자 시안). 가운데 값은 BasicTextField라 탭해서 직접 숫자를 입력할 수도 있다.
 * 147차: 테두리 상자 → [LedgerInputFrame], −/+ 글자 → 판 없는 벡터 아이콘([LedgerInputGlyph]).
 */
@Composable
fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    step: Int = 1
) {
    fun bump(delta: Int) {
        val current = value.toIntOrNull() ?: 0
        onValueChange((current + delta).coerceIn(min, max).toString())
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LedgerInputFrame(modifier, label = label, focused = focused, centerLabel = true) {
        LedgerInputGlyph(Icons.Filled.Remove, contentDescription = "줄이기") { bump(-step) }
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = calcFieldTextStyle().copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth()
            )
        }
        LedgerInputGlyph(Icons.Filled.Add, contentDescription = "늘리기") { bump(step) }
    }
}
