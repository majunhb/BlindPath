@echo off
chcp 65001
echo ==========================================
echo BlindPath 低内存构建脚本
echo ==========================================

cd /d C:\Users\Administrator\BlindPath

:: 清理缓存
echo [1/4] 清理构建缓存...
rmdir /s /q .gradle 2>nul
rmdir /s /q app\build 2>nul
rmdir /s /q module_voice\build 2>nul
del /q hs_err_pid*.log 2>nul
echo 清理完成

:: 设置低内存JVM参数
echo [2/4] 配置JVM参数（低内存模式）...
set GRADLE_OPTS=-Xmx512m -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=256m
set JAVA_TOOL_OPTIONS=-Xmx512m
set _JAVA_OPTIONS=-Xmx512m
echo JVM参数设置完成

:: 使用Gradle Wrapper构建
echo [3/4] 开始构建（单线程，低内存）...
echo 这可能需要较长时间，请耐心等待...

.\gradlew.bat assembleDebug --no-daemon --max-workers=1 --offline 2>&1

if %ERRORLEVEL% == 0 (
    echo [4/4] 构建成功！
    echo APK位置: app\build\outputs\apk\debug\app-debug.apk
) else (
    echo [4/4] 构建失败，错误码: %ERRORLEVEL%
    echo 请检查内存是否充足，或尝试关闭其他程序
)

echo ==========================================
pause
