package io.yamsergey.adt.tools.android.drawables.resolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.ide.SourceProvider;
import com.android.builder.model.v2.models.BasicAndroidProject;

import io.yamsergey.adt.tools.android.drawables.model.DrawableResource;
import io.yamsergey.adt.tools.android.gradle.FetchBasicAndroidProject;
import io.yamsergey.adt.tools.android.resolver.Resolver;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

/**
 * Resolves drawable resources from an Android module.
 *
 * This resolver:
 * 1. Fetches the Android project model via Gradle Tooling API
 * 2. Extracts resource directories from the main source set
 * 3. Scans drawable folders (drawable, drawable-*, etc.)
 * 4. Identifies and catalogs all drawable resources
 */
public class DrawableResourceResolver implements Resolver<Collection<DrawableResource>> {

  @Nonnull
  private final GradleProject project;
  @Nonnull
  private final ProjectConnection projectConnection;

  public DrawableResourceResolver(
      GradleProject project,
      ProjectConnection projectConnection) {
    this.project = project;
    this.projectConnection = projectConnection;
  }

  @Override
  public Result<Collection<DrawableResource>> resolve() {
    var basicAndroidProject = projectConnection
        .action(FetchBasicAndroidProject.builder().gradleProject(project).build())
        .run();

    return switch (basicAndroidProject) {
      case Success<BasicAndroidProject> success -> {
        try {
          var drawables = extractDrawables(success.value());
          yield Result.<Collection<DrawableResource>>success()
              .value(drawables)
              .description(String.format("Found %d drawable resources", drawables.size()))
              .build().asResult();
        } catch (IOException e) {
          yield Result.<Collection<DrawableResource>>failure()
              .description(String.format("Failed to scan drawable resources: %s", e.getMessage()))
              .build();
        }
      }
      case Failure<BasicAndroidProject> failure -> failure.<Collection<DrawableResource>>forward();
      default -> Result.<Collection<DrawableResource>>failure()
          .description(String.format("Couldn't resolve Android Project: %s", project.getName()))
          .build();
    };
  }

  /**
   * Extract all drawable resources from the Android project's resource directories.
   */
  private Collection<DrawableResource> extractDrawables(BasicAndroidProject project) throws IOException {
    SourceProvider sourceProvider = project.getMainSourceSet().getSourceProvider();

    return sourceProvider.getResDirectories().stream()
        .flatMap(resDir -> scanDrawableFolders(resDir.toPath()))
        .toList();
  }

  /**
   * Scan all drawable folders within a res directory.
   */
  private Stream<DrawableResource> scanDrawableFolders(Path resDir) {
    if (!Files.exists(resDir) || !Files.isDirectory(resDir)) {
      return Stream.empty();
    }

    String sourceSet = DrawableResource.extractSourceSet(resDir);

    try {
      return Files.list(resDir)
          .filter(Files::isDirectory)
          .filter(dir -> dir.getFileName().toString().startsWith("drawable"))
          .flatMap(drawableDir -> scanDrawableFiles(drawableDir, sourceSet));
    } catch (IOException e) {
      return Stream.empty();
    }
  }

  /**
   * Scan files within a drawable folder and create DrawableResource objects.
   */
  private Stream<DrawableResource> scanDrawableFiles(Path drawableDir, String sourceSet) {
    try {
      String qualifier = DrawableResource.extractQualifier(drawableDir);

      return Files.list(drawableDir)
          .filter(Files::isRegularFile)
          .map(filePath -> DrawableResource.builder()
              .name(DrawableResource.extractResourceName(filePath))
              .filePath(filePath)
              .type(DrawableResource.determineType(filePath))
              .qualifier(qualifier)
              .sourceSet(sourceSet)
              .build());
    } catch (IOException e) {
      return Stream.empty();
    }
  }
}
