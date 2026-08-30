# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# PDFBox can optionally decode JPEG 2000 images through Gemalto's JP2 codec.
# Novex only extracts PDF text and does not bundle that optional decoder.
-dontwarn com.gemalto.jp2.**
