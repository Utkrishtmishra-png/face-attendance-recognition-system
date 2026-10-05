#!/usr/bin/env bash
set -e
mvn -q clean package
echo "Built: target/face-attendance.jar"
