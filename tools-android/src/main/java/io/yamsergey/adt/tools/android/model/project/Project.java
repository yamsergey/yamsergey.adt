package io.yamsergey.adt.tools.android.model.project;

import java.util.Collection;
import io.yamsergey.adt.tools.android.model.module.ResolvedModule;

import lombok.Builder;

@Builder(toBuilder = true)
public record Project(
    String path,
    String name,
    Collection<ResolvedModule> modules) {
}
