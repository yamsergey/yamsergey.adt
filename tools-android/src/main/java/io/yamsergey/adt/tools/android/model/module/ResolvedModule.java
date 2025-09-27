package io.yamsergey.adt.tools.android.model.module;

public sealed interface ResolvedModule
    permits ResolvedAndroidModule, ResolvedGenericModule, UnknownModule, FailedModule {
}
