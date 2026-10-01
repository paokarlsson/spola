#!/bin/sh
# Fixture: a shell script is not Java source (javac cannot parse it): skipped.
exec java --source 25 "$(dirname "$0")/wire" "$@"
