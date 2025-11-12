package io.yamsergey.adt.tools.android.drawables.resolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.yamsergey.adt.tools.android.drawables.aapt2.Aapt2Wrapper;
import io.yamsergey.adt.tools.android.drawables.aapt2.Aapt2Wrapper.FlatResourceInfo;
import io.yamsergey.adt.tools.android.drawables.model.DrawableResource;
import io.yamsergey.adt.tools.sugar.Result;

/**
 * Resolves drawable resources from merged_res directory using aapt2.
 *
 * This resolver discovers ALL drawable resources including those from:
 * - App source code (main, debug, release, flavors)
 * - External AAR libraries (androidx, material, etc.)
 *
 * It uses aapt2 to parse .flat files in the merged_res directory to extract:
 * - Resource name and type
 * - Source origin (app package or library coordinates)
 * - Configuration qualifiers (hdpi, night, etc.)
 */
public class MergedDrawableResourceResolver {

  private final Path mergedResDir;
  private final Aapt2Wrapper aapt2;
  private final Path projectDir;
  private final String moduleName;

  public MergedDrawableResourceResolver(Path mergedResDir, Aapt2Wrapper aapt2, Path projectDir, String moduleName) {
    this.mergedResDir = mergedResDir;
    this.aapt2 = aapt2;
    this.projectDir = projectDir;
    this.moduleName = moduleName;
  }

  /**
   * Resolve all drawable resources from merged_res directory.
   */
  public Result<Collection<DrawableResource>> resolve() {
    if (!Files.exists(mergedResDir) || !Files.isDirectory(mergedResDir)) {
      return Result.<Collection<DrawableResource>>failure()
          .description("Merged resources directory not found: " + mergedResDir)
          .build();
    }

    try {
      List<DrawableResource> drawables = new ArrayList<>();

      // Find all .flat files for drawables
      try (Stream<Path> paths = Files.walk(mergedResDir)) {
        paths.filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().startsWith("drawable"))
            .filter(path -> path.getFileName().toString().endsWith(".xml.flat"))
            .forEach(flatFile -> {
              try {
                Optional<FlatResourceInfo> info = aapt2.dumpApc(flatFile);
                info.ifPresent(resourceInfo -> {
                  DrawableResource drawable = createDrawableResource(resourceInfo, flatFile);
                  drawables.add(drawable);
                });
              } catch (IOException e) {
                // Log error but continue processing
                System.err.println("Warning: Failed to parse " + flatFile + ": " + e.getMessage());
              }
            });
      }

      return Result.<Collection<DrawableResource>>success()
          .value(drawables)
          .description(String.format("Found %d drawable resources (including libraries)", drawables.size()))
          .build().asResult();

    } catch (IOException e) {
      return Result.<Collection<DrawableResource>>failure()
          .description("Failed to scan merged resources: " + e.getMessage())
          .build();
    }
  }

  /**
   * Create DrawableResource from FlatResourceInfo.
   */
  private DrawableResource createDrawableResource(FlatResourceInfo info, Path flatFile) {
    // Try to find the original source file
    Path sourceFile = findSourceFile(info);
    if (sourceFile == null || !Files.exists(sourceFile)) {
      // Fall back to .flat file if source not found
      sourceFile = flatFile;
    }

    return DrawableResource.builder()
        .name(info.resourceName())
        .filePath(sourceFile)
        .type(DrawableResource.DrawableType.VECTOR_XML) // Assume XML for now
        .qualifier(info.qualifier())
        .sourceSet(info.getSourceSet())
        .libraryName(info.getLibraryName())
        .build();
  }

  /**
   * Find the original source file for a drawable resource.
   * For app resources, looks in module's src/{sourceSet}/res/drawable directories.
   * For library resources, returns null (source not accessible).
   */
  private Path findSourceFile(FlatResourceInfo info) {
    if (info.isFromLibrary()) {
      // Library resources are not directly accessible as source files
      return null;
    }

    // For app resources, search in source directories
    String sourceSet = info.getSourceSet();
    if (sourceSet == null) {
      sourceSet = "main";
    }

    // Build potential paths
    Path moduleDir = projectDir.resolve(moduleName);
    Path srcDir = moduleDir.resolve("src").resolve(sourceSet).resolve("res");

    if (!Files.exists(srcDir)) {
      return null;
    }

    // Search in drawable folders (drawable, drawable-hdpi, drawable-xxhdpi, etc.)
    try {
      String drawableFileName = info.resourceName() + ".xml";
      Optional<Path> found = Files.list(srcDir)
          .filter(Files::isDirectory)
          .filter(dir -> dir.getFileName().toString().startsWith("drawable"))
          .flatMap(drawableDir -> {
            Path drawableFile = drawableDir.resolve(drawableFileName);
            return Files.exists(drawableFile) ? Stream.of(drawableFile) : Stream.empty();
          })
          .findFirst();

      return found.orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Find merged_res directory for a given build variant.
   *
   * @param projectDir Project root directory
   * @param moduleName Module name (e.g., "app")
   * @param variant Build variant (e.g., "debug", "stagingDebug")
   * @return Path to merged_res directory if found
   */
  public static Optional<Path> findMergedResDir(File projectDir, String moduleName, String variant) {
    // Path pattern: {project}/{module}/build/intermediates/merged_res/{variant}/merge{Variant}Resources
    Path basePath = projectDir.toPath()
        .resolve(moduleName)
        .resolve("build")
        .resolve("intermediates")
        .resolve("merged_res");

    if (!Files.exists(basePath)) {
      return Optional.empty();
    }

    // Try to find variant directory
    try {
      return Files.list(basePath)
          .filter(Files::isDirectory)
          .filter(dir -> dir.getFileName().toString().toLowerCase().contains(variant.toLowerCase()))
          .flatMap(dir -> {
            try {
              return Files.list(dir)
                  .filter(Files::isDirectory)
                  .filter(subdir -> subdir.getFileName().toString().startsWith("merge"));
            } catch (IOException e) {
              return Stream.empty();
            }
          })
          .findFirst();
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
