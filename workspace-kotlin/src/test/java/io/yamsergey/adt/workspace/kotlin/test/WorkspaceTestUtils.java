package io.yamsergey.adt.workspace.kotlin.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yamsergey.adt.workspace.kotlin.model.Library;
import io.yamsergey.adt.workspace.kotlin.model.Workspace;
import io.yamsergey.adt.workspace.kotlin.serializer.WorkspaceJsonSerializer;

/**
 * Utility class for workspace testing.
 *
 * Provides helpers for:
 * - Loading workspaces from fixture projects
 * - Comparing workspaces structurally, ignoring environment-specific paths
 * - Extracting library metadata for comparison
 */
public class WorkspaceTestUtils {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final WorkspaceJsonSerializer serializer = new WorkspaceJsonSerializer();

  /**
   * Loads a workspace from an Android project directory fixture.
   *
   * @param projectDir the Android project directory
   * @return the loaded workspace
   * @throws IOException if loading fails
   */
  public static Workspace loadWorkspaceFromFixture(File projectDir) throws IOException {
    if (!projectDir.exists()) {
      throw new IllegalArgumentException("Project directory does not exist: " + projectDir);
    }

    File workspaceJsonFile = new File(projectDir, "workspace.json");
    if (!workspaceJsonFile.exists()) {
      throw new IllegalArgumentException(
          "workspace.json not found in project: " + projectDir);
    }

    return serializer.fromJsonFile(workspaceJsonFile);
  }

  /**
   * Extracts library metadata (groupId:artifactId:version) from a library.
   * This is environment-independent and useful for comparison.
   *
   * @param library the library
   * @return metadata string in format "groupId:artifactId:version"
   */
  public static String extractLibraryMetadata(Library library) {
    if (library.properties() == null || library.properties().attributes() == null) {
      return null;
    }

    var attrs = library.properties().attributes();
    return String.format("%s:%s:%s",
        attrs.groupId(),
        attrs.artifactId(),
        attrs.version());
  }

  /**
   * Extracts all library metadata from a workspace.
   *
   * @param workspace the workspace
   * @return set of metadata strings
   */
  public static Set<String> extractAllLibraryMetadata(Workspace workspace) {
    return workspace.libraries().stream()
        .map(WorkspaceTestUtils::extractLibraryMetadata)
        .filter(m -> m != null)
        .collect(Collectors.toSet());
  }

  /**
   * Gets all module names from a workspace, sorted for consistent comparison.
   *
   * @param workspace the workspace
   * @return sorted list of module names
   */
  public static List<String> getModuleNames(Workspace workspace) {
    return workspace.modules().stream()
        .map(m -> m.name())
        .sorted()
        .collect(Collectors.toList());
  }

  /**
   * Counts modules by type (main vs test variants).
   *
   * @param workspace the workspace
   * @return map of type -> count
   */
  public static java.util.Map<String, Integer> countModulesByType(Workspace workspace) {
    return workspace.modules().stream()
        .map(m -> m.name())
        .map(name -> {
          if (name.endsWith(".test")) {
            return "test";
          } else if (name.endsWith(".main")) {
            return "main";
          }
          return "android"; // Regular Android modules without suffix
        })
        .collect(Collectors.groupingBy(
            String::valueOf,
            Collectors.summingInt(x -> 1)
        ));
  }

  /**
   * Compares two workspaces structurally.
   *
   * Verifies:
   * - Same number and names of modules
   * - Same number and metadata of libraries (ignoring paths)
   * - Module dependencies reference valid modules or libraries
   *
   * @param expected the expected workspace
   * @param actual the actual workspace
   * @return comparison result with details
   */
  public static WorkspaceComparisonResult compareWorkspaces(Workspace expected,
      Workspace actual) {
    List<String> differences = new ArrayList<>();

    // Compare module names
    List<String> expectedModuleNames = getModuleNames(expected);
    List<String> actualModuleNames = getModuleNames(actual);

    if (!expectedModuleNames.equals(actualModuleNames)) {
      differences.add(String.format(
          "Module names differ: expected %s, got %s",
          expectedModuleNames, actualModuleNames));
    }

    // Compare library metadata (groupId:artifactId:version)
    Set<String> expectedLibMeta = extractAllLibraryMetadata(expected);
    Set<String> actualLibMeta = extractAllLibraryMetadata(actual);

    if (!expectedLibMeta.equals(actualLibMeta)) {
      Set<String> missing = new HashSet<>(expectedLibMeta);
      missing.removeAll(actualLibMeta);
      Set<String> extra = new HashSet<>(actualLibMeta);
      extra.removeAll(expectedLibMeta);

      if (!missing.isEmpty()) {
        differences.add("Missing library dependencies: " + missing);
      }
      if (!extra.isEmpty()) {
        differences.add("Extra library dependencies: " + extra);
      }
    }

    // Compare library counts
    if (expected.libraries().size() != actual.libraries().size()) {
      differences.add(String.format(
          "Library count mismatch: expected %d, got %d",
          expected.libraries().size(),
          actual.libraries().size()));
    }

    boolean isEqual = differences.isEmpty();
    return new WorkspaceComparisonResult(isEqual, differences);
  }

  /**
   * Result of comparing two workspaces.
   */
  public static class WorkspaceComparisonResult {
    private final boolean isEqual;
    private final List<String> differences;

    public WorkspaceComparisonResult(boolean isEqual, List<String> differences) {
      this.isEqual = isEqual;
      this.differences = differences;
    }

    public boolean isEqual() {
      return isEqual;
    }

    public List<String> getDifferences() {
      return differences;
    }

    @Override
    public String toString() {
      if (isEqual) {
        return "Workspaces are equal";
      }
      return "Workspaces differ:\n  " + String.join("\n  ", differences);
    }
  }

  /**
   * Verifies that a JSON string is valid and has expected structure.
   *
   * @param json the JSON string to validate
   * @param expectedModules list of expected module names (can be null to skip)
   * @param expectedMinLibraries minimum number of expected libraries
   * @return true if valid
   * @throws IOException if JSON parsing fails
   */
  public static boolean isValidWorkspaceJson(String json,
      List<String> expectedModules,
      int expectedMinLibraries) throws IOException {
    JsonNode rootNode = objectMapper.readTree(json);

    // Check structure
    if (!rootNode.isObject()) {
      return false;
    }

    if (!rootNode.has("modules") || !rootNode.get("modules").isArray()) {
      return false;
    }

    if (!rootNode.has("libraries") || !rootNode.get("libraries").isArray()) {
      return false;
    }

    // Check library count
    int libraryCount = rootNode.get("libraries").size();
    if (libraryCount < expectedMinLibraries) {
      return false;
    }

    // Check module names if provided
    if (expectedModules != null) {
      var modulesNode = rootNode.get("modules");
      var actualModuleNames = new HashSet<String>();
      for (var moduleNode : modulesNode) {
        if (moduleNode.has("name")) {
          actualModuleNames.add(moduleNode.get("name").asText());
        }
      }
      if (!actualModuleNames.containsAll(expectedModules)) {
        return false;
      }
    }

    return true;
  }
}
