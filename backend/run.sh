#!/usr/bin/env bash
# Local dev server: load .env, start Jetty on :8082 serving target/classes (no WAR).
# The plugin prefix `jetty-ee10:run` only resolves with a pluginGroup in
# ~/.m2/settings.xml, so the goal is spelled out in full here.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
# `clean` first: the VS Code Java extension writes its own .class files into
# target/classes, and if its classpath can't resolve a dep it emits stub classes
# that throw `java.lang.Error: Unresolved compilation problems` at runtime. clean
# forces a fresh javac build so Jetty never serves a poisoned class.
exec mvn clean org.eclipse.jetty.ee10:jetty-ee10-maven-plugin:12.0.19:run -Dmaven.test.skip=true "$@"
