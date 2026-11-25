# 삼성 뷰어 Android — PC 기능 대조 / 인계 (2026-11-14)

현재 버전: **9.5 Book 2.0.9** (versionCode 209), Android 앱 표시 이름 **뷰어**. Android 8.0(API 26) 이상.
`build.ps1`로 서명 APK를 만들며 기존 `keystore.jks`를 유지한다. APK는 프로젝트 최상위 `SamsungViewer_Android.apk`와 이 폴더 및 `dist/SamsungViewer.apk`에 복사된다.

## 2.0.9 — 사용자 사진 기준 시작 화면 / 모바일 배치 / 원본 해상도

- 새로 실행하면 샘플 파일을 자동으로 열지 않는다. 사진의 회색 작업 영역, 흰색 중앙 카드, 원본 512px 아이콘, Samsung Viewer 제목, 세 가지 동작을 네이티브 Android 뷰로 배치했다. 런처·Android 앱 정보의 이름은 **뷰어**다.
- 기본 파일 열기·상단 폴더·Ctrl+O는 모두 `ACTION_OPEN_DOCUMENT` 시스템 선택기다. 기존 자체 파일 고르기 대화상자를 제거했다. 폴더 열기는 `ACTION_OPEN_DOCUMENT_TREE`, 앱 전용 캡처/백업 목록은 간단한 네이티브 목록이다.
- 다중 파일 드롭: 위치 강조, content/file URI 중복 제거, 작업 복사가 끝난 뒤 임시 드롭 권한 해제. 변경 내용 보호에서 취소하거나 Activity가 종료되어도 한 번만 해제한다. 확장자가 없는 사진 URI는 제공자의 MIME으로 확장자를 붙인다.
- 화면 캡처는 Android의 MediaProjection 동의 → 일회성 foreground service → PNG 열기로 연결했다. 거부하면 서비스를 시작하지 않는다. 캡처 크기를 임의로 4096px로 줄이지 않는다.
- 320~1280dp, 짧은 가로 화면, 3배 밀도 1080×2400, 큰 글씨·다크 모드·분리 힌지 배치를 렌더링했다. 주요 도구 5개는 고정 배치, 추가 기능은 도구/보기 메뉴다. 태블릿에서는 문서가 도구 막대 아래에서 시작한다. 원본 APK/기존 아이콘을 재사용했고 UI 텍스트·도구 아이콘은 네이티브로 그린다.
- 이미지의 **3200px 열기 제한 및 8192px 크기 조절 제한을 제거**했다. 원본 픽셀을 백그라운드에서 읽고, 큰 텍스처는 `ImageTiles`로 표시한다. 화면 맞춤용 표현과 실제 편집/저장 비트맵을 구분하므로 확대·저장에 축소 미리보기가 사용되지 않는다. 고밀도 Canvas에서 자동 밀도 배율이 섞이지 않도록 명시적 픽셀 경계로 그린다. 반투명 타일 경계도 중복 합성하지 않는다.
- 수정하지 않은 파일의 저장 사본은 **바이트 그대로 복사**한다. 수정한 PNG는 원본 크기/픽셀을 보존한다. 기본 품질은 100, PDF는 300dpi(기존 사용자 설정은 유지). PDF 확대는 보이는 영역을 원본에서 다시 렌더링하고, 내보내기의 2800/3600px 상한을 제거했다. 100 품질의 PDF는 RGB Flate 무손실 압축을 쓴다. 회전된 PDF도 선택한 DPI를 유지한다. 인쇄는 프린터가 요청한 DPI를 사용한다.
- 원본 처리에 필요한 메모리가 없는 경우 해상도를 자동으로 낮춰 저장하지 않는다. 열기/저장/크기 조절 실패를 안내한다. 초대형 파일의 전체 비트맵 편집에는 기기 메모리 한계가 있다. JPEG/GIF/ICO 등 선택한 출력 형식 자체의 특성은 유지된다.
- 검증: `tests/ui/run.ps1`의 Robolectric **18개 네이티브 그래픽/동작 검사** 및 기존 JVM **41개 검사**. 5200×600 PNG 픽셀·저장 왕복, 9000px 타일/알파 경계, 원본 사본 바이트 일치, PDF DPI/회전 및 무손실 RGB 스트림, 파일 선택 Intent, 실제 DragEvent→열기→닫기, 취소 시 권한 정리, 캡처 동의 거부를 검사한다. 결과는 `tests/ui/verification.json`, 화면은 `tests/ui/.cache/output/`. 이것은 Windows에서 실행한 Android 프레임워크 검사이며 실기기 실행이 아니다.
- ADB 연결 기기가 없어 **APK 설치, OEM 시스템 파일 선택기 실화면, 앱 간 실제 드롭 권한, 실제 화면 캡처, PDF/인쇄 서비스 실행은 확인 못 함**. 전체 PC 형식 동등성 작업도 아래 제약 때문에 계속 미완료다.

