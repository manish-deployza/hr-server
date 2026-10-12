# Local dev server: load .env, start Jetty on :8082 serving target\classes (no WAR).
# The plugin prefix `jetty-ee10:run` only resolves with a pluginGroup in the Maven
# settings.xml, so the goal is spelled out in full here.
Get-Content $PSScriptRoot\.env |
  Where-Object { $_ -match '^\s*[^#].*=' } |
  ForEach-Object { $k, $v = $_ -split '=', 2; Set-Item "env:$($k.Trim())" $v.Trim() }
# `clean` first: the VS Code Java extension writes its own .class files into
# target\classes, and if its classpath can't resolve a dep it emits stub classes
# that throw `java.lang.Error: Unresolved compilation problems` at runtime. clean
# forces a fresh javac build so Jetty never serves a poisoned class.
mvn clean org.eclipse.jetty.ee10:jetty-ee10-maven-plugin:12.0.19:run "-Dmaven.test.skip=true" $args
