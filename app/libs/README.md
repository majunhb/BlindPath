# App Libraries

This directory contains local AAR/JAR dependencies for SDKs that cannot be downloaded from Maven repositories.

## Required SDK Files

Download the following SDK files from their official sources and place them in this directory:

### 1. 高德地图 SDK (AMap)
Download from: https://lbs.amap.com/api/android-sdk/download

Required files:
- AMapLocation_xxx.aar (定位 SDK)
- AMap_xxx.aar (地图 SDK)
- AMap3DMap_xxx.aar (3D地图 SDK，可选)

### 2. 百度 TTS SDK
Download from: https://ai.baidu.com/ai-doc/SPEECH/qkdy38h8z

Required files:
- baidu-tts-android-xxx.jar

### 3. 百度 OCR SDK
Download from: https://ai.baidu.com/ai-doc/OCR/3khq60mrn

Required files:
- ocr-sdk-xxx.jar

## After Adding Files

After adding the SDK files, update app/build.gradle.kts to use local dependencies:

```kotlin
dependencies {
    // 使用本地依赖
    implementation(fileTree("libs") { include("*.aar", "*.jar") })

    // 或者指定单个文件
    // implementation(files("libs/amap-location.aar"))
}
```
