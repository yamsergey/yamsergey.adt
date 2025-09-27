package io.yamsergey.adt.tools.android.model.project;

import io.yamsergey.adt.tools.android.model.module.RawModule;
import lombok.Builder;

/**
 * Represents raw structure of the prject.
 **/
@Builder
public record RawProject(
    RawModule module) {
}
