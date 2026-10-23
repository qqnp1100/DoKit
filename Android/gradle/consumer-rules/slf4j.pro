# SLF4J 1.x deliberately checks these optional bindings and uses NOP fallbacks.
# Ktor's declared logging dependency is usable without installing a binding.
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
