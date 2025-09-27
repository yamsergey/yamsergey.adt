package io.yamsergey.adt.tools.android.resolver;

import java.util.Collection;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.AndroidProject;
import com.android.builder.model.v2.models.VariantDependencies;

import io.yamsergey.adt.tools.android.gradle.FetchAndroidDependencies;
import io.yamsergey.adt.tools.android.gradle.utils.GraphItemUtils;
import io.yamsergey.adt.tools.android.model.SourceRoot;
import io.yamsergey.adt.tools.android.model.SourceRoot.Language;
import io.yamsergey.adt.tools.android.model.dependency.AarDependency;
import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import io.yamsergey.adt.tools.android.model.dependency.JarDependency;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.android.model.variant.ResolvedVariant;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

/**
 * This class will resolve dependncies for provided module and selected build
 * variant. Android project dependencies highly depends on selected variant for
 * the build.
 **/
public class AndroidModuleDependencyResolver implements Resolver<ResolvedVariant> {
  @Nonnull
  private final BuildVariant selectedVariant;
  @Nonnull
  private final GradleProject gradleProject;
  @Nonnull
  private final ProjectConnection projectConnection;
  @Nonnull
  private final AndroidProject androidProject;

  /**
   * @param selectedVariant   {@link BuildVariant} build variant to resolve.
   * @param androidProject    {@link AndroidProject} android representation of the
   *                          project.
   * @param gradleProject     {@link GradleProject} gradle representation of the
   *                          project.
   * @param projectConnection {@link ProjectConnection} for root project.
   */
  public AndroidModuleDependencyResolver(BuildVariant selectedVariant, GradleProject gradleProject,
      ProjectConnection projectConnection, AndroidProject androidProject) {
    this.selectedVariant = selectedVariant;
    this.gradleProject = gradleProject;
    this.projectConnection = projectConnection;
    this.androidProject = androidProject;
  }

  @Override
  public Result<ResolvedVariant> resolve() {
    var variantDependencies = projectConnection
        .action(FetchAndroidDependencies.builder()
            .buildVariant(selectedVariant)
            .project(gradleProject)
            .build())
        .run();

    return switch (variantDependencies) {
      case Success<VariantDependencies> dependencies -> Result.<ResolvedVariant>success()
          .value(ResolvedVariant.builder()
              .dependencies(extractDependencies(dependencies.value()))
              .generatedRoots(extractRoots())
              .build())
          .build()
          .asResult();
      case Failure<VariantDependencies> failure -> failure.<ResolvedVariant>forward();
      default -> Result.<ResolvedVariant>failure()
          .description(String.format("Unknown result of variant dependencies resolution for: %s , with variant: %s",
              androidProject, selectedVariant.displayName()))
          .build()
          .asResult();
    };
  }

  /**
   * Extract project dependencies. {@link VariantDependencies} contains list of
   * all libraries for the project
   * and dependencies graph for the variant. This functiin will parse
   * {@link GraphItem} from compile Dependencies
   * and try to find path to resolved jars in provided libraries list. There are
   * two types of dependencies in the result: {@link JarDependency} ,
   * {@link AarDependency}.
   *
   * @param dependencies dependencies for resolving.
   * @return collection of resolved dependencies for provided variant.
   **/
  private Collection<Dependency> extractDependencies(VariantDependencies dependencies) {
    var librarires = dependencies.getLibraries();
    return dependencies.getMainArtifact().getCompileDependencies().stream()
        .map(item -> GraphItemUtils.resolveDependency(item, Dependency.Scope.COMPILE, librarires))
        .filter(optional -> optional.isPresent()).map(optional -> optional.get()).toList();
  }

  /**
   * Extract source roots pointing to generated source files.
   *
   * @param {@link VariantDependencies}
   * @return collection of {@link SourceRoot} of the project.
   **/
  private Collection<SourceRoot> extractRoots() {
    var variant = androidProject.getVariants().stream().filter(item -> item.getName().equals(selectedVariant.name()))
        .findFirst().get();
    var mainArtifact = variant.getMainArtifact();
    var generatedRoots = mainArtifact.getGeneratedSourceFolders().stream()
        .map(file -> SourceRoot.builder().language(Language.JAVA).path(file.getAbsolutePath()).build()).toList();
    return generatedRoots;
  }

}
