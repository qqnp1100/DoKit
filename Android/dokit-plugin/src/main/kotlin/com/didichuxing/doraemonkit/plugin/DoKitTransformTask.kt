package com.didichuxing.doraemonkit.plugin

import com.didichuxing.doraemonkit.plugin.thirdlib.ThirdLibInfo
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Whole-variant transformation is deliberate: stack reachability needs a complete input graph. */
@CacheableTask
abstract class DoKitTransformTask : DefaultTask() {
    @get:Classpath abstract val allJars: ListProperty<RegularFile>
    @get:Classpath abstract val allDirectories: ListProperty<Directory>
    @get:Classpath abstract val hierarchyClasspath: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val runtimeArtifacts: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mergedManifest: RegularFileProperty
    @get:Input abstract val namespace: Property<String>
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val options: MapProperty<String, String>
    @get:Nested abstract val dependencyArtifacts: ListProperty<DoKitDependencyArtifact>
    @get:OutputFile abstract val outputJar: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun instrument() {
        val classes = sortedMapOf<String, ByteArray>()
        val notices = mutableListOf<String>()
        fun add(name: String, bytes: ByteArray) {
            val className = name.removeSuffix(".class")
            if (className == "module-info" || name.startsWith("META-INF/versions/")) return
            val previous = classes.putIfAbsent(className, bytes)
            require(previous == null || previous.contentEquals(bytes)) { "Conflicting duplicate class $className in DoKit scoped inputs" }
        }
        // Preserve AGP's declared classpath order; conflicting duplicates fail instead of silently changing semantics.
        allDirectories.get().forEach { directory -> readClasses(directory.asFile, ::add) }
        allJars.get().forEach { jar -> readClasses(jar.asFile, ::add) }
        val hierarchy = sortedMapOf<String, ByteArray>()
        hierarchyClasspath.files.forEach { readClasses(it) { name, bytes -> hierarchy.putIfAbsent(name.removeSuffix(".class"), bytes) } }
        hierarchy.putAll(classes)
        // Keep the coordinate paired with its actual artifact; project dependencies
        // routinely all use the same classes.jar filename.
        val dependencies = dependencyArtifacts.get().groupBy { it.coordinate }.map { (coordinate, artifacts) ->
            ThirdLibInfo(coordinate, artifacts.distinctBy { it.file }.sumOf { it.file.length() })
        }.sortedBy { it.variant }
        val manifest = ManifestInfo.read(mergedManifest.get().asFile, namespace.get())
        val state = DoKitState(options.get(), manifest, hierarchy.keys, dependencies)
        val pipeline = DoKitBytecodePipeline(state, hierarchy, variantName.get())
        val transformed = pipeline.transform(classes)
        notices.addAll(pipeline.notices)
        val output = outputJar.get().asFile.apply { parentFile.mkdirs() }
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            transformed.toSortedMap().forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry("$name.class").apply { time = 0L }); zip.write(bytes); zip.closeEntry()
            }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(buildString {
            appendLine("variant=${variantName.get()}")
            appendLine("applicationId=${manifest.packageName}")
            appendLine("namespace=${manifest.namespace}")
            appendLine("applications=${manifest.applications.sorted().joinToString()}")
            appendLine("classes=${classes.size}")
            appendLine("resourcePolicy=CLASSES contains bytecode only; AGP retains Java resources separately")
            appendLine("dependencySizePolicy=resolved android-classes-jar runtime artifact bytes (sum per component)")
            dependencies.forEach { appendLine("dependency=${it.variant}\t${it.fileSize}") }
            pipeline.selectedMethods.forEach { (key, level) -> appendLine("stack[$level]=$key") }
            notices.sorted().forEach { appendLine("notice=$it") }
        })
        logger.lifecycle("DoKit ${variantName.get()}: ${classes.size} classes, ${pipeline.selectedMethods.size} slow methods; ${report.get().asFile}")
    }
}

/** A stable coordinate/file association; Gradle fingerprints file contents rather than absolute paths. */
data class DoKitDependencyArtifact(
    @get:Input val coordinate: String,
    @get:Classpath val file: File
) : java.io.Serializable

/** Read JAR, AAR (classes.jar/libs), or class directory without relying on AGP internals. */
internal fun readClasses(file: File, consume: (String, ByteArray) -> Unit) {
    if (file.isDirectory) {
        file.walkTopDown().filter { it.isFile && it.extension == "class" }.sortedBy { it.relativeTo(file).invariantSeparatorsPath }
            .forEach { consume(it.relativeTo(file).invariantSeparatorsPath, it.readBytes()) }
    } else if (file.isFile && file.extension in setOf("jar", "zip", "aar")) {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().sortedBy { it.name }.forEach { entry ->
                if (!entry.isDirectory && entry.name.endsWith(".class")) consume(entry.name, zip.getInputStream(entry).use { it.readBytes() })
                else if (file.extension == "aar" && (entry.name == "classes.jar" || entry.name.startsWith("libs/") && entry.name.endsWith(".jar"))) {
                    ZipInputStream(zip.getInputStream(entry)).use { nested ->
                        var member = nested.nextEntry
                        while (member != null) {
                            if (!member.isDirectory && member.name.endsWith(".class")) consume(member.name, nested.readBytes())
                            member = nested.nextEntry
                        }
                    }
                }
            }
        }
    }
}
