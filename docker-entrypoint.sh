#!/bin/sh
if [ ! -f /root/configs/config.yaml ]; then
    echo "No config.yaml found, copying default..."
    cp /root/configs/default_config.yaml /root/configs/config.yaml
fi
exec ./agent --web
