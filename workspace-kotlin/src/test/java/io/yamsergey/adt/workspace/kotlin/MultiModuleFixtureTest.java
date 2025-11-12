package io.yamsergey.adt.workspace.kotlin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;

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
 * Tests for workspace JSON logic against the multi-module-kotlin-compose-dsl fixture.
 *
 * Tests verify that:
 * 1. The Android project with multiple modules can be resolved
 * 2. The project can be converted to workspace format
 * 3. All modules are correctly represented in the workspace
 * 4. The workspace serializes to valid JSON
 * 5. The generated workspace matches the expected reference fixture
 */
@DisplayName("Multi-Module Fixture Tests (AGP 8.13)")
class MultiModuleFixtureTest {

  private static String getFixturePath() {
    String testFixturesDir = System.getProperty("testFixturesDir");
    if (testFixturesDir == null) {
      throw new IllegalStateException(
          "testFixturesDir system property not set. Please run tests via Gradle.");
    }
    return testFixturesDir + "/android-projects/agp/8.13/multi-module-kotlin-compose-dsl";
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
  @DisplayName("Should resolve the multi-module fixture project")
  void testResolveProject() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    assertTrue(projectDir.exists(),
        "Fixture directory should exist: " + projectDir.getAbsolutePath());

    // Act
    projectResolver = new AndroidProjectResolver(FIXTURE_PATH);
    Result<Project> resolveResult = projectResolver.resolve();

    // Assert
    assertTrue(resolveResult instanceof Success,
        "Project resolution should succeed");
    Project project = ((Success<Project>) resolveResult).value();
    assertNotNull(project);
    assertTrue(project.modules().size() >= 3,
        "Multi-module project should have at least 3 modules");
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
        "Workspace should have modules");
    assertTrue(workspace.libraries().size() > 10,
        "Multi-module project should have many libraries");
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
    assertTrue(WorkspaceTestUtils.isValidWorkspaceJson(json, null, 10));
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
  @DisplayName("Should contain expected modules in workspace")
  void testModulePresence() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    var moduleNames = WorkspaceTestUtils.getModuleNames(referenceWorkspace);

    // Assert
    assertTrue(moduleNames.contains("app"),
        "Reference workspace should contain 'app' module");
    assertTrue(moduleNames.contains("kotlin-android-library-one"),
        "Reference workspace should contain 'kotlin-android-library-one' module");
  }

  @Test
  @DisplayName("Should handle generic library modules with .main and .test suffixes")
  void testGenericLibraryModules() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    var moduleNames = WorkspaceTestUtils.getModuleNames(referenceWorkspace);

    // Assert
    assertTrue(moduleNames.contains("kotlin-generic-library-one.main"),
        "Reference workspace should contain generic library main module");
    assertTrue(moduleNames.contains("kotlin-generic-library-one.test"),
        "Reference workspace should contain generic library test module");
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
    assertTrue(libraryMetadata.size() > 20,
        "Multi-module fixture should have numerous library dependencies");

    // Check for androidx libraries
    assertTrue(libraryMetadata.stream()
        .anyMatch(m -> m.startsWith("androidx.")),
        "Should have androidx libraries");
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
    assertEquals(4, moduleCount,
        "Multi-module fixture should have exactly 4 modules (from reference)");
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
    assertEquals(86, libraryCount,
        "Multi-module fixture should have exactly 86 libraries (from reference)");
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

  @Test
  @DisplayName("Should correctly classify modules by type")
  void testModuleTypeClassification() throws IOException {
    // Arrange
    File projectDir = new File(FIXTURE_PATH);
    Workspace referenceWorkspace = WorkspaceTestUtils.loadWorkspaceFromFixture(projectDir);

    // Act
    var modulesByType = WorkspaceTestUtils.countModulesByType(referenceWorkspace);

    // Assert
    assertEquals(2, modulesByType.get("test"),
        "Should have 2 test modules (generic-library-one.test)");
    assertEquals(2, modulesByType.get("main"),
        "Should have 2 main modules (app and others)");
    assertEquals(0, (int) modulesByType.getOrDefault("android", 0),
        "No plain Android modules (all are named with suffixes or are Android-app module)");
  }
}
