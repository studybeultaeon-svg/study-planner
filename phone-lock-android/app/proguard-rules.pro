# 63차 이후 배포 계획에 맞춰 release 빌드에 R8 코드 축소/난독화를 켜면서 추가.
# 목적: 디컴파일 난이도를 올려 코드 복제를 어렵게 함(완전 차단은 아님). 기존 동작은 그대로 유지해야 하므로
# 리플렉션/직렬화로 이름에 의존하는 라이브러리 클래스들만 최소한으로 keep한다.

# Firebase Auth / Credential Manager / Google ID 토큰 파싱 — 필드명 기반 리플렉션 직렬화를 쓸 수 있어 keep
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Room 엔티티/DB — ksp가 생성한 코드가 엔티티 필드명을 직접 참조하므로 데이터 클래스 필드는 보존
-keep class com.phonelock.app.data.** { *; }

# 위젯/브로드캐스트 리시버 등 매니페스트에서 이름으로 참조되는 컴포넌트는 AGP 기본 규칙이 이미 보존함
