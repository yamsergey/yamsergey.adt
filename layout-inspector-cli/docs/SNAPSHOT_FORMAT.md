# Android Studio Layout Inspector Snapshot Format (.li)

This document describes the binary format of Android Studio Layout Inspector snapshot files.

**Note:** The Compose proto definitions in this document are based on the official
`compose_layout_inspection.proto` from `prebuilts/tools/common/app-inspection/androidx/compose/ui/compose-ui-inspection.jar`
in the Android Studio source tree.

## Overview

Layout Inspector snapshots (`.li` files) capture the complete state of an Android app's UI hierarchy, including:
- View tree structure
- Compose hierarchy (for Jetpack Compose apps)
- Screenshot data (SKP/Skia Picture format)
- Layout bounds and properties
- Source file locations

## File Structure

```
┌─────────────────────────────────────────┐
│ Java Serialization Header               │
│ Magic: 0xAC 0xED 0x00 0x05              │
├─────────────────────────────────────────┤
│ TC_BLOCKDATALONG (0x7A)                 │
│ Block length (4 bytes, big-endian)      │
├─────────────────────────────────────────┤
│ Version JSON (length-prefixed)          │
│ {"version":"4","title":"package.name"}  │
├─────────────────────────────────────────┤
│ Metadata Protobuf (delimited)           │
├─────────────────────────────────────────┤
│ Snapshot Protobuf (delimited)           │
│ - CaptureSnapshotResponse (view data)   │
│ - ComposeInfo[] (compose data)          │
└─────────────────────────────────────────┘
```

### Java Serialization Wrapper

The file uses Java's ObjectOutputStream serialization format:

| Offset | Bytes | Description |
|--------|-------|-------------|
| 0x00 | `AC ED` | Java serialization magic number |
| 0x02 | `00 05` | Serialization version |
| 0x04 | `7A` | TC_BLOCKDATALONG marker |
| 0x05 | 4 bytes | Block length (big-endian) |
| 0x09 | N bytes | Protobuf data |

### Version Header

The first data in the block is a JSON string with version info:

```json
{"version":"4","title":"com.example.app"}
```

- `version`: Snapshot format version (currently "4")
- `title`: Application package name

## Protobuf Structure

### Metadata Message

```protobuf
message Metadata {
  uint32 api_level = 1;        // Android API level
  string process_name = 2;     // Package name
  bool contains_compose = 3;   // Has Jetpack Compose UI
  bool live_during_capture = 4;
  Source source = 5;           // STUDIO=1, CLI=2
  string source_version = 6;   // e.g., "Narwhal 3 Feature Drop | 2025.1.3"
  uint32 dpi = 7;              // Screen density
  float font_scale = 8;        // System font scale
  uint32 screen_width = 9;
  uint32 screen_height = 10;
}
```

### Snapshot Message

```protobuf
message Snapshot {
  CaptureSnapshotResponse view_snapshot = 1;  // View hierarchy + screenshot
  repeated ComposeInfo compose_info = 2;       // Compose hierarchy
  FoldEvent fold_info = 3;                     // Foldable device info
}
```

### View Hierarchy

```protobuf
message CaptureSnapshotResponse {
  repeated WindowSnapshot window_snapshots = 1;
  WindowRootsEvent window_roots = 2;
}

message WindowSnapshot {
  LayoutEvent layout = 1;
  PropertiesEvent properties = 2;
}

message LayoutEvent {
  repeated StringEntry string_entries = 1;  // String table
  ViewNode root_view = 2;                   // View tree root
  Screenshot screenshot = 3;                // SKP data
  AppContext app_context = 4;
  Configuration configuration = 5;
}
```

### Screenshot (SKP) Data

The screenshot is stored in Skia Picture (SKP) format:

```protobuf
message Screenshot {
  enum Type {
    UNKNOWN = 0;
    SKP = 1;      // Skia Picture format
    BITMAP = 2;   // Raw bitmap
  }
  Type type = 1;
  bytes bytes = 2;  // Binary SKP/bitmap data
}
```

**Note:** The SKP bytes contain arbitrary binary data that may include invalid UTF-8 sequences. This causes standard protobuf parsers to fail with "Protocol message had invalid UTF-8" errors.

### Compose Hierarchy

