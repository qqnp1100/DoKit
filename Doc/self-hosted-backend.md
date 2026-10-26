# DoKit 五端自建后台接入

适配基线：`de071324fd7afded4335953f9d330f8b7cc65689`（保留 Android 工具链升级）。

HTTP 地址、Mock 地址、资源地址、WS 完整配对地址、项目 `legacyProjectId` 和 SDK token 均由宿主配置。配置放在 SDK 初始化前；切换后台或项目后重启 App/重载网页，避免已初始化的缓存、请求和 WS 会话混用。示例中的 `backendUrl` 等变量由宿主环境提供，不是 SDK 内的固定服务器。

项目 ID 使用控制台的 `legacyProjectId`。`sdkToken` 是 HTTP `X-DoKit-Token`，不是管理员凭证。WS 使用控制台生成的完整短期配对 URL，保留路径和 query；允许扫码/手动入口覆盖。HTTP 基址可带端口与 `/dokit` 前缀，不附加 `/api/app`。资源完整 URL 使用发布结果中的只读资源凭证。

## Android

仅在 Debug 初始化路径使用完整 SDK：

```kotlin
import com.didichuxing.doraemonkit.DoKit
import com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig

val backend = DoKitBackendConfig.Options().apply {
    platformBaseUrl = backendUrl
    mockBaseUrl = backendUrl
    resourceBaseUrl = resourceRoot
    hybridJsUrl = publishedDokitJsUrl
    vConsoleUrl = publishedVConsoleUrl
    webSocketUrl = pairingUrl
    projectId = legacyProjectId
    sdkToken = projectSdkToken
    queryTimeoutMs = 8000
}
DoKitBackendConfig.configure(backend)
DoKit.Builder(application).build()
```

已有 `.productId(...)` 接法继续可用，显式 Builder 值优先。配置在 `build()` 时锁定，网络端点和项目缓存以此次初始化为准。Java 使用同一 `Options` 类及公开字段。

`Options` 的所有字段均允许传 `null`。字符串字段的 `null`、空字符串和纯空白统一保存为 `""`：空地址不发起对应后台请求，空 `sdkToken` 不提供凭证，空 `projectId` 不提供默认项目 ID。`queryTimeoutMs` 为可空 `Long`，传 `null` 使用默认 8000 ms；非空值仍须在 100–60000 ms 范围。未赋值的字段沿用当前配置，非空 URL 和 token 仍会校验格式。

`hybridJsUrl` 为空时跳过远程脚本注入，本地内嵌脚本模式仍可用；`vConsoleUrl` 为空时跳过 vConsole 注入；`webSocketUrl` 为空时配置式多控连接不启动，仍可通过扫码或手动传入完整地址连接。内嵌 Web 使用相同的空配置语义。

安装 `dokit-mc` 后，需要连接时显式调用（不会因配置地址自动连线）：

```kotlin
MultiControlManager.startConfiguredClientMode()
// 或 MultiControlManager.startConfiguredHostMode()
```

也可使用现有扫码入口，或 `startClientMode(ConnectAddress(...))` 传入新的配对 URL。HTTP token 不作为 WS token。

Android 内嵌 Web/Hybrid 接收相同的后台配置，业务 WebView 请求不会自动携带 SDK token。内嵌 `dokit.js` 必须来自下面 Web 构建；发布 AAR 前先同步资源。Release 继续使用原 `dokit-no-op`，不要在 Release 代码中引用只存在于 Debug 的配置类。

## iOS

通过 CocoaPods 引入本地 `DoraemonKit`。Core 与 Foundation 共用新增 Backend subspec，配置在所有编译单元间共享，凭证只保存在内存。

```objc
#import <DoraemonKit/DoraemonBackendConfig.h>
#import <DoraemonKit/DoraemonManager.h>

DoraemonBackendConfigure(@{
    @"platformBaseUrl": backendUrl,
    @"mockBaseUrl": backendUrl,
    @"resourceBaseUrl": resourceRoot,
    @"hybridJsUrl": publishedDokitJsUrl,
    @"vConsoleUrl": publishedVConsoleUrl,
    @"webSocketUrl": pairingUrl,
    @"projectId": legacyProjectId,
    @"sdkToken": projectSdkToken,
    @"queryTimeoutMs": @8000
});
[[DoraemonManager shareInstance] install];
```

安装一次即可；配置会初始化 `pId` 和 `mockDomain`。如果仍手动设置 `mockDomain`，它必须与配置的 `mockBaseUrl` 一致，否则平台请求过滤和凭证范围不同。安装后禁止重新配置。

使用含多控的 Pod 组合时，在需要连接时调用：

```objc
#import <DoraemonKit/DKMultiControlStreamManager.h>
[[DKMultiControlStreamManager sharedInstance] enableMultiControlWithBackend];
```

现有 `enableMultiControlWithUrl:` 支持新的完整配对 URL。保留正常 TLS 校验。iOS 提供原有多控/事件捕获，不宣称实现 Android/Web 的自动化协议。

