#!/system/bin/sh
# MiMic Magisk Module service script
# Keep service running

while true; do
    # Check if service is running
    if ! pgrep -f "io.github.teamclouday.androidMic" > /dev/null 2>&1; then
        # Try to start the service
        am start-foreground-service -n io.github.teamclouday.androidMic/.domain.service.ForegroundService -a BIND_SERVICE_ACTION 2>/dev/null || true
    fi
    sleep 30
done &

echo "MiMic service watchdog started"