```protobuf
message ComposeInfo {
  int64 view_id = 1;
  GetComposablesResponse composables = 2;
  GetAllParametersResponse compose_parameters = 3;
}

message GetComposablesResponse {
  repeated StringEntry strings = 1;    // String table for composables
  repeated ComposableRoot roots = 2;   // Tree roots
}

message ComposableRoot {
  int64 view_id = 1;                   // The View ID this Compose tree is rooted under
  repeated ComposableNode nodes = 2;   // Top-level composables
  repeated int64 views_to_skip = 3;    // View IDs to hide (internal views)
}
```

### Compose Parameters (GetAllParametersResponse)

Based on the official `compose_layout_inspection.proto` from Android Studio sources:

```protobuf
message GetAllParametersResponse {
  int64 root_view_id = 1;                  // Echoed from command
  repeated StringEntry strings = 2;        // String table
  repeated ParameterGroup parameter_groups = 3;  // Per-composable parameters
}

message ParameterGroup {
  sint64 composable_id = 1;                // Matches ComposableNode.id
  // Field 2 is reserved
  repeated Parameter parameter = 3;        // Parameters for this composable (singular name!)
  repeated Parameter merged_semantics = 4; // Merged accessibility semantics
  repeated Parameter unmerged_semantics = 5; // Unmerged semantics
}

message Parameter {
  enum Type {
    UNSPECIFIED = 0;
    STRING = 1;
    BOOLEAN = 2;
    DOUBLE = 3;
    FLOAT = 4;
    INT32 = 5;
    INT64 = 6;
    COLOR = 7;
    RESOURCE = 8;
    DIMENSION_DP = 9;
    DIMENSION_SP = 10;
    DIMENSION_EM = 11;
    LAMBDA = 12;
    FUNCTION_REFERENCE = 13;
    ITERABLE = 14;
  }

  Type type = 1;                           // Parameter type
  int32 name = 2;                          // String table index for param name
  repeated Parameter elements = 3;         // Nested elements (for complex types)
  ParameterReference reference = 4;        // Reference for detail fetching
  sint32 index = 5;                        // Index in parent composite

  oneof value {
    int32 int32_value = 11;                // Integer/boolean/string-ref value
    int64 int64_value = 12;                // Long value
    double double_value = 13;              // Double value
    float float_value = 14;                // Float value
    Resource resource_value = 15;          // Android resource reference
    LambdaValue lambda_value = 16;         // Lambda function reference
  }
}
```

**Key notes:**
- The `parameter` field in `ParameterGroup` uses a **singular** name (not `parameters`)
- String values are stored with `type = STRING` and the value in `int32_value` as a string table index
- Boolean values use `type = BOOLEAN` with 0/1 in `int32_value`

### Text Content Extraction

Text displayed in Compose UI (e.g., from `Text` composables) is extracted from parameters:

1. Build string table from `GetAllParametersResponse.strings`
2. For each `ParameterGroup` in `parameter_groups`:
   - Iterate over `parameter` list (note: singular field name, not `parameters`)
   - Look up parameter name using `param.name` (field 2) → string table
   - For STRING type parameters, look up value using `param.int32_value` (field 11) → string table
   - If param name is "text", "content", "label", "title", etc., include the value

**Example wire format for a text parameter:**
```
Parameter for Text("Hello Android!"):
  Field 1 (type): 1 → STRING
  Field 2 (name): 92 → "text" (string table lookup)
  Field 11 (int32_value): 93 → "Hello Android!" (string table lookup)
```

**Wire bytes example:**
```
08 01        # Field 1 (type) = 1 (STRING)
10 5C        # Field 2 (name) = 92
58 5D        # Field 11 (int32_value) = 93
```

### ComposableNode

The core structure for compose UI elements. Based on the official `compose_layout_inspection.proto`:

