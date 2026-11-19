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
