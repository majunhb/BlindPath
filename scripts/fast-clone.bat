@echo off
chcp 65001 >nul
echo 🚀 BlindPath 快速克隆（Windows）
echo ========================
echo.
echo 📥 使用 shallow clone 只下载最新版本...
echo.

set ORIGIN_URL=https://github.com/majunhb/BlindPath.git

:: 尝试直接克隆（GitHub官方）
git clone --depth=1 --single-branch --branch main %ORIGIN_URL% BlindPath-fast 2>nul
if %errorlevel% == 0 (
    echo ✅ 克隆成功！
    cd BlindPath-fast
    
    echo.
    echo 📊 克隆结果:
    for /f "tokens=*" %%a in ('git rev-list --count HEAD') do echo    提交历史: %%a 条
    echo.
    echo 💡 提示:
    echo    - 如需完整历史: git fetch --unshallow
    echo    - 模型文件(yolov8n.tflite)仅9字节占位符，APP首次运行会自动下载
    goto :done
)

echo ❌ GitHub官方连接失败，尝试国内镜像...
echo    请确保已安装 Git 并配置代理，或手动执行:
echo    git clone --depth=1 https://ghfast.top/https://github.com/majunhb/BlindPath.git

:done
echo.
pause
