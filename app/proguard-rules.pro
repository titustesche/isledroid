# kotlin.text's multifile facade parts extend each other package-privately
# (StringsKt__StringBuilderKt extends StringsKt__IndentKt …). R8 moved one of them out of
# kotlin.text, which crashes at startup with IllegalAccessError. Keeping their names keeps them
# in their package; they can still be shrunk and optimized.
-keep,allowshrinking,allowoptimization class kotlin.text.StringsKt__* { *; }
