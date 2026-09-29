#!/usr/bin/env bash
cd "$(dirname "$0")"
./run.sh 8080 &
SERVER_PID=$!
sleep 2
open "http://localhost:8080"
wait "$SERVER_PID"
