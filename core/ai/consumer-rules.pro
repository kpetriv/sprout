# ML Kit GenAI structured output (core/ai, M6). The KSP-generated schema providers are found through
# META-INF/services (ServiceLoader), and the @Generable output classes are built from the model's JSON by property
# name, so neither may be removed, renamed or have members stripped.
-keep class * implements com.google.mlkit.genai.schema.guided.GenerableProvider { *; }
-keep @com.google.mlkit.genai.schema.annotations.Generable class * { *; }
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault, InnerClasses, EnclosingMethod
