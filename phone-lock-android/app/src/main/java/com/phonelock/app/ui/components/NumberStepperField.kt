package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    overlayStepper: Boolean = false
) {
    fun bump(delta: Int) {
        val current = value.toDoubleOrNull()?.toInt() ?: 0
        onValueChange((current + delta).coerceIn(min, max).toString())
    }

    if (overlayStepper) {
        Box(modifier) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = label?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = calcFieldTextStyle().copy(
                    textAlign = if (centerValue) TextAlign.Center else TextAlign.Start
                )
            )
            if (showStepper) {
                // 85차 3차(사용자 지적): 가장자리에 너무 딱 붙었었다 — end padding을 살짝 늘려 여유를
                // 두고, label이 위쪽 공간을 차지해 값 입력 줄의 실제 세로 중심이 Box 전체 높이의
                // 기하학적 중심보다 아래에 있어 화살표 위/아래 여백이 짝짝이로 보였던 것도 아래로
                // 살짝 밀어(offset) 값 줄과 눈높이를 맞췄다.
                Column(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 5.dp).offset(y = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconChip(Icons.Filled.KeyboardArrowUp, size = stepperSize, iconSize = stepperIconSize, onClick = { bump(step) })
                    Spacer(Modifier.size(2.dp))
                    IconChip(Icons.Filled.KeyboardArrowDown, size = stepperSize, iconSize = stepperIconSize, onClick = { bump(-step) })
                }
            }
        }
        return
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = calcFieldTextStyle().copy(
            textAlign = if (centerValue) TextAlign.Center else TextAlign.Start
        ),
        trailingIcon = if (!showStepper) null else {
            {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconChip(Icons.Filled.KeyboardArrowUp, size = stepperSize, iconSize = stepperIconSize, onClick = { bump(step) })
                    Spacer(Modifier.size(3.dp))
                    IconChip(Icons.Filled.KeyboardArrowDown, size = stepperSize, iconSize = stepperIconSize, onClick = { bump(-step) })
                }
            }
        }
    )
}

/** 계산기 업무 카드 안 모든 입력칸(숫자/이름/단위/휴일/날짜)이 공유하는 폰트 스타일(83차) — bodyLarge를
 *  그대로 쓴다. 카페24 써라운드는 실제로 Bold(700) 한 벌짜리 얼굴이라(AppFontFamily 주석 참고) 굳이
 *  다른 굵기를 요청하지 않는다 — 요청 굵기가 등록된 얼굴과 안 맞으면 글자가 깨져 보인다. */
@Composable
fun calcFieldTextStyle() = MaterialTheme.typography.bodyLarge
