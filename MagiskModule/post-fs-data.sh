#!/system/bin/sh
# MiMic Magisk Module post-fs-data script

# Grant auto-start permission
pm grant io.github.teamclouday.androidMic android.permission.RECEIVE_BOOT_COMPLETED

# Grant other permissions needed
pm grant io.github.teamclouday.androidMic android.permission.FOREGROUND_SERVICE
pm grant io.github.teamclouday.androidMic android.permission.FOREGROUND_SERVICE_MICROPHONE
pm grant io.github.teamclouday.androidMic android.permission.POST_NOTIFICATIONS

# Force enable boot receiver (in case system disabled it)
settings put global device_provisioned 1

# Optional: enable app ops for microphone
appops set io.github.teamclouday.androidMic android:record_audio allow

# Set app as persistent (optional, helps with system killing it)
pm setAppIdle io.github.teamclouday.androidMic false

# Enable auto-start in Miui settings (if MIUI)
if [ -f /system/bin/settings ]; then
    settings put secure enabled_accessibility_services ""
fi

echo "MiMic: Auto-start configured"