package io.yamsergey.adt.tools.android.drawables.model;

import java.nio.file.Path;

import lombok.Builder;

/**
 * Represents a drawable resource in an Android project.
 *
 * Drawable resources can be:
 * - Vector drawables (XML format)
 * - Bitmap images (PNG, JPG, WEBP, etc.)
 * - Nine-patch images (.9.png)
 * - Shape drawables (XML)
 * - Layer list drawables (XML)
 */
@Builder(toBuilder = true)
public record DrawableResource(
    String name,
    Path filePath,
    DrawableType type,
    String qualifier,
    String sourceSet,     // e.g., "main", "debug", "release", "staging", "uat"
    String libraryName) { // e.g., "androidx.appcompat:appcompat" or null if from app

  public enum DrawableType {
    VECTOR_XML,
    BITMAP_PNG,
    BITMAP_JPG,
    BITMAP_WEBP,
    NINE_PATCH,
    SHAPE_XML,
    LAYER_LIST_XML,
    SELECTOR_XML,
    UNKNOWN
  }

  /**
   * Determine drawable type from file extension and content.
   */
  public static DrawableType determineType(Path filePath) {
    String fileName = filePath.getFileName().toString().toLowerCase();

    if (fileName.endsWith(".xml")) {
      // Will need to parse XML to determine exact type
      // For now, assume vector drawable
      return DrawableType.VECTOR_XML;
    } else if (fileName.endsWith(".9.png")) {
      return DrawableType.NINE_PATCH;
    } else if (fileName.endsWith(".png")) {
      return DrawableType.BITMAP_PNG;
    } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
      return DrawableType.BITMAP_JPG;
    } else if (fileName.endsWith(".webp")) {
      return DrawableType.BITMAP_WEBP;
    }

    return DrawableType.UNKNOWN;
  }

  /**
   * Extract resource name from file name (without extension).
   */
  public static String extractResourceName(Path filePath) {
    String fileName = filePath.getFileName().toString();
    int lastDot = fileName.lastIndexOf('.');

    if (lastDot > 0) {
      String nameWithoutExt = fileName.substring(0, lastDot);
      // Handle nine-patch (.9.png)
      if (nameWithoutExt.endsWith(".9")) {
        return nameWithoutExt.substring(0, nameWithoutExt.length() - 2);
      }
      return nameWithoutExt;
    }

    return fileName;
  }

  /**
   * Extract qualifier from drawable folder name (e.g., "hdpi", "xxhdpi", "night").
   */
  public static String extractQualifier(Path drawableFolderPath) {
    String folderName = drawableFolderPath.getFileName().toString();

    if (folderName.equals("drawable")) {
      return null; // Default, no qualifier
    }

    if (folderName.startsWith("drawable-")) {
      return folderName.substring("drawable-".length());
    }

    return null;
  }

  /**
   * Extract source set name from resource directory path.
   * Expects path like: .../src/main/res/... or .../src/debug/res/...
   * Returns the source set name (e.g., "main", "debug", "release").
   */
  public static String extractSourceSet(Path resDirectoryPath) {
    String pathStr = resDirectoryPath.toString();

    // Look for /src/{sourceSet}/res pattern
    int srcIndex = pathStr.lastIndexOf("/src/");
    if (srcIndex == -1) {
      srcIndex = pathStr.lastIndexOf("\\src\\"); // Windows path
    }

    if (srcIndex != -1) {
      int startIndex = srcIndex + 5; // Length of "/src/"
      int resIndex = pathStr.indexOf("/res", startIndex);
      if (resIndex == -1) {
        resIndex = pathStr.indexOf("\\res", startIndex); // Windows path
      }

      if (resIndex != -1) {
        return pathStr.substring(startIndex, resIndex);
      }
    }

    return "unknown";
  }

  /**
   * Check if this drawable comes from an external library.
   */
  public boolean isFromLibrary() {
    return libraryName != null;
  }

  /**
   * Get display name for origin (library name or "App").
   */
  public String getOriginDisplay() {
    return libraryName != null ? libraryName : "App";
  }
}
