// Top-level build file
// 新增：io.gitlab.arturbosch.detekt + org.jlleitschuh.gradle.ktlint

plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.18" apply false
    // 静态分析：Detekt
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
    // 代码格式：ktlint
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

// ----------------------------------------------------------------
// 对所有子模块统一应用 Detekt 和 ktlint
// ----------------------------------------------------------------
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // Detekt 配置
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        allRules = false
        // 并行分析，加速 CI
        parallel = true
        // 发现问题时阻断构建
        isIgnoreFailures = false
        source.setFrom(
            "src/main/kotlin",
            "src/main/java"
        )
    }

    // ktlint 配置
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.2.1")
        android.set(true)
        outputToConsole.set(true)
        outputColorName.set("RED")
        // 发现格式问题时阻断构建
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
        }
        // 排除生成的代码
        filter {
            exclude("**/build/**")
            exclude("**/*.kts")
            include("**/kotlin/**")
        }
    }
}
