#!/usr/bin/env bash
# Prints a JDK 21 home on stdout, or a message on stderr and exits 1.
#
# Gradle itself launches on any JDK, but `kotlin { jvmToolchain(21) }` needs a 21 and
# auto-download is off, so the build needs one it can find. Every candidate is verified by
# running its own `java`: `/usr/libexec/java_home -v 21` answers with a newer JDK when no 21
# is registered with it.
set -uo pipefail

is_jdk21() {
  local home="$1"
  [[ -n "$home" && -x "$home/bin/java" ]] || return 1
  "$home/bin/java" -version 2>&1 | head -1 | grep -q 'version "21'
}

# First hit wins. An explicit JAVA_HOME overrides everything; `mise which` answers from
# .mise.toml and resolves to the version symlink, so it survives a patch upgrade.
candidates() {
  echo "${JAVA_HOME:-}"
  local mise_java
  mise_java="$(mise which java 2>/dev/null)" && echo "${mise_java%/bin/java}"
  /usr/libexec/java_home -v 21 2>/dev/null
  for keg in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21; do
    echo "$keg/libexec/openjdk.jdk/Contents/Home"
    echo "$keg"
  done
  ls -d "$HOME"/.sdkman/candidates/java/21* 2>/dev/null
  ls -d /Library/Java/JavaVirtualMachines/*21*/Contents/Home 2>/dev/null
  ls -d "$HOME"/Library/Java/JavaVirtualMachines/*21*/Contents/Home 2>/dev/null
}

while IFS= read -r home; do
  if is_jdk21 "$home"; then echo "$home"; exit 0; fi
done < <(candidates)

echo "No JDK 21 found. Install one ('mise install', 'brew install openjdk@21' or" >&2
echo "'sdk install java 21'), or set JAVA_HOME to a JDK 21." >&2
exit 1
