#!/bin/bash
#
# Copyright The Dongting Project
#
# The Dongting Project licenses this file to you under the Apache License,
# version 2.0 (the "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at:
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.
#

# Resolve the script directory
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

LIB_DIR="$BASE_DIR/lib"
JRE_DIR="$BASE_DIR/jre"

# Check lib directory exists
if [ ! -d "$LIB_DIR" ]; then
    echo "Error: lib directory not found at $LIB_DIR" >&2
    exit 1
fi

# Check for JAR files
JAR_COUNT=$(ls -1 "$LIB_DIR"/*.jar 2>/dev/null | wc -l)
if [ "$JAR_COUNT" -eq 0 ]; then
    echo "Error: No JAR files found in $LIB_DIR" >&2
    exit 1
fi

echo "Found $JAR_COUNT JAR files in $LIB_DIR"

# Check disk space (require at least 100MB free)
check_disk_space() {
    local available
    available=$(df -k "$BASE_DIR" | tail -1 | awk '{print $4}')
    
    if [ "$available" -lt 102400 ]; then
        echo "Warning: Less than 100MB disk space available on $BASE_DIR" >&2
        echo "This may cause jlink to fail. Continue anyway? (y/n)" >&2
        read -r response
        if [ "$response" != "y" ] && [ "$response" != "Y" ]; then
            echo "Aborted."
            exit 1
        fi
    else
        echo "Available disk space: $((available / 1024))MB"
    fi
}

# Check if JRE directory already exists
if [ -d "$JRE_DIR" ]; then
    echo "Warning: JRE directory already exists at $JRE_DIR" >&2
    echo "This will overwrite the existing JRE. Continue? (y/n)" >&2
    read -r response
    if [ "$response" != "y" ] && [ "$response" != "Y" ]; then
        echo "Aborted."
        exit 1
    fi
    echo "Removing existing JRE directory..."
    rm -rf "$JRE_DIR"
fi

# Find jdeps and jlink
find_java_tools() {
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/jdeps" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then
        JDEPS="$JAVA_HOME/bin/jdeps"
        JLINK="$JAVA_HOME/bin/jlink"
        echo "Using Java tools from JAVA_HOME: $JAVA_HOME"
    else
        JDEPS="jdeps"
        JLINK="jlink"
        echo "Using Java tools from PATH"
    fi
}

find_java_tools

# Check if jdeps and jlink are available
if ! command -v "$JDEPS" &> /dev/null; then
    echo "Error: jdeps not found. Please set JAVA_HOME or ensure jdeps is in PATH" >&2
    exit 1
fi

if ! command -v "$JLINK" &> /dev/null; then
    echo "Error: jlink not found. Please set JAVA_HOME or ensure jlink is in PATH" >&2
    exit 1
fi

# Detect jlink version for compress option compatibility
JLINK_VERSION=$($JLINK --version 2>&1 | head -1 | sed -E 's/^([0-9]+).*/\1/')
if [ -z "$JLINK_VERSION" ] || ! [ "$JLINK_VERSION" -gt 0 ] 2>/dev/null; then
    echo "Failed to detect jlink version" >&2
    exit 1
fi

# Java 21+ uses --compress=zip-N, Java 11-20 uses --compress=N
if [ "$JLINK_VERSION" -ge 21 ] 2>/dev/null; then
    COMPRESS_OPT="--compress=zip-6"
else
    COMPRESS_OPT="--compress=2"
fi
echo "Detected jlink version: $JLINK_VERSION, using $COMPRESS_OPT"

# Check disk space before proceeding
check_disk_space

# Analyze module dependencies with jdeps
echo "Analyzing JDK module dependencies..."
echo "This may take a while..."

# Use --module-path for modular JARs, --ignore-missing-deps for optional dependencies
# Analyze module dependencies with jdeps
# --print-module-deps outputs only the JDK modules required by the application
MODULES=$($JDEPS --multi-release 11 --module-path "$LIB_DIR" --ignore-missing-deps --print-module-deps "$LIB_DIR"/*.jar 2>/dev/null)

if [ -z "$MODULES" ]; then
    echo "Error: Failed to determine module dependencies" >&2
    exit 1
fi

echo "Required modules: $MODULES"

# Create jlink image
echo "Creating trimmed JRE at $JRE_DIR..."
$JLINK $COMPRESS_OPT --strip-debug --no-header-files --no-man-pages --add-modules "$MODULES" --output "$JRE_DIR"

if [ $? -eq 0 ]; then
    echo "Successfully created trimmed JRE at $JRE_DIR"
    echo "JRE size: $(du -sh "$JRE_DIR" | cut -f1)"
else
    echo "Error: Failed to create trimmed JRE" >&2
    exit 1
fi
