# Android 릴리스 의존성

다운로드한 원본 AAR/JAR/모델과 `checksums.json`을 함께 보관한다. `build.ps1`는 SHA-256을 검증하고 AAR의 classes.jar/assets/JNI를 임시 빌드에 배치한다. 모델은 빌드 때 assets/tessdata에 넣는다. `Documents/Codex` 아래 툴체인은 수정하지 않는다.

| 파일 | 공식 출처 |
|---|---|
| pdfbox-android-2.0.27.0.aar | https://repo.maven.apache.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/ |
| tesseract4android-4.9.0.aar | https://jitpack.io/cz/adaptech/tesseract4android/tesseract4android/4.9.0/ |
| annotation-1.3.0.jar | https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/ |
| bcprov/bcpkix/bcutil-jdk15to18-1.72.jar | https://repo.maven.apache.org/maven2/org/bouncycastle/ |
| eng.traineddata, kor.traineddata | https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0 |

Tesseract4Android에는 Tesseract 5.5.1, Leptonica 1.85.0, libpng 1.6.48, libjpeg 9f가 들어 있다. 라이선스는 `app/src/main/assets/licenses`에 포함한다. PDFBox/모델/annotation은 Apache-2.0, Bouncy Castle은 해당 배포 라이선스, Leptonica/libpng/libjpeg는 동봉 원문을 따른다. `jpegsrc.v9f.tar.gz`는 JPEG 배포 원문의 출처 보관용이며 APK에 넣지 않는다.

수동 릴리스 빌드: `./build.ps1`. 서명 키를 새로 만들지 말 것. 실제 기기 설치/실행은 아직 확인하지 못했다.
