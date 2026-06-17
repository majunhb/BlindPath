# 第三方 SDK 集成指南

## 📍 高德地图 SDK

### 1. 申请 API Key
1. 访问 [高德开放平台](https://lbs.amap.com/)
2. 注册账号并创建应用
3. 获取 Key：`YOUR_AMAP_KEY`

### 2. Gradle 配置
```kotlin
// app/build.gradle.kts
dependencies {
    // 高德地图定位 SDK
    implementation("com.amap.api:location:latest.integration")
    
    // 高德地图 3D 地图 SDK
    implementation("com.amap.api:3dmap:latest.integration")
    
    // 高德地图搜索 SDK
    implementation("com.amap.api:search:latest.integration")
    
    // 高德地图导航 SDK
    implementation("com.amap.api:navi-3dmap:latest.integration")
}
```

### 3. AndroidManifest 配置
```xml
<manifest>
    <!-- 高德地图所需权限 -->
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    
    <application>
        <!-- 高德地图 API Key -->
        <meta-data
            android:name="com.amap.api.v2.apikey"
            android:value="YOUR_AMAP_KEY" />
    </application>
</manifest>
```

### 4. 定位功能实现
```kotlin
// 初始化定位客户端
val locationClient = AMapLocationClient(context)
val locationOption = AMapLocationClientOption().apply {
    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
    interval = 2000 // 2秒更新一次
    isNeedAddress = true // 需要地址信息
}
locationClient.setLocationOption(locationOption)
locationClient.setLocationListener { location ->
    if (location.errorCode == 0) {
        // 定位成功
        val latitude = location.latitude
        val longitude = location.longitude
        val address = location.address
        val road = location.road // 道路名称
        val direction = location.bearing // 方向
    }
}
locationClient.startLocation()
```

### 5. 步行导航实现
```kotlin
// 初始化导航
val navi = AMapNavi.getInstance(context)
navi.addAMapNaviListener(object : AMapNaviListener {
    override fun onCalculateRouteSuccess(routeIds: IntArray?) {
        // 路线规划成功，开始导航
        navi.startNavi(NaviType.GPS)
    }
    
    override fun onGetNavigationText(text: String?) {
        // 导航语音播报
        text?.let { speak(it) }
    }
})

// 规划步行路线
val from = NaviLatLng(startLat, startLng)
val to = NaviLatLng(endLat, endLng)
navi.calculateWalkRoute(from, to)
```

---

## 🤖 百度 AI 开放平台

### 1. 申请 API Key
1. 访问 [百度 AI 开放平台](https://ai.baidu.com/)
2. 创建应用获取：`API_KEY` 和 `SECRET_KEY`

### 2. 图像识别 SDK
```kotlin
// app/build.gradle.kts
dependencies {
    // 百度 AI 图像识别
    implementation("com.baidu.aip:java-sdk:4.16.14")
}
```

### 3. 物体检测实现
```kotlin
class ObjectDetectionService {
    private val client: AipImageClassify
    
    init {
        client = AipImageClassify(APP_ID, API_KEY, SECRET_KEY)
        client.setConnectionTimeoutInMillis(2000)
        client.setSocketTimeoutInMillis(60000)
    }
    
    fun detectObjects(imageData: ByteArray): List<DetectedObject> {
        val options = HashMap<String, String>()
        options["baike_num"] = "5"
        
        val res = client.advancedGeneral(imageData, options)
        val result = res.getJSONArray("result")
        
        return (0 until result.length()).map { i ->
            val item = result.getJSONObject(i)
            DetectedObject(
                name = item.getString("keyword"),
                confidence = item.getDouble("score"),
                description = item.optString("baike_info", "")
            )
        }
    }
}
```

### 4. 离线物体检测（推荐）
使用 TensorFlow Lite 模型实现本地检测：

```kotlin
// 使用 TensorFlow Lite
dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}
```

预训练模型：
- [MobileNet SSD](https://www.tensorflow.org/lite/models/object_detection/overview)
- [YOLOv5](https://github.com/ultralytics/yolov5)

---

## 🎯 腾讯 AI 语音合成

### 1. 申请密钥
1. 访问 [腾讯云](https://cloud.tencent.com/)
2. 开通语音合成服务

### 2. SDK 集成
```kotlin
dependencies {
    implementation("com.tencentcloudapi:tencentcloud-sdk-java:3.1.800")
}
```

### 3. 语音合成实现
```kotlin
class TtsService {
    private val client: TtsClient
    
    init {
        val cred = Credential(SECRET_ID, SECRET_KEY)
        val httpProfile = HttpProfile()
        httpProfile.endpoint = "tts.tencentcloudapi.com"
        val clientProfile = ClientProfile(httpProfile)
        client = TtsClient(cred, "ap-beijing", clientProfile)
    }
    
    fun synthesizeSpeech(text: String): ByteArray? {
        val req = TextToVoiceRequest()
        req.text = text
        req.sessionId = UUID.randomUUID().toString()
        req.modelType = 1 // 通用模型
        req.volume = 5 // 音量
        req.speed = 0 // 语速
        req.voiceType = 0 // 女声
        
        val resp = client.textToVoice(req)
        return Base64.getDecoder().decode(resp.audio)
    }
}
```

---

## 📋 完整集成步骤

### 第一步：添加依赖
```kotlin
// app/build.gradle.kts
dependencies {
    // 高德地图
    implementation("com.amap.api:location:latest.integration")
    implementation("com.amap.api:3dmap:latest.integration")
    implementation("com.amap.api:navi-3dmap:latest.integration")
    
    // TensorFlow Lite（离线物体检测）
    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    
    // 相机 X
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
}
```

### 第二步：配置密钥
创建 `local.properties`（不要提交到版本控制）：
```properties
AMAP_API_KEY=your_amap_key_here
BAIDU_API_KEY=your_baidu_key_here
BAIDU_SECRET_KEY=your_baidu_secret_here
```

### 第三步：BuildConfig 配置
```kotlin
// app/build.gradle.kts
android {
    buildFeatures {
        buildConfig = true
    }
    
    defaultConfig {
        val localProperties = Properties()
        localProperties.load(project.rootProject.file("local.properties").inputStream())
        
        buildConfigField("String", "AMAP_API_KEY", "\"${localProperties["AMAP_API_KEY"]}\"")
        buildConfigField("String", "BAIDU_API_KEY", "\"${localProperties["BAIDU_API_KEY"]}\"")
    }
}
```

### 第四步：权限申请
```kotlin
// MainActivity.kt
val permissions = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.RECORD_AUDIO
)

ActivityCompat.requestPermissions(this, permissions, 100)
```

---

## 🔧 测试验证

### 1. 定位测试
```kotlin
@Test
fun testLocationService() {
    val locationService = LocationService(context)
    locationService.startLocation()
    
    // 验证能否获取位置
    val location = locationService.getLastLocation()
    assertNotNull(location)
    assertTrue(location.latitude != 0.0)
    assertTrue(location.longitude != 0.0)
}
```

### 2. 物体检测测试
```kotlin
@Test
fun testObjectDetection() {
    val detector = ObjectDetector(context)
    val testImage = loadTestImage("test_person.jpg")
    
    val results = detector.detect(testImage)
    assertTrue(results.isNotEmpty())
    assertTrue(results.any { it.label == "person" })
}
```

---

## ⚠️ 注意事项

1. **隐私合规**：使用定位功能需明确告知用户
2. **API 限额**：免费版有调用次数限制
3. **离线功能**：建议核心功能支持离线使用
4. **错误处理**：网络异常时提供降级方案
5. **性能优化**：相机预览和AI识别注意性能

---

## 📚 参考文档

- [高德地图 Android SDK 文档](https://lbs.amap.com/api/android-sdk/summary)
- [百度 AI 图像识别文档](https://ai.baidu.com/ai-doc/IMAGERECOGNITION/)
- [TensorFlow Lite 文档](https://www.tensorflow.org/lite/guide)
- [腾讯云语音合成文档](https://cloud.tencent.com/document/product/1073)
