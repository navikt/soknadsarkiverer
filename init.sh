#!/bin/bash

PATH_PROPERTIES=/var/run/secrets/nais.io/kv

if test -f "PATH_PROPERTIES"; then
  DISCOVERYURL=$(cat "PATH_PROPERTIES"/DISCOVERYURL)
  echo Exporting property DISCOVERYURL
else
  echo No external properties...
fi
