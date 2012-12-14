#!/bin/bash

JAVA_OPTS="-showversion -d32 -server -Xmx512m -XX:PermSize=256m -XX:MaxPermSize=512m" sbt -DPLRZ_HOSTNAME="http://localhost:9000" -DPLRZ_SECUREHOSTNAME="http://localhost:9000"
