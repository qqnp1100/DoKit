package com.didichuxing.doraemonkit.plugin.extension

import groovy.lang.Closure

/** Public Groovy closure delegation, replacing Gradle's removed ConfigureUtil. */
internal fun configureDsl(closure: Closure<*>, target: Any) {
    val configured = closure.rehydrate(target, closure.owner, closure.thisObject)
    configured.resolveStrategy = Closure.DELEGATE_FIRST
    if (configured.maximumNumberOfParameters == 0) configured.call() else configured.call(target)
}
