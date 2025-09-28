package io.yamsergey.adt.tools.android.resolver;

import static io.yamsergey.adt.tools.android.gradle.utils.GradleProjectUtils.isAndroidProject;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.AndroidProject;

import io.yamsergey.adt.tools.android.gradle.FetchAndroidProject;
import io.yamsergey.adt.tools.android.gradle.utils.VariantUtils;
import io.yamsergey.adt.tools.android.model.SourceRoot;
import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import io.yamsergey.adt.tools.android.model.module.FailedModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedAndroidModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedGenericModule;
import io.yamsergey.adt.tools.android.model.module.ResolvedModule;
import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.android.model.variant.ResolvedVariant;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Success;

public class ModuleResolver implements ModuleResolutionStrategy {

    @Override
    public List<ResolvedModule> resolveModules(
            @Nonnull ProjectConnection connection,
            @Nonnull GradleProject rootProject,
            @Nonnull BuildVariant selectedBuildVariant) {
        return flattenGradleProjectTree(rootProject)
                .stream()
                .map(subProject -> resolveModule(subProject, connection, rootProject, selectedBuildVariant))
                .collect(Collectors.toList());
    }

    private List<GradleProject> flattenGradleProjectTree(GradleProject project) {
        return project.getChildren()
                .stream()
                .flatMap(child -> {
                    List<GradleProject> childModules = flattenGradleProjectTree(child);
                    childModules.add(0, child);
                    return childModules.stream();
                })
                .collect(Collectors.toList());
    }

    private ResolvedModule resolveModule(
            GradleProject subProject,
            ProjectConnection connection,
            GradleProject rootProject,
            BuildVariant selectedBuildVariant) {
        if (isAndroidProject(subProject)) {
            return resolveAndroidModule(subProject, connection, rootProject, selectedBuildVariant);
        } else {
            return resolveGenericModule(subProject);
        }
    }

    private ResolvedModule resolveAndroidModule(
            GradleProject subProject,
            ProjectConnection connection,
            GradleProject rootProject,
            BuildVariant selectedBuildVariant) {
        var androidProjectResult = connection.action(
                FetchAndroidProject.builder().gradleProject(subProject).build()).run();

        return switch (androidProjectResult) {
            case Success<AndroidProject> success -> {
                var moduleBuildVariants = new BuildVariantsResolver(success.value()).resolve();

                yield switch (moduleBuildVariants) {
                    case Success<Collection<BuildVariant>> variants -> {
                        var resolvedModuleSources = new AndroidModuleSourcesResolver(
                                subProject, connection, rootProject).resolve();
                        var moduleBuildVariant = VariantUtils.chooseBuildVariant(
                                selectedBuildVariant, variants.value());
                        var resolvedVariantResult = new AndroidModuleDependencyResolver(
                                moduleBuildVariant,
                                subProject,
                                connection,
                                success.value()).resolve();

                        yield switch (resolvedModuleSources) {
                            case Success<Collection<SourceRoot>> sources -> switch (resolvedVariantResult) {
                                case Success<ResolvedVariant> resolvedVariant -> ResolvedAndroidModule.builder()
                                        .name(subProject.getName())
                                        .path(subProject.getPath())
                                        .buildVariants(variants.value())
                                        .dependencies(resolvedVariant.value().dependencies())
                                        .roots(resolvedVariant.value().generatedRoots())
                                        .roots(sources.value())
                                        .build();
                                case Failure<ResolvedVariant> failure -> FailedModule.from(failure, subProject);
                                default -> FailedModule.builder()
                                        .name(subProject.getName())
                                        .path(subProject.getPath())
                                        .details("Unknown result of module variant resolution for: " + subProject.getPath())
                                        .build();
                            };
                            case Failure<Collection<SourceRoot>> failure -> FailedModule.from(failure, subProject);
                            default -> FailedModule.builder()
                                    .name(subProject.getName())
                                    .path(subProject.getPath())
                                    .details("Unknown result for source set resolution for: " + subProject.getPath())
                                    .build();
                        };
                    }
                    case Failure<Collection<BuildVariant>> failure -> FailedModule.from(failure, subProject);
                    default -> FailedModule.builder()
                            .name(subProject.getName())
                            .path(subProject.getPath())
                            .details("Unknown build variant resolution result")
                            .build();
                };
            }
            case Failure<AndroidProject> failure -> FailedModule.from(failure, subProject);
            default -> FailedModule.builder()
                    .name(subProject.getName())
                    .path(subProject.getPath())
                    .details("Unknown android project resolution result")
                    .build();
        };
    }

    private ResolvedModule resolveGenericModule(GradleProject subProject) {
        return ResolvedGenericModule.builder()
                .name(subProject.getName())
                .path(subProject.getPath())
                .roots(List.of()) // Generic modules don't have Android-specific source roots
                .dependencies(List.of()) // Generic modules don't resolve dependencies in this context
                .build();
    }

}