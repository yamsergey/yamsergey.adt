package io.yamsergey.adt.tools.android.resolver;

import io.yamsergey.adt.tools.sugar.Result;

public interface Resolver<M> {

  Result<M> resolve();
}
