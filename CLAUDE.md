# 프로젝트 운영 원칙

- 추측하거나 임의로 구현하지 말고 반드시 기존 코드와 프로젝트 구조를 먼저 확인한다.
- 기존 아키텍처와 코딩 스타일을 최대한 유지한다.
- 변경 범위를 최소화한다.
- 불필요한 리팩터링은 하지 않는다.
- 새로운 파일은 꼭 필요한 경우에만 생성한다.
- 동일하거나 유사한 기능이 이미 존재하는지 먼저 검색한 후 구현한다.
- TODO, FIXME, 알려진 문제를 항상 확인한다.
- 중요한 설계 변경이나 영향이 큰 변경은 이유를 함께 설명한다.

# 프로젝트 문서 관리 원칙

프로젝트 문서는 역할별로 관리하며, 각 문서의 목적을 명확히 구분한다.

## 1. HANDOFF.md

프로젝트의 현재 상태를 기록하는 문서이다. 항상 최신 상태만 유지한다.

다음 내용만 포함한다.

- 프로젝트 개요
- 현재 개발 단계
- 현재 진행률
- 현재 구현된 주요 기능
- 현재 진행 중인 작업
- 다음 작업 우선순위
- 현재 주의사항
- 현재 알려진 문제
- 현재 중요한 파일
- 다음 세션에서 반드시 알아야 하는 내용

다음 내용은 HANDOFF.md에 장기간 보관하지 않는다.

- 오래된 작업 내역
- 완료된 기능 목록
- 예전 버그 기록
- 지난 세션 기록
- 오래된 TODO

항상 다음 세션에서 2~3분 안에 프로젝트를 이해할 수 있는 수준으로 유지한다. 내용이 오래되거나 현재와 관련 없는 정보는 제거하거나 다른 문서로 이동한다.

**강제 규칙(2026-09-10, HANDOFF.md가 453줄까지 불어났던 것을 계기로 추가)**: 세션 종료 시 HANDOFF.md를 갱신할 땐 "추가"만 하지 말고 그 자리에서 반드시 "가지치기"도 같이 한다.
- "현재 진행 중인 작업" 섹션엔 **가장 최근 세션 1개의 상세 내용만** 남긴다. 그보다 오래된 세션의 요약(예: "OO차 세션 요약: ...")은 이미 CHANGELOG.md에 상세 기록이 있으므로 그 자리에서 즉시 삭제한다 — "나중에 정리"로 미루지 않는다.
- "현재 구현된 주요 기능"과 "현재 중요한 파일" 같은 목록/표에 새 항목을 적을 땐 **"지금 무엇인지"만 한 줄로 쓴다.** "OO차에 이렇게 바뀌었다가 XX차에 다시 이렇게 바뀌었다" 같은 세션별 변천사 서술은 넣지 않는다 — 그런 이력은 CHANGELOG.md/DECISIONS.md의 역할이며, HANDOFF.md는 항상 "현재 상태"의 스냅샷이어야 한다.

## 2. CHANGELOG.md

변경 이력을 기록하는 문서이다. 다음 내용을 누적 기록한다.

- 날짜
- 구현한 기능
- 수정한 기능
- 삭제한 기능
- 리팩터링
- 버그 수정

기존 내용을 삭제하지 않는다.

## 3. DECISIONS.md

중요한 설계 결정만 기록한다. (예: 라이브러리 선택 이유, 구조 변경 이유, 아키텍처 변경, 기술 선택 이유) 단순 작업 내용은 기록하지 않는다.

## 4. BUGS.md

현재 알려진 버그를 관리한다. 각 버그마다 설명 / 원인 / 상태(Open / In Progress / Fixed) / 해결 방법(있다면)을 기록한다. 버그가 해결되면 Fixed로 변경하거나 필요하면 제거한다.

## 5. IDEAS.md

나중에 구현할 아이디어를 기록한다. (예: 신규 기능, UX 개선, 성능 개선, 리팩터링 아이디어)

# 작업 시작 절차

새로운 세션에서는 반드시 다음 순서를 따른다.

1. `HANDOFF.md` 읽기
2. 프로젝트 구조 확인
3. 현재 진행 중인 작업 확인
4. 필요한 경우 `CHANGELOG.md`, `DECISIONS.md`, `BUGS.md`, `IDEAS.md` 참고
5. 영향 범위 분석
6. 작업 계획 수립
7. 구현 시작

# 작업 중 원칙

- 기존 구현 방식을 우선적으로 따른다.
- 중복 코드를 만들지 않는다.
- 유지보수가 쉬운 구조를 우선한다.
- 버그를 발견하면 원인을 먼저 분석한 후 수정한다.
- 수정 전후의 영향을 항상 고려한다.
- 필요하면 관련 파일도 함께 검토한다.
- 구현보다 코드 품질과 일관성을 우선한다.
- 중요한 설계 변경은 `DECISIONS.md`에도 기록한다.

# 작업 종료 절차

세션 종료 시 반드시 다음을 수행한다.

1. `HANDOFF.md`를 현재 상태 기준으로 갱신한다(오래된 정보는 제거하거나 해당 문서로 이동).
2. 이번 작업 내용을 `CHANGELOG.md`에 추가한다(기존 내용 삭제하지 않음).
3. 새로운 설계 결정이 있으면 `DECISIONS.md`를 갱신한다.
4. 버그가 발견되거나 해결되면 `BUGS.md`를 갱신한다.
5. 새로운 아이디어가 생기면 `IDEAS.md`에 추가한다.

# 행동 지침 (LLM 코딩 실수 방지)

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
