# 할 일 앱 (Android)

Kotlin 네이티브 할 일 앱. 추가 / 완료 체크 / 수정(항목 탭) / 삭제(버튼·스와이프, 실행 취소) / 전체·진행중·완료 필터 / 완료 항목 일괄 삭제. 데이터는 기기 내에 저장됩니다.

## APK 설치
`dist/todo-app.apk`를 휴대폰으로 옮겨 실행 → "출처를 알 수 없는 앱 설치" 허용 → 설치. (Android 8.0+)

## 빌드
```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```
JDK 17+ 필요. 디버그 키로 서명된 APK이므로 개인 사용용입니다. 스토어 배포 시 release 서명 설정이 필요합니다.