| PC 기능 | Android 구현 및 이번 보완 | 확인 범위 |
|---|---|---|
| 이미지/문서/PDF/미디어 열기 | 네이티브 Canvas/문서 뷰/미디어 뷰, 시스템 파일 선택기 | Java 컴파일 |
| 폴더 탐색·이전/다음·정렬 | Android 시스템 파일/SAF 폴더 선택 + 선택한 폴더의 파일 목록, 원본 URI와 작업 파일 연결 | 파일 선택 Intent 검사·컴파일 |
| 여러 파일 열기·드롭 | 다중 선택, SEND/SEND_MULTIPLE, 드롭 강조·복사 후 임시 권한 해제 | URI/DragEvent/변경 보호 취소 네이티브 검사, 실제 앱 간 드롭 확인 못 함 |
| 확대·이동·맞춤 | 핀치/더블탭/플링, 도구 모음 | 기존 구현·컴파일 |
| 선택·자르기·비율·크기 변경 | Canvas 도구·비율·크기 대화상자 | 기존 구현·컴파일 |
| 회전·뒤집기·색 보정·알파 제거 | 네이티브 비트맵 연산, 범위/허용치 | 기존 구현·컴파일 |
| 이미지 실행 취소/다시 실행 | 비트맵·주석 스냅샷 | 기존 구현·컴파일 |
| 마크업·텍스트·서명·가리기 | 펜/형광펜/선/화살표/사각형/원/텍스트/서명/가리기 | 기존 구현·컴파일 |
| PDF 단면/연속/양면·페이지 이동 | Canvas PDF 뷰 및 썸네일 | 기존 구현·컴파일 |
| PDF 삽입·복제·삭제·정렬·회전 | 페이지 관리·외부 PDF/이미지 삽입·빈 페이지 | 기존 구현·컴파일 |
| PDF 실행 취소/다시 실행 | 페이지·주석 상태 30단계, 비트맵 공유 | 이번 추가·컴파일 |
| PDF 암호 | PDFBox로 사용자 입력 암호 처리 후 앱 캐시에서 렌더, 종료/재시작 시 임시 평문 삭제 | 이번 추가, 실제 암호 PDF 기기 실행 확인 못 함 |
| 이미지 OCR·검색·텍스트 복사 | Tesseract 5.5.1, 한국어+영어 모델 동봉. 가짜 텍스트 영역 검출 제거 | JNI/모델 APK 포함 확인, 실제 인식 실행 확인 못 함 |
| PDF 텍스트 검색·스캔 OCR | PDFBox의 실제 페이지/단어 좌표, 이미지 PDF는 OCR. 편집된 페이지 순서·삽입·회전 반영 | 코드·컴파일, 기기 실행 확인 못 함 |
| Word/HWPX/스프레드시트/슬라이드/텍스트 편집 | 단위 키 기반 수정, 검색/치환·실행 취소. 잘못 연결됐던 `DocEdit.save` 인자 및 CSV 행 payload 수정 | DOCX/HWPX/CSV/텍스트 JVM 왕복 검사 통과, UI 입력→저장은 기기 확인 못 함 |
| 구형 DOC/HWP/RTF 저장 | 텍스트를 새 DOCX로 변환하여 시스템 저장기로 내보내기 | 코드·컴파일, 실파일 UI 확인 못 함 |
| 이미지·PDF 내보내기 | PNG/JPEG/WebP/BMP/GIF/TIFF/ICO/PDF, JPEG 품질·PDF DPI 설정 | 코드·컴파일 |
| 문서 인쇄/PDF | 전체 문서를 여러 페이지로 나눔. 인쇄 범위·취소 반영 | 이번 보완, Android 인쇄 서비스 확인 못 함 |
| 클립보드 복사·붙여넣기 | 이미지/선택 영역 복사, 클립보드 URI 열기 | 이번 추가·컴파일 |
| 저장/다른 이름 저장 | 원본 URI 쓰기, 백업, 별도 사본 생성, 저장 실패 시 편집 유지, 다른 이름 저장 시 원본 미변경 | 이번 보완·컴파일, provider별 쓰기/이름 변경/삭제 기기 확인 못 함 |
| 이름 변경·삭제·공유 | 권한 있는 원본 URI에도 반영, 변경분 공유 사본. 삭제 전 원본 영구 삭제 안내 | 코드·컴파일 |
| 정보·EXIF·AI 프롬프트 | AiMeta, 파일 정보, 프롬프트 복사 | 기존 구현·컴파일 |
| 슬라이드쇼·북마크 | 폴더 이미지/현재 PDF, 주석 목록 및 북마크 | 기존 구현·컴파일 |
| 설정·단축키 | 시스템/라이트/다크, 자동/모바일/넓은 배치, 품질/DPI/간격, Ctrl+O/S/Shift+S/P/F/Z/Y/C/V, PageUp/Down | 이번 보완·컴파일 |
| 캡처/탐색기 빠른 보기/업데이트 | MediaProjection 화면 캡처·카메라·Android 공유·시스템 APK 설치. Windows 전역 Space 후킹은 해당 없음 | 캡처 거부 시 서비스 시작 안 함 검사, 실제 캡처 확인 못 함 |

