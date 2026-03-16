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
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BASE_DIR = Split-Path -Parent $ScriptDir

$LIB_DIR = Join-Path $BASE_DIR "lib"
$JRE_DIR = Join-Path $BASE_DIR "jre"

# Check lib directory exists
if (-not (Test-Path $LIB_DIR)) {
    Write-Error "Error: lib directory not found at $LIB_DIR"
    exit 1
}

# Check for JAR files
$JarFiles = Get-ChildItem -Path $LIB_DIR -Filter "*.jar" -ErrorAction SilentlyContinue
if ($null -eq $JarFiles -or $JarFiles.Count -eq 0) {
    Write-Error "Error: No JAR files found in $LIB_DIR"
    exit 1
}

$JarCount = $JarFiles.Count
Write-Host "Found $JarCount JAR files in $LIB_DIR"

# Find jdeps and jlink
function Find-JavaTool {
    param([string]$ToolName)
    if ($env:JAVA_HOME) {
        # Try .exe first (Windows), then without extension (cross-platform)
        $toolWithExe = Join-Path $env:JAVA_HOME "bin\$ToolName.exe"
        $toolNoExt = Join-Path $env:JAVA_HOME "bin\$ToolName"
        if (Test-Path $toolWithExe) {
            return $toolWithExe
        } elseif (Test-Path $toolNoExt) {
            return $toolNoExt
        }
    }
    return $ToolName
}

$JDEPS = Find-JavaTool "jdeps"
$JLINK = Find-JavaTool "jlink"

if ($env:JAVA_HOME) {
    if ((Test-Path $JDEPS) -and (Test-Path $JLINK)) {
        Write-Host "Using Java tools from JAVA_HOME: $env:JAVA_HOME"
    } else {
        Write-Host "Warning: JAVA_HOME is set but jdeps/jlink not found in JAVA_HOME, falling back to PATH"
        $JDEPS = "jdeps"
        $JLINK = "jlink"
    }
} else {
    Write-Host "Using Java tools from PATH"
}

# Check if jdeps and jlink are available
try {
    & $JDEPS --version 2>&1 | Out-Null
} catch {
    Write-Error "Error: jdeps not found. Please set JAVA_HOME or ensure jdeps is in PATH"
    exit 1
}

try {
    & $JLINK --version 2>&1 | Out-Null
} catch {
    Write-Error "Error: jlink not found. Please set JAVA_HOME or ensure jlink is in PATH"
    exit 1
}

# Check available disk space (require at least 100MB free)
$drive = Split-Path -Qualifier $BASE_DIR
if ($drive) {
    $diskInfo = Get-CimInstance -ClassName Win32_LogicalDisk -Filter "DeviceID='$drive'" -ErrorAction SilentlyContinue
    if ($diskInfo) {
        $freeSpaceMB = [math]::Floor($diskInfo.FreeSpace / 1MB)
        Write-Host "Available disk space: $freeSpaceMB MB"
        if ($freeSpaceMB -lt 100) {
            Write-Warning "Less than 100MB disk space available on $drive"
            $response = Read-Host "This may cause jlink to fail. Continue anyway? (y/n)"
            if ($response -ne "y" -and $response -ne "Y") {
                Write-Host "Aborted."
                exit 1
            }
        }
    }
}

# Check if JRE directory already exists
if (Test-Path $JRE_DIR) {
    Write-Host "Warning: JRE directory already exists at $JRE_DIR" -ForegroundColor Yellow
    $response = Read-Host "This will overwrite the existing JRE. Continue? (y/n)"
    if ($response -ne "y" -and $response -ne "Y") {
        Write-Host "Aborted."
        exit 0
    }
    Write-Host "Removing existing JRE directory..."
    Remove-Item -Path $JRE_DIR -Recurse -Force
}

# Analyze module dependencies with jdeps
Write-Host "Analyzing JDK module dependencies..."
Write-Host "This may take a while..."

# Build the JAR file list
$JarList = ($JarFiles | ForEach-Object { $_.FullName }) -join " "

# Run jdeps and capture output
$jdepsOutput = & $JDEPS --multi-release 11 --module-path $LIB_DIR --ignore-missing-deps --print-module-deps $JarList 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Error "Error: Failed to analyze module dependencies"
    Write-Error $jdepsOutput
    exit 1
}

# Parse the modules from jdeps output
# --print-module-deps outputs only the JDK modules required by the application
$Modules = ($jdepsOutput -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }) -join ","

if ([string]::IsNullOrEmpty($Modules)) {
    Write-Error "Error: Failed to determine module dependencies"
    exit 1
}

Write-Host "Required modules: $Modules"

# Create jlink image
Write-Host "Creating trimmed JRE at $JRE_DIR..."
& $JLINK --compress=2 --strip-debug --no-header-files --no-man-pages --add-modules $Modules --output $JRE_DIR

if ($LASTEXITCODE -eq 0) {
    $jreSize = (Get-ChildItem -Path $JRE_DIR -Recurse | Measure-Object -Property Length -Sum).Sum
    $jreSizeMB = [math]::Round($jreSize / 1MB, 2)
    Write-Host "Successfully created trimmed JRE at $JRE_DIR" -ForegroundColor Green
    Write-Host "JRE size: $jreSizeMB MB"
} else {
    Write-Error "Error: Failed to create trimmed JRE"
    exit 1
}
