import java.io.File
import java.io.ByteArrayOutputStream

fun main() {
    val input = File("compose-snapshot.li").readBytes()
    
    // Find the 'S' marker after the JSON
    var jsonEnd = -1
    for (i in 0 until input.size - 1) {
        if (input[i] == '}' .code.toByte() && input[i+1] == 'S'.code.toByte()) {
            jsonEnd = i + 2  // Position after 'S'
            break
        }
    }
    println("JSON ends at offset: $jsonEnd (hex: ${jsonEnd.toString(16)})")
    
    // The metadata starts at jsonEnd
    // We need to find where metadata ends and snapshot begins
    // For now, assume the metadata is small (< 128 bytes) and snapshot is large
    
    // First, let's find the metadata size by looking for a pattern change
    // Metadata fields: api_level, process_name, contains_compose, etc.
    // Snapshot starts with field 1 (CaptureSnapshotResponse)
    
    // The metadata in our file: 08 24 12 22 ... (api_level=36, process_name)
    // Looking at the hex dump, metadata seems to end around offset 0x88
    // where we see 'a3 fc 90 01' which looks like a large varint (snapshot length)
    
    // Let me find the snapshot length varint - it's after the last metadata field
    // Metadata fields end with: dpi (field 7), font_scale (field 8), screen_width (9), screen_height (10)
    // These are around offset 0x70-0x88
    
    // Looking at the hex: 50 e0 12 = field 10 (screen_height), value 0x1260 = 4704? No...
    // 50 = field 10 varint, e0 12 = value (varint) = 0x960 = 2400 (screen_height)
    // After that: a3 fc 90 01 = this could be the snapshot length varint
    
    // Actually, let me just calculate the metadata size from the offsets
    // Metadata starts at 0x48, snapshot length varint might start at 0x88
    val metadataStart = jsonEnd  // 0x48
    
    // Find the snapshot start - look for the snapshot length varint pattern
    // The snapshot is large (~2.3MB), so the varint will be several bytes
    var snapshotLengthPos = -1
    for (i in metadataStart until minOf(metadataStart + 100, input.size)) {
        // Look for a large varint that could represent ~2.3MB
        // 2375188 bytes = 0x2438F4
        // As varint: F4 8F 91 01 or similar pattern
        val b1 = input[i].toInt() and 0xFF
        if ((b1 and 0x80) != 0) {  // Continuation bit set
            val b2 = if (i+1 < input.size) input[i+1].toInt() and 0xFF else 0
            if ((b2 and 0x80) != 0) {  // Multi-byte varint
                val b3 = if (i+2 < input.size) input[i+2].toInt() and 0xFF else 0
                if ((b3 and 0x80) != 0 && (i+3 < input.size && (input[i+3].toInt() and 0x80) == 0)) {
                    // 4-byte varint
                    val value = (b1 and 0x7F) or 
                                ((b2 and 0x7F) shl 7) or
                                ((b3 and 0x7F) shl 14) or
                                ((input[i+3].toInt() and 0x7F) shl 21)
                    if (value > 2000000 && value < 3000000) {
                        println("Found likely snapshot length at offset ${i.toString(16)}: $value bytes")
                        snapshotLengthPos = i
                        break
                    }
                }
            }
        }
    }
    
    if (snapshotLengthPos == -1) {
        println("Could not find snapshot length position, checking offset 0x88...")
        snapshotLengthPos = 0x88
    }
    
    val metadataSize = snapshotLengthPos - metadataStart
    println("Metadata size: $metadataSize bytes (from $metadataStart to $snapshotLengthPos)")
    
    // Now patch the file: insert a varint length before metadata
    val output = ByteArrayOutputStream()
    
    // Copy everything up to metadata start
    output.write(input, 0, metadataStart)
    
    // Write metadata length as varint
    var len = metadataSize
    while (len >= 0x80) {
        output.write((len and 0x7F) or 0x80)
        len = len ushr 7
    }
    output.write(len)
    
    // Copy metadata
    output.write(input, metadataStart, metadataSize)
    
    // Copy rest of file (snapshot length + snapshot data)
    output.write(input, snapshotLengthPos, input.size - snapshotLengthPos)
    
    val result = output.toByteArray()
    File("compose-snapshot-patched.li").writeBytes(result)
    println("Patched file written: ${result.size} bytes (original: ${input.size})")
    
    // Fix the block length in the header
    val blockLength = result.size - 9  // Subtract header (magic + TC_BLOCKDATALONG + 4-byte length)
    val patched = result.copyOf()
    patched[5] = ((blockLength shr 24) and 0xFF).toByte()
    patched[6] = ((blockLength shr 16) and 0xFF).toByte()
    patched[7] = ((blockLength shr 8) and 0xFF).toByte()
    patched[8] = (blockLength and 0xFF).toByte()
    File("compose-snapshot-patched.li").writeBytes(patched)
    println("Block length updated to: $blockLength")
}
