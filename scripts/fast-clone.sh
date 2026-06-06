#!/bin/bash
# BlindPath 快速克隆脚本（国内用户）
# 使用 shallow clone + 国内镜像，1分钟内完成

echo "🚀 BlindPath 快速克隆"
echo "========================"

# 原始仓库
ORIGIN_URL="https://github.com/majunhb/BlindPath.git"

# 国内镜像（按速度排序）
MIRRORS=(
    "https://ghfast.top/https://github.com/majunhb/BlindPath.git"
    "https://mirror.ghproxy.com/https://github.com/majunhb/BlindPath.git"
    "https://gh.api.99988866.xyz/https://github.com/majunhb/BlindPath.git"
)

# 尝试每个镜像
CLONE_SUCCESS=false
for url in "$ORIGIN_URL" "${MIRRORS[@]}"; do
    echo ""
    echo "📥 尝试克隆: $url"
    echo "   使用 shallow clone (--depth=1)，只下载最新版本"
    
    # 先尝试 shallow clone
    if git clone --depth=1 --single-branch --branch main "$url" BlindPath-fast 2>/dev/null; then
        echo "✅ 克隆成功！"
        CLONE_SUCCESS=true
        cd BlindPath-fast
        
        # 配置原始仓库（方便后续push）
        git remote set-url origin "$ORIGIN_URL"
        git remote add mirror "$url" 2>/dev/null || true
        
        echo ""
        echo "📊 克隆结果:"
        echo "   仓库大小: $(du -sh . | cut -f1)"
        echo "   提交历史: $(git rev-list --count HEAD) 条（完整历史需 git fetch --unshallow）"
        echo ""
        echo "💡 提示:"
        echo "   - 如需完整历史: git fetch --unshallow"
        echo "   - 如需所有分支: git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*' && git fetch"
        echo "   - 模型文件(yolov8n.tflite)仅9字节占位符，APP首次运行会自动下载"
        break
    else
        echo "❌ 失败，尝试下一个镜像..."
        rm -rf BlindPath-fast 2>/dev/null
    fi
done

if [ "$CLONE_SUCCESS" = false ]; then
    echo ""
    echo "❌ 所有镜像均失败，请检查网络连接"
    echo "   或手动设置代理: git config --global http.proxy http://127.0.0.1:7890"
    exit 1
fi
