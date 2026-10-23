package com.didichuxing.doraemonkit.plugin

import com.didichuxing.doraemonkit.plugin.extension.*
import com.didichuxing.doraemonkit.plugin.thirdlib.ThirdLibInfo
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Snapshot all DSL and property values before task execution. */
internal fun snapshot(extension: DoKitExtension, properties: Map<String, String>): Map<String, String> {
    val result = sortedMapOf<String, String>()
    fun put(name: String, value: Any) { result[name] = value.toString() }
    put("pluginEnable", extension.pluginEnable)
    put("logEnable", extension.logEnable)
    put("thirdLibEnable", extension.thirdLibEnable)
    put("gpsEnable", extension.gpsEnable)
    put("networkEnable", extension.networkEnable)
    put("bigImageEnable", extension.bigImageEnable)
    put("webViewEnable", extension.webViewEnable)
    put("slowMethodEnable", extension.slowMethodEnable)
    for ((prefix, bean) in listOf("gps" to extension.gps, "network" to extension.network,
        "bigImage" to extension.bigImage, "webView" to extension.webView)) {
        bean.javaClass.methods.filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType }
            .forEach { put("$prefix.${it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar)}", it.invoke(bean)) }
    }
    put("normal.threshold", extension.slowMethod.normalMethod.thresholdTime)
    put("normal.packages", extension.slowMethod.normalMethod.packageNames.sorted().joinToString("\n"))
    put("normal.blacklist", extension.slowMethod.normalMethod.methodBlacklist.sorted().joinToString("\n"))
    put("stack.threshold", extension.slowMethod.stackMethod.thresholdTime)
    put("stack.entries", extension.slowMethod.stackMethod.enterMethods.sorted().joinToString("\n"))
    put("stack.blacklist", extension.slowMethod.stackMethod.methodBlacklist.sorted().joinToString("\n"))
    result.putAll(properties)
    return result
}

internal fun readExtension(values: Map<String, String>): DoKitExtension {
    fun bool(name: String, default: Boolean = true) = values[name]?.toBooleanStrictOrNull() ?: default
    val extension = DoKitExtension(
        pluginEnable = bool("pluginEnable"), logEnable = bool("logEnable"), thirdLibEnable = bool("thirdLibEnable"),
        gpsEnable = bool("gpsEnable"), networkEnable = bool("networkEnable"), bigImageEnable = bool("bigImageEnable"),
        webViewEnable = bool("webViewEnable"), slowMethodEnable = bool("slowMethodEnable"))
    for ((prefix, bean) in listOf("gps" to extension.gps, "network" to extension.network,
        "bigImage" to extension.bigImage, "webView" to extension.webView)) {
        bean.javaClass.methods.filter { it.name.startsWith("set") && it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType }
            .forEach { method ->
                val key = "$prefix.${method.name.removePrefix("set").replaceFirstChar(Char::lowercaseChar)}"
                values[key]?.let { method.invoke(bean, it.toBooleanStrict()) }
            }
    }
    fun names(key: String) = values[key].orEmpty().lineSequence().filter(String::isNotEmpty).toMutableSet()
    extension.slowMethod.normalMethod.apply {
        thresholdTime = values["normal.threshold"]?.toInt() ?: 500
        packageNames = names("normal.packages"); methodBlacklist = names("normal.blacklist")
    }
    extension.slowMethod.stackMethod.apply {
        thresholdTime = values["stack.threshold"]?.toInt() ?: 5
        enterMethods = names("stack.entries"); methodBlacklist = names("stack.blacklist")
    }
    return extension
}

data class ManifestInfo(val packageName: String, val applications: Set<String>, val namespace: String = packageName) {
    companion object {
        fun read(file: File, namespace: String): ManifestInfo {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(file)
            val packageName = document.documentElement.getAttribute("package").ifEmpty { namespace }
            val relativePackage = namespace.ifEmpty { packageName }
            val applications = document.getElementsByTagName("application")
            return ManifestInfo(packageName, (0 until applications.length).mapNotNull { index ->
                val element = applications.item(index) as org.w3c.dom.Element
                val name = element.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                when { name.isEmpty() -> null; name.startsWith(".") -> relativePackage + name
                    !name.contains('.') -> "$relativePackage.$name"; else -> name }
            }.toSortedSet(), relativePackage)
        }
    }
}

/** Task-local capability and slow-method configuration, never shared between variants. */
class DoKitState(
    val values: Map<String, String>, val manifest: ManifestInfo,
    val availableClasses: Set<String>, val THIRD_LIB_INFOS: List<ThirdLibInfo> = emptyList()
) {
    val extension = readExtension(values)
    val DOKIT_GPS_MOCK_INCLUDE = availableClasses.any { it.startsWith("com/didichuxing/doraemonkit/gps_mock/") }
    val SLOW_METHOD_STRATEGY = values["DOKIT_METHOD_STRATEGY"]?.toInt() ?: 0
    val STACK_METHOD_LEVEL = (values["DOKIT_METHOD_STACK_LEVEL"]?.toInt() ?: 5).also { require(it in 1..128) { "DOKIT_METHOD_STACK_LEVEL must be in 1..128" } }
    val WEBVIEW_CLASS_NAME = values["DOKIT_WEBVIEW_CLASS_NAME"].orEmpty().replace('.', '/')
    val THIRD_LIBINFO_SWITCH = values["DOKIT_THIRD_LIB_SWITCH"]?.toBoolean() ?: true
    val slowMethodExt = extension.slowMethod.apply {
        if (normalMethod.packageNames.isEmpty() && manifest.namespace.isNotEmpty()) normalMethod.packageNames.add(manifest.namespace)
        manifest.applications.forEach { stackMethod.enterMethods.add("$it.onCreate"); stackMethod.enterMethods.add("$it.attachBaseContext") }
    }
    init { require(SLOW_METHOD_STRATEGY in 0..1) { "DOKIT_METHOD_STRATEGY must be 0 (stack) or 1 (normal)" } }
    fun log(message: String) {
        if (extension.logEnable && values["DOKIT_LOG_SWITCH"] == "true") kotlin.io.println("[dokit plugin] $message")
    }
    fun hasClass(name: String) = name in availableClasses
    fun dokitPluginSwitchOpen() = extension.pluginEnable && (values["DOKIT_PLUGIN_SWITCH"]?.toBoolean() ?: true)
    fun dokitSlowMethodSwitchOpen() = extension.slowMethodEnable && (values["DOKIT_METHOD_SWITCH"]?.toBoolean() ?: false)
    fun ignorePackageNames(className: String): Boolean {
        if (listOf("com.didichuxing.doraemonkit.DoraemonKit", "com.didichuxing.doraemonkit.DoKit", "com.didichuxing.doraemonkit.DoKitReal").any { className.startsWith(it, true) }) return false
        return listOf("com.didichuxing.doraemonkit.", "kotlin.", "java.", "android.", "androidx.").any { className.startsWith(it, true) }
    }
}

data class TransformContext(val state: DoKitState, val variantName: String) {
    val dokit: DoKitExtension get() = state.extension
}
