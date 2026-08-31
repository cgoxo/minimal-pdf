# ---------------------------------------------------------------------------------------
# kotlinx.serialization
#
# This app serialises through explicit serializers (ScanDocument.serializer()), so R8 can
# see the generated classes are used. These rules are the belt to that braces: the data
# package is the app's entire persistence layer, and a stripped serializer would only fail
# at runtime, on someone else's phone, with a document that no longer opens.
# ---------------------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

-keep class com.minimal.pdfcreate.data.** { *; }
-keepclassmembers class com.minimal.pdfcreate.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.minimal.pdfcreate.data.**$$serializer { *; }

-dontwarn kotlinx.serialization.**
