package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor

/**
 * 숫자 입력칸 + 오른쪽 위/아래 화살표 버튼(83차, 사용자 요청 — "숫자 입력 칸은 오른쪽에 위아래 화살표를
 * 배치하여 버튼을 눌러 양을 조절"). 값은 문자열로 유지(계산기 필드들이 소수/빈 문자열을 그대로 저장하는
 * 기존 방식과 호환) — 화살표는 현재 값을 정수로 해석해 step만큼 증감하고, 키보드 직접 입력도 지원한다.
 *
 * **83차 UI 재설계(2차)**: 처음 버전은 라벨이 길면 줄바꿈되며 아래와 겹쳤다 — `maxLines=1`+ellipsis로
 * 고정. 7칸으로 나뉘는 요일별 목표처럼 아주 좁은 칸은 화살표를 넣으면 숫자가 안 보여 `showStepper=false`
 * 로 뺄 수 있게 했다. **83차 UI 재설계(5차)**: 화살표를 "▲"/"▼" 텍스트 글리프로 그리던 걸 [IconChip]
 * (벡터 아이콘)으로 교체했다 — 앱 폰트를 카페24 써라운드로 바꾸면서 이 폰트에 기하학 기호 글리프가
 * 없어 화살표가 전부 안 보이는 문제가 생겼기 때문(자세한 경위는 IconChip.kt 참고).
 *
 * **85차 발견**: `trailingIcon` 슬롯은 아무리 아이콘 자체를 작게 줄여도(`stepperSize`) M3
 * `OutlinedTextField`가 그 슬롯을 위한 폭을 별도로 예약해버려, 요일별 목표처럼 아주 좁은 칸에서는
 * 화살표를 조금 줄이는 것만으론 숫자가 여전히 잘려 보였다(실기기 확인). `overlayStepper=true`로
 * 켜면 `trailingIcon` 슬롯 자체를 안 쓰고 텍스트필드 전체 폭을 값 표시에 내준 뒤, 화살표를 그 위에
 * `Box`로 겹쳐 그린다 — 텍스트필드가 예약하는 폭이 없어지므로 값 표시 공간이 실제로 넓어진다.
 */
@Composable
fun NumberStepperField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
    showStepper: Boolean = true,
    centerValue: Boolean = false,
    stepperSize: androidx.compose.ui.unit.Dp = 20.dp,
    stepperIconSize: androidx.compose.ui.unit.Dp = 14.dp,
    @Suppress("UNUSED_PARAMETER") overlayStepper: Boolean = false
) {
    fun bump(delta: Int) {
        val current = value.toDoubleOrNull()?.toInt() ?: 0
        onValueChange((current + delta).coerceIn(min, max).toString())
    }
    // 147차: OutlinedTextField(테두리 상자 + 따로 예약되는 trailingIcon 폭) → [LedgerInputFrame](라벨 + 값 + 아래 가는 선).
    // 화살표는 판 없는 아이콘을 값 오른쪽에 위/아래로 쌓는다 — 상자가 없어 85차의 "좁은 칸에서 숫자가 가려짐"
    // 문제(overlayStepper로 피하던 것)가 생기지 않으므로 overlayStepper는 받기만 하고 쓰지 않는다.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LedgerInputFrame(modifier, label = label, focused = focused, centerLabel = centerValue) {
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = calcFieldTextStyle().copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = if (centerValue) TextAlign.Center else TextAlign.Start
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (showStepper) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LedgerInputGlyph(Icons.Filled.KeyboardArrowUp, contentDescription = "늘리기", size = stepperSize, iconSize = stepperIconSize) { bump(step) }
                LedgerInputGlyph(Icons.Filled.KeyboardArrowDown, contentDescription = "줄이기", size = stepperSize, iconSize = stepperIconSize) { bump(-step) }
            }
        }
    }
}

/** 계산기 업무 카드 안 모든 입력칸(숫자/이름/단위/휴일/날짜)이 공유하는 폰트 스타일(83차) — bodyLarge를
 *  그대로 쓴다. 카페24 써라운드는 실제로 Bold(700) 한 벌짜리 얼굴이라(AppFontFamily 주석 참고) 굳이
 *  다른 굵기를 요청하지 않는다 — 요청 굵기가 등록된 얼굴과 안 맞으면 글자가 깨져 보인다. */
@Composable
fun calcFieldTextStyle() = MaterialTheme.typography.bodyLarge
