#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$SCRIPT_DIR/../../src/android" && pwd)"

grep -Fq 'include(":novex-core")' "$ANDROID_ROOT/settings.gradle.kts"
grep -Fq 'id("org.jetbrains.kotlin.jvm")' "$ANDROID_ROOT/build.gradle.kts"
grep -Fq 'implementation(project(":novex-core"))' "$ANDROID_ROOT/app/build.gradle.kts"
grep -Fq 'id("org.jetbrains.kotlin.jvm")' "$ANDROID_ROOT/novex-core/build.gradle.kts"
grep -Fq 'testImplementation("junit:junit:4.13.2")' "$ANDROID_ROOT/novex-core/build.gradle.kts"
grep -Fq 'rootProject.name = "NovexCore"' "$ANDROID_ROOT/novex-core/settings.gradle.kts"

echo "Novex core module wiring tests passed"
