# Android Studio Layout Inspector Snapshot Format (.li)

This document describes the binary format of Android Studio Layout Inspector snapshot files, based on reverse engineering of actual snapshot data.

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
  int64 view_id = 1;
  repeated ComposableNode nodes = 2;   // Top-level composables
  int32 recompose_count = 3;
  int32 skip_count = 4;
}
```

### ComposableNode

The core structure for compose UI elements. **Note:** The actual wire format from Android devices differs from the proto definitions found in AOSP. The field numbers below reflect the actual on-device format discovered through reverse engineering:

```protobuf
message ComposableNode {
  int64 id = 1;                            // Unique node identifier
  repeated ComposableNode children = 2;    // Child composables (recursive)
  int64 anchor_hash = 3;                   // Anchor hash for recomposition tracking
  int32 filename = 4;                      // String table index -> source file
  int32 line_number = 5;                   // Source line number
  int32 offset = 6;                        // Character offset in source
  int32 name = 7;                          // String table index -> composable name
  Quad bounds = 8;                         // Layout bounds
  int32 flags = 9;                         // Node flags
  int32 package_hash = 10;
  int32 recompose_count = 11;
  int32 skip_count = 12;
  int64 view_id = 13;                      // Associated Android View ID
  repeated RenderNode render_nodes = 14;   // Render tree nodes
  bool has_merged_semantics = 15;
  bool has_unmerged_semantics = 16;
}
```

**Key difference from AOSP definitions:** Children are at field 2 (not 9), and name is at field 7 (not 2).

### Bounds (Quad)

Bounds can be stored in two formats:

**Format 1: Nested Size (for simple rectangular bounds)**
```protobuf
message Quad {
  QuadSize size = 1;  // Simple rectangle bounds
}

message QuadSize {
  int32 x = 1;       // Left X coordinate
  int32 y = 2;       // Top Y coordinate
  int32 width = 3;   // Width
  int32 height = 4;  // Height
}
```

**Format 2: Full Quadrilateral (for rotated/transformed views)**
```protobuf
message Quad {
  // When size is not present, use corner coordinates:
  int32 x0 = 2;  // Top-left X
  int32 y0 = 3;  // Top-left Y
  int32 x1 = 4;  // Top-right X
  int32 y1 = 5;  // Top-right Y
  int32 x2 = 6;  // Bottom-right X
  int32 y2 = 7;  // Bottom-right Y
  int32 x3 = 8;  // Bottom-left X
  int32 y3 = 9;  // Bottom-left Y
}
```

Most composables use Format 1 with nested QuadSize containing width/height.

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
| `textContent` | Text strings displayed in the UI |
| `allStrings` | Complete string table from the capture |

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
