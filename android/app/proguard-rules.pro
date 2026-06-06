# Keep kotlinx.serialization generated serializers (we parse JSON dynamically,
# but keep this here in case typed serializers are added later).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
