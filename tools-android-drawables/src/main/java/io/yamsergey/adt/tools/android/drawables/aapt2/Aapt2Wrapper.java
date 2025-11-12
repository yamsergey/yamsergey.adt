package io.yamsergey.adt.tools.android.drawables.aapt2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper for aapt2 command-line tool to extract resource information.
 *
 * Uses aapt2 dump apc command to parse compiled resource files (.flat)
 * and extract drawable resource metadata including source origin.
 */
public class Aapt2Wrapper {

  private final Path aapt2Path;

  /**
   * Pattern to extract resource name from aapt2 dump output.
   * Example: "Resource: drawable/ic_launcher"
   */
  private static final Pattern RESOURCE_PATTERN = Pattern.compile("^\\s*Resource:\\s+(\\w+)/(\\S+)\\s*$");

  /**
   * Pattern to extract source/origin from aapt2 dump output.
   * Example: "Source:   life.league.cigna.app-main-162:/drawable/ic_launcher.xml"
   * Example: "Source:   androidx.appcompat:appcompat-1.6.1:/drawable/abc_ic_star.xml"
   */
  private static final Pattern SOURCE_PATTERN = Pattern.compile("^\\s*Source:\\s+([^:]+):(.*?)\\s*$");

  /**
   * Pattern to extract config (qualifiers) from aapt2 dump output.
   * Example: "Config:   hdpi-v4"
   */
  private static final Pattern CONFIG_PATTERN = Pattern.compile("^\\s*Config:\\s+(\\S*)\\s*$");

  public Aapt2Wrapper(Path aapt2Path) {
    this.aapt2Path = aapt2Path;
  }

  /**
   * Find aapt2 executable in Android SDK.
   */
  public static Optional<Path> findAapt2() {
    // Try common locations
    String[] possiblePaths = {
        System.getenv("ANDROID_HOME") + "/build-tools",
        System.getProperty("user.home") + "/Library/Android/sdk/build-tools",
        System.getProperty("user.home") + "/Android/Sdk/build-tools"
    };

    for (String basePath : possiblePaths) {
      if (basePath.contains("null")) continue;

      try {
        Path buildToolsDir = Path.of(basePath);
        if (!Files.exists(buildToolsDir)) continue;

        // Find latest version
        Optional<Path> aapt2 = Files.list(buildToolsDir)
            .filter(Files::isDirectory)
            .map(dir -> dir.resolve("aapt2"))
            .filter(Files::exists)
            .filter(Files::isExecutable)
            .findFirst();

        if (aapt2.isPresent()) {
          return aapt2;
        }
      } catch (IOException e) {
        // Continue searching
      }
    }

    return Optional.empty();
  }

  /**
   * Dump APC information from a compiled resource file (.flat).
   *
   * @param flatFile Path to .flat file
   * @return Parsed resource information
   */
  public Optional<FlatResourceInfo> dumpApc(Path flatFile) throws IOException {
    List<String> command = List.of(
        aapt2Path.toString(),
        "dump",
        "apc",
        flatFile.toString()
    );

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    FlatResourceInfo.Builder builder = FlatResourceInfo.builder();
    boolean foundResource = false;

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        // Parse resource name
        Matcher resourceMatcher = RESOURCE_PATTERN.matcher(line);
        if (resourceMatcher.matches()) {
          String type = resourceMatcher.group(1);
          String name = resourceMatcher.group(2);
          if ("drawable".equals(type)) {
            builder.resourceName(name);
            foundResource = true;
          }
        }

        // Parse source/origin
        Matcher sourceMatcher = SOURCE_PATTERN.matcher(line);
        if (sourceMatcher.matches()) {
          String source = sourceMatcher.group(1);
          String path = sourceMatcher.group(2);
          builder.source(source);
          builder.sourcePath(path);
        }

        // Parse config/qualifier
        Matcher configMatcher = CONFIG_PATTERN.matcher(line);
        if (configMatcher.matches()) {
          String config = configMatcher.group(1);
          if (!config.isEmpty()) {
            builder.qualifier(config);
          }
        }
      }

      process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Process interrupted", e);
    }

    return foundResource ? Optional.of(builder.build()) : Optional.empty();
  }

  /**
   * Information extracted from a .flat file.
   */
  public record FlatResourceInfo(
      String resourceName,
      String source,      // e.g., "life.league.cigna.app-main-162" or "androidx.appcompat:appcompat-1.6.1"
      String sourcePath,  // e.g., "/drawable/ic_launcher.xml"
      String qualifier) { // e.g., "hdpi-v4" or null for default

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {
      private String resourceName;
      private String source;
      private String sourcePath;
      private String qualifier;

      public Builder resourceName(String resourceName) {
        this.resourceName = resourceName;
        return this;
      }

      public Builder source(String source) {
        this.source = source;
        return this;
      }

      public Builder sourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
        return this;
      }

      public Builder qualifier(String qualifier) {
        this.qualifier = qualifier;
        return this;
      }

      public FlatResourceInfo build() {
        return new FlatResourceInfo(resourceName, source, sourcePath, qualifier);
      }
    }

    /**
     * Check if this resource comes from an external library.
     */
    public boolean isFromLibrary() {
      return source != null && source.contains(":");
    }

    /**
     * Extract library name from source.
     * Example: "androidx.appcompat:appcompat-1.6.1" -> "androidx.appcompat:appcompat"
     */
    public String getLibraryName() {
      if (!isFromLibrary()) {
        return null;
      }

      // Remove version suffix
      int dashIndex = source.lastIndexOf('-');
      int colonIndex = source.indexOf(':');

      if (dashIndex > colonIndex) {
        return source.substring(0, dashIndex);
      }

      return source;
    }

    /**
     * Extract source set from source.
     * Example: "life.league.cigna.app-main-162" -> "main"
     */
    public String getSourceSet() {
      if (isFromLibrary()) {
        return null;
      }

      // Extract source set from app package
      int dashIndex = source.lastIndexOf('-');
      if (dashIndex > 0) {
        int prevDashIndex = source.lastIndexOf('-', dashIndex - 1);
        if (prevDashIndex > 0) {
          return source.substring(prevDashIndex + 1, dashIndex);
        }
      }

      return "main"; // Default
    }
  }
}