```protobuf
message ComposableNode {
  sint64 id = 1;                           // Unique node identifier
  repeated ComposableNode children = 2;    // Child composables (recursive)
  int32 package_hash = 3;                  // Hash of source file's package
  int32 filename = 4;                      // String table index -> source file
  int32 line_number = 5;                   // Source line number
  int32 offset = 6;                        // Character offset in source
  int32 name = 7;                          // String table index -> composable name
  Bounds bounds = 8;                       // Layout bounds

  enum Flags {
    NONE = 0;
    SYSTEM_CREATED = 0x1;
    HAS_MERGED_SEMANTICS = 0x2;
    HAS_UNMERGED_SEMANTICS = 0x4;
    INLINED = 0x8;
    NESTED_SINGLE_CHILDREN = 0x10;
    HAS_DRAW_MODIFIER = 0x20;
    HAS_CHILD_DRAW_MODIFIER = 0x40;
  }
  int32 flags = 9;                         // Bitmask of Flags enum

  int64 view_id = 10;                      // Associated Android View ID
  int32 recompose_count = 11;              // Recomposition count since reset
  int32 recompose_skips = 12;              // Skipped recompositions count
  sint32 anchor_hash = 13;                 // Unique anchor ID for this node
}
```

**Key structure notes:**
- `children` is at field 2 and `name` at field 7
- `recompose_skips` (not `skip_count`) at field 12
- `anchor_hash` is `sint32` (signed, uses ZigZag encoding) at field 13

### Bounds

Bounds describe the position and shape of a composable. Based on the official proto:

```protobuf
message Bounds {
  Rect layout = 1;   // Layout bounds (simple rectangle)
  Quad render = 2;   // Render bounds (transformed quadrilateral)
}

message Rect {
  int32 x = 1;       // Left X coordinate
  int32 y = 2;       // Top Y coordinate
  int32 w = 3;       // Width (not 'width')
  int32 h = 4;       // Height (not 'height')
}

message Quad {
  // Four corners of a transformed polygon in drawing order
  sint32 x0 = 1;     // Top-left X
  sint32 y0 = 2;     // Top-left Y
  sint32 x1 = 3;     // Top-right X
  sint32 y1 = 4;     // Top-right Y
  sint32 x2 = 5;     // Bottom-right X
  sint32 y2 = 6;     // Bottom-right Y
  sint32 x3 = 7;     // Bottom-left X
  sint32 y3 = 8;     // Bottom-left Y
}
```

**Usage:**
- `layout` contains the pre-transform layout bounds (simple x, y, width, height)
- `render` contains the post-transform bounds as a quadrilateral (for rotated/scaled views)
- Most composables have `layout` populated; `render` is used when transformations are applied

### String Table

Both view and compose data use string tables for efficient storage:

```protobuf
message StringEntry {
  int32 id = 1;    // Numeric identifier
  string str = 2;  // Actual string value
}
```

**Wire format pattern:**
```
0A XX 08 YY 12 ZZ [string bytes]
│  │  │  │  │  │  └─ UTF-8 string
│  │  │  │  │  └─ String length (varint)
│  │  │  │  └─ Field 2 (str), wire type 2
│  │  │  └─ ID value (varint)
│  │  └─ Field 1 (id), wire type 0
│  └─ Message length (varint)
└─ Field tag (embedded message)
```

**Example entries from a snapshot:**
```
ID  String
─────────────────────────────
1   MainActivity.kt
2   SnapshotExampleTheme
3   Theme.kt
4   MaterialTheme
5   MaterialTheme.kt
6   ProvideTextStyle
7   Scaffold
...
```

## Parsing Considerations

### UTF-8 Issues

Standard protobuf parsers may fail on snapshot files because:
1. SKP data contains arbitrary binary bytes
2. Some text rendered in the UI may contain special characters

**Workaround:** Use raw binary parsing for the protobuf data, skipping length-delimited fields that contain binary data.

### Varint Encoding

Protobuf uses variable-length integer encoding:

```kotlin
fun readVarint(data: ByteArray, pos: Int): Pair<Int, Int> {
    var value = 0
    var shift = 0
    var bytesRead = 0
    var p = pos

    while (p < data.size && shift < 35) {
        val b = data[p].toInt() and 0xFF
        value = value or ((b and 0x7F) shl shift)
        bytesRead++
        p++
        if ((b and 0x80) == 0) break
        shift += 7
    }

    return Pair(value, bytesRead)
}
```

### ZigZag Encoding

Signed integers use ZigZag encoding:

```kotlin
fun decodeZigZag(n: Int): Int = (n ushr 1) xor -(n and 1)
```

## Example: Parsing String Table

