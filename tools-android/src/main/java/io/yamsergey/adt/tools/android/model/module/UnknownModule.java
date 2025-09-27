package io.yamsergey.adt.tools.android.model.module;

import lombok.Builder;

@Builder
public record UnknownModule(
    String name,
    String path) implements ResolvedModule, RawModule {

}
