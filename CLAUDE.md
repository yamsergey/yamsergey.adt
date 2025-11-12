# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android Development Tools (ADT) - A multi-module Gradle project providing programmatic access to Android project structure and dependencies using the Gradle Tooling API. Targets Java 21 and uses Gradle 9.0.

## Build Commands

```bash
# Build entire project
./gradlew build

# Build specific module
./gradlew :tools-android:build
./gradlew :tools-android-cli:build

# Run tests
./gradlew test

# Run tests for specific module
./gradlew :tools-android:test

# Clean build artifacts
./gradlew clean

# Clean LSP artifacts (.settings, .project files from jdtls)
./gradlew cleanLsp

# Install CLI distribution
./gradlew :tools-android-cli:installDist
# Executable: tools-android-cli/build/install/tools-android-cli/bin/tools-android-cli

# Run CLI directly
./gradlew :tools-android-cli:run --args="resolve /path/to/android/project --workspace"
```

## Module Architecture

### tools-android (Core Library)
Core library providing Android project analysis via Gradle Tooling API.

**Package Structure:**
- `model/` - Data models for projects, modules, dependencies, variants, and source roots
  - `project/` - `Project`, `RawProject`
  - `module/` - `ResolvedModule` (sealed interface), `ResolvedAndroidModule`, `ResolvedGenericModule`, `FailedModule`, `UnknownModule`, `RawModule`, `RawAndroidModule`, `RawGenericModule`
  - `dependency/` - `Dependency` hierarchy: `ExternalDependency`, `LocalDependency`, `GradleJarDependency`, `GradleAarDependency`, `LocalJarDependency`, `ClassFolderDependency`
  - `variant/` - `BuildVariant`, `ResolvedVariant`
- `resolver/` - Resolution strategies and main entry points
  - `AndroidProjectResolver` - Primary API for project analysis
  - `RawProjectResolver` - Extract raw Gradle project data
  - `BuildVariantsResolver` - Build variant analysis
  - Module resolvers: `AndroidModuleResolver`, `GenericModuleResolver`, `DefaultModuleResolver`
  - Dependency/source resolvers: `AndroidModuleDependencyResolver`, `AndroidModuleSourcesResolver`
  - `ModuleResolutionStrategy` - Strategy pattern for module resolution
- `gradle/` - Gradle Tooling API integration
  - Fetchers: `FetchAndroidProject`, `FetchBasicAndroidProject`, `FetchGradleProject`, `FetchIdeaProject`, `FetchAndroidDependencies`, `FetchAndroidDsl`
  - Utils: `GradleProjectUtils`, `VariantUtils`, `GraphItemUtils`
- `tools.sugar/` - `Result<T>` type (sealed interface: `Success<T>`, `Failure<T>`)

**Key Design Patterns:**
- **Result Pattern**: All operations return serializable `Result<T>` (Success/Failure) for cross-process communication with Gradle daemon. Both Success and Failure carry optional descriptions for debugging.
- **Sealed Interfaces**: `ResolvedModule` and `Result` use sealed interfaces for compile-time exhaustive pattern matching.
- **Strategy Pattern**: `ModuleResolutionStrategy` allows customizable module resolution logic.

### tools-android-cli (CLI Interface)
Command-line interface wrapping tools-android library.

**Key Files:**
- `App.java` - Main entry point using picocli
- `ResolveCommand.java` - Primary command implementation
- `serialization/jackson/` - Jackson customizations for safe Gradle object serialization (`SafeSerializerModifier`, `ParentIgnoreMixIn`, `ProjectMixIn`, `TaskMixIn`)

**CLI Usage:**
```bash
# Analyze project structure
tools-android-cli resolve /path/to/project --workspace

# List build variants
tools-android-cli resolve /path/to/project --variants

# Extract raw Gradle data (use --output, result is large)
tools-android-cli resolve /path/to/project --raw --output data.json

# Save output to file
tools-android-cli resolve /path/to/project --workspace --output analysis.json
```

### tools-android-drawables (Drawable Resources Library)
Library for discovering and rendering Android drawable resources to images with markdown reports.

**Package Structure:**
- `model/` - `DrawableResource` with types (VECTOR_XML, BITMAP_PNG, etc.), tracks source set and qualifier
- `resolver/` - `DrawableResourceResolver` - Discovers drawable resources from Android modules
- `renderer/` - `VectorDrawableRenderer` - Converts Android vector drawable XML to PNG using Apache Batik
- `report/` - `MarkdownReportGenerator` - Generates markdown documentation with drawable table

