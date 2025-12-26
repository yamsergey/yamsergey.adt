# Layout Inspector CLI

A command-line tool for capturing and converting Android Layout Inspector snapshots.

## Features

- **Live Capture**: Connect to a running Android app and capture layout snapshots
- **Convert Snapshots**: Convert binary `.li` files to human-readable JSON
- **Compose Support**: Extract Jetpack Compose hierarchy and component information
- **View Hierarchy**: Parse traditional Android View tree structure

## Installation

```bash
./gradlew :layout-inspector-cli:installDist
```

The CLI will be available at:
```
./layout-inspector-cli/build/install/layout-inspector-cli/bin/layout-inspector-cli
```

## Usage

### Convert Snapshot to JSON

Convert an Android Studio Layout Inspector snapshot to JSON:

```bash
layout-inspector-cli --convert snapshot.li -o output.json -v
```

Options:
- `--convert <file>`: Path to `.li` snapshot file
- `-o, --output <file>`: Output file path
- `-v, --verbose`: Enable verbose output

### Example Output

```json
{
  "version": "4",
  "metadata": {
    "apiLevel": 36,
    "processName": "com.example.app",
    "containsCompose": true,
    "source": "STUDIO",
    "sourceVersion": "Narwhal 3 Feature Drop | 2025.1.3",
    "dpi": 360,
    "fontScale": 0.9
  },
  "composeHierarchy": [
    {
      "name": "MaterialTheme",
      "id": 1
    },
    {
      "name": "Scaffold",
      "file": "MainActivity.kt",
      "line": 22,
      "id": 2
    },
    {
      "name": "Text",
      "id": 3
    }
  ],
  "viewClasses": [
    "Layout",
    "ScaffoldLayout"
  ],
  "textContent": [
    "Hello Android!"
  ],
  "allStrings": {
    "1": "MaterialTheme",
    "2": "Scaffold",
    "3": "Text"
  }
}
```

### List Connected Devices

```bash
layout-inspector-cli --list-devices
```

### List Debuggable Processes

```bash
layout-inspector-cli --list-processes -s <device-serial>
```

### Live Capture (Experimental)

```bash
layout-inspector-cli <package-name> -s <device-serial> -o snapshot.json
```

## Snapshot Format

See [docs/SNAPSHOT_FORMAT.md](docs/SNAPSHOT_FORMAT.md) for detailed documentation of the binary `.li` file format.

## Extracted Data

The CLI extracts the following from Layout Inspector snapshots:

| Category | Description |
|----------|-------------|
| `metadata` | API level, process name, DPI, font scale, source info |
| `composeHierarchy` | Composable components with names, source files, line numbers |
| `viewClasses` | Android View class names (Layout, ScaffoldLayout, etc.) |
| `sourceFiles` | Kotlin/Java source files referenced in the hierarchy |
| `textContent` | User-visible text strings rendered in the UI |
| `parameterNames` | Compose parameter names |
| `allStrings` | Complete string table from the snapshot |

## Technical Details

### Why Convert?

Android Studio Layout Inspector snapshots (`.li` files) use a complex binary format:
- Java serialization wrapper
- Protobuf-encoded data
- Embedded Skia Picture (SKP) screenshot data

The SKP data contains arbitrary binary bytes that prevent standard protobuf parsers from working. This CLI uses custom binary parsing to extract the meaningful data.

### Limitations

- **Nested Tree Structure**: The hierarchical parent-child relationships are partially extracted. The flat list shows all composables but may not show full nesting.
- **Bounds Data**: Layout bounds (x, y, width, height) extraction is experimental.
- **SKP Screenshots**: The binary screenshot data is not converted (would require Skia library).

## Development

### Build

```bash
./gradlew :layout-inspector-cli:build
```

### Run Tests

```bash
./gradlew :layout-inspector-cli:test
```

### Project Structure

```
layout-inspector-cli/
├── src/main/kotlin/
│   └── io/yamsergey/adt/layoutinspector/
│       ├── cli/Main.kt           # CLI entry point and conversion logic
│       ├── transport/            # Device communication
│       └── snapshot/             # Snapshot parsing
├── src/main/proto/               # Protobuf definitions
│   ├── snapshot.proto
│   ├── compose_layout_inspection.proto
│   └── view_layout_inspection.proto
└── docs/
    └── SNAPSHOT_FORMAT.md        # Binary format documentation
```

## License

Apache 2.0
