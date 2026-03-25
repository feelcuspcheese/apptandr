#!/bin/sh
set -e

# If config file does not exist in mounted volume, copy default
if [ ! -f /root/configs/config.yaml ]; then
    echo "No config.yaml found, copying default..."
    cp /root/default-config.yaml /root/configs/config.yaml
fi

# Run the agent
exec "$@"
