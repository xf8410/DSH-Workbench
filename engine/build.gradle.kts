import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    // 花落纯 JVM 引擎：DSH 主 App 复用其实现，不迁移任何花落 UI。
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    // 只要运行时库，不要编译器插件：这里全部用 Json.parseToJsonElement 动态读，
    // 没有 @Serializable 数据类，所以不引 kotlin 序列化插件。
    // 为什么必须引：提供商返回的错误体形状五花八门（error.message / error 是字符串 /
    // detail / reason / error_description / 顶层 code+type / 网关直接吐 HTML），
    // 手写 JSON 扫描器正是最容易出 bug 的地方，而这段代码要处理的是**不可信输入**。
    // 后面搬对话请求体组装时同一份库还要用，不重复引。
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    // 合入 DSH 后这些闸门只约束迁入的 engine，不把花落 UI 规则 retroactively 套到 DSH 旧页面。
    // 显式登记被扫描目录，engine 任何源码变化都不会命中过期的测试缓存。
    inputs.dir(rootProject.file("engine/src"))
        .withPropertyName("engineSourcesScannedByGuards")
        .optional()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        // 红必须自带原因：默认短格式只打 "java.lang.AssertionError at 文件:行"，
        // 断言里写的消息文本一个字都不显示 —— M1 连红两轮查不出根因就是卡在这。
        // 旧 Agora 用 continue-on-error + tail 截断藏错误，这是同一类病的轻量版。
        exceptionFormat = TestExceptionFormat.FULL
    }
}
