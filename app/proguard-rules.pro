# kotlinx.serialization keeps generated serializers on the companion; the plugin's
# own rules cover most of it, but the dataset models are read reflectively by name.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.pankaj.mlbbdraft.engine.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.pankaj.mlbbdraft.engine.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
