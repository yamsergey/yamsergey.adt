package io.yamsergey.adt.cli.drawables;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.concurrent.Callable;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

import io.yamsergey.adt.tools.android.drawables.aapt2.Aapt2Wrapper;
import io.yamsergey.adt.tools.android.drawables.model.DrawableResource;
import io.yamsergey.adt.tools.android.drawables.renderer.VectorDrawableRenderer;
import io.yamsergey.adt.tools.android.drawables.report.MarkdownReportGenerator;
import io.yamsergey.adt.tools.android.drawables.resolver.MergedDrawableResourceResolver;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI command to generate images from Android drawable resources.
 *
 * Usage:
 * adt-cli drawables /path/to/android/project --output ./output --module app
 */
@Command(name = "drawables",
         mixinStandardHelpOptions = true,
         description = "Generate images from Android drawable resources")
public class DrawablesCommand implements Callable<Integer> {

  @Parameters(index = "0", description = "Path to Android project root")
  private File projectPath;

  @Option(names = {"-o", "--output"},
          description = "Output directory for generated images (default: ./drawables-output)")
  private File outputDir = new File("./drawables-output");

  @Option(names = {"-m", "--module"},
          description = "Module name to analyze (default: app)")
  private String moduleName = "app";

  @Option(names = {"-v", "--variant"},
          description = "Build variant to analyze (default: debug)")
  private String variantName = "debug";

  @Option(names = {"-w", "--width"},
          description = "Output image width in pixels (for vector drawables)")
  private Integer width;

  @Option(names = {"-h", "--height"},
          description = "Output image height in pixels (for vector drawables)")
  private Integer height;

  @Option(names = {"--vectors-only"},
          description = "Only process vector drawable XML files")
  private boolean vectorsOnly = false;

  @Option(names = {"--report"},
          description = "Generate markdown report (default: README.md in output directory)")
  private String reportFileName = "README.md";

  @Override
  public Integer call() {
    if (!projectPath.exists() || !projectPath.isDirectory()) {
      System.err.println("Error: Project path does not exist or is not a directory: " + projectPath);
      return 1;
    }

    System.out.println("Analyzing Android project: " + projectPath.getAbsolutePath());
    System.out.println("Module: " + moduleName);
    System.out.println("Variant: " + variantName);
    System.out.println("Output directory: " + outputDir.getAbsolutePath());

    try {
      // Create output directory
      Files.createDirectories(outputDir.toPath());

      // Find aapt2 executable
      System.out.println("\nLocating aapt2...");
      var aapt2PathOpt = Aapt2Wrapper.findAapt2();
      if (aapt2PathOpt.isEmpty()) {
        System.err.println("Error: aapt2 not found. Please install Android SDK build-tools.");
        return 1;
      }
      System.out.println("Found aapt2: " + aapt2PathOpt.get());

      // Find merged_res directory
      System.out.println("\nLocating merged_res directory...");
      var mergedResDirOpt = MergedDrawableResourceResolver.findMergedResDir(
          projectPath,
          moduleName,
          variantName
      );
      if (mergedResDirOpt.isEmpty()) {
        System.err.println("Error: merged_res directory not found.");
        System.err.println("Please build the project first: ./gradlew :" + moduleName + ":merge" +
            capitalize(variantName) + "Resources");
        return 1;
      }
      System.out.println("Found merged_res: " + mergedResDirOpt.get());

      // Resolve drawable resources using aapt2
      System.out.println("\nDiscovering drawable resources with aapt2...");
      Aapt2Wrapper aapt2 = new Aapt2Wrapper(aapt2PathOpt.get());
      MergedDrawableResourceResolver resolver = new MergedDrawableResourceResolver(
          mergedResDirOpt.get(),
          aapt2,
          projectPath.toPath(),
          moduleName
      );
      Result<Collection<DrawableResource>> result = resolver.resolve();

      switch (result) {
        case Success<Collection<DrawableResource>> success -> {
          var drawables = success.value();
          System.out.println("Found " + drawables.size() + " drawable resources");

          // Count library vs app resources
          long appCount = drawables.stream().filter(d -> !d.isFromLibrary()).count();
          long libCount = drawables.stream().filter(DrawableResource::isFromLibrary).count();
          System.out.println("  - From app: " + appCount);
          System.out.println("  - From libraries: " + libCount);

          // Process drawables
          return processDrawables(drawables);
        }
        case Failure<Collection<DrawableResource>> failure -> {
          System.err.println("Error: " + failure.description());
          return 1;
        }
        default -> {
          System.err.println("Unexpected result type");
          return 1;
        }
      }
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace();
      return 1;
    }
  }

