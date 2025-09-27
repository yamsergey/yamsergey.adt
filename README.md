# Android Development Tools (ADT)

A suite of tools for analyzing Android project structures and dependencies using the Gradle Tooling API.

## Overview

This project provides programmatic access to Android project information, enabling analysis of project structure, build variants, dependencies, and source roots with JSON export capabilities.

## Modules

- **[tools-android](tools-android/)** - Core library for Android project analysis
- **[tools-android-cli](tools-android-cli/)** - Command-line interface for project analysis
- **workspace-kotlin** - Kotlin workspace library (future development)
- **workspace-kotlin-cli** - Kotlin workspace CLI (future development)

## Quick Start

```bash
# Build the project
./gradlew build

# Run the CLI tool
./gradlew :tools-android-cli:run --args="resolve /path/to/android/project --workspace"
```

## Requirements

- Java 21+
- Gradle 8.0+

## Use Cases

- IDE integration for enhanced project understanding
- Build tool enhancement with comprehensive project information
- Large codebase analysis and migration assistance
- Automated project structure documentation

See individual module READMEs for detailed documentation.