package com.didichuxing.doraemonkit.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.BuildResult
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.objectweb.asm.Opcodes

/** Explicit SDK opt-in keeps JVM bytecode tests usable on machines without Android SDK. */
class AndroidHostFunctionalTest {
    @Test fun androidPluginOrdersVariantsAndCachesUseThePublicArtifactPipeline() {
        val sdk = System.getProperty("dokit.androidSdk").orEmpty()
        assumeTrue("Set -Pdokit.androidSdk=<SDK> to run the AGP host acceptance test", sdk.isNotBlank())
        require(File(sdk, "platforms/android-34/android.jar").isFile) { "Host fixture requires Android SDK 34" }
        val sourceRoot = File(System.getProperty("dokit.sourceRoot"))
        val host = Files.createTempDirectory("dokit-agp-host").toFile()
        try {
            // A separate copy exercises the real included-build plugin classloader and avoids
            // taking locks on this outer test build while Gradle TestKit is running.
            val plugin = File(host, "dokit-plugin").apply { mkdirs() }
            for (name in listOf("settings.gradle", "build.gradle", "gradle.properties")) File(sourceRoot, name).copyTo(File(plugin, name))
            File(sourceRoot, "gradle").copyRecursively(File(plugin, "gradle"))
            File(sourceRoot, "src/main").copyRecursively(File(plugin, "src/main"))
            File(host, "settings.gradle").writeText("""
                pluginManagement { includeBuild('dokit-plugin'); repositories { google(); mavenCentral(); gradlePluginPortal() } }
                dependencyResolutionManagement { repositories { maven { url = uri('fixture-repository') }; google(); mavenCentral() } }
                rootProject.name = 'dokit-functional-host'
                include ':before', ':after', ':library', ':otherLibrary'
            """.trimIndent())
            val legacyRepository = File(host, "fixture-repository/example/legacy-finally/1.0").apply { mkdirs() }
            File(legacyRepository, "legacy-finally-1.0.pom").writeText("""<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>legacy-finally</artifactId><version>1.0</version></project>""")
            ZipOutputStream(File(legacyRepository, "legacy-finally-1.0.jar").outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("example/LegacyFinally.class").apply { time = 0L })
                zip.write(LegacyBytecodeFixtures.jsrFinally()); zip.closeEntry()
            }
            File(host, "local.properties").writeText("sdk.dir=${File(sdk).invariantSeparatorsPath}\n")
            File(host, "gradle.properties").writeText("android.useAndroidX=true\nDOKIT_METHOD_SWITCH=true\nDOKIT_METHOD_STRATEGY=1\n")
            File(host, "build.gradle").writeText("""
                buildscript {
                    repositories { google(); mavenCentral() }
                    dependencies {
                        classpath 'com.android.tools.build:gradle:9.4.1'
                        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21'
                    }
                }
                plugins { id 'com.didi.dokit' apply false }
                subprojects {
                    if (name == 'before') {
                        apply plugin: 'com.didi.dokit'
                        apply plugin: 'com.android.application'
                    } else {
                        apply plugin: (name in ['library', 'otherLibrary'] ? 'com.android.library' : 'com.android.application')
                        apply plugin: 'com.didi.dokit'
                    }
                    android {
                        namespace = (name == 'library' ? 'com.example.library' : name == 'otherLibrary' ? 'com.example.otherlibrary' : 'com.example.host')
                        compileSdk = 34
                        defaultConfig { minSdk = 21 }
                        buildTypes { staging { initWith debug; matchingFallbacks = ['debug'] } }
                        compileOptions { sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }
                    }
                    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8 } }
                    if (name in ['before', 'after']) {
                        dependencies {
                            implementation project(':library')
                            implementation project(':otherLibrary')
                            implementation 'example:legacy-finally:1.0' 
                        }
                    }
                    dokit {
                        slowMethod {
                            normalMethod { packageNames = ['example'] as Set }
                            stackMethod { enterMethods = ['example.Subject.entry'] as Set }
                        }
                    }
                }
            """.trimIndent())
            for (name in listOf("before", "after", "library", "otherLibrary")) {
                val project = File(host, name).apply { mkdirs() }
                File(project, "src/main").mkdirs()
                File(project, "src/main/AndroidManifest.xml").writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application/></manifest>""")
                val source = File(project, "src/main/java").apply { mkdirs() }
                if (name in listOf("library", "otherLibrary")) {
                    val className = if (name == "library") "LibrarySubject" else "OtherLibrarySubject"
                    val padding = if (name == "library") "short" else "larger payload to distinguish two runtime class JAR artifacts with the same classes.jar basename"
                    File(source, "example").mkdirs()
                    File(source, "example/$className.java").writeText("package example; public class $className { public static String payload() { return \"$padding\"; } public static int execute(int value) { return Math.abs(value); } }")
                } else {
                    File(sourceRoot, "src/test/java").copyRecursively(source, overwrite = true)
                    // JVM verifier fixtures provide an Activity stub; an Android host uses android.jar.
                    File(source, "android").deleteRecursively()
                    File(source, "example/KotlinSubject.kt").writeText("""
                        package example
                        class KotlinSubject { fun execute(value: Int): Int = Subject.branch(value) }
                    """.trimIndent())
                    File(project, "src/main/resources/META-INF/services").mkdirs()
                    File(project, "src/main/resources/META-INF/services/example.Service").writeText("example.Subject\n")
                }
            }
            val evidence = File(sourceRoot, "build/reports/migration-host").apply { mkdirs() }
            val offlineArguments = if (System.getProperty("dokit.functionalOffline").toBoolean()) listOf("--offline") else emptyList()
            val gradleUserHome = System.getProperty("dokit.gradleUserHome")
            var buildNumber = 0
            fun run(vararg tasks: String): BuildResult {
                val result = GradleRunner.create().withProjectDir(host)
                    .withArguments(tasks.toList() + offlineArguments + listOf("--stacktrace", "--configuration-cache", "--configuration-cache-problems=fail", "--build-cache",
                        "--gradle-user-home", gradleUserHome))
                    .build()
                File(evidence, "${++buildNumber}-gradle.log").writeText(result.output)
                return result
            }
            val tasks = arrayOf(":before:assembleDebug", ":before:assembleRelease", ":after:assembleStaging", ":library:assembleDebug")
            val first = run(*tasks)
            assertTrue(first.task(":before:dokitTransformDebug")!!.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
            assertNull("release must never register instrumentation", first.task(":before:dokitTransformRelease"))
            assertTrue(first.task(":after:dokitTransformStaging")!!.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
            assertTrue(first.task(":library:dokitTransformDebug")!!.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
            val jar = File(host, "before/build/intermediates").walkTopDown().first {
                it.isFile && it.extension == "jar" && it.invariantSeparatorsPath.contains("dokitTransformDebug")
            }
            val report = File(host, "before/build/reports/dokit/debug/instrumentation.txt").readText()
            assertTrue(report, report.contains("android-classes-jar"))
            val sizes = report.lineSequence().filter { it.startsWith("dependency=") }.associate {
                val fields = it.removePrefix("dependency=").split('\t')
                fields[0] to fields[1].toLong()
            }
            val projectSizes = listOf("library", "otherLibrary").associateWith { name ->
                File(host, "$name/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar").length()
            }
            assertNotEquals("two same-named source dependency artifacts must have different sizes", projectSizes.getValue("library"), projectSizes.getValue("otherLibrary"))
            projectSizes.forEach { (name, bytes) ->
                assertTrue("resolved source dependency has a nonempty runtime JAR", bytes > 0)
                assertEquals("coordinate stays paired with its own classes.jar", bytes, sizes.getValue("project::$name"))
            }
            ZipFile(jar).use { zip ->
                for (name in listOf("LibrarySubject", "OtherLibrarySubject")) {
                    val entry = zip.getEntry("example/$name.class")
                    assertNotNull("Scoped ALL includes source project classes", entry)
                    val klass = ClassNode().also { ClassReader(zip.getInputStream(entry).readBytes()).accept(it, 0) }
                    assertTrue("source library method instrumented in application scope", klass.methods.any { method -> method.instructions.filterIsInstance<MethodInsnNode>().any {
                        it.owner == "com/didichuxing/doraemonkit/aop/MethodCostUtil" && it.name.endsWith("CostStart")
                    } })
                }
            }
            assertTrue("real external Java5 input is normalized before frame writing", report.contains("Inlined legacy JSR/RET example/LegacyFinally.compute"))
            ZipFile(jar).use { zip ->
                val legacy = ClassNode().also { ClassReader(zip.getInputStream(zip.getEntry("example/LegacyFinally.class")).readBytes()).accept(it, 0) }
                assertFalse("D8 receives inlined legacy bytecode", legacy.methods.any { method -> method.instructions.any { it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET } })
            }
            val original = jar.readBytes()
            jar.copyTo(File(evidence, "normal-classes.jar"), overwrite = true)
            File(host, "before/build/reports/dokit/debug/instrumentation.txt").copyTo(File(evidence, "normal-instrumentation.txt"), overwrite = true)
            File(host, "library/build/outputs/aar/library-debug.aar").copyTo(File(evidence, "library-debug.aar"), overwrite = true)
            ZipFile(jar).use { zip ->
                for (name in listOf("example/Subject.class", "example/KotlinSubject.class")) {
                    val data = zip.getInputStream(zip.getEntry(name)).readBytes()
                    val node = ClassNode().also { ClassReader(data).accept(it, 0) }
                    assertTrue("instrument Java and Kotlin: $name", node.methods.any { method -> method.instructions.filterIsInstance<MethodInsnNode>().any {
                        it.owner == "com/didichuxing/doraemonkit/aop/MethodCostUtil" && it.name.endsWith("CostStart")
                    } })
                }
            }
            val apk = File(host, "before/build/outputs/apk/debug/before-debug.apk")
            apk.copyTo(File(evidence, "before-debug.apk"), overwrite = true)
            ZipFile(apk).use { zip -> assertNotNull("AGP retains services outside the CLASSES transform", zip.getEntry("META-INF/services/example.Service")) }
            val cached = run(*tasks)
            assertTrue(cached.output, cached.output.contains("Reusing configuration cache"))
            assertEquals(TaskOutcome.UP_TO_DATE, cached.task(":before:dokitTransformDebug")!!.outcome)
            assertArrayEquals(original, jar.readBytes())
            val rebuild = run(":before:clean", ":before:assembleDebug")
            assertEquals("clean rebuild restores the complete transform output", TaskOutcome.FROM_CACHE, rebuild.task(":before:dokitTransformDebug")!!.outcome)
            assertArrayEquals("reproducible transform JAR", original, jar.readBytes())
            val added = File(host, "before/src/main/java/example/Added.java")
            added.writeText("package example; public class Added { public static int execute() { return Math.abs(-3); } }")
            val addition = run(":before:assembleDebug")
            assertTrue(addition.task(":before:dokitTransformDebug")!!.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
            ZipFile(jar).use { assertNotNull(it.getEntry("example/Added.class")) }
            added.writeText(added.readText().replace("-3", "-8"))
            val modification = run(":before:assembleDebug")
            assertTrue(modification.task(":before:dokitTransformDebug")!!.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
            val changed = jar.readBytes()
            added.delete()
            val deletion = run(":before:assembleDebug")
            assertTrue(deletion.task(":before:dokitTransformDebug")!!.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
            ZipFile(jar).use { assertNull("deleted classes cannot remain in scoped output", it.getEntry("example/Added.class")) }
            assertFalse(changed.contentEquals(jar.readBytes()))
            assertArrayEquals(original, jar.readBytes())
            val stack = run(":before:assembleDebug", "-PDOKIT_METHOD_STRATEGY=0", "-PDOKIT_METHOD_STACK_LEVEL=4")
            assertTrue(stack.task(":before:dokitTransformDebug")!!.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
            ZipFile(jar).use { zip ->
                val node = ClassNode().also { ClassReader(zip.getInputStream(zip.getEntry("example/Subject.class")).readBytes()).accept(it, 0) }
                assertTrue("stack strategy reaches entry hooks through AGP/D8", node.methods.any { method -> method.instructions.filterIsInstance<MethodInsnNode>().any {
                    it.owner == "com/didichuxing/doraemonkit/aop/method_stack/MethodStackUtil" && it.name.endsWith("CostStart")
                } })
            }
            jar.copyTo(File(evidence, "stack-classes.jar"), overwrite = true)
            File(host, "before/build/reports/dokit/debug/instrumentation.txt").copyTo(File(evidence, "stack-instrumentation.txt"), overwrite = true)
            val off = run(":before:assembleDebug", "-PDOKIT_PLUGIN_SWITCH=false")
            assertNull(off.task(":before:dokitTransformDebug"))

            // Publish complete marker + implementation metadata, then remove includeBuild.
            // This consumer can only load the plugin from its file Maven repository.
            val publication = GradleRunner.create().withProjectDir(plugin)
                .withArguments(listOf("publishAllPublicationsToMigrationRepository", "--gradle-user-home", gradleUserHome) + offlineArguments).build()
            assertEquals(TaskOutcome.SUCCESS, publication.task(":publishAllPublicationsToMigrationRepository")!!.outcome)
            val repository = File(plugin, "build/migration-repository")
            val publishingProperties = Properties().apply { File(plugin, "gradle.properties").inputStream().use { load(it) } }
            val publishedGroup = publishingProperties.getProperty("dokitPublishingGroup")
            val publishedVersion = publishingProperties.getProperty("dokitPublishingVersion")
            val pom = File(repository, "${publishedGroup.replace('.', '/')}/dokitx-plugin/$publishedVersion").listFiles()!!.single { it.extension == "pom" }.readText()
            File(evidence, "plugin-publication.log").writeText(publication.output)
            File(evidence, "plugin.pom").writeText(pom)
            assertFalse("compileOnly AGP must not leak into the published POM", pom.contains("com.android.tools.build"))
            assertTrue(pom.contains("kotlin-stdlib"))
            assertTrue(pom.contains("asm-analysis"))
            val settings = File(host, "settings.gradle")
            settings.writeText(settings.readText().replace("includeBuild('dokit-plugin');", "repositories { maven { url = uri('dokit-plugin/build/migration-repository') } };"))
            val build = File(host, "build.gradle")
            build.writeText(build.readText().replace("id 'com.didi.dokit' apply false", "id 'io.github.qqnp1100.dokit' version '$publishedVersion' apply false"))
            val consumed = run(":before:assembleDebug")
            assertTrue(consumed.task(":before:dokitTransformDebug")!!.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
            assertNull("published consumer cannot compile a substituted plugin project", consumed.task(":dokit-plugin:compileKotlin"))
        } finally { host.deleteRecursively() }
    }
}
