package com.yingxia.tv;

import android.app.Application;
import com.yingxia.tv.data.*;
import java.util.concurrent.*;

public final class YingxiaApp extends Application {
  private static YingxiaApp app;
  public Http http;
  public Repository repository;
  public SpiderEngine engine;
  public final ExecutorService io =
      Executors.newFixedThreadPool(4, r -> new Thread(r, "yingxia-io"));

  public static YingxiaApp get() {
    return app;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    app = this;
    http = new Http(this);
    repository = new Repository(this);
    engine = new SpiderEngine(this);
  }
}
