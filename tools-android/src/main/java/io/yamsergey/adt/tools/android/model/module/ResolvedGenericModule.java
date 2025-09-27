package io.yamsergey.adt.tools.android.model.module;

import java.util.Collection;

import io.yamsergey.adt.tools.android.model.SourceRoot;
import io.yamsergey.adt.tools.android.model.dependency.Dependency;
import lombok.Builder;

@Builder(toBuilder = true)
public record ResolvedGenericModule(
    String name,
    String path,
    Collection<SourceRoot> roots,
    Collection<Dependency> dependencies) implements ResolvedModule {
}
