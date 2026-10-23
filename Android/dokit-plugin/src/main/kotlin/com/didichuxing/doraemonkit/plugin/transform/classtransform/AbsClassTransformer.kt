package com.didichuxing.doraemonkit.plugin.transform.classtransform

import com.didichuxing.doraemonkit.plugin.*
import com.didichuxing.doraemonkit.plugin.extension.DoKitExtension
import org.objectweb.asm.tree.ClassNode

/** One instance per transform task; no Project or daemon-wide mutable state. */
open class AbsClassTransformer(protected val state: DoKitState) {
    open fun transform(context: TransformContext, klass: ClassNode): ClassNode {
        if (klass.name == "module-info" || state.ignorePackageNames(klass.className)) return klass
        return transform(context.dokit, context, klass)
    }
    open fun transform(dokit: DoKitExtension, context: TransformContext, klass: ClassNode): ClassNode = klass
}
