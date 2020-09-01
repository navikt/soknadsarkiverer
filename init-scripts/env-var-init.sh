#!/bin/bash

export CLIENTID="No authentication available"

PATH_CLIENTID=/var/run/secrets/nais.io/serviceuser/username
PATH_CLIENTSECRET=/var/run/secrets/nais.io/serviceuser/password

if test -f "PATH_CLIENTSECRET"; then
  CLIENTID=$(cat "$PATH_CLIENTID")
  CLIENTSECRET=$(cat "$PATH_CLIENTSECRET")
  echo Exporting authentication
else
  echo No authentication for serviceuser is exported...
fi