## Web

在加载构建后的 `dokit.js` **之前**设置：

```javascript
window.DOKIT_CONFIG = {
  platformBaseUrl: backendUrl,
  mockBaseUrl: backendUrl,
  resourceBaseUrl: resourceRoot,
  webSocketUrl: pairingUrl,
  projectId: legacyProjectId,
  sdkToken: projectSdkToken,
  queryTimeoutMs: 8000,
};
```

SDK 加载后自动以该项目初始化 Mock。需要多控时调用 `window.Dokit.startMultiControl(undefined, 'client')`，默认使用配置地址；也可传新配对 URL，或使用原 UI 输入/扫码入口。

另一个入口是先加载 SDK，再调用 `window.Dokit.configureBackend({...})`。必须早于 `setProductId(...)`；传入 `projectId` 时配置方法会自动设置项目。已选项目后禁止换后台/项目，以避免重复安装拦截器和串用缓存。

宿主 CSP/CORS 需允许实际 HTTP、WS 和资源源，以及 `X-DoKit-Token`。SDK token 仅附加到配置范围内的平台请求，不附加到业务请求。

## 微信小程序

在 App 初始化时配置，早于首次打开 DoKit Mock 组件：

```javascript
const { configureBackend } = require('./dist/utils/backend');
App({
  onLaunch() {
    configureBackend({
      platformBaseUrl: backendUrl,
      mockBaseUrl: backendUrl,
      resourceBaseUrl: resourceRoot,
      projectId: legacyProjectId,
      sdkToken: projectSdkToken,
    });
  }
});
```

也可以提供 `App({ dokitBackend: {...} })`；首次读取时自动载入。原组件 `projectId` 属性仍可用，优先于配置中的缺省项目。保持同一项目至进程重启。组件的缓存按后台与项目隔离。场景请求保留原 method/data，可省略 success 回调，并返回 RequestTask 以继续使用 `abort()`。

小程序仅接入已有 Mock/模板功能，不新增原本没有的 WS 多控协议。请求域名需在微信后台登记。

## Flutter

在原 `DoKit.runApp` 增加参数；其他本地诊断接法不变：

```dart
DoKit.runApp(
  app: yourDoKitApp,
  backend: DoKitBackendConfig(
    platformBaseUrl: backendUrl,
    projectId: legacyProjectId,
    sdkToken: projectSdkToken,
  ),
);
```

也支持 `--dart-define=DOKIT_PLATFORM_URL=...`、`DOKIT_PROJECT_ID`、`DOKIT_SDK_TOKEN` 作为缺省配置。Flutter 只迁移已有启动统计；不新增原本没有的 Mock、体检上传和 WS 多控。保留历史 `playload` 字段，增加项目字段，HTTP 失败捕获并关闭客户端。

仓库当前 Flutter 声明 Dart `>=2.12.0 <3.0.0`。本轮未进行 Flutter 主版本升级。

## 构建顺序

1. `Web/`：安装依赖，运行 `rtk proxy npm run build`。四个 monorepo 包均须成功。独立版在 `Web/packages/web-independent/dist/dokit.js`，外置 Vue 版在 `Web/packages/web/dist/dokit.js`。
2. 把独立版复制到 `Android/dokit/src/main/assets/h5help/dokit.js`，再打 Android AAR。后台仓库的 `client-adapters/dokit/build-web.py` 会自动构建、复制并生成资源 SHA-256 manifest。
3. `Android/`：`rtk proxy ./gradlew :dokit:assembleRelease :dokit-ft:assembleRelease :dokit-test:assembleRelease :dokit-mc:assembleRelease :dokit-autotest:assembleRelease`。宿主 Debug 使用完整库，Release 使用 no-op。
4. `miniapp/`：`rtk proxy npm run build`，分发整个 `dist/`，包括新增的 `utils/backend.js`。
5. iOS 在配有 Xcode/CocoaPods 的机器执行安装和宿主编译；Flutter 在兼容 SDK 环境执行 `flutter analyze` 和宿主构建。

源码补丁生成工具仍支持构建时注入缺省配置；运行时初始化配置优先。含真实凭证的配置、补丁、bundle 和日志只存放在私有构建目录，不发布到公共包源。

## 验证记录（2026-10-23）

已验证：五个 Android 核心 AAR 构建、12 项 JVM 测试；Web 四包构建与独立 bundle 挂载/配置/鉴权隔离；小程序构建与 dist 场景行为；Java 配置和文件路径边界；iOS Foundation 配置跨编译单元共享、配置锁及路径边界；Podspec Ruby 语法。

本机未安装完整 Xcode/CocoaPods 和 Flutter/Dart，因此不声称 iOS SDK 全量编译、Flutter analysis 或五端真机联调通过。尚需在部署环境验证真实证书、配对/断线、平台登录、Mock HTTP 状态与原工具菜单等设备链路。
