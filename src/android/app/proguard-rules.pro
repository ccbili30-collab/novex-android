# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# PDFBox can optionally decode JPEG 2000 images through Gemalto's JP2 codec.
# Novex only extracts PDF text and does not bundle that optional decoder.
-dontwarn com.gemalto.jp2.**

# poi-on-android shades Apache POI plus optional integrations for desktop-only
# rendering, XPath engines, OSGi and logging annotations. Novex only opens
# XWPF documents and extracts text, so those optional surfaces are never used.
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.awt.**
-dontwarn java.beans.ConstructorProperties
-dontwarn net.sf.saxon.**
-dontwarn org.apache.batik.**
-dontwarn com.github.javaparser.**
-dontwarn com.microsoft.schemas.**
-dontwarn com.sun.org.apache.xml.internal.resolver.**
-dontwarn org.apache.maven.**
-dontwarn org.apache.tools.ant.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn org.osgi.annotation.**
-dontwarn org.osgi.framework.**

# XMLBeans resolves generated OOXML type systems and Aalto StAX factories by
# class name. Keep them stable under R8; these are required by the code path
# exercised by the on-device DOCX regression test.
-keep,allowoptimization class org.apache.poi.schemas.** { *; }
-keep,allowoptimization class org.apache.xmlbeans.** { *; }
-keep,allowoptimization class org.openxmlformats.schemas.** { *; }
-keep,allowoptimization class com.microsoft.schemas.** { *; }
-keep class com.fasterxml.aalto.stax.** { *; }

# Matches the upstream poi-on-android sample's safe logging rules.
-keep,allowoptimization,allowobfuscation class org.apache.logging.log4j.** { *; }
-keep,allowoptimization class org.apache.commons.compress.archivers.zip.** { *; }
