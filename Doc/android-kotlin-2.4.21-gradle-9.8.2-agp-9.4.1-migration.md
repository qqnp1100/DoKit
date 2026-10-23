# Android 适配方案：Kotlin 2.4.21 / Gradle 9.8.2 / AGP 9.4.1

> 调研日期：2026-10-22（Asia/Shanghai）
>
> 代码基线：`master`，提交 `626827cd`。
>
> 本文状态：已开始按阶段实施。第 1–13 节保留实施前设计与基线统计；实际改动、构建结果及剩余限制见第 14 节。目标版本始终为 Kotlin 2.4.21 / Gradle 9.8.2 / AGP 9.4.1。

## 1. 结论与适配边界

此次升级需要同时处理构建脚本、DoKit 插桩插件、Android 模块、Kotlin 源码和发布流程。工作量最大的部分是自研插件：现有代码基于 AGP Transform、旧 Variant API 和 Booster，无法仅通过替换三个版本号迁移到目标环境。

建议按以下顺序实施：

1. 整理插件工程与仓库配置，消除旧 `buildSrc` 对所有构建的阻塞。
2. 建立目标工具链，完成 Android DSL、namespace、Kotlin 编译和注解处理迁移。
3. 迁移插桩，保留网络、WebView、GPS、大图和两种慢函数模式的行为。
4. 修复 Demo 的 Debug/Release 依赖隔离，验证 no-op。
5. 重建发布元数据，并用独立宿主验证发布产物。

范围为 `Android/` 独立 Gradle 工程，包括 `settings.gradle` 当前包含的 15 个子项目及自动参与构建的 `buildSrc`。`dokit-leakcanary/` 存在于磁盘，但未被 settings 包含，单列为可选模块。`Flutter/example/android/` 属于另一套工程，不在本次迁移范围内；Web、iOS、miniapp 同样不涉及。

完成标准包括源码构建、插件实际生效、Release no-op 隔离和发布产物可消费。关闭 `DOKIT_PLUGIN_SWITCH` 后编译成功只能作为中间检查点。

## 2. 目标版本核实及环境

### 2.1 官方版本与兼容性

| 项目 | 指定目标 | 核实结果与约束 |
| --- | --- | --- |
| Kotlin / KGP | 2.4.21 | 官方插件已发布；KGP 与 Kotlin 版本号一致 |
| Gradle Wrapper | 9.8.2 | 官方分发已发布，升级时同时更新 Wrapper 脚本、JAR、配置及校验值 |
| AGP | 9.4.1 | 官方 API 站列为当前发布版；9.4 系列要求 Gradle ≥ 9.6.0、JDK ≥ 17 |
| SDK Build Tools | 36.0.0 | AGP 9.4 文档给出的最低值及默认值 |
| Android SDK | 初期保持 compileSdk/targetSdk 31 | 与工具链版本分开管理；没有必要因版本号相近而改成 API 37 |
| Android minSdk | 保持各模块现有 16 / 21 | 若依赖升级提高最低版本，作为独立兼容性变更处理 |

