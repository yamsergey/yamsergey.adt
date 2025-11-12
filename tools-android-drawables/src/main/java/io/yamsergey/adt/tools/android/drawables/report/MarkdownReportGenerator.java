package io.yamsergey.adt.tools.android.drawables.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.yamsergey.adt.tools.android.drawables.model.DrawableResource;

/**
 * Generates markdown reports for drawable resources.
 *
 * Creates a comprehensive table showing:
 * - Drawable name
 * - Image preview (for rendered images)
 * - Type (vector, bitmap, etc.)
 * - Variants across different source sets (main, debug, release, etc.)
 * - Qualifiers (hdpi, xxhdpi, night, etc.)
 */
public class MarkdownReportGenerator {

  private final Path imageOutputDir;
  private final String imagePathPrefix;

  public MarkdownReportGenerator(Path imageOutputDir, String imagePathPrefix) {
    this.imageOutputDir = imageOutputDir;
    this.imagePathPrefix = imagePathPrefix;
  }

  /**
   * Generate a markdown report with a table of all drawables.
   */
  public String generateReport(Collection<DrawableResource> drawables) {
    StringBuilder md = new StringBuilder();

    md.append("# Drawable Resources Report\n\n");
    md.append(String.format("Total drawables: %d\n\n", drawables.size()));

    // Group drawables by name
    Map<String, List<DrawableResource>> byName = groupByName(drawables);

    // Get all unique source sets and qualifiers
    Set<String> sourceSets = extractSourceSets(drawables);
    Set<String> qualifiers = extractQualifiers(drawables);

    md.append(String.format("- **Source sets**: %s\n", String.join(", ", sourceSets)));
    md.append(String.format("- **Qualifiers**: %s\n\n",
        qualifiers.isEmpty() ? "default only" : String.join(", ", qualifiers)));

    // Generate summary by type
    md.append(generateTypeSummary(drawables));

    // Generate summary by origin
    md.append(generateOriginSummary(drawables));

    // Generate detailed table
    md.append("## Drawable Resources\n\n");
    md.append(generateDrawableTable(byName, sourceSets));

    return md.toString();
  }

  /**
   * Group drawables by their resource name.
   */
  private Map<String, List<DrawableResource>> groupByName(Collection<DrawableResource> drawables) {
    Map<String, List<DrawableResource>> grouped = new LinkedHashMap<>();

    for (DrawableResource drawable : drawables) {
      grouped.computeIfAbsent(drawable.name(), k -> new ArrayList<>()).add(drawable);
    }

    // Sort by name
    return grouped.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (a, b) -> a,
            LinkedHashMap::new
        ));
  }

  /**
   * Extract all unique source sets from drawables.
   */
  private Set<String> extractSourceSets(Collection<DrawableResource> drawables) {
    return drawables.stream()
        .map(DrawableResource::sourceSet)
        .filter(s -> s != null && !s.equals("unknown"))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * Extract all unique qualifiers from drawables.
   */
  private Set<String> extractQualifiers(Collection<DrawableResource> drawables) {
    return drawables.stream()
        .map(DrawableResource::qualifier)
        .filter(q -> q != null)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * Generate summary statistics by drawable type.
   */
  private String generateTypeSummary(Collection<DrawableResource> drawables) {
    Map<DrawableResource.DrawableType, Long> typeCounts = drawables.stream()
        .collect(Collectors.groupingBy(DrawableResource::type, Collectors.counting()));

    StringBuilder summary = new StringBuilder();
    summary.append("### Summary by Type\n\n");
    summary.append("| Type | Count |\n");
    summary.append("|------|-------|\n");

    typeCounts.entrySet().stream()
        .sorted(Map.Entry.<DrawableResource.DrawableType, Long>comparingByValue().reversed())
        .forEach(entry -> summary.append(String.format("| %s | %d |\n", entry.getKey(), entry.getValue())));

    summary.append("\n");
    return summary.toString();
  }

  /**
   * Generate summary statistics by origin (app vs libraries).
   */
  private String generateOriginSummary(Collection<DrawableResource> drawables) {
    long appCount = drawables.stream()
        .filter(d -> !d.isFromLibrary())
        .count();

    Map<String, Long> libraryCounts = drawables.stream()
        .filter(DrawableResource::isFromLibrary)
        .collect(Collectors.groupingBy(DrawableResource::getOriginDisplay, Collectors.counting()));

    StringBuilder summary = new StringBuilder();
    summary.append("### Summary by Origin\n\n");
    summary.append("| Origin | Count |\n");
    summary.append("|--------|-------|\n");
    summary.append(String.format("| **App** | %d |\n", appCount));

    libraryCounts.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .forEach(entry -> summary.append(String.format("| %s | %d |\n", entry.getKey(), entry.getValue())));

    summary.append("\n");
    return summary.toString();
  }

  /**
   * Generate the main drawable table.
   */
  private String generateDrawableTable(Map<String, List<DrawableResource>> byName, Set<String> sourceSets) {
    StringBuilder table = new StringBuilder();

    // Table header
    table.append("| Name | Preview | Type | Qualifiers | Source Sets | Origin |\n");
    table.append("|------|---------|------|------------|-------------|--------|\n");

    // Table rows
    for (Map.Entry<String, List<DrawableResource>> entry : byName.entrySet()) {
      String name = entry.getKey();
      List<DrawableResource> variants = entry.getValue();

      // Use first variant for basic info
      DrawableResource first = variants.get(0);

      // Generate preview (if PNG was generated)
      String preview = generatePreview(name);

      // Get type
      String type = first.type().toString();

      // Collect qualifiers
      String qualifiersStr = variants.stream()
          .map(DrawableResource::qualifier)
          .filter(q -> q != null)
          .distinct()
          .sorted()
          .collect(Collectors.joining(", "));
      if (qualifiersStr.isEmpty()) {
        qualifiersStr = "default";
      }

      // Collect source sets
      String sourceSetsStr = variants.stream()
          .map(DrawableResource::sourceSet)
          .filter(s -> s != null && !s.equals("unknown"))
          .distinct()
          .sorted()
          .collect(Collectors.joining(", "));
      if (sourceSetsStr.isEmpty()) {
        sourceSetsStr = "main";
      }

      // Get origin (library or app)
      String origin = first.getOriginDisplay();

      table.append(String.format("| `%s` | %s | %s | %s | %s | %s |\n",
          name, preview, type, qualifiersStr, sourceSetsStr, origin));
    }

    table.append("\n");
    return table.toString();
  }

  /**
   * Generate markdown image preview for a drawable.
   */
  private String generatePreview(String drawableName) {
    // Check if PNG was generated
    String imagePath = String.format("%s/%s.png", imagePathPrefix, drawableName);
    Path imageFile = imageOutputDir.resolve(drawableName + ".png");

    if (imageFile.toFile().exists()) {
      return String.format("![%s](%s)", drawableName, imagePath);
    }

    return "—";
  }
}