```kotlin
fun extractStringTable(data: ByteArray): Map<Int, String> {
    val table = mutableMapOf<Int, String>()
    var pos = 0

    while (pos < data.size - 6) {
        val b0 = data[pos].toInt() and 0xFF

        // Look for StringEntry message start: 0x0A (field 1, wire type 2)
        if (b0 == 0x0A) {
            val msgLen = readVarint(data, pos + 1)
            val innerStart = pos + 1 + msgLen.second

            if ((data[innerStart].toInt() and 0xFF) == 0x08) {
                val idResult = readVarint(data, innerStart + 1)
                val id = idResult.first
                val afterId = innerStart + 1 + idResult.second

                if ((data[afterId].toInt() and 0xFF) == 0x12) {
                    val strLenResult = readVarint(data, afterId + 1)
                    val strLen = strLenResult.first
                    val strStart = afterId + 1 + strLenResult.second

                    val str = String(data, strStart, strLen, Charsets.UTF_8)
                    table[id] = str
                }
            }
        }
        pos++
    }

    return table
}
```

## JSON Output Format

The Layout Inspector CLI can output captured data as JSON using `--json`:

```json
{
  "metadata": {
    "processName": "com.example.app",
    "apiLevel": 36,
    "captureTime": "2025-12-26T15:55:15Z",
    "source": "LIVE_CAPTURE"
  },
  "viewTree": {
    "id": 1,
    "className": "DecorView (com.android.internal.policy)",
    "bounds": {"x": 0, "y": 0, "width": 1080, "height": 2520},
    "childCount": 1,
    "children": [...]
  },
  "composeHierarchy": [
    {
      "name": "SnapshotExampleTheme",
      "file": "MainActivity.kt",
      "line": 21,
      "bounds": {"x": 0, "y": 0, "width": 1080, "height": 2520},
      "children": [
        {
          "name": "Scaffold",
          "file": "MainActivity.kt",
          "line": 22,
          "children": [...]
        }
      ],
      "id": 4159472675
    }
  ],
  "viewClasses": ["DecorView", "LinearLayout", "ComposeView", ...],
  "composeComposables": ["MaterialTheme", "Scaffold", "Text", ...],
  "sourceFiles": ["MainActivity.kt", "Theme.kt", ...],
  "textContent": ["Hello World", ...],
  "allStrings": {"1": "style", "2": "package.name", ...}
}
```

### JSON Fields

| Field | Description |
|-------|-------------|
| `viewTree` | Android View hierarchy (DecorView → children) |
| `composeHierarchy` | Jetpack Compose component tree (requires `--compose-version`) |
| `viewClasses` | List of Android View class names found |
| `composeComposables` | List of Compose function names found |
| `sourceFiles` | Kotlin/Java source files referenced |
| `textContent` | User-visible text strings extracted from UI (see below) |
| `allStrings` | Complete string table from the capture |

### textContent Extraction

The `textContent` array contains user-visible text extracted from Compose parameters. Text is extracted when:

1. A parameter has a name like "text", "content", "label", "title", "message", "hint", "value", or "placeholder"
2. The value looks like user text (contains spaces, isn't a class name, package name, or enum value)

**Filtering rules applied:**
- Excluded: Package names (e.g., `androidx.compose.material3`)
- Excluded: Class names (e.g., `MaterialTheme`, `TextStyle`)
- Excluded: Enum values (e.g., `UPPER_CASE_VALUES`)
- Excluded: Color/resource references (e.g., `#FF0000`, `@string/app_name`)
- Included: Text with spaces (e.g., `"Hello Android!"`)
- Included: Short alphanumeric labels (e.g., `"OK"`, `"Cancel"`)

**Example output:**
```json
"textContent": [
  "Hello Android!",
  "Welcome to the app",
  "Submit"
]
```

## Version History

| Version | Android Studio | Changes |
|---------|---------------|---------|
| 4 | Narwhal (2025.1+) | Current format, Compose support |
| 3 | Ladybug (2024.x) | Added compose parameters |
| 2 | Koala (2024.x) | Compose hierarchy support |
| 1 | Earlier versions | View-only snapshots |

## References

- [Protocol Buffers Wire Format](https://protobuf.dev/programming-guides/encoding/)
- [Skia Picture Format](https://skia.org/docs/dev/design/pdftheory/)
- [Java Serialization Specification](https://docs.oracle.com/javase/8/docs/platform/serialization/spec/protocol.html)
