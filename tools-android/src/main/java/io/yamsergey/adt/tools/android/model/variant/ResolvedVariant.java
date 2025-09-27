package io.yamsergey.adt.tools.android.model.variant;

import java.util.Collection;

import io.yamsergey.adt.tools.android.model.SourceRoot;
import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import lombok.Builder;

@Builder
public record ResolvedVariant(
    Collection<Dependency> dependencies,
    Collection<SourceRoot> generatedRoots) {
}
