# tools-android-cli

Command-line interface for Android project analysis using the tools-android library.

## Overview

This CLI tool provides easy access to Android project analysis features, offering JSON output for project structure, build variants, and raw project data. Perfect for integration with other tools or automated workflows.

## Installation

### Build from Source

```bash
# Build the distribution
./gradlew :tools-android-cli:installDist

# The executable will be created at:
# tools-android-cli/build/install/tools-android-cli/bin/tools-android-cli
```

### Run with Gradle

```bash
# Run directly with Gradle
./gradlew :tools-android-cli:run --args="resolve /path/to/android/project --workspace"
```

## Usage

### Basic Command Structure

```bash
tools-android-cli resolve [PROJECT_PATH] [OPTIONS]
```

**Arguments:**
- `PROJECT_PATH`: Path to Android project directory (default: current directory)

**Global Options:**
- `-h, --help`: Show help message
- `-V, --version`: Show version information

### Analysis Options

#### Project Structure Analysis (`--workspace`)

Analyzes and outputs the complete project structure including modules, dependencies, and source roots.

```bash
tools-android-cli resolve /path/to/android/project --workspace
```

**Example Output:**
```json
{
  "path": "/path/to/android/project",
  "name": "MyAndroidApp",
  "modules": [
    {
      "name": "app",
      "type": "android",
      "dependencies": [...],
      "sourceRoots": [...]
    }
  ]
}
```

#### Build Variants Analysis (`--variants`)

Lists all available build variants for the Android project.

```bash
tools-android-cli resolve /path/to/android/project --variants
```

**Example Output:**
```json
[
  {
    "name": "debug",
    "displayName": "debug",
    "isDefault": true
  },
  {
    "name": "release",
    "displayName": "release",
    "isDefault": false
  }
]
```

#### Raw Project Data (`--raw`)

Outputs raw Gradle project information for detailed analysis.

> WARN: result is huge, it's better to always use --output option to store result in a JSON file.

```bash
tools-android-cli resolve /path/to/android/project --raw
```

### Output Options

#### Save to File (`--output`)

Save analysis results to a file instead of stdout.

```bash
tools-android-cli resolve /path/to/android/project --workspace --output project-analysis.json
```

## Examples

### Analyze Project Structure

```bash
# Analyze current directory
tools-android-cli resolve . --workspace

# Analyze specific project
tools-android-cli resolve /home/user/MyApp --workspace

# Save to file
tools-android-cli resolve /home/user/MyApp --workspace --output analysis.json
```

### List Build Variants

```bash
# List all build variants
tools-android-cli resolve /path/to/project --variants

# Save variants to file
tools-android-cli resolve /path/to/project --variants --output variants.json
```

### Export Raw Data

```bash
# Get raw project data
tools-android-cli resolve /path/to/project --raw

# Save raw data for debugging
tools-android-cli resolve /path/to/project --raw --output debug-data.json
```
