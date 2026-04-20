# Don't delete this
ui_print "MiMic Magisk Module v1.0"
ui_print "Installing MiMic to priv-app..."

# Copy APK
mkdir -p $MODPATH/system/priv-app/MiMic
cp -f $MODPATH/MiMic.apk $MODPATH/system/priv-app/MiMic/MiMic.apk
set_perm $MODPATH/system/priv-app/MiMic/MiMic.apk 0 0 644

# Set permissions for scripts
set_perm $MODPATH/post-fs-data.sh 0 0 755
set_perm $MODPATH/service.sh 0 0 755

# Trigger broadcast to start service (helps if receiver is disabled)
sleep 5
am broadcast -a android.intent.action.BOOT_COMPLETED -n io.github.teamclouday.androidMic/.BootReceiver 2>/dev/null || true

ui_print "Done!"