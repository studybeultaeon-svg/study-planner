package com.phonelock.desktop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 숫자 입력칸 + 오른쪽 위/아래 화살표 버튼(83차, 사용자 요청 — 안드로이드판과 대칭). 값은 문자열로
 * 유지(계산기 필드들이 소수/빈 문자열을 그대로 저장하는 기존 방식과 호환).
 *
 * **83차 UI 재설계(2차)**: 처음 버전은 라벨이 길면 줄바꿈되며 아래와 겹쳤다 — `maxLines=1`+ellipsis로
 * 고정. 7칸으로 나뉘는 요일별 목표처럼 아주 좁은 칸은 화살표를 넣으면 숫자가 안 보여 `showStepper=false`
 * 로 뺄 수 있게 했다. **83차 UI 재설계(5차)**: 화살표를 "▲"/"▼" 텍스트 글리프로 그리던 걸 [IconChip]
 * (벡터 아이콘)으로 교체했다 — 앱 폰트를 카페24 써라운드로 바꾸면서 이 폰트에 기하학 기호 글리프가
 * 없어 화살표가 전부 안 보이는 문제가 생겼기 때문(자세한 경위는 IconChip.kt 참고).
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
    centerValue: Boolean = false
) {
    fun bump(delta: Int) {
        val current = value.toDoubleOrNull()?.toInt() ?: 0
        onValueChange((current + delta).coerceIn(min, max).toString())
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = calcFieldTextStyle().copy(
            textAlign = if (centerValue) TextAlign.Center else TextAlign.Start
        ),
        trailingIcon = if (!showStepper) null else {
            {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconChip(Icons.Filled.KeyboardArrowUp, onClick = { bump(step) })
                    Spacer(Modifier.size(3.dp))
                    IconChip(Icons.Filled.KeyboardArrowDown, onClick = { bump(-step) })
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