**Key Features:**
- Discovers drawable resources from `res/drawable*` folders using Gradle Tooling API
- Converts Android vector drawable XML format to SVG, then renders to PNG
- Supports qualifier-based drawables (hdpi, xxhdpi, night, etc.)
- Tracks source sets (main, debug, release, flavors) for variant-specific drawables
- Handles bitmap drawables (PNG, JPG, WEBP)
- Generates comprehensive markdown reports with image previews
- Resolves Android color references (@android:color/white, etc.)

**How It Works:**
1. Uses `SourceProvider.getResDirectories()` to find resource folders
2. Scans all `drawable` and `drawable-*` folders across all source sets
3. Identifies drawable types by file extension and extracts metadata (name, qualifier, source set)
4. For vector drawables: converts Android XML → SVG → PNG via Apache Batik
5. For bitmap drawables: copies files as-is
6. Generates markdown table with image previews, grouping variants by resource name

### workspace-kotlin (Workspace Library)
Converts tools-android `Project` model to workspace format for IDE integration.

**Key Components:**
- `model/` - `Workspace`, `Module`, `Dependency`, `Sdk`, `ContentRoot`, `SourceRoot`, `Library`, `KotlinSettings`
- `converter/ProjectToWorkspaceConverter` - Converts `Project` to `Workspace`
- `serializer/WorkspaceJsonSerializer` - JSON serialization

### workspace-kotlin-cli (Workspace CLI)
CLI for generating workspace files from Android projects.

**Key Files:**
- `App.java` - Main entry point
- `GenerateCommand.java` - Workspace generation command

### adt-cli (Unified CLI)
Unified command-line interface combining all ADT functionality.

**Commands:**
- `resolve` - Analyze Android project structure and dependencies
- `workspace` - Generate workspace.json for IDE integration
- `drawables` - Generate images from Android drawable resources

**Drawables Command Usage:**
```bash
# Generate images and markdown report from all drawable resources
adt-cli drawables /path/to/android/project --output ./images

# Only process vector drawables with report
adt-cli drawables /path/to/android/project --vectors-only --output ./vectors

# Specify module and custom dimensions
adt-cli drawables /path/to/android/project --module app --width 512 --height 512

# Custom report filename
adt-cli drawables /path/to/android/project --output ./images --report DRAWABLES.md

# Install and run
./gradlew :adt-cli:installDist
./adt-cli/build/install/adt-cli/bin/adt-cli drawables --help
```

**Markdown Report Features:**
- Automatically generates `README.md` (or custom filename) in output directory
- Table with drawable name, image preview, type, qualifiers, and source sets
- Summary statistics by drawable type
- Lists all source sets and qualifiers found
- Relative image paths work when viewing in GitHub or any markdown viewer

## Dependencies and Technologies

- **Gradle Tooling API 8.10.2** - Core Gradle project access
- **Android Gradle Plugin 8.11.1** - Android-specific models
- **Google Guava** - Utility collections
- **Lombok** - Code generation for models (builders, getters)
- **Jackson** - JSON serialization (CLI modules)
- **Picocli** - CLI framework
- **JUnit Jupiter** - Testing framework
- **Apache Batik 1.17** - SVG rendering for vector drawable conversion (tools-android-drawables)

## Important Architecture Notes

### Cross-Process Communication
The library executes code in the Gradle daemon process (separate from main process), so shared logging isn't possible. This is why the `Result<T>` pattern exists - to pass execution details across process boundaries in a serializable format.

### Module Resolution
Modules are resolved through a strategy pattern:
1. `AndroidProjectResolver.resolve()` fetches all Gradle modules
2. For each module, a `ModuleResolutionStrategy` determines how to resolve it
3. Strategies can produce `ResolvedAndroidModule`, `ResolvedGenericModule`, `UnknownModule`, or `FailedModule`
4. Android modules use `AndroidModuleResolver` which fetches dependencies and sources

### Build Variant Handling
Android modules require a build variant context for dependency resolution. The default is "debug". Raw project resolution requires explicitly specifying a `BuildVariant`.

## Development Notes

- Java 21 is required (sourceCompatibility and targetCompatibility set to VERSION_21)
- All modules apply the java plugin in root build.gradle
- JUnit Platform is used for testing (useJUnitPlatform() configured)
- Version is 1.0.0, group is io.yamsergey.adt
