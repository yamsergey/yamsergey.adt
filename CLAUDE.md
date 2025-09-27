# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a multi-module Gradle project for Android Development Tools (ADT), providing functionality to analyze and resolve Android project structures, dependencies, and build variants. The project consists of four main modules:

- **tools-android**: Core library for Android project analysis using Gradle Tooling API
- **tools-android-cli**: Command-line interface for the tools-android library
- **workspace-kotlin**: Kotlin workspace library (placeholder for future development)
- **workspace-kotlin-cli**: Kotlin workspace CLI application

## Build Commands

### Basic Commands
```bash
# Build all modules
./gradlew build

# Run tests for all modules
./gradlew test

# Run tests for specific module
./gradlew :tools-android:test
./gradlew :tools-android-cli:test

# Clean build
./gradlew clean

# Build without tests
./gradlew assemble
```

### CLI Application Commands
```bash
# Build and run the Android tools CLI
./gradlew :tools-android-cli:run --args="resolve /path/to/android/project --workspace"

# Create distribution
./gradlew :tools-android-cli:installDist

# Run built distribution
./tools-android-cli/build/install/tools-android-cli/bin/tools-android-cli resolve --help
```

### Development Commands
```bash
# Run a single test class
./gradlew :tools-android:test --tests "io.yamsergey.adt.tools.android.resolver.AndroidProjectResolverTest"

# Run with debug output
./gradlew build --debug

# Show dependency tree
./gradlew :tools-android:dependencies
```

## Git Commit Guidelines

**IMPORTANT**: When creating commits, do NOT add the Claude co-author tag. Create standard commit messages without any AI attribution.

## Architecture

### Core Architecture
The project uses a layered architecture centered around Android project analysis:

1. **Model Layer** (`tools-android/src/main/java/io/yamsergey/adt/tools/android/model/`):
   - `Project`: Top-level project representation with modules
   - `ResolvedModule`: Resolved Android/generic modules with dependencies and source roots
   - `BuildVariant`: Android build variant information
   - `Dependency`: Various dependency types (JAR, AAR, classpath)

2. **Resolver Layer** (`tools-android/src/main/java/io/yamsergey/adt/tools/android/resolver/`):
   - `AndroidProjectResolver`: Main entry point for project resolution
   - `RawProjectResolver`: Raw project data resolution
   - `BuildVariantsResolver`: Build variant analysis
   - Module-specific resolvers for sources and dependencies

3. **Gradle Integration** (`tools-android/src/main/java/io/yamsergey/adt/tools/android/gradle/`):
   - Gradle Tooling API integration for accessing Android project models
   - Fetchers for different project aspects (dependencies, variants, basic info)

### CLI Layer
The CLI layer (`tools-android-cli`) provides:
- **ResolveCommand**: Main command for project analysis with options:
  - `--workspace`: Output project structure as JSON
  - `--variants`: Output build variants as JSON
  - `--raw`: Output raw project data
  - `--output`: Save results to file

### Key Dependencies
- **Gradle Tooling API**: Core integration with Gradle projects
- **Android Gradle Plugin**: Access to Android-specific project models
- **PicoCLI**: Command-line interface framework
- **Jackson**: JSON serialization for CLI output
- **Lombok**: Code generation for models

## Project Structure Patterns

### Module Organization
Each module follows standard Maven/Gradle structure:
```
module-name/
├── src/main/java/io/yamsergey/adt/module/name/
├── src/test/java/io/yamsergey/adt/module/name/
└── build.gradle
```

### Package Structure
- `io.yamsergey.adt.tools.android.model.*`: Data models and records
- `io.yamsergey.adt.tools.android.resolver.*`: Resolution logic
- `io.yamsergey.adt.tools.android.gradle.*`: Gradle API integration
- `io.yamsergey.adt.tools.sugar.*`: Utility classes (Result, Success, Failure)

### Code Patterns
- **Records with Lombok @Builder**: Used for immutable data models
- **Result Pattern**: Success/Failure types for error handling
- **Gradle Tooling API**: Consistent pattern for fetching project information
- **Builder Pattern**: Extensive use for complex object construction

## Development Notes

### Java Version
- Target: Java 21 (configured in all modules)
- Gradle toolchain ensures consistent Java version across environments

### Testing
- Uses JUnit 5 (Jupiter) for all test modules
- Test classes follow naming convention: `*Test.java`
- Integration tests may require actual Android projects for validation

### Dependency Resolution
The Android tools resolve dependencies by:
1. Using Gradle Tooling API to connect to target project
2. Fetching Android plugin models for build variants and dependencies
3. Resolving source roots and classpath information
4. Building a unified project model with all modules and dependencies

### Error Handling
- Uses custom Result types (Success/Failure) for operations that may fail
- CLI commands return appropriate exit codes (0 for success, 1 for failure)
- JSON output includes error information when resolution fails