版本来源：[Kotlin 2.4.21 插件](https://plugins.gradle.org/plugin/org.jetbrains.kotlin.android/2.4.21)、[Gradle 发布列表](https://gradle.org/releases/)、[AGP 当前版本](https://developer.android.com/reference/tools/gradle-api)、[AGP 9.4 兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes)。

**需要保留的一项风险：**Kotlin 官方当前列出的 KGP 2.4.20–2.4.21 完整支持范围是 Gradle 7.6.3–9.7.0、AGP 8.5.2–9.3.1。用户指定的 Gradle 9.8.2 / AGP 9.4.1 超出该表上限。官方允许尝试更新版本，但不能据此宣称该组合已经得到完整兼容保证。本文保持指定目标，并要求用真实构建和宿主测试验证；若遇到工具链问题，可用表内版本作诊断对照，不能将降级后的结果作为目标验收。[Kotlin 官方兼容矩阵](https://kotlinlang.org/docs/gradle-configure-project.html)

Gradle 自带的 Kotlin 用于构建逻辑，Android 源码使用的 Kotlin/KGP、运行时 stdlib 是另两个需要核对的层次。不能把 `gradlew --version` 显示的 Kotlin 当作 Android 编译器版本。[Gradle 兼容矩阵](https://docs.gradle.org/current/userguide/compatibility.html)

### 2.2 本机检查结果及本轮验证边界

| 检查项 | 结果 | 影响 |
| --- | --- | --- |
| 工作区 | 调研开始时 Git 工作区干净 | 本轮只新增本文 |
| Java | 当前命令行 JDK 21.0.11；`java_home -V` 仅列出该版本 | 可作为目标环境候选；旧 Gradle 7.0.2 不支持用 JDK 21 运行 |
| Android SDK | SDK 目录存在；含 API 34/35/36/37 等，未找到 API 31 | 保持 compileSdk 31 时需要补装对应 SDK |
| Build Tools | 已有 36.0.0 等版本 | 满足目标 AGP 文档要求 |
| `Android/local.properties` | 不存在 | 现有仓库和发布脚本会无条件读该文件 |
| 本地缓存 | 存在 Gradle 9.8.2、AGP 9.4.1、KGP 2.4.21 的缓存 | 仅证明本机缓存存在，不证明本工程能构建 |
| AGP 9.4.1 缓存 POM | 声明 KGP / stdlib 2.2.10 | 需要显式将源码编译所用 KGP 升至 2.4.21 |
| AGP 9.4.1 缓存 API JAR | 已用 `javap` 核对 ScopedArtifacts、Instrumentation、Library DSL 接口 | 迁移设计使用目标版本公开 API |

本轮进行了配置、源码、Manifest、依赖声明、发布脚本、测试目录和缓存 API 的静态检查，并查阅官方文档。没有执行 Gradle 配置、依赖解析、编译、设备测试或发布。因此下文的“阻塞点”来自代码和 API 对照，不能当作已运行构建的错误日志。

后续旧基线复现使用单独的 JDK 11 环境；目标构建至少使用 JDK 17，建议 CI 固定 JDK 17，并增加现有 JDK 21 的验证。Android 字节码先保持 Java/Kotlin 1.8，Gradle 插件产物建议统一为 JVM 17。运行 Gradle 的 JDK、编译工具链、输出字节码版本应分别声明。

## 3. 当前工程盘点

### 3.1 构建基线

| 配置项 | 当前内容 | 位置 |
| --- | --- | --- |
| Gradle | 7.0.2 | [Wrapper 配置](../Android/gradle/wrapper/gradle-wrapper.properties)，第 6 行 |
| Android 模块 AGP | 7.0.0 | [config.gradle](../Android/config.gradle)，第 35 行，键名 `agp_module_verson` |
| 插件编译 AGP | 4.2.2 | 同文件第 36 行，键名 `agp_plugin_verson` |
| Kotlin | 默认 1.3.72；另一分支 1.4.32；另有 1.5.31 未作为主选择分支 | 同文件第 29–31 行 |
| Kotlin 选择方式 | 发布版本名包含 `kotlin-13` 时选 1.3，否则选 1.4 | 同文件第 163–166 行 |
| 当前发布版本 | `3.7.14.9-kotlin-13` | 同文件第 17 行 |
| 运行/发布模式 | `run_type=0`、`archives_type=0`、`run_env=1`、`use_local=true` | 同文件第 6–14 行 |
| Booster | 4.13.0 | 同文件第 34 行 |
| Dokka | 1.4.30，根构建声明 classpath | [Android/build.gradle](../Android/build.gradle)，第 47 行 |
| SDK | compileSdk=31、targetSdk=31、minSdk 分为 16/21 | config.gradle 第 20–24 行 |
| Kotlin Android 插件 | 13 处启用 `kotlin-android` | Android 应用及含 Kotlin 的库 |
| kapt | 11 处启用，其中含两个 JVM 插件工程 | 各模块及 buildSrc |
| namespace | Android Gradle 脚本中没有显式声明 | 全部 Android 模块 |
| 仓库 | 4 处有效 `jcenter()`，另有 Sonatype 老地址及私有/本地仓库 | 根构建及 buildSrc |

`needKotlinV14()` 将发布命名与编译器选择耦合，升级时应删除这条分支，改成唯一 Kotlin 2.4.21 配置。仅改 `kotlin_version_v15` 不会改变当前选择结果。

### 3.2 模块与 namespace 迁移表

下面的 namespace 直接来自现有源码 Manifest 的 `package`，用于保持 R/BuildConfig 包名稳定。应用的 `applicationId` 单独保留。

| 模块 | 类型/职责 | minSdk | 建议 namespace | 专项关注 |
| --- | --- | --- | --- | --- |
| app | Demo 应用 | 21 | `com.didichuxing.doraemondemo` | 三份 Manifest、Glide kapt、resValue、Debug/Release 隔离 |
| dokit-util | Java 基础库 | 16 | `com.didichuxing.doraemonkit.util` | 不需要额外 Kotlin Android 插件；Java 目标版本 |
| dokit | 核心库 | 16 | `com.didichuxing.doraemonkit` | BuildConfig、Room、AutoService、网络和 UI |
| dokit-ft | 文件传输 | 16 | `com.didichuxing.doraemonkit.ft` | Ktor、AutoService、跨模块 R |
| dokit-mc | 一机多控 | 21 | `com.didichuxing.doraemonkit.mc` | Ktor/WebSocket、Epic、依赖 dokit-test |
| dokit-autotest | 自动化能力 | 21 | `com.didichuxing.doraemonkit.autotest` | Ktor、依赖 dokit / dokit-test |
| dokit-test | 测试与 Mock 运行库 | 21 | `com.didichuxing.doraemonkit.test` | 这是产品库，不是 Gradle 测试工程 |
| dokit-okhttp-api | 网络适配入口 | 16 | `com.didichuxing.doraemonkit.okhttp_api` | 同时依赖 v3/v4 适配模块 |
| dokit-okhttp-v3 | OkHttp 3 适配 | 16 | `com.didichuxing.doraemonkit.okhttp_v3` | `api` 暴露 OkHttp 3.14.7 |
| dokit-okhttp-v4 | OkHttp 4 适配 | 16 | `com.didichuxing.doraemonkit.okhttp_v4` | `compileOnly` OkHttp 4.7.0 |
| dokit-weex | Weex 扩展 | 16 | `com.didichuxing.doraemonkit.weex` | Weex SDK 为 compileOnly、AutoService |
| dokit-no-op | Release 空实现 | 16 | `com.didichuxing.doraemonkit` | 与完整库是替代关系，不能同时进入宿主 |
| dokit-pthread-hook | 线程/FD Hook | 21 | `com.kronos.dokit.pthread` | Matrix 2.0.2、ViewBinding、Native 依赖 |
| dokit-gps-mock | GPS Mock | 16 | `com.didichuxing.doraemonkit.gps_mock` | 百度本地 JAR/SO、地图 SDK、AutoService |
| dokit-plugin | JVM Gradle 插件 | 不适用 | 不适用 | 目标 AGP API、ASM、发布插件标识 |
| buildSrc | 隐式 JVM 构建逻辑 | 不适用 | 不适用 | 所有工程配置前执行，保存插件源码副本 |
| dokit-leakcanary（未启用） | 历史扩展 | 16 | `com.squareup.leakcanary` | 如恢复：AIDL、BuildConfig、缺失的 `dependencies["kotlin"]` 键 |

迁移 namespace 后删除相应源 Manifest 上用于定义 namespace 的 `package`。`app/src/debug/java/AndroidManifest.xml` 与 `src/release/java/AndroidManifest.xml` 是自定义路径，也必须处理，不能只改 `src/main/AndroidManifest.xml`。Merged Manifest 仍包含最终应用包名，插件读取它时应区分 namespace 和 applicationId。

namespace 要求及非传递 R、BuildConfig 默认值变化见 [AGP 8.0 迁移说明](https://developer.android.com/build/releases/agp-8-0-0-release-notes)。

### 3.3 依赖和测试现状

当前关键依赖包括：协程 1.3.7/1.4.3、Ktor 1.5.4、Room 2.0.0、Glide 4.9.0、AutoService 1.0、Coil 1.1.0/1.3.2、OkHttp 3.14.7/4.7.0、WCDB 1.0.8、Weex SDK 0.28.0、Epic 0.11.1、Matrix 2.0.2。它们的版本老并不自动意味着全部不兼容，但不能假定旧处理器理解 Kotlin 2.4 的元数据，或旧 Hook 适配当前 Android 运行环境。

建议先固定运行时依赖，优先替换阻塞编译的处理器和构建依赖；协程/Ktor、网络适配、地图/Hook 的大版本变化分开实施。每次升级记录解析后的版本、minSdk/compileSdk 要求及运行验证，避免用“全部升到最新版”掩盖插桩行为变化。

现有 `src/test` / `src/androidTest` 共发现 17 个 Java/Kotlin 文件；插件两个源码目录下未发现测试。`.github` 当前仅有 issue 模板，没有 Android 构建工作流。已有测试不能替代插件字节码测试、发布消费测试和设备回归。

## 4. 已确认的主要阻塞点

P0 表示阻塞配置、编译或核心功能，P1 表示影响产物正确性、运行或发布验收。

| 优先级 | 现状与证据 | 必须采取的动作 |
| --- | --- | --- |
| P0 | `buildSrc/build.gradle:58` 以 implementation 引入 AGP 4.2.2，主工程另用 7.0.0 | 独立插件构建并统一目标 API，避免旧 AGP 经 buildSrc 污染类路径 |
| P0 | `DoKitPlugin.kt:102/112/151` 调用 `registerTransform` | 改用公开 Instrumentation / Scoped Artifacts API |
| P0 | 插件使用 `AppExtension`、`BaseExtension`、`BaseVariant`、`applicationVariants` / `libraryVariants` | 迁移到 `com.android.build.api.dsl` 和 `androidComponents` |
| P0 | `DoKitTransformTaskExecutionListener.kt:3/17/19` 引用内部 TransformTask 并反射 outputStream | 删除该补丁，以正式 Artifact 任务依赖替代 |
| P0 | Android 模块重复应用 `kotlin-android` / `kotlin-kapt` | 启用 AGP 内置 Kotlin；有处理器的模块迁移 legacy-kapt 或 KSP |
| P0 | 所有 Android 模块缺 namespace；库 defaultConfig 保留应用版本字段 | 补 namespace，移除库中的 versionCode/versionName/targetSdkVersion 等不适用 DSL |
| P0 | 根构建/buildSrc 有 `jcenter()` | 改成可用仓库并验证历史依赖能解析 |
| P0 | 发布脚本用 `classifier`，并无条件读取 local.properties | 改用 archiveClassifier/Provider；正常 assemble 不依赖发布凭证 |
| P0 | `dokit` 定义 DOKIT_VERSION；app 定义 `resValue` | 分别显式启用 buildConfig、resValues |
| P0 | 多个模块调用 `getDefaultProguardFile('proguard-android.txt')` | 改为 optimize 配置并单独验证压缩行为 |
| P0 | Kotlin 源码有 `toLowerCase` / `toUpperCase` | 改为新版 API，明确 Locale 语义 |
| P1 | 插件配置、第三方列表、调用栈是全局可变对象 | 改成每个变体的输入/产物，解决并行及缓存串扰 |
| P1 | 插件通过命令行任务名包含 Release 判断是否关闭 | 改成逐变体选择，覆盖一次构建 Debug + Release 的情形 |
| P1 | 发布 POM 只遍历 implementation，遗漏 api，依赖内部 DefaultProjectDependency | 从软件组件发布正确 POM / Gradle Module Metadata |
| P1 | Demo 通用 implementation 引入完整 DoKit，Release 又引入 no-op | 调整 Debug 专属依赖及共享源码，做依赖树与重复类检查 |
| P1 | GPS 本地 SO、Matrix、WCDB 等含 Native 代码 | 核对 ABI、重复 SO、设备加载和 16 KB 兼容性 |

Transform 自 AGP 8.0 被移除，关闭 AGP 9 新 DSL 也不能恢复它。[AGP API 迁移说明](https://developer.android.com/build/releases/gradle-plugin-api-updates) `jcenter()` 在 Gradle 9.0 被移除。[Gradle 9 升级指南](https://docs.gradle.org/current/userguide/upgrading_major_version_9.html)

## 5. 构建与 Kotlin 迁移设计

### 5.1 Wrapper、仓库和配置来源

1. 将 Wrapper 更新到 9.8.2，保留 `-bin` 分发，填写对应官方 `distributionSha256Sum`。使用目标 Gradle 生成并提交 `gradlew`、`gradlew.bat`、`gradle-wrapper.jar` 和 properties，不能只改 URL。
2. 旧工程在生成 Wrapper 前也会配置 buildSrc；若被旧插件卡住，可在临时空工程用目标 Gradle 生成 Wrapper，再将四个生成文件复制回工程。不要用“先在旧工程执行 wrapper 肯定成功”作为启动前提。
3. 根构建与独立插件构建均使用 Google Maven、Maven Central；插件解析补充 Gradle Plugin Portal。删除有效 `jcenter()` 和不再需要的旧 Sonatype 地址。
4. 私有仓库按需启用，凭证由环境变量或 Gradle 用户属性注入；普通构建允许缺少发布配置。
5. `privateRepository()` / `localRepository()` 先判断配置是否存在，返回可选值。当前日志语句在判断 `usePrivateEnv()` 前就调用这些函数，只改 `run_env=0` 并不能解决缺文件问题。
6. 若集中仓库到 settings，先移除各子项目的 repositories，再启用严格仓库模式；独立插件构建有自己的 settings，不会自动继承根仓库。
7. 删除无实际使用的 Dokka 1.4.30 classpath；如保留文档任务，单独选择并验证支持目标工具链的 Dokka 版本。

Wrapper 校验来源：[Gradle 官方校验值](https://gradle.org/release-checksums/)。

### 5.2 AGP 内置 Kotlin 与版本固定

采用 AGP 9 默认内置 Kotlin。Android 模块删除 `kotlin-android`，JVM 插件构建保留 `org.jetbrains.kotlin.jvm` 2.4.21。不要把 JVM 插件工程也改成依赖 AGP 内置 Kotlin。

根构建可以保留现有 Groovy DSL，第一阶段无需同时改为 Kotlin DSL。以下为目标声明示意，版本配置最终应集中到一处：

```groovy
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:9.4.1'
        // 提升 AGP 内置 Kotlin 使用的 KGP；不在 Android 模块应用 kotlin-android。
        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21'
    }
}
```

AGP 官方提供通过 buildscript 提升 KGP 的方式；本机 AGP 9.4.1 POM 也确认其默认依赖仍为 2.2.10。最终必须检查 buildEnvironment 与编译日志，确认解析结果是 2.4.21，不能只检查声明值。[AGP KGP 版本配置](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin)

删除 `kotlin_version_v13/v14/v15`、`kotlin_v13/v14` 和 `needKotlinV14()` 的分流，统一 stdlib 版本。协程拥有独立版本体系，不能写成 2.4.21。不要将 `languageVersion` 设为补丁版本字符串 `2.4.21`；一般采用编译器默认语言版本即可。

有注解处理器的 Android 模块先用 `com.android.legacy-kapt` 9.4.1 承接现有处理器，避免一次引入 Kotlin、AGP、KSP 和处理器四重迁移。移除 `kotlin-kapt`，并在根插件解析处提供 legacy-kapt 的版本。迁移后验证生成物；KSP 作为后续逐处理器优化，不假设现有 AutoService/Room/Glide 声明直接支持 KSP。[内置 Kotlin 迁移](https://developer.android.com/build/migrate-to-built-in-kotlin)

### 5.3 Android DSL 示例及全模块规则

下面是核心库的配置片段，需合并到现有 dependencies、buildTypes 等块中，不能直接覆盖整个文件：

```groovy
plugins {
    id 'com.android.library'
    id 'com.android.legacy-kapt' // 根插件解析处固定 9.4.1
}

android {
    namespace 'com.didichuxing.doraemonkit'
    compileSdk 31
    defaultConfig {
        minSdk 16
        // Android Library 中不保留 versionCode/versionName/targetSdkVersion。
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    buildFeatures {
        buildConfig = true // DOKIT_VERSION 与 BuildConfig.DEBUG
    }
    lint {
        // 初期可保留已记录的历史基线，验收新增问题时必须可见。
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
    }
}
```

同时落实：

- app 保留 applicationId、targetSdk、versionCode、versionName；配置 `buildFeatures.resValues=true`，保留已启用的 ViewBinding。
- 将 `android.kotlinOptions` / `compileKotlin.kotlinOptions` 改为 `kotlin.compilerOptions`；显式统一关联 Java/Kotlin 任务的目标版本，尤其是目前仅设置 Kotlin 1.8 的库。
- 将 `lintOptions` 移到正确层级的 `lint`；将 packagingOptions 改为 `packaging.resources` / `packaging.jniLibs` 对应配置，SO 的 pickFirst 不能误放到 Java resources。
- 移除 `includeCompileClasspath` 隐式发现处理器的做法，显式声明处理器依赖。
- 逐项清理 `android.enableD8`、`android.debug.obsoleteApi` 等历史属性；Jetifier 待确认依赖均已迁移后再关闭。
- 非传递 R 下核查跨库资源的所有者与 import；例如 dokit-ft 的 `FileListAction.kt` 同时访问核心库 R。没有发现有效 `case R.*` 命中，不能把全局恢复 final R 当作必要步骤。
- Kotlin 文件大量位于 `src/main/java`，要验证主源码与 debug/release 自定义目录是否仍进入 Kotlin 编译；新增/生成源码使用官方 Source API，不手工修改编译任务输入。
- 当前 dokit 开启 DataBinding，但扫描未发现 `<layout>` XML；确认没有实际使用后可以关闭，不把它误写成已经使用 ViewBinding。dokit-pthread-hook 和 app 的 ViewBinding 则明确开启。
- 如恢复 dokit-leakcanary，单独开启 AIDL、BuildConfig 并修复依赖配置；当前不将它加入默认构建来扩大范围。

`resValues` 默认变化、旧 ProGuard 默认文件限制及旧 DSL 移除见 [AGP 9 行为变化](https://developer.android.com/build/releases/agp-9-0-0-release-notes)。

### 5.4 已定位的 Kotlin 源码修改

| 文件（Android/ 下） | 当前语句 | 改法与注意事项 |
| --- | --- | --- |
| `dokit/src/main/java/com/didichuxing/doraemonkit/extension/DokitExtension.kt:190` | `toLowerCase(Locale.ROOT)` | `lowercase(Locale.ROOT)` |
| `dokit/src/main/java/com/didichuxing/doraemonkit/kit/h5_help/JsHttpUtil.kt:151/249/386` | 无 Locale 的大小写转换 | HTTP scheme/method 使用 ROOT；普通用户文本若需保持旧行为使用默认 Locale |
| `dokit-test/src/main/java/com/didichuxing/doraemonkit/kit/test/mock/http/DoKitMockInterceptor.kt:79` | `toLowerCase(Locale.ROOT)` | `lowercase(Locale.ROOT)` |
| 插件 `DoKitExt.kt:177` | 文件后缀 `toLowerCase()` | 使用 `lowercase(Locale.ROOT)` |
| 插件 `transform/asmtransform/BaseDoKitAsmTransformer.kt:78` | `.max() ?: 20` | 改为 `.maxOrNull() ?: 20`，保持空集合回退语义 |

插件路径在两份源码中均存在；收敛源码后只修改保留的一份。Kotlin 旧大小写扩展从 2.1 起已提升为错误，Java 的同名 String 方法不应被批量替换。[Kotlin 标准库说明](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/to-lower-case.html)

其余 Kotlin 2/K2 的空安全、重载解析、泛型边界、Java 互操作和默认接口方法变化，以实际编译诊断逐项修复。保留 public API 的 `@JvmStatic` / `@JvmOverloads` 与方法签名，使用 ABI 对比核验 no-op 和完整库。不要用跳过元数据版本检查来代替兼容修复。

扫描仅发现 3 条已注释的 Kotlin synthetic import，没有发现有效 synthetic 使用，因此不安排一轮无依据的全项目视图绑定重写。

### 5.5 注解处理器验收

| 处理器 | 当前调用方 | 迁移后必须检查 |
| --- | --- | --- |
| Room 2.0.0 | dokit 的 DokitDatabase、MockApiDao（Java 源码） | 数据库实现生成、DAO 编译、Mock 数据读写；可评估 annotationProcessor，但不可与 kapt 双重生成 |
| AutoService 1.0 | dokit、ft、mc、autotest、test、weex、gps-mock、pthread-hook | 每个 AAR/JAR 中 `META-INF/services` 内容，以及 ServiceLoader 能加载工具和能力 |
| Glide compiler 4.9.0 | app | 先确认实际处理输入与生成 API；保留时验证生成物，升级需与 Glide 运行库配套 |
| 插件工程 kapt | buildSrc / dokit-plugin | 当前未声明 kapt processor 依赖，可在源码收敛时移除无用插件 |

`DoKitManager.kt` 和 `ToolPanelUtil.kt` 通过 ServiceLoader 加载能力/工具。因此“编译成功但工具面板为空”可能是处理器或服务资源丢失，必须纳入设备回归。

处理器生成的服务声明还需要经过合并、打包和 R8 验证。当前核心库的 `proguard-rules.pro` 没有有效规则，当前启用模块中只有 gps-mock 显式接入 consumerProguardFiles，且该文件为空。后续应根据实际反射、JNI、ServiceLoader 和注入调用补充精确的消费者规则，并确认规则随 AAR 发布；模块自己的 proguardFiles 不等同于向宿主交付 consumer rules，不建议用保留整个包来掩盖缺失规则。

## 6. 自研 Gradle 插件迁移设计

### 6.1 先消除两份源码

静态比较发现 buildSrc 与 dokit-plugin 各有 40 个 Kotlin 文件，内容全部相同；差异只有插件 descriptor 文件名：本地 `com.didi.dokit.debug`，发布 `com.didi.dokit`。根构建的 `copyPluginSource` 依赖 `deleteSource`，会先删除发布模块源码再复制。

建议以 `Android/dokit-plugin/` 为唯一源码工程，转换为独立 included build：

- 新增独立 `settings.gradle`，自管 repositories、KGP 2.4.21、发布配置，不再依赖父工程的 rootProject.ext。
- 应用 `java-gradle-plugin`、`org.jetbrains.kotlin.jvm`、`maven-publish`，生成插件描述和 marker。
- 主工程在 `pluginManagement { includeBuild("dokit-plugin") }` 接入；同时移除普通 `include ':dokit-plugin'`，不能两种方式重复纳入。
- app 改用 plugins DSL 解析插件；保留正式 ID `com.didi.dokit`。若继续支持调试别名，可让两个 ID 指向同一实现，但每个项目只应用一次。
- 删除 buildSrc 中的插件副本及自动编译入口，移除 `copyPluginSource` / `deleteSource`。旧 buildSrc 不能留在原位继续被编译。
- 发布插件测试通过独立构建命令执行；主工程不再使用 `:dokit-plugin:...` 普通子项目路径。

插件编译优先 `compileOnly("com.android.tools.build:gradle-api:9.4.1")`，使用 Gradle 提供的公开 API；不将完整 AGP implementation 打入发布运行依赖。独立测试宿主显式提供 AGP，ASM tree/commons 等依赖显式声明并统一版本，验证所处理字节码版本。

Booster 4.13.0 既提供旧 Gradle 接入又提供字节码辅助方法。此次建议移除其旧 AGP 集成，用项目内部小型适配层替代所用工具方法；可保留不依赖旧 API 的纯算法，但必须检查传递依赖。不能只把 Booster 版本号改大就宣称 Transform 已迁移。

### 6.2 API 对照

| 当前实现 | 目标实现 |
| --- | --- |
| `project.plugins.hasPlugin(...)`，依赖应用顺序 | `pluginManager.withPlugin(...)` 注册对应 Android 类型 |
| `AppExtension` / `LibraryExtension` / `BaseExtension` | 新 DSL 中的 ApplicationExtension / LibraryExtension；只访问公开接口 |
| `applicationVariants` / `libraryVariants` | 对应 AndroidComponentsExtension 的 onVariants |
| `registerTransform` / TransformInvocation | Instrumentation 或 Scoped Artifacts 的类处理任务 |
| `projectsEvaluated` / `afterEvaluate` 中完成变体扫描 | 在 onVariants 注册任务，用 Provider 延迟获得输入 |
| 通过任务名寻找 `processDebugManifest` 并 doLast | `variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)` |
| `variant.displayName.split(" ")` 获取坐标 | 公开 ResolutionResult / ComponentIdentifier / ArtifactCollection |
| 反射 TransformTask 的 outputStream | 删除；用 Artifact 关联完成任务依赖 |
| 全局可变 DoKitExtUtil / METHOD_STACK_KEYS | 每个变体的不可变参数、声明的输入文件和输出文件 |
| `taskNames` 中出现 Release 就全局关闭 | 对当前变体的 buildType/debuggable/显式允许列表进行选择 |

新 DSL 不应继续强转旧类型；AGP 9 的 CommonExtension 形状也有变化，勿直接复制旧版本带六个类型参数的样例。插件需覆盖应用、库的接入；dynamic-feature 若继续承诺支持，应加入独立测试宿主。

### 6.3 插桩链路拆分与保留行为

现有 `DoKitCommonTransform` 顺序为：Comm → ThirdLibs → OkHttp → URLConnection → WebView → 系统/高德/百度/腾讯 GPS → BigImg → 普通慢函数 GSM → 入口调用栈 EnterMS，再追加 N 轮 MSD 依赖扫描。

| 能力 | 原实现 | 迁移要求 |
| --- | --- | --- |
| 核心初始化、组件 Hook | CommClassTransformer | 保留 owner/name/descriptor、调用时机与开关行为 |
| 第三方列表注入 | ThirdLibsClassTransformer | 构建依赖清单作为声明输入，再注入 DoKitReal.initThirdLibraryInfo |
| OkHttp / URLConnection | Okhttp3ClassTransformer / UrlConnectionTransformer | 覆盖外部依赖中的类，防止拦截器重复插入 |
| WebView | WebViewClassTransformer | 保留替换类名、网络、dokitWeb、vConsole 各自开关 |
| GPS | GPS*ClassTransformer | SDK 存在/缺失两条路径；关闭时不引入缺失类引用 |
| 大图检测 | BigImgClassTransformer | 验证 Glide/Picasso/Fresco/Coil 等真实签名和版本边界 |
| 普通慢函数 | GSMClassTransformer | 包筛选、黑名单、阈值、返回与异常路径 |
| 调用栈慢函数 | EnterMS + MSD + MethodStackNodeUtil | 保留跨类、跨层依赖分析，不能降级为只插入口方法 |

**普通逐类能力：**可迁移到 `AsmClassVisitorFactory`，通过 ClassNode 适配现有规则，再交还 AGP 下游 visitor。应用使用 `InstrumentationScope.ALL` 才能覆盖 DoKit、OkHttp、图片库等依赖；库只能用 PROJECT。不能全局排除 `com.didichuxing.doraemonkit`，否则 Comm/ThirdLibs 需要处理的目标也会被排除，应按规则细分过滤。[InstrumentationScope 文档](https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/instrumentation/InstrumentationScope)

Visitor 处理顺序及 frames 计算要显式设计。改变控制流/继承关系后选择合适的 FramesComputationMode，通过 ASM 校验和 D8/设备加载验证；不要无条件照搬原来的 COMPUTE_MAXS。

**调用栈能力：**现有 MSD 第 N 层读取前一层扫描积累的 METHOD_STACK_KEYS，因此单次无序、并行逐类回调不具备等价语义。建议第一版为 stack 模式注册一个 Scoped Artifacts 全量任务：

1. 从声明的 JAR/目录输入建立类和调用关系索引，读取入口、黑名单、层数等输入。
2. 按旧逻辑逐层计算匹配的方法集合，明确重载、递归、重复边和层数边界。
3. 在同一任务中按确定顺序执行公共改写及多层插桩，写入一个输出 JAR，避免和普通 visitor 重复处理同一能力。
4. 初版允许任务级全量处理；输入未变化时可 UP-TO-DATE/缓存复用。确认等价后再优化局部增量。
5. 不依赖另一个“恰好先执行”的 visitor 填充全局状态。

目标 API 接线示意（Task 类型及属性需实现，片段不是可直接编译的完整插件）：

```kotlin
androidComponents.onVariants { variant ->
    // 先按该变体判断 DoKit 是否启用，再注册处理任务。
    val task = project.tasks.register<DoKitStackTransformTask>(
        "dokitStack${variant.name.replaceFirstChar { it.uppercase() }}"
    ) {
        mergedManifest.set(
            variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        )
        // 参数、依赖报告等使用 Provider / Task 输出关联。
    }
    variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
        .use(task)
        .toTransform(
            ScopedArtifact.CLASSES,
            DoKitStackTransformTask::allJars,
            DoKitStackTransformTask::allDirectories,
            DoKitStackTransformTask::outputJar
        )
}
```

应用示意中的 ALL 不能无条件套到库项目。实现时为任务的类路径声明 `@Classpath`，为 Manifest/报告声明带路径敏感性的输入，为配置声明 `@Input`，为输出 JAR 声明 `@OutputFile`。保留类路径完整性，确定重复条目处理、稳定排序和时间戳；不要在类变换过程中误删服务资源或重复打包 Java resources。与其他字节码插件组合的顺序也需通过宿主验证。[Scoped Artifacts 迁移示例](https://developer.android.com/build/releases/gradle-plugin-api-updates)

### 6.4 Manifest、依赖报告和缓存

Manifest 分析任务使用公开 [MERGED_MANIFEST Artifact](https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/artifact/SingleArtifact.MERGED_MANIFEST) 作为输入，输出规范化配置文件，解析 applicationId、Application 类与所需组件名。`.App`、无点类名、完整类名必须正确展开；不再依赖硬编码的 processDebugManifest，也不在配置期读取尚未生成的文件。

依赖统计通过公开标识获取 group/name/version 或 project identity，文件大小来自实际 artifact。约定统计口径是原始 JAR/AAR 或某种已转换产物，并在新旧报告对照时使用同一口径；不能将 displayName 的空格数量当作稳定格式。

每个变体独立存储这些产物，不使用 daemon 内的共享集合传递状态。配置变化、GPS 模块增删、依赖版本变化、调用栈入口变化都应成为任务输入。避免 task action 捕获 Project；优先以 Property/FileCollection/注入服务访问输入。先通过普通构建，再单独验证 configuration cache 与 build cache，不把它们混为同一种缓存。

## 7. Demo、运行时依赖及 Native 专项

### 7.1 Release no-op 隔离

`app/doraemonkit.gradle` 当前确实为 Release 引入 dokit-no-op，但 `app/build.gradle:154–156/223` 同时使用 implementation 引入 dokit-mc、autotest、test、pthread-hook。这些模块又 implementation 依赖完整 dokit，存在完整库与 no-op 同时进入 Release 的路径。

迁移动作：

1. 将仅用于调试的扩展及核心依赖统一放到 debugImplementation，梳理 main/release 源码引用。
2. app 的 release App.kt 也使用 DoKit Builder 和若干能力接口，逐项与 no-op API 对齐；缺少的桩接口或调试页面迁到正确 sourceSet。
3. 分别输出 Debug/Release runtimeClasspath，检查 Release 仅包含预期 no-op 以及必要的公共 API。
4. 构建同一进程中的 Debug+Release，确认插件只改写允许的变体，Release 不残留插桩调用。
5. 对 APK 检查完整实现、调试组件、服务注册及重复类，而不是仅看 assemble 退出码。

此问题属于已有依赖结构风险，尚未在本轮运行构建复现。

### 7.2 OkHttp 与其他旧运行库

OkHttp 适配不是简单地把 3.14.7 改成 4.x。当前 v3 模块通过 api 引入 v3，v4 模块只在编译时使用 v4，最终宿主解析结果可能被其他依赖改变。准备两个独立宿主分别强制 3.14.7、4.7.0，验证拦截器、Mock、弱网、请求体和响应体读取；新版 OkHttp 支持单列后续范围。

Ktor/协程一起建立运行测试：HTTP 文件传输、WebSocket 多控、连接重建、取消与生命周期。若升级，成组更新相关 artifact，并检查 minSdk；不要让 metadata 解析失败通过全局 force 老 stdlib 或关闭检查绕过。

### 7.3 Native 与 ABI

app 当前 `abiFilters` 是 `armeabi` 与 `arm64-v8a`，本地 libs 同时存在 armeabi/armeabi-v7a/arm64-v8a。应清理过时 ABI，并按实际完整依赖能力选择 arm64-v8a、可选 armeabi-v7a；不能承诺没有完整 SO 集合的 x86_64 模拟器可运行全部功能。

GPS 模块和 app 都带有百度、SSL、crypto、gnustl 等 SO，另有 Matrix/WCDB/地图等传递 Native 库。先形成“来源—ABI—文件哈希—重复项”清单，解释每条 pickFirst 的保留依据，不用全局 pickFirst 隐藏不同版本冲突。

`dokit/src/main/cpp/CMakeLists.txt` 存在，但模块中的 CMake path 配置被注释，当前不能据目录存在就认为这些 C++ 会参与编译。若恢复本地编译，应固定 NDK/CMake 版本；仅升级 NDK 不会重编译第三方预置 SO。

需要支持现代 16 KB 设备时，对实际 SO 做 ELF segment 与 APK ZIP 对齐检查并上机运行；旧二进制不因升级 AGP 自动获得兼容能力。[Android 16 KB 指南](https://developer.android.com/guide/practices/page-sizes)

## 8. 发布流程迁移

### 8.1 当前发布实现的问题

`upload_private.gradle` 和 `upload_maven_central.gradle` 使用内部 DefaultProjectDependency、旧 Jar classifier 属性、硬编码 build 输出文件名，并手写 implementation 的依赖列表。这样会遗漏 api 依赖，例如 dokit 的 Gson、dokit-weex 的核心库和 okhttp-v3 的 OkHttp。

`dokit_module.json` 的项目名与 Maven artifactId 映射必须保留语义；其中还存在未启用模块的历史条目。`run_type=1` 的依赖硬编码 `io.github.didi.dokit`，当前 publish group 却是 `com.didichuxing.doraemonkit`，GPS 依赖名还与映射中的 `dokitx-gps-mock` 不同。需要统一发布坐标来源，不能假设本地发布后远程模式会自动消费到同一批产物。

### 8.2 推荐发布模型

Android Library 使用 `android.publishing.singleVariant("release")` 提供 component，再从 `components.release` 建立 MavenPublication，sources 等通过相应 DSL/任务输出声明。下面是合并到发布约定中的示意：

```groovy
android {
    publishing {
        singleVariant('release') {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            release(MavenPublication) {
                from components.release
                groupId = publishingGroup
                artifactId = publishingArtifact
                version = publishingVersion
            }
        }
    }
}
```

这里保留 afterEvaluate 仅用于等待 publication component 可用，不用于插桩扫描。具体约定与目标 AGP 的 component 创建时机验证后固定。[Android 发布变体说明](https://developer.android.com/build/publish-library/configure-pub-variants)

JVM 插件从 Java component 发布，用 java-gradle-plugin 生成 descriptor/marker；若继续支持原 buildscript classpath 接入，维持既有 artifact 坐标。手工保留的归档任务使用 `archiveClassifier.set(...)` 和 TaskProvider，并保证生成任务依赖可追踪。

自定义 artifactId 时，检查 Gradle 生成的项目依赖映射是否指向正确 publication。必须打开 POM 和 `.module` 检查 group/artifact/version、api/runtime scope、可选/compileOnly 依赖、版本约束，不能假定替换为 component 后无需验证。

发布验收先用工程内的临时 file Maven 仓库和独立消费工程，禁用 project substitution / includeBuild 替代；本地发布与源码依赖两条路径均通过后，再验证远程发布。普通 assemble 不应要求 signing 配置。

现有 Central 脚本仍指向 s01.oss.sonatype.org 的 OSSRH staging/snapshot 地址。OSSRH 已于 2025-06-30 关闭，实际 Central 发布还要迁移到 Central Publisher Portal 支持的流程，并重新核对账号令牌和 namespace；不能将此问题归因于 Kotlin。[Sonatype 官方迁移说明](https://central.sonatype.org/pages/ossrh-eol/)

## 9. 文件级实施清单

| 文件/目录 | 改动内容 | 依赖阶段 |
| --- | --- | --- |
| `Android/gradle/wrapper/*`、`gradlew*` | 生成 9.8.2 Wrapper 和校验配置 | 工具链 |
| `Android/settings.gradle` | 插件解析/仓库；插件 included build；保持 Android 模块清单 | 插件组织 |
| `Android/config.gradle` | 唯一 KGP/AGP 版本；删除 Kotlin 1.x 分支；仓库配置可选；坐标统一 | 工具链 |
| `Android/build.gradle` | classpath、删除 jcenter/Dokka 老声明、移除复制删除插件源码任务 | 工具链 |
| `Android/gradle.properties` | 清理旧属性，保留 DoKit 开关，后续启用缓存验收 | 工具链 |
| `Android/buildSrc/` | 将插件实现收敛到独立工程后移除旧自动构建入口 | 插件组织 |
| `Android/dokit-plugin/settings.gradle`（新增） | 独立构建仓库/插件版本 | 插件组织 |
| `Android/dokit-plugin/build.gradle` | JVM 插件、Gradle API、AGP API、ASM、测试及 publication | 插件迁移 |
| `Android/dokit-plugin/src/main/kotlin/...` | 新 Variant/Artifact API；上下文/调用栈状态改造；Kotlin API 修复 | 插件迁移 |
| 各 Android 模块 `build.gradle` | namespace、内置 Kotlin、legacy-kapt、DSL、JVM target、features | 模块迁移 |
| 各源码 `AndroidManifest.xml` | 删除 namespace 来源 package；保留组件声明语义 | 模块迁移 |
| `Android/app/build.gradle` / `doraemonkit.gradle` | 新插件解析、Debug 专属依赖、resValues、ABI/打包、坐标来源 | 宿主验证 |
| 第 5.4 节列出的 Kotlin 文件 | 大小写与集合 API；后续处理实际 K2 编译诊断 | 源码迁移 |
| `Android/upload*.gradle` / `dokit_module.json` | component 发布、坐标、POM/GMM、可选凭证、Central 新流程 | 发布迁移 |
| 插件测试与独立宿主（新增） | 字节码、真实 Gradle 接入、Maven 消费、缓存/变体测试 | 测试 |
| Android CI（新增） | 固定 JDK/SDK，构建与产物归档，发布和构建分离 | 验收 |

## 10. 分阶段实施与退出条件

| 阶段 | 主要交付 | 退出条件 |
| --- | --- | --- |
| M0：基线 | 旧版可构建环境、依赖/ABI/功能基线、目标兼容风险记录 | 能区分旧环境缺失与迁移引入的问题；如旧依赖不可恢复，明确记录缺失基线 |
| M1：构建组织 | 单份插件源码、独立插件工程、目标 Wrapper/仓库/KGP/AGP、发布脚本基本修复 | 插件 JVM 源码可编译；根 help/projects 能配置，普通任务不需要发布凭证 |
| M2：模块编译 | namespace、DSL、内置 Kotlin、处理器、源码修复 | 插桩关闭时各库及 Demo 可构建；生成代码和服务资源存在 |
| M3：基础插桩 | 核心、依赖统计、网络、WebView、GPS、大图、普通慢函数 | 对照字节码/设备回归通过；开关关闭和依赖缺失路径通过 |
| M4：调用栈 | Scoped Artifacts 多层分析与改写、逐变体状态 | 层数/入口/黑名单/递归/增删类验证通过，无访问顺序依赖 |
| M5：消费与发布 | Release no-op 隔离、组件发布、独立源码/制品消费宿主 | Debug/Release、POM/GMM/marker、必要 Native 与压缩测试通过 |
| M6：稳定性 | configuration/build cache、并行/增量、CI | 目标精确版本下完整验收，留存报告和产物 |

M1 中如果旧插桩暂时无法编译，可以隔离旧代码并实现可配置的新插件骨架；阶段报告必须明确“插桩尚未交付”。只设置 `DOKIT_PLUGIN_SWITCH=false` 无法阻止旧 buildSrc 编译，不能代替构建组织迁移。

可以用同一目标版本逐阶段修改，不强制每一代 AGP 都发布一个过渡版本。需要定位回归时再创建小型对照工程。若 Kotlin/KGP 对目标工具链出现真实不兼容，优先定位可复现问题与支持范围差异，保留指定目标，不默默降低验收标准。

## 11. 验证命令与测试矩阵

### 11.1 执行前提

以下命令是**实施完成相应阶段后的验收方案，本轮未执行**。工作目录为 `Android/`；沿用仓库 RTK 约定，使用 `rtk proxy` 保留完整构建输出。先补齐 SDK、修复配置及仓库访问。所需任务以迁移后的 `tasks --all` 为准。

```sh
rtk proxy ./gradlew --version
rtk proxy ./gradlew help projects --warning-mode all --stacktrace
rtk proxy ./gradlew buildEnvironment
rtk proxy ./gradlew -p dokit-plugin tasks --all
rtk proxy ./gradlew -p dokit-plugin test validatePlugins --warning-mode all
```

核实 Wrapper 9.8.2、AGP 9.4.1、KGP/编译器 2.4.21；保存 buildEnvironment 和具体 Kotlin 编译任务的诊断日志。included build 有独立依赖图，也要检查其编译器/stdlib，不能只查看根构建。

```sh
rtk proxy ./gradlew :app:dependencies --configuration debugRuntimeClasspath
rtk proxy ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
rtk proxy ./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency kotlin-stdlib
rtk proxy ./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency okhttp
rtk proxy ./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency dokit
```

### 11.2 分层编译

```sh
rtk proxy ./gradlew :dokit-util:assembleRelease :dokit-no-op:assembleRelease :dokit-okhttp-api:assembleRelease --warning-mode all
rtk proxy ./gradlew :dokit:assembleRelease :dokit-ft:assembleRelease :dokit-weex:assembleRelease --warning-mode all
rtk proxy ./gradlew :dokit-test:assembleRelease :dokit-mc:assembleRelease :dokit-autotest:assembleRelease :dokit-gps-mock:assembleRelease :dokit-pthread-hook:assembleRelease --warning-mode all
rtk proxy ./gradlew :app:assembleDebug -PDOKIT_PLUGIN_SWITCH=false --stacktrace
rtk proxy ./gradlew :app:assembleDebug -PDOKIT_PLUGIN_SWITCH=true -PDOKIT_METHOD_SWITCH=false --stacktrace
rtk proxy ./gradlew :app:assembleDebug -PDOKIT_PLUGIN_SWITCH=true -PDOKIT_METHOD_SWITCH=true -PDOKIT_METHOD_STRATEGY=1 --stacktrace
rtk proxy ./gradlew :app:assembleDebug -PDOKIT_PLUGIN_SWITCH=true -PDOKIT_METHOD_SWITCH=true -PDOKIT_METHOD_STRATEGY=0 --stacktrace
rtk proxy ./gradlew :app:assembleDebug :app:assembleRelease --parallel --stacktrace
rtk proxy ./gradlew :app:testDebugUnitTest :dokit:testDebugUnitTest :app:lintDebug :dokit:lintDebug
```

还应执行其余含测试模块的 unitTest 任务和连接设备后的 instrumentation 任务。旧测试如依赖真实服务或 Android 环境，先区分环境失败与编译迁移失败，不能简单删除失败用例来通过。

### 11.3 插件、缓存与发布验收

| 维度 | 测试场景 | 通过标准 |
| --- | --- | --- |
| 应用顺序 | Android 插件前/后接入 DoKit | 能正确注册或给出明确限制，不静默失效 |
| 变体 | debug、release、带 flavor 的 debug、自定义调试 buildType、同次构建多变体 | 逐变体启停，不因某任务名包含 Release 关闭全部插桩 |
| 编译输入 | Java、Kotlin 2.4、依赖 JAR/AAR、生成类、不同允许的字节码版本 | 输入均可处理；缺依赖时按能力降级，不制造非法引用 |
| 字节码 | 构造、静态方法、分支、try/finally、异常、协程状态机 | verifier/D8 通过，Hook 次数与调用顺序正确 |
| 慢函数调用栈 | 深度 1/4、重载、跨类、递归、入口与黑名单变化 | 与预期图一致，不依赖类遍历顺序 |
| 增量 | 修改/新增/删除类，修改配置/依赖/Manifest | 新产物正确，不复用旧索引或漏插桩 |
| Configuration cache | 同一任务两次启用缓存 | 第二次复用；没有任务执行期访问 Project 等问题 |
| Build cache | 同一输入重建、不同工作目录复用 | 命中与输出一致；不因绝对路径或 ZIP 时间戳破坏可复现性 |
| 并行 | 多 app、多 flavor、多个 daemon 连续构建 | 配置、第三方列表、调用栈无串扰 |
| 发布 | file Maven 仓库 + 完全独立宿主 | 不依赖本仓库 project substitution；API/runtime 依赖完整 |
| Release | no-op 独立宿主、开启 R8 的消费宿主 | 无完整实现重复类、无 Debug 插桩；服务/反射规则有效 |
| 核心服务打包/缓存 | 29 个 AbstractKit 提供者全部进入 AAR；配置缓存复用、自定义任务 FROM-CACHE 恢复、AAR 哈希一致 | `m2-kapt-services-aar.json`、`m2-kapt-services-build-cache.log` |
| Native | arm64 真机，承诺支持的其他 ABI，必要时 16 KB 环境 | SO 可加载，定位/线程/数据库功能可用 |

缓存检查示例：

```sh
rtk proxy ./gradlew :app:assembleDebug --configuration-cache --configuration-cache-problems=fail --build-cache
rtk proxy ./gradlew :app:assembleDebug --configuration-cache --configuration-cache-problems=fail --build-cache
```

这两次运行仅覆盖相同输入复用，还需按矩阵改变配置/输入验证失效。R8 验收需要新增开启 minify 的消费宿主，当前 app 的 release minifyEnabled=false，不能通过原 Release 构建推断压缩后正常。

发布阶段使用明确指向 file 仓库的发布任务，避免直接运行未核对目的地的全局 `publish`。保存 AAR/JAR、sources、POM、`.module`、插件 marker、依赖树和构建日志；独立宿主必须实际解析这些文件。

### 11.4 设备功能回归清单

- DoKit 初始化、悬浮入口、工具列表及扩展能力发现。
- OkHttp 3/4、URLConnection、WebView 网络、弱网、请求 Mock。
- WebView 自定义类与 dokitWeb/vConsole 独立开关。
- 系统 GPS、GPS Mock，以及实际支持的百度/高德/腾讯 SDK。
- 图片监控、普通慢函数、调用栈慢函数及阈值边界。
- 文件传输、WebSocket 一机多控、自动化操作与重连。
- Room Mock 数据读写、Matrix 线程/FD Hook、数据库及其他 Native 功能。
- Release 安装启动与 no-op 行为；旧 minSdk 支持范围内的代表设备。

## 12. 风险控制、回滚与最终交付

### 12.1 风险处置原则

| 风险 | 默认处理 |
| --- | --- |
| KGP 完整支持范围外的精确目标组合 | 保持目标，增加独立工具链与插件测试，记录真实失败和对照结果 |
| 历史依赖仅能从私有/失效仓库获得 | 先建立坐标清单与可用来源，不在未验证前随意替换实现 |
| 插桩改写不等价 | 逐能力启用，与旧行为及字节码基线对照；调用栈单独验收 |
| 旧 Kotlin/AGP 消费者无法使用新产物 | 维护独立旧版本发布线；本次不承诺新插件同时支持 AGP 4.x/7.x |
| 新依赖抬高 minSdk/compileSdk | 保持当前 SDK 契约作为默认；需要提高时在具体依赖变更中说明影响 |
| 库 compileSdk 被提高 | 检查 AAR metadata 对消费者的 compileSdk 下限，不能只改 Demo |
| Native SDK 无可用新版 | 记录具体能力/ABI限制，未验证的能力不标记为适配完成 |

### 12.2 回滚方案

实施前保留旧构建配置、插件源码、SDK依赖、发布坐标和已验证产物的版本基线。建议按 M1–M6 分批提交，每阶段记录构建与行为结果。回滚以阶段提交为单位，避免只回退 AGP 版本却保留不兼容的插件实现。

迁移产物使用新的候选版本，避免覆盖旧坐标版本的已发布文件。源码依赖和 Maven 消费都通过后再切换正式消费方。临时关闭插桩只用于诊断；如必须回退功能，应明确受影响能力，不能用它宣称升级完成。

### 12.3 完成检查表

- [x] 根构建和独立插件构建均确认 Kotlin 2.4.21、Gradle 9.8.2、AGP 9.4.1。
- [x] 14 个当前 Android 模块均完成 namespace/DSL/内置 Kotlin 迁移。
- [x] 插件仅保留一份源码，旧 Transform/Variant/反射补丁不再进入运行路径。
- [x] 处理器生成物与 ServiceLoader 功能通过。
- [ ] 插桩开关、所有承诺能力、两种慢函数策略通过。
- [ ] Debug/Release、多变体、增删类、并行及缓存验证通过。
- [x] Release no-op 依赖隔离及独立 R8 宿主通过。
- [x] 本地 Maven 发布的 AAR/JAR/POM/GMM/marker 被独立工程成功消费。
- [ ] 承诺的 ABI、设备和 Native 能力完成验证，未验证项有明确记录。
- [ ] CI、版本支持范围、接入说明、迁移说明和回滚基线交付。

本文提供实施前的详细设计与验收依据。完成上述检查后，才能将工程状态更新为“已适配目标版本”。


## 14. 实施记录（2026-10-22）

### 14.1 已落地改动

- Gradle 9.8.2 重新生成完整 Wrapper；分发校验为 `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`，Wrapper JAR 校验与官方值一致。
- `dokit-plugin` 为独立 included build，固定 Kotlin JVM 2.4.21 / JVM 17，使用 AGP 9.4.1 公开 Scoped Artifacts API 和 ASM。已删除重复 `buildSrc`、旧 Transform/Variant/Booster 集成及源码复制删除任务。
- Android 14 个模块完成 namespace/Manifest、内置 Kotlin、legacy-kapt、Java/Kotlin JVM 1.8 和新 DSL 迁移。核心库保留实际引用的 DataBinding API；app/pthread 保留 ViewBinding。
- **compileSdk 从初始计划的 31 调整为 34**：真实 `checkReleaseAarMetadata` 证明 `androidx.databinding:viewbinding:9.4.1` 要求至少 API 34。targetSdk 31 和各模块 minSdk 16/21 保持。没有降低指定 Kotlin/Gradle/AGP 目标。
- Demo 调试源码及布局移入 Debug，Release 独立入口仅引用 no-op 公共 API；调试扩展依赖限定到 Debug。百度 JAR/SO 经哈希确认重复后，由 GPS 模块统一提供，Demo 仅启用 arm64-v8a。
- 处理旧 Kotlin 大小写、OkHttp/Okio API、API 34 动画回调空性、枚举/multipart 穷举和非传递 R 的资源所有者；慢函数计时状态改为线程独立、按描述符与对象身份匹配的调用栈，覆盖递归、重载及并发调用，保留 Hook JVM 签名。
- 新增公开资源 Source API 约定，从 legacy-kapt 声明的输出中整理 AutoService 文件，修复服务已生成但未进入 AAR 的实际问题；核心 AAR 的 29 个提供者以及配置/构建缓存恢复均已验证。
- 发布改用 release software component，保留 artifactId 映射，生成 sources/POM/GMM；默认发布到工程内 file Maven。移除内部 Gradle 类型、手写 implementation 依赖和旧 classifier；普通 assemble 不依赖发布凭证。旧 OSSRH 地址不再默认使用，远程发布尚未执行。
- 历史依赖恢复使用 `legacy-repositories.gradle` 与 `gradle/legacy-artifacts.json`：仅允许核验过的精确 GAV 从历史镜像回退解析，Google/Maven Central 仍优先；记录 POM/AAR 的 SHA-256、大小与已有缓存对照，未升级运行库版本。
- 新候选版本为 `3.7.14.10-kotlin-2.4.21-SNAPSHOT`，避免覆盖旧发布版本。发布 group/version 统一读取 `dokit-plugin/gradle.properties`，主工程与独立插件不会因两处版本常量产生分流。

### 14.2 已执行验证与阶段状态

日志保存在 `Android/build/reports/migration/`，生成产物与报告均不提交。JDK 使用本机 21.0.11；CI 另配置 JDK 17/21，CI 尚未实际运行。

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| Wrapper | 实际运行 Gradle 9.8.2 | `./gradlew --version` |
| 根配置和发布 POM | help/projects/buildEnvironment、dokit/v3 POM 通过 | `m1-config-and-pom.log` |
| 实际编译器 | AGP 9.4.1，KGP 默认 2.2.10 被解析为 2.4.21；compiler-embeddable 2.4.21 | `m1-config-and-pom.log`、`m2-noop-release.log` |
| 基础库 | util、no-op、OkHttp API/v3/v4 Release 编译通过 | `m2-base-libraries.log`、`m5-noop-local-publish.log` |
| Demo Release | assembleRelease 通过；依赖图只有 no-op，无完整核心/调试扩展 | `m2-app-release.log`、`m5-noop-artifacts.json` |
| 本地 Maven 消费 | util/no-op 发布及独立宿主 R8 Release 通过；不使用 includeBuild/project substitution | `m5-maven-noop-r8-consumer.log` |
| 完整 Maven 消费 | 6 个完整运行库在 GMM、强制 POM 两种解析模式下 Debug/R8 Release 通过；刷新依赖确认最终核心 SNAPSHOT 3，全部 38 个 provider 的资源及 DEX 类保留 | `m6-final-full-maven-{module,pom}-verification.json` |
| POM/GMM | no-op/util/v3/v4 坐标、api/runtime/sources 和 compileOnly 排除检查通过 | `scripts/verify-publication-metadata.py` |
| 发布保护 | 无 Central URL 时 help 可用，显式远程任务失败且未写入占位仓库 | `m5-central-guard.log` |
| 插件字节码和宿主 | 17 项测试与公开 API/插件验证通过；独立 AGP/D8 宿主覆盖应用顺序、Debug/Release、自定义变体、服务资源、两种慢函数、历史 JSR/RET、继承接口默认方法、构造器 local 0 复用、配置/构建缓存、增改删及纯 Maven 插件消费 | `dokit-plugin/build/reports/migration-host/`、插件测试报告 |
| 运行时递归/并发 | 独立 JVM 工程 7 项测试通过，JVM 字节码 major 52 | `m4-runtime-recorder-gradle-tests.log` |
| 完整源码与 Debug | 13 个库 Release 构建/本地发布及关闭插桩的 Debug APK 均通过；历史依赖 14 个二进制和 13 个缓存 POM 哈希一致 | `m2-debug-and-m5-publications.log`、`m2-legacy-cache-verification.json` |
| no-op 设备运行 | API 31、4 KB 真机安装成功、冷启动通过；R8 后 Builder/show/hide 状态断言通过 | `m5-noop-device.log` 的 `NO_OP_VERIFIED` |
| 完整 Maven 设备运行 | 最终 SNAPSHOT 3 的 R8 Release 安装/冷启动、Builder 配置及 35 个 AbstractKit 实例发现通过；不调用 Builder.build()/原生初始化 | `m6-full-maven-device-verification.json`、`m6-full-maven-device-process.log` |
| 现有模块单元测试 | app 8、核心 11、ft 1，共 20 项通过，无失败/跳过；最终调用栈插桩启用时再次通过；WebSocket 使用本地 MockWebServer、有界等待 | `m6-final-stack-and-unit-tests.log`、`m6-final-unit-tests.json` |
| 真实多变体/配置缓存 | 普通慢函数 Debug 与 no-op Release 同次并行构建通过，第二次配置缓存复用 | `m6-normal-release-cache-{first,second}.log` |
| 真实普通慢函数 | API 31 真机冷启动、工具窗口渲染；点击 Demo 耗时函数得到 602/1608/1611 ms 记录，阈值 500 ms | `m3-normal-device.log`、`m3-normal-device.json` |
| 真实调用栈慢函数 | 最终 APK 真机冷启动通过，App.onCreate 633 ms，观测层级 0/1/2/3 与配置 4 层一致 | `m4-final-stack-device.log`、`m4-final-stack-device.json` |
| 最终基础插桩 | 最终插件和录屏兼容改动下 Debug/Release 构建及基础 APK 真机冷启动通过；0 个慢函数，公共 Hook 保留 | `m6-final-basic-build-lint-publication.log`、`m3-basic-device.json` |
| Lint | app/core 任务执行成功，沿用原 abortOnError=false；最终 app 7 Error，core 11 Error/3 Fatal，不能标记为 lint clean | `m6-lint-summary.json`、`m6-lint-triage.json` |
| 核心服务打包/缓存 | 29 个 AbstractKit 提供者全部进入 AAR；配置缓存复用、自定义任务 FROM-CACHE 恢复、AAR 哈希一致 | `m2-kapt-services-aar.json`、`m2-kapt-services-build-cache.log` |
| Native | 36 个预置 SO、18 组同名项哈希相同；24 个副本 ELF LOAD 对齐小于 16 KB | `native-inventory.json` |

Gradle 自带的 Kotlin 2.4.10 是构建逻辑版本，不是本项目源码编译器；源码编译器已单独验证为 2.4.21。

### 14.3 可复现入口与未完成项

- `Android/scripts/verify-migration.sh` 按 configuration/modules/instrumentation/cache 阶段验证并保存日志。
- `Android/migration-tests/runtime-recorders` 直接引用生产源码与测试，独立验证慢函数运行时，不依赖完整 Android 运行库的历史依赖。
- `Android/migration-tests/maven-consumer` 独立消费本地 Maven no-op/util，启用 R8，包含 Builder/show/hide 状态断言入口。
- `Android/migration-tests/full-runtime-consumer` 独立消费完整运行库，以 GMM/POM 两种模式验证 Debug 与 R8 Release；`verify-consumption.py` 同时检查发布 AAR 的服务文件、精确构造器保留规则及 APK 资源/DEX。真机断言覆盖 Builder 配置与 35 个 AbstractKit 实例发现，3 个 DokitAbility 已做 APK 静态保留验证，未调用其初始化。
- `Android/scripts/native-inventory.py` 可重新生成 SO 来源、ABI、SHA-256、重复项及 ELF 对齐报告。ELF 对齐检查不能代替 APK ZIP 对齐与设备加载测试。
- no-op 与完整库仍有历史公共 API 命名/入口差异。当前 Release 入口与消费宿主通过，只覆盖其实际引用的公共 API，不等于完整 ABI 一致。
- 旧 JDK 11 / Gradle 7 基线未复现，不能把目标源码构建与旧功能基线宣称为完整等价；网络/GPS/图片/Hook 等真实运行库设备回归仍须逐能力完成。
- 预置 Native 二进制的 16 KB 支持、远程 Central 传输及完整真实设备功能矩阵仍须完成或记录限制。因此本工程目前不标记为“已完成全部目标适配”。

### 14.4 实际构建中补充的回归点

- 真实 app→Android Library 源码依赖触发 AGP secondary artifact 歧义。插件已使用公开 ArtifactView 属性明确选择 `android-classes-jar`，编译层级与统计文件一致；坐标与文件通过嵌套任务输入配对，避免同名 `classes.jar` 误归属大小。统计口径改为处理后的运行时类 JAR，并加入两个不同大小源码库的测试。
- namespace 与 applicationId 不同（包括测试包名/后缀）时，普通慢函数默认匹配 namespace，Manifest 应用类展开也保留 namespace，避免漏插业务类。
- 实际 Debug APK 保留 6 个启用模块全部 38 个服务提供者；核心 Room 的 DokitDatabase_Impl、MockApiDao_Impl 在 AAR 中存在。
- APK 仅打包 arm64-v8a；现有 Weex `libc++_shared.so` 的 SHA-256 固定为 `59aa4cc063b05735c785a70c3ce3598a4e600f9521e8c479151b8d673e7156bf`，静态核对可满足 APK 99 个必需 C++ runtime 符号。`verify-native-cpp-runtime.py` 验证 ABI、路径、提供者哈希、符号与版本。Weex 另依赖未在 APK 中找到的 `libjsc.so`；C++ 符号检查不代表 Weex/Matrix/Native 功能已通过设备验收。
- Demo 无百度地图 API Key 时，旧代码在 `Application.onCreate` 内先于 DoKit 初始化崩溃。现通过 `-PBAIDU_MAP_API_KEY=...` 或同名环境变量配置 Debug Manifest；未配置时跳过百度初始化并提示，其他工具正常运行。真实地图功能需有效且与 applicationId 匹配的 Key，未以虚构凭据通过测试。设备测试使用独立包名 `com.didichuxing.doraemondemo.migration`，不覆盖原 Demo。
- 最终审查新增两项失败复现并修复：调用栈补齐继承接口的默认实现；Activity 构造器按原始 `this` 起源追踪，避免混淆代码重用 local 0 时误改其他对象的构造调用。嵌套 TestKit 宿主改为继承外层 offline 状态和 Gradle user home，首次 CI 可正常获取 Android 工具。
- Lint 发现 Demo 录屏通知 `PendingIntent` 恒传 0，已按 API 23 边界补充 `FLAG_IMMUTABLE`，避免 target 31 在 Android 12 抛异常；核心录屏原本已有相同保护，仅统一 flags 写法以消除路径误报。剩余错误逐项对照 git HEAD：包括历史翻译/资源/注解问题、部分已核实误报，以及宿主将来提高 targetSdk 至 34 时需要处理的录屏前台服务权限与 receiver 导出标志；本次 targetSdk 保持 31。CI 保存实际 lint XML/HTML，不以 Gradle 任务成功替代零错误。

### 14.5 分阶段复现

在 `Android/` 配好 JDK 17/21 与 Android SDK 34、Build Tools 36.0.0 后执行：

```sh
MIGRATION_PHASE=configuration rtk proxy bash scripts/verify-migration.sh
MIGRATION_PHASE=modules rtk proxy bash scripts/verify-migration.sh
MIGRATION_PHASE=instrumentation rtk proxy bash scripts/verify-migration.sh
MIGRATION_PHASE=cache rtk proxy bash scripts/verify-migration.sh
```

每个阶段包含前面阶段，默认最多 2 个 worker，可用 `MIGRATION_MAX_WORKERS` 调整。脚本日志在 `build/migration-reports/`；本次人工实施证据在 `build/reports/migration/`。配置阶段运行独立插件/运行时测试，模块阶段验证全部库、no-op/完整 Maven 消费与 R8，插桩阶段验证三种模式、并行变体、单元测试及 lint，缓存阶段再验证真实 app 配置缓存。设备 smoke 日志单独记录，不由无设备 CI 宣称通过。

本次未执行远程发布或覆盖原 Demo 安装。独立测试包保留于设备用于复核，候选制品仅写入工程内 file Maven 仓库。
