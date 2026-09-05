# kotlinx-datetime(안드로이드판과 공유하는 :shared 모듈이 의존)이 선택적으로 참조하는
# kotlinx.serialization 클래스들은 실제로 이 프로젝트 클래스패스에 없다(직렬화 기능 자체를 안 씀) —
# ProGuard 7.4.2에서 이 미해결 참조를 경고가 아니라 빌드 실패로 취급해서(2026-09-05 발견) 명시적으로
# 무시하도록 추가.
-dontwarn kotlinx.serialization.**
