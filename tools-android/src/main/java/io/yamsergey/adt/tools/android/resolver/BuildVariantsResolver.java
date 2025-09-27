package io.yamsergey.adt.tools.android.resolver;

import java.util.Collection;

import com.android.builder.model.v2.ide.Variant;
import com.android.builder.model.v2.models.AndroidProject;

import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.sugar.Result;

/**
 * Resolve build variants for Android Project.
 */
public class BuildVariantsResolver implements Resolver<Collection<BuildVariant>> {

  private final AndroidProject androidProject;

  public BuildVariantsResolver(AndroidProject androidProject) {
    this.androidProject = androidProject;
  }

  public Result<Collection<BuildVariant>> resolve() {
    return Result.<Collection<BuildVariant>>success().value(
        androidProject.getVariants()
            .stream()
            .map(variant -> BuildVariant.builder()
                .name(variant.getName())
                .displayName(variant.getDisplayName())
                .isDefault(isDefault(variant)).build())
            .toList())
        .description(
            String.format("Successfully resolved build variants for Android Project: %s",
                androidProject.getNamespace()))
        .build().asResult();
  }

  private boolean isDefault(Variant variant) {
    return variant.getName().toLowerCase().contains("debug");
  }
}
