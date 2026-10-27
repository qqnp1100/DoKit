# 发布到 Maven Central

发布坐标的唯一来源是 `dokit-plugin/gradle.properties`：

```properties
dokitPublishingGroup=io.github.qqnp1100
dokitPublishingVersion=3.7.12
```

一次部署包含 `settings.gradle` 中启用的 13 个 Android Library、`dokitx-plugin`
实现 JAR，以及 `io.github.qqnp1100.dokit` 的插件 marker，共 15 项 publication。
开发者为 xzin（798494008@163.com），主页和 SCM 为
https://github.com/qqnp1100/DoKit，许可证保留 Apache-2.0。

## 本机凭证和签名

在 Central Portal 中验证 `io.github.qqnp1100` namespace，并生成有发布权限的
User Token。将 Token 的用户名和密码配置到 `Android/central-publishing.properties`。
这个文件已被 Git 忽略；模板为 `central-publishing.properties.example`。

```properties
DOKIT_PUBLISH_USERNAME=<Central Portal Token 的用户名>
DOKIT_PUBLISH_PASSWORD=<Central Portal Token 的密码>
DOKIT_SIGNING_KEY_FILE=/absolute/path/to/dokit-private.asc
DOKIT_SIGNING_PASSWORD=<GPG 私钥密码，未加密的私钥可留空>
DOKIT_SIGNING_KEY_ID=
```

限制本机文件权限：

```sh
rtk proxy chmod 600 central-publishing.properties /absolute/path/to/dokit-private.asc
```

私钥需为 ASCII-armored OpenPGP 格式；如果还没有密钥，可在安装 GnuPG 的机器上生成、
导出私钥并发布对应公钥。以下命令中的 `KEY_FINGERPRINT` 替换为实际指纹：

```sh
rtk proxy gpg --full-generate-key
rtk proxy gpg --list-secret-keys --keyid-format LONG
rtk proxy gpg --output /absolute/path/to/dokit-private.asc --armor --export-secret-keys KEY_FINGERPRINT
rtk proxy gpg --keyserver keyserver.ubuntu.com --send-keys KEY_FINGERPRINT
```

发布时由 Gradle 的 signing 插件在内存中签名，不需要调用本机 GPG。
也可通过同名环境变量注入配置；`DOKIT_SIGNING_KEY` 支持直接注入完整私钥文本。
使用签名子密钥时可设置 `DOKIT_SIGNING_KEY_ID`。
`DOKIT_PUBLISH_PROPERTIES_FILE` 环境变量可指定另一份本机 properties 文件。
凭证不会作为命令行参数、POM 或 bundle 内容输出；无需 Maven `settings.xml` 的 server id。

## 验证和准备部署

以下命令均在 `Android/` 目录执行，使用仓库现有的 Gradle/JDK/Android SDK 工具链。

```sh
# 验证坐标和私钥配置，不联网。
rtk proxy python3 scripts/central-publishing.py check

# 生成签名制品及 bundle，不上传。依赖已缓存时可追加 --offline。
rtk proxy bash upload_maven.sh --prepare --max-workers=2
```

`--prepare` 会刷新 `build/central-staging-repository`，分别构建独立插件和 Android
Library，保留 release component 生成的依赖范围和 Gradle Module Metadata，生成
sources、文档 JAR、POM、PGP 签名和 MD5/SHA-1 校验和。
文档 JAR 内含指向维护文档及 Java/Kotlin 源码的 README，不是生成式 API 文档。

输出为 `build/central/dokit-3.7.12-bundle.zip`，使用 Maven 仓库目录结构。
脚本验证 15 项 publication 的坐标、POM 必需字段、内部依赖、GMM、签名文件和校验和，
排除仓库级 `maven-metadata.xml`，并拒绝旧 namespace 的插件 marker 混入。
缺少私钥会在构建前失败，普通构建及本地 Maven 发布不要求发布凭证或签名。

## 上传和公开发布

上传已经准备好的 bundle：

```sh
rtk proxy python3 scripts/central-publishing.py upload
```

也可以一次完成构建、签名、校验、打包和上传：

```sh
rtk proxy bash upload_maven.sh --max-workers=2
```

脚本使用 Central Publisher API 的 `Bearer base64(username:password)` 认证，
一次上传整批制品，部署类型为 `USER_MANAGED`。上传成功后打印 deployment id，
并保存到 `build/central/dokit-3.7.12-bundle.deployment-id`。
到 [Central Portal Deployments](https://central.sonatype.com/publishing/deployments)
查看验证结果；状态为 `VALIDATED` 后点击 Publish 才会公开发布。
上传超时或网络中断时先查看 Deployments，避免重复上传已被服务器接受的部署。
已公开的版本不可覆盖，下次发布需要修改共享版本号。

## 消费 SDK 和插件

正式公开后，DoKit 制品通过 `mavenCentral()` 解析：

```groovy
dependencies {
    debugImplementation 'io.github.qqnp1100:dokitx:3.7.12'
    releaseImplementation 'io.github.qqnp1100:dokitx-no-op:3.7.12'
}
```

完整 SDK 仍引用部分历史第三方版本。宿主还需要为 `gradle/legacy-artifacts.json`
中列出的精确 GAV 配置回退仓库，可参考
`migration-tests/full-runtime-consumer/settings.gradle` 中的仓库和版本白名单；
发布 DoKit 不会将这些第三方依赖一并搬迁到 Central。

通过插件 DSL 使用 Central marker 时，在宿主 `settings.gradle` 中增加：

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

宿主模块应用 Android 插件及 DoKit 插件：

```groovy
plugins {
    id 'com.android.application'
    id 'io.github.qqnp1100.dokit' version '3.7.12'
}
```

使用现有 `buildscript` 方式时，依赖为
`classpath 'io.github.qqnp1100:dokitx-plugin:3.7.12'`，仍可
`apply plugin: 'com.didi.dokit'` 或 `com.didi.dokit.debug`。
这两个旧 ID 的 descriptor 保留在实现 JAR 内，旧 marker 只发布到本地验证仓库，
不上传到本次 `io.github.qqnp1100` namespace。
新插件面向 AGP 9.4.1；更改 Maven 版本号不代表恢复对历史 AGP 的兼容性。

## 本地回归

```sh
rtk proxy python3 -m unittest discover -s scripts/tests -p test_central_publishing.py
rtk proxy bash upload_local.sh --offline --max-workers=2
rtk proxy python3 scripts/verify-publication-metadata.py
rtk proxy ./gradlew -p dokit-plugin test validatePlugins --offline --max-workers=2
```

官方参考：[发布要求](https://central.sonatype.org/publish/requirements/)、
[Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/)、
[Namespace 验证](https://central.sonatype.org/register/namespace/)、
[GPG 公钥发布](https://central.sonatype.org/publish/requirements/gpg/)。
