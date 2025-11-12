# tools-android-drawables

Library for discovering and rendering Android drawable resources to images.

## Overview

This library provides tools to:
- Discover drawable resources from Android project modules
- Convert Android vector drawable XML files to PNG images
- Handle bitmap drawables (PNG, JPG, WEBP)
- Support qualifier-based drawables (hdpi, xxhdpi, night, etc.)

## Features

### Drawable Discovery

Uses the Gradle Tooling API to:
1. Connect to an Android project
2. Fetch resource directories via `SourceProvider.getResDirectories()`
3. Scan all `drawable` and `drawable-*` folders
4. Identify drawable types by file extension

### Vector Drawable Rendering

Converts Android vector drawable XML to PNG:
1. Parses Android vector drawable XML format
2. Converts to SVG format (handles namespace differences)
3. Renders SVG to PNG using Apache Batik transcoder
4. Supports custom output dimensions

### Supported Drawable Types

- **Vector drawables** (`.xml`) - Rendered to PNG
- **Bitmap images** (`.png`, `.jpg`, `.jpeg`, `.webp`) - Copied as-is
- **Nine-patch images** (`.9.png`) - Identified but not processed
- **Other XML drawables** - Identified but not rendered (shape, layer-list, selector)

## Usage

### As a Library

```java
// Connect to Gradle project
GradleConnector connector = GradleConnector.newConnector()
    .forProjectDirectory(new File("/path/to/android/project"));

try (ProjectConnection connection = connector.connect()) {
    GradleProject project = connection.getModel(GradleProject.class);
    GradleProject appModule = findModule(project, "app");

    // Discover drawable resources
    DrawableResourceResolver resolver = new DrawableResourceResolver(appModule, connection);
    Result<Collection<DrawableResource>> result = resolver.resolve();

    if (result instanceof Success<Collection<DrawableResource>> success) {
        Collection<DrawableResource> drawables = success.value();

        // Render vector drawables
        VectorDrawableRenderer renderer = new VectorDrawableRenderer();
        for (DrawableResource drawable : drawables) {
            if (drawable.type() == DrawableResource.DrawableType.VECTOR_XML) {
                renderer.renderToPng(
                    drawable.filePath(),
                    Paths.get("output/" + drawable.name() + ".png"),
                    512,  // width in pixels
                    512   // height in pixels
                );
            }
        }
    }
}
```

### Via CLI (adt-cli)

```bash
# Install CLI
./gradlew :adt-cli:installDist

# Generate images from all drawables
./adt-cli/build/install/adt-cli/bin/adt-cli drawables /path/to/android/project

# Only process vector drawables
adt-cli drawables /path/to/android/project --vectors-only

# Specify output directory
adt-cli drawables /path/to/android/project --output ./drawable-images

# Custom dimensions for vector drawables
adt-cli drawables /path/to/android/project --width 256 --height 256

# Specify module (default: app)
adt-cli drawables /path/to/android/project --module mylibrary
```

## Architecture

### Models

- **`DrawableResource`** - Represents a drawable resource with:
  - `name` - Resource name (without extension)
  - `filePath` - Path to the drawable file
  - `type` - Drawable type (VECTOR_XML, BITMAP_PNG, etc.)
  - `qualifier` - Qualifier from folder name (hdpi, night, etc.)

### Resolvers

- **`DrawableResourceResolver`** - Discovers drawables from Android modules
  - Uses Gradle Tooling API to fetch Android project model
  - Scans resource directories for drawable folders
  - Creates `DrawableResource` objects for each file

### Renderers

- **`VectorDrawableRenderer`** - Converts vector drawables to PNG
  - Parses Android vector drawable XML
  - Converts Android format to SVG
  - Uses Apache Batik to render PNG

## Implementation Details

### Android Vector Drawable → SVG Conversion

Android vector drawables use a custom XML format similar to SVG:

**Android Format:**
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:pathData="M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"
        android:fillColor="#000000"/>
</vector>
```

**Converted to SVG:**
```xml
<svg xmlns="http://www.w3.org/2000/svg"
     width="24px"
     height="24px"
     viewBox="0 0 24 24">
    <path d="M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"
          fill="#000000"/>
</svg>
```

The renderer handles:
- Namespace conversion (`android:` → standard SVG attributes)
- Unit conversion (`dp` → `px`)
- Color format conversion (`#AARRGGBB` → `#RRGGBB`)
- Path data, fill colors, stroke attributes
- Fill rules (nonZero, evenOdd)

### Limitations

Current limitations:
- **Gradients** - Basic support, complex gradients may not render correctly
- **Groups/transforms** - Not yet supported
- **Animation** - Not supported (static rendering only)
- **Shape drawables** - Not rendered (XML-based shapes)
- **Layer lists** - Not rendered (composite drawables)
- **Selectors** - Not rendered (state-based drawables)

## Dependencies

- **tools-android** - Core Android project analysis
- **Apache Batik 1.17** - SVG transcoding and PNG rendering
  - `batik-transcoder` - Core transcoding functionality
  - `batik-codec` - PNG codec support
- **Lombok** - Code generation
- **JUnit Jupiter** - Testing

## Development

```bash
# Build module
./gradlew :tools-android-drawables:build

# Run tests
./gradlew :tools-android-drawables:test

# Publish to local Maven
./gradlew :tools-android-drawables:publishToMavenLocal
```

## License

GNU General Public License v3.0
