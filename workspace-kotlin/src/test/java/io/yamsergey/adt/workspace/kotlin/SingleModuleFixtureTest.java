package io.yamsergey.adt.workspace.kotlin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.yamsergey.adt.tools.android.model.project.Project;
import io.yamsergey.adt.tools.android.resolver.AndroidProjectResolver;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import io.yamsergey.adt.workspace.kotlin.converter.ProjectToWorkspaceConverter;
import io.yamsergey.adt.workspace.kotlin.model.Workspace;
import io.yamsergey.adt.workspace.kotlin.serializer.WorkspaceJsonSerializer;
import io.yamsergey.adt.workspace.kotlin.test.WorkspaceTestUtils;

/**
 * Tests for workspace JSON logic against the single-module-kotlin-compose-dsl fixture.
 *
 * Tests verify that:
 * 1. The Android project can be resolved
 * 2. The project can be converted to workspace format
 * 3. The workspace serializes to valid JSON
 * 4. The generated workspace matches the expected reference fixture
 */
@DisplayName("Single-Module Fixture Tests (AGP 8.11)")
class SingleModuleFixtureTest {

  private static String getFixturePath() {
    String testFixturesDir = System.getProperty("testFixturesDir");
    if (testFixturesDir == null) {
      throw new IllegalStateException(
          "testFixturesDir system property not set. Please run tests via Gradle.");
    }
    return testFixturesDir + "/android-projects/agp/8.11/single-module-kotlin-compose-dsl";
  }

  private static final String FIXTURE_PATH = getFixturePath();

  private static AndroidProjectResolver projectResolver;
  private static ProjectToWorkspaceConverter converter;
  private static WorkspaceJsonSerializer serializer;

  @BeforeAll
  static void setUpAll() {
    converter = new ProjectToWorkspaceConverter();
    serializer = new WorkspaceJsonSerializer();
  }

  @Test
  @DisplayName("Should resolve the single-module fixture project")
  void testResolveProject() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    assertTrue(projectDir.exists(),
        "Fixture directory should exist: " + projectDir.getAbsolutePath());

    System.err.println("\n=== DEBUG: Attempting to resolve project ===");
    System.err.println("FIXTURE_PATH constant: " + FIXTURE_PATH);
    System.err.println("projectDir from File(FIXTURE_PATH): " + projectDir.getAbsolutePath());
    System.err.println("Exists: " + projectDir.exists());

    // List files in directory to verify it's a gradle project
    System.err.println("\nFiles in directory:");
    File[] files = projectDir.listFiles();
    if (files != null) {
      for (File f : files) {
        System.err.println("  - " + f.getName());
      }
    }

    // Act
    System.err.println("\nCreating AndroidProjectResolver with path: " + FIXTURE_PATH);
    projectResolver = new AndroidProjectResolver(FIXTURE_PATH);

    System.err.println("Calling resolver.resolve()...");
    Result<Project> resolveResult = projectResolver.resolve();

    // Print detailed error if resolution fails
    System.err.println("\nResolution result type: " + resolveResult.getClass().getSimpleName());
    System.err.println("Resolution description: " + resolveResult.description());