  private int processDrawables(Collection<DrawableResource> drawables) {
    VectorDrawableRenderer renderer = new VectorDrawableRenderer();
    int successCount = 0;
    int skipCount = 0;
    int errorCount = 0;

    for (DrawableResource drawable : drawables) {
      try {
        // Skip non-vector drawables if vectors-only flag is set
        if (vectorsOnly && drawable.type() != DrawableResource.DrawableType.VECTOR_XML) {
          skipCount++;
          continue;
        }

        // Process based on type
        switch (drawable.type()) {
          case VECTOR_XML -> {
            // Generate PNG from vector drawable
            String outputFileName = drawable.name() +
                (drawable.qualifier() != null ? "-" + drawable.qualifier() : "") +
                ".png";
            Path outputPath = outputDir.toPath().resolve(outputFileName);

            System.out.println("Rendering: " + drawable.name() +
                (drawable.qualifier() != null ? " (" + drawable.qualifier() + ")" : ""));

            renderer.renderToPng(drawable.filePath(), outputPath, width, height);
            successCount++;
          }
          case BITMAP_PNG, BITMAP_JPG, BITMAP_WEBP -> {
            if (!vectorsOnly) {
              // Copy bitmap files as-is
              String outputFileName = drawable.name() +
                  (drawable.qualifier() != null ? "-" + drawable.qualifier() : "") +
                  getFileExtension(drawable.filePath());
              Path outputPath = outputDir.toPath().resolve(outputFileName);

              Files.copy(drawable.filePath(), outputPath);
              successCount++;
            } else {
              skipCount++;
            }
          }
          default -> {
            System.out.println("Skipping unsupported type: " + drawable.name() +
                " (" + drawable.type() + ")");
            skipCount++;
          }
        }
      } catch (IOException e) {
        System.err.println("Error processing " + drawable.name() + ": " + e.getMessage());
        errorCount++;
      }
    }

    System.out.println("\n=== Summary ===");
    System.out.println("Successfully processed: " + successCount);
    System.out.println("Skipped: " + skipCount);
    System.out.println("Errors: " + errorCount);

    // Generate markdown report
    try {
      System.out.println("\nGenerating markdown report...");
      MarkdownReportGenerator reportGen = new MarkdownReportGenerator(
          outputDir.toPath(),
          "." // Relative path for images in markdown
      );
      String report = reportGen.generateReport(drawables);

      Path reportPath = outputDir.toPath().resolve(reportFileName);
      Files.writeString(reportPath, report);
      System.out.println("Report saved to: " + reportPath);
    } catch (IOException e) {
      System.err.println("Error generating report: " + e.getMessage());
      errorCount++;
    }

    return errorCount > 0 ? 1 : 0;
  }

  private String getFileExtension(Path filePath) {
    String fileName = filePath.getFileName().toString();
    int lastDot = fileName.lastIndexOf('.');
    if (lastDot > 0) {
      return fileName.substring(lastDot);
    }
    return "";
  }

  private String capitalize(String str) {
    if (str == null || str.isEmpty()) {
      return str;
    }
    return str.substring(0, 1).toUpperCase() + str.substring(1);
  }
}
