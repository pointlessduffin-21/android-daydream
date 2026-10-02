# kotlinx.serialization ships its own consumer rules; keep our DTOs' generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.daydream.standby.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