    // Assert
    if (!(resolveResult instanceof Success)) {
      fail("Project resolution failed: " + resolveResult.description());
    }
    Project project = ((Success<Project>) resolveResult).value();
    assertNotNull(project);
  }

  @Test
  @DisplayName("Should convert resolved project to workspace format")
  void testConvertToWorkspace() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    projectResolver = new AndroidProjectResolver(FIXTURE_PATH);
    Result<Project> resolveResult = projectResolver.resolve();
    assertTrue(resolveResult instanceof Success);
    Project project = ((Success<Project>) resolveResult).value();

    // Act
    Workspace workspace = converter.convert(project);

    // Assert
    assertNotNull(workspace);
    assertNotNull(workspace.modules());
    assertNotNull(workspace.libraries());
    assertFalse(workspace.modules().isEmpty(),
        "Workspace should have at least one module");
  }

  @Test
  @DisplayName("Should serialize workspace to valid JSON")
  void testSerializeToJson() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    projectResolver = new AndroidProjectResolver(FIXTURE_PATH);
    Result<Project> resolveResult = projectResolver.resolve();
    Project project = ((Success<Project>) resolveResult).value();
    Workspace workspace = converter.convert(project);

    // Act
    String json = serializer.toJson(workspace);

    // Assert
    assertNotNull(json);
    assertFalse(json.isEmpty());
    assertTrue(WorkspaceTestUtils.isValidWorkspaceJson(json, null, 1));
  }

  @Test
  @DisplayName("Should load reference workspace.json from fixture")
  void testLoadReferenceWorkspace() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    assertTrue(projectDir.exists());

    // Act
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Assert
    assertNotNull(referenceWorkspace);
    assertFalse(referenceWorkspace.modules().isEmpty(),
        "Reference workspace should have modules");
    assertFalse(referenceWorkspace.libraries().isEmpty(),
        "Reference workspace should have libraries");
  }

  @Test
  @DisplayName("Should have 'app' module in workspace")
  void testModulePresence() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    var moduleNames = WorkspaceTestUtils.getModuleNames(referenceWorkspace);

    // Assert
    assertTrue(moduleNames.contains("app"),
        "Reference workspace should contain 'app' module");
  }

  @Test
  @DisplayName("Should have expected library dependencies")
  void testLibraryDependencies() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    var libraryMetadata = WorkspaceTestUtils.extractAllLibraryMetadata(referenceWorkspace);

    // Assert
    assertTrue(libraryMetadata.size() > 10,
        "Single-module fixture should have numerous library dependencies");

    // Check for some common androidx libraries
    assertTrue(libraryMetadata.stream()
        .anyMatch(m -> m.startsWith("androidx.fragment")),
        "Should have androidx.fragment libraries");
    assertTrue(libraryMetadata.stream()
        .anyMatch(m -> m.startsWith("androidx.appcompat")),
        "Should have androidx.appcompat libraries");
  }

  @Test
  @DisplayName("Should contain android.jar SDK library")
  void testAndroidSdkLibrary() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    boolean hasAndroidJar = referenceWorkspace.libraries().stream()
        .anyMatch(lib -> lib.name().contains("android:android"));

    // Assert
    assertTrue(hasAndroidJar,
        "Workspace should contain android SDK library (android:android)");
  }

  @Test
  @DisplayName("Should match generated workspace against reference")
  void testGeneratedWorkspaceMatchesReference() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    projectResolver = new AndroidProjectResolver(FIXTURE_PATH);
    Result<Project> resolveResult = projectResolver.resolve();
    assertTrue(resolveResult instanceof Success);
    Project project = ((Success<Project>) resolveResult).value();

    // Load reference workspace
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    Workspace generatedWorkspace = converter.convert(project);

    // Compare workspaces
    var comparisonResult = WorkspaceTestUtils.compareWorkspaces(
        referenceWorkspace,
        generatedWorkspace);

    // Assert
    assertTrue(comparisonResult.isEqual(),
        "Generated workspace should match reference: " + comparisonResult);
  }

  @Test
  @DisplayName("Should have correct number of modules")
  void testModuleCount() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    int moduleCount = referenceWorkspace.modules().size();

    // Assert
    assertTrue(moduleCount >= 1,
        "Single-module project should have at least one module");
    assertTrue(moduleCount <= 5,
        "Single-module project should have at most a few modules (main + test variants)");
  }

  @Test
  @DisplayName("Should have correct number of libraries")
  void testLibraryCount() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    int libraryCount = referenceWorkspace.libraries().size();

    // Assert
    assertEquals(69, libraryCount,
        "Single-module fixture should have exactly 69 libraries (from reference)");
  }

  @Test
  @DisplayName("Should round-trip JSON serialization")
  void testJsonRoundTrip() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace originalWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    String json = serializer.toJson(originalWorkspace);
    Workspace deserializedWorkspace = serializer.fromJson(json);

    // Assert
    assertEquals(originalWorkspace.modules().size(), deserializedWorkspace.modules().size());
    assertEquals(originalWorkspace.libraries().size(), deserializedWorkspace.libraries().size());

    var originalLibMeta = WorkspaceTestUtils.extractAllLibraryMetadata(originalWorkspace);
    var deserializedLibMeta = WorkspaceTestUtils.extractAllLibraryMetadata(deserializedWorkspace);
    assertEquals(originalLibMeta, deserializedLibMeta);
  }
}