## 모바일/폴더블/태블릿 배치

- 자동: 840dp 미만은 전체 화면 + 하단 주요 도구 5개 + 드로어/정보 시트. 840dp 이상은 패널과 작업 화면, 상단 주요 도구(문서와 겹치지 않음). 추가 기능은 도구/보기 메뉴, 세부 도구만 가로 스크롤한다. 1150dp 미만에서는 목록·정보를 함께 고정하지 않는다.
- 수동 넓은 배치도 600dp 미만에서는 강제하지 않는다. 주요 아이콘·도구에 48dp 터치 영역과 한국어 접근성 이름, 시스템 바/디스플레이 컷아웃/키보드 인셋 적용.
- OEM Window Extensions의 실제 힌지 경계/반접힘 이벤트를 수신한다. 세로 분리 시 왼쪽 목록/오른쪽 작업, 가로 반접힘 시 위쪽 작업/아래쪽 도구. API가 없는 기기는 창 크기 변경으로 대응한다.
- 회전·창 크기·접힘 상태 변경 시 핵심 편집 뷰를 유지한다. 시스템이 Activity를 재생성하는 경우 경로·페이지·줌과 변경 사본을 Bundle/앱 전용 파일로 복원한다. 복원용 이미지/PDF 사본은 평탄화되므로 이전 주석의 개별 편집 이력은 복원하지 않는다.
- 바형/폴더블/태블릿 배치의 네이티브 프레임워크 렌더링은 검사했으나 **실기기/멀티윈도우 화면은 확인 못 함**. ADB 연결 기기/에뮬레이터 없음.

## 형식/운영 제약 — 다음 작업자가 유지할 사실

- BMP/PNG/JPEG/GIF/WebP 및 플랫폼 HEIF/AVIF, 자체 PSD/TGA/PNM/QOI/ICO 등. TIFF 읽기는 첫 페이지의 chunky RGB/RGBA/gray 8/16비트, raw/PackBits/LZW/Deflate를 구현·색상 fixture 검사했다. 타일/분리 평면/CCITT/16비트 Predictor 등은 미지원.
- PC의 Windows 코덱/외부 변환기와 동일한 전체 이미지 형식 보장은 못 한다. RAW/JXR/JXL/JP2/DDS/WMF/EMF 등은 추가 디코더 필요. 플랫폼 의존 코덱도 기기마다 다르다.
- 큰 일반 이미지도 원본 해상도로 연다(2.0.9). GPU 화면 표시는 타일/화면 배율에 맞춘 표현을 쓰며 편집·저장은 원본 비트맵을 쓴다. 메모리 부족 시 임의로 축소해 저장하지 않는다.
- PDF 저장은 PC판과 같이 이미지 PDF로 평탄화한다. 텍스트·링크·양식이 사라진다는 안내가 있다. 원래 벡터/양식을 보존하는 편집은 미구현.
- 네이티브 문서 뷰는 Office/한글 전용 앱과 같은 레이아웃/매크로/수식 계산 엔진이 아니다. DOCX/HWPX/XLSX 등은 기존 ZIP의 텍스트 단위를 바꾸며, 구형 형식 변환은 새 DOCX다. 실제 복잡한 문서/글꼴/도형/차트 재현은 미검증.
- `test.ps1`는 JVM 기반 41개 검사(반응형 정책, TIFF 네 압축, 한글 텍스트·CSV·DOCX·HWPX 저장 왕복)를 실행한다. 테스트용 android.util.Base64와 JSON 라이브러리는 APK에 넣지 않는다.
- 토큰/비밀 키를 복사하지 않았다. `C:\Users\Bundell\Documents\Codex` 툴체인은 읽기만 했다.
- 저장 전 백업은 앱 전용 `files/backups`에 두며 더보기의 '원본 백업 열기'로 찾아 시스템 저장기로 복구 사본을 내보낼 수 있다.

## 근거

- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android), [tessdata_fast 4.1.0](https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0)
- [PDFBox Android](https://github.com/TomRoush/PdfBox-Android), [Android 폴더블 가이드](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware)
- [Android BitmapFactory.Options](https://developer.android.com/reference/android/graphics/BitmapFactory.Options), [MediaProjection](https://developer.android.com/media/grow/media-projection), [Robolectric](https://github.com/robolectric/robolectric)
