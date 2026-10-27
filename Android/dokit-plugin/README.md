# DoKit AGP 9 插件迁移

本目录是唯一插件源码，作为独立 included build 使用。固定 Kotlin/KGP **2.4.21**、AGP public gradle-api **9.4.1**，主工程 Wrapper **9.8.2**，插件输出 JVM **17**。ASM 9.9 的 core/tree/commons/analysis 显式声明；生产依赖不包含完整 AGP 或 Booster。

## 接入

宿主 settings.gradle：

```groovy
pluginManagement {
    includeBuild('dokit-plugin') // 相对宿主 settings 的路径
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
```

宿主通过 Maven Central 的插件 DSL 应用 `io.github.qqnp1100.dokit`。`com.didi.dokit` 和 `com.didi.dokit.debug` 保留为兼容别名，三个 ID 指向同一个实现；旧 ID 可通过实现 JAR 的 buildscript classpath 使用。插件支持在 Android 插件之前或之后应用；Android/DoKit 插件仍应在项目配置阶段应用。

默认仅处理 `variant.debuggable == true` 的变体，包括自定义调试 buildType；同一次调用的 Release 不注册插桩任务。`DOKIT_PLUGIN_SWITCH=false` 或 `dokit.pluginEnable=false` 关闭该变体的插桩。

AGP 9 使用内置 Kotlin。宿主通过 buildscript 显式提升 `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21`，不再应用 `kotlin-android`。目标版本组合超出 Kotlin 已公布的完整支持范围，必须保留真实构建测试，不能仅以版本声明判定兼容。

## 实现与保留的行为

每个启用变体注册 `dokitTransform<Variant>`，通过公开 `ScopedArtifact.CLASSES` 接入。应用使用 `Scope.ALL` 覆盖依赖，库使用 `Scope.PROJECT`；dynamic-feature 有公开 API 接线，仍需独立功能宿主验收。全部能力在同一个有声明输入的全量任务中运行，避免公共改写和慢函数重复插入。

保留核心配置/Activity 代理、第三方依赖信息、OkHttp、URLConnection、WebView、系统/高德/百度/腾讯 GPS、大图检测，以及两种慢函数模式。可选 Hook helper 不在类路径时跳过相应规则，在变体报告中记录原因，不生成不可解析的新引用。

WebView 的 network、dokitWeb、vConsole 独立保存并注入 DoKitManager 的对应运行时开关。任一 WebView 功能开启时接入 WebViewHook。自定义 WebView owner 同时接受点号和斜线形式。

普通慢函数保留包筛选/黑名单/阈值；未显式配置包时使用代码 namespace，独立 applicationId 或 applicationIdSuffix 不会改变筛选范围；入口与所有正常返回插入配对 Hook，外围 catch-all 覆盖显式和隐式异常，key 包含 descriptor 区分重载。调用栈模式先在完整输入建立关系图，再按入口、黑名单、层数计算最短可达层级；跨类、重载、递归、继承、interface/virtual 的已知候选实现及 invokedynamic 的方法 Handle 都纳入分析，每个方法最多插入一对 Hook。动态反射调用、未出现在变换输入中的运行时实现仍无法静态推断。

所有配置、Manifest、依赖、调用图和输出均属于单个变体任务。历史 Java 5 的 JSR/RET finally 子程序先通过 ASM JSRInlinerAdapter 内联，再改写和计算 frames；失败诊断包含类、方法 descriptor 和变体。ASM 依据完整类路径中的类头计算 frames，不在 Gradle daemon 加载 Android/业务类。Activity 超类改写通过源值分析定位 `this` 的 super 构造调用，保留普通 `new Activity()`。

任务为 `@CacheableTask`；JAR 条目排序并固定时间戳。冲突的重复类直接报错，相同字节的重复类去重。Scoped CLASSES 输出仅包含类；服务等 Java resources 由 AGP 的资源链路保留。依赖信息使用公开 ComponentIdentifier，大小口径是显式 `android-classes-jar` ArtifactView 解析出的运行时类 JAR 字节数，同一 component 的多个类 JAR 求和。坐标与文件以独立的嵌套输入对象配对，避免多个源码项目的 `classes.jar` 重名导致统计串扰。编译层级索引也使用同类型的公开 ArtifactView，避免 AGP secondary variants 解析歧义。

报告位于 `build/reports/dokit/<variant>/instrumentation.txt`，包含应用类、依赖大小、慢函数集合和能力跳过原因。字节码 JAR 的实际位置由 AGP Scoped Artifacts 分配。

## 验证

在 Android 目录执行：

```sh
rtk proxy ./gradlew -p dokit-plugin test validatePlugins
rtk proxy ./gradlew -p dokit-plugin test validatePlugins -Pdokit.androidSdk=/absolute/path/to/android-sdk
```

第二条启用独立 Android 宿主验收，需要 API 34 和 Build Tools 36.0.0，以及目标 AGP/KGP 依赖。测试宿主继承外层 Gradle 的 offline 状态和 Gradle user home；首次在线 CI 可解析 AAPT2 等工具，缓存预热后可用外层 `--offline` 验收。SDK 路径只作为测试输入，不写入源码。JVM 测试不要求 Android SDK；未指定 SDK 时只跳过带 SDK 的功能测试。

字节码测试覆盖返回、try/finally、显式/隐式异常、静态/实例、阈值 70000、核心配置/依赖注入、GPS helper 缺失、WebView 无调试局部表/重复处理、网络重复处理、调用图深度/黑名单/顺序/递归/继承/接口和 Activity 构造 verifier。独立 Android 宿主覆盖插件应用顺序、Java/Kotlin、库/应用作用域、自定义 buildType、Debug+Release 同次构建、D8、服务资源、开关切换、两次 configuration cache 复用、clean 后 FROM_CACHE 恢复、跨临时目录缓存复用、源码增改删失效，以及关闭 included build 后从 file Maven 消费插件 marker/JAR/POM/module。功能测试证据与最小宿主产物保存在 `build/reports/migration-host/`。

尚需对真实三方 SDK 版本、图片库完整矩阵、真机 Hook 行为、dynamic-feature、其他字节码插件组合、远程发布和完整 Demo 进行验收。独立最小宿主通过不能替代这些项目。

## 本地发布

```sh
rtk proxy ./gradlew -p dokit-plugin publishAllPublicationsToMigrationRepository
```

也可以调用本目录 `upload.sh`。仅发布至 `build/migration-repository`，不需要发布凭证。Java component 生成 JAR/sources/文档 JAR/POM/module metadata，java-gradle-plugin 生成三个插件 marker。发布 group/version 的唯一来源是本目录 `gradle.properties` 的 `dokitPublishingGroup` / `dokitPublishingVersion`，主 Android 工程读取同一文件。当前坐标为 `io.github.qqnp1100:dokitx-plugin:3.7.12`，独立消费验收应关闭 included-build substitution。

## Maven Central 发布

在 `Android/` 配置本机 `central-publishing.properties` 后执行
`rtk proxy bash upload_maven.sh --prepare --max-workers=2`，将插件实现与
`io.github.qqnp1100.dokit` marker 和全部 SDK 打包到一个签名 bundle。
旧 ID 的 marker 不上传到 Central；完整流程、GPG 配置及消费示例见 [发布说明](../PUBLISHING.md)。
