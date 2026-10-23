package com.didichuxing.doraemonkit.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.*
import com.didichuxing.doraemonkit.plugin.extension.DoKitExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

/** AGP 9 public API entry point. Android can be applied before or after DoKit. */
class DoKitPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.extensions.findByType(DoKitExtension::class.java) != null) return
        val extension = project.extensions.create("dokit", DoKitExtension::class.java)
        fun properties(): Map<String, String> = listOf("DOKIT_PLUGIN_SWITCH", "DOKIT_LOG_SWITCH", "DOKIT_METHOD_SWITCH",
            "DOKIT_METHOD_STRATEGY", "DOKIT_METHOD_STACK_LEVEL", "DOKIT_WEBVIEW_CLASS_NAME", "DOKIT_THIRD_LIB_SWITCH")
            .mapNotNull { name -> project.providers.gradleProperty(name).orNull?.let { name to it } }.toMap()
        fun register(variant: Variant, scope: ScopedArtifacts.Scope, bootClasspath: org.gradle.api.provider.Provider<List<org.gradle.api.file.RegularFile>>) {
            val options = snapshot(extension, properties())
            if (!extension.pluginEnable || options["DOKIT_PLUGIN_SWITCH"] == "false" || !variant.debuggable) return
            // AGP projects expose many secondary variants. Request the bytecode artifact
            // explicitly through Gradle's public ArtifactView API, never raw incoming.artifacts.
            val artifacts = variant.runtimeConfiguration.incoming.artifactView { view ->
                view.attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
            }.artifacts
            val compileArtifacts = variant.compileConfiguration.incoming.artifactView { view ->
                view.attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
            }.artifacts
            val dependencies = artifacts.resolvedArtifacts.map { resolved -> resolved.map { artifact ->
                val coordinate = when (val id = artifact.id.componentIdentifier) {
                    is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                    is ProjectComponentIdentifier -> "project:${id.projectPath}"
                    else -> id.displayName
                }
                DoKitDependencyArtifact(coordinate, artifact.file)
            }.sortedWith(compareBy({ it.coordinate }, { it.file.name })) }
            val task = project.tasks.register("dokitTransform${variant.name.replaceFirstChar(Char::uppercaseChar)}", DoKitTransformTask::class.java) { task ->
                task.group = "dokit"
                task.description = "Instrument ${variant.name} classes with DoKit using AGP scoped artifacts"
                task.variantName.set(variant.name)
                task.namespace.set(variant.namespace)
                task.options.set(options)
                task.mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                task.hierarchyClasspath.from(compileArtifacts.artifactFiles, bootClasspath)
                task.runtimeArtifacts.from(artifacts.artifactFiles)
                task.dependencyArtifacts.set(dependencies)
                task.report.set(project.layout.buildDirectory.file("reports/dokit/${variant.name}/instrumentation.txt"))
            }
            variant.artifacts.forScope(scope).use(task).toTransform(ScopedArtifact.CLASSES,
                DoKitTransformTask::allJars, DoKitTransformTask::allDirectories, DoKitTransformTask::outputJar)
        }
        project.pluginManager.withPlugin("com.android.application") {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { register(it, ScopedArtifacts.Scope.ALL, components.sdkComponents.bootClasspath) }
        }
        project.pluginManager.withPlugin("com.android.library") {
            val components = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { register(it, ScopedArtifacts.Scope.PROJECT, components.sdkComponents.bootClasspath) }
        }
        project.pluginManager.withPlugin("com.android.dynamic-feature") {
            val components = project.extensions.getByType(DynamicFeatureAndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { register(it, ScopedArtifacts.Scope.ALL, components.sdkComponents.bootClasspath) }
        }
    }
}
