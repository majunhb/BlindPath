@echo off
set JAVA_HOME=D:\jdk17
set ANDROID_HOME=C:\Users\Administrator\AppData\Local\Android\Sdk
set GRADLE_USER_HOME=C:\Users\Administrator\.gradle
set PATH=D:\jdk17\bin;%PATH%
call gradlew.bat --no-daemon %* 2>&1
