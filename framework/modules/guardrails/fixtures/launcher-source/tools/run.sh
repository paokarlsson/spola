#!/bin/sh
# Fixture: a shell script is not a document either: reported. The check does not guess
# from the content which files the launcher or jshell could run.
exec java --source 25 "$(dirname "$0")/wire" "$@"
