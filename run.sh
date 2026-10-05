#!/usr/bin/env bash
cd "$(dirname "$0")"
exec java -Xmx1g -Dattendance.data=./data -Dattendance.camera=0 -Dattendance.threshold=70 -jar target/face-attendance.jar
