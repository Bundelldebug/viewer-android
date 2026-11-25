# Android 네이티브 화면·해상도 검사

프로젝트 루트에서 `& OneUI_Viewer_Android/tests/ui/run.ps1` 실행. JDK/SDK는 `build.ps1`과 같은 읽기 전용 툴체인을 사용한다. 처음 실행하면 Maven/Robolectric 테스트 의존성을 이 폴더의 `.cache`에 내려받는다. 테스트 파일/의존성은 릴리스 APK에 포함하지 않는다.

- Android API 34 / Robolectric 4.16.1 / Windows native graphics. 실제 Android 기기나 에뮬레이터에서 실행한 검사는 아니다.
- 결과: `verification.json`, 네이티브 PNG: `.cache/output/`. 320/360/600/840/1280dp, 가로 800×360, 3배 밀도 1080×2400, 큰 글씨·다크 모드·세로 힌지와 모바일/태블릿 편집 도구 배치를 검사한다.
- 동작: 시스템 파일 선택 Intent, DragEvent→파일 열기→시작 화면 복귀, 변경 보호 취소 시 정리, content provider MIME, 캡처 동의 거부.
- 화질: 5200×600 원본의 픽셀·PNG 저장·회전/undo, 9000px 크기 유지·타일/알파 경계, 고밀도 Canvas 픽셀 경계, 수정 없는 사본 바이트 일치, PDF DPI/회전/무손실 RGB 스트림. PDF 스트림은 PDFBox의 디코딩된 RGB 값을 읽어 호스트 비트맵 메모리 순서와 독립적으로 확인한다.
- ICU/글꼴을 여는 Windows 네이티브 라이브러리가 한국어 임시 경로를 처리하지 못하므로 렌더러의 일회성 파일만 `%TEMP%/OneUIViewerUiNative`에 둔다. 실제 사용자 앱 데이터는 사용하지 않는다.
- APK 앱 이름/버전, 서명, ZIP/네이티브 정렬은 별도의 최종 APK 구성 검사다. 실기기의 시스템 선택기·앱 간 권한·MediaProjection 캡처·PDF/인쇄 실행은 후속 기기 검증이 필요하다.
