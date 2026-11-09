package com.github.catvod.utils;

import com.yingxia.tv.*;
import com.yingxia.tv.runtime.ScriptRuntime;
import java.util.concurrent.*;

/** The new Spider.js contract, backed by the same native engine as JS subscription sources. */
public final class Js implements AutoCloseable {
  private ScriptRuntime runtime;
  private boolean closed;
  private String owner = "jar-js";

  public synchronized void scope(String sourceId) {
    if (runtime != null) throw new IllegalStateException("Script runtime is already initialized");
    owner = sourceId;
  }

  public String eval(String source) throws Exception {
    return eval(source, "{}");
  }

  public synchronized String eval(String source, String options) throws Exception {
    if (closed) throw new IllegalStateException("Script owner closed");
    if (runtime == null) runtime = new ScriptRuntime(YingxiaApp.get(), owner, "https://localhost/");
    Object result = runtime.call(source);
    return result == null ? "null" : result.toString();
  }

  @androidx.annotation.RequiresApi(24)
  public CompletableFuture<String> evalAsync(String source, String options) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return eval(source, options);
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        Task.executor());
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (runtime != null) runtime.close();
    runtime = null;
  }
}
