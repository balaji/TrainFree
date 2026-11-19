# Keep source file names and line numbers so Play Console de-obfuscates stack
# traces accurately when mapping.txt is uploaded.
-keepattributes SourceFile,LineNumberTable

# Preserve Kotlin/serialization metadata used by kotlinx.serialization and Room.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Room KMP generated database implementation and expect/actual constructor.
-keep class dev.balaji.moonrose.trainfree.db.AppDatabase_Impl { *; }
-keep class dev.balaji.moonrose.trainfree.db.AppDatabaseConstructor { *; }

# Keep @Serializable DTOs and their companion serializers for JSON backup/restore
# and local AI content reports.
-keep,includedescriptorclasses class dev.balaji.moonrose.trainfree.db.TrainFreeBackup { *; }
-keep,includedescriptorclasses class dev.balaji.moonrose.trainfree.db.Backup* { *; }
-keep,includedescriptorclasses class dev.balaji.moonrose.trainfree.chat.AiContentReport { *; }

# firebase-components:16.1.0 (pulled in by mlkit:common) only ships a bodyless
# `-keep class * implements ComponentRegistrar`, which causes R8 full mode to
# strip CommonComponentRegistrar.<init>() and break MlKitContext setup.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
    java.util.List getComponents();
}
