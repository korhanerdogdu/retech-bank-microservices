#!/bin/sh
# Creates each service's database if it does not exist yet. Safe to run on every start.
set -e

for db in user_db account_db transaction_db; do
  if psql -h postgres -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$db'" | grep -q 1; then
    echo "database $db exists"
  else
    psql -h postgres -U postgres -c "CREATE DATABASE $db"
    echo "database $db created"
  fi
done
