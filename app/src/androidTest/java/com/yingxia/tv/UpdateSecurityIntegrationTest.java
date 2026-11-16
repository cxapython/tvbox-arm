package com.yingxia.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import androidx.core.content.FileProvider;
import com.yingxia.tv.core.AppUpdate;
import com.yingxia.tv.data.AppUpdates;
import java.io.*;
import java.util.Map;
import org.json.JSONObject;

/** Real PackageManager/certificate and content URI checks; the inert fixture is NEVER installed. */
@SuppressWarnings("deprecation")
public final class UpdateSecurityIntegrationTest extends InstrumentationTestCase {
  private Context context;
  private AppUpdates updates;
  private AppUpdate manifest;
  private Map<String, ?> preferences;
  private File backup;

  @Override protected void setUp() throws Exception {
    super.setUp();
    context = getInstrumentation().getTargetContext();
    updates = new AppUpdates(context);
    SharedPreferences prefs = context.getSharedPreferences("app_updates", 0);
    preferences = prefs.getAll();
    prefs.edit().clear().putBoolean(AppUpdates.AUTO_CHECK, false).commit();
    File file = updates.readyFile();
    file.getParentFile().mkdirs();
    backup = new File(file.getParentFile(), "previous-test-backup.apk");
    if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot preserve existing update");
    manifest = new AppUpdate(new JSONObject(new String(asset("update-fixture.json"), java.nio.charset.StandardCharsets.UTF_8)));
    try (FileOutputStream out = new FileOutputStream(file)) { out.write(asset("update-fixture.apk")); }
  }
  @Override protected void tearDown() throws Exception {
    updates.readyFile().delete();
    if (backup.exists() && !backup.renameTo(updates.readyFile())) throw new IOException("Cannot restore previous update");
    SharedPreferences.Editor editor = context.getSharedPreferences("app_updates", 0).edit().clear();
    for (Map.Entry<String, ?> entry : preferences.entrySet()) {
      Object value = entry.getValue(); String key = entry.getKey();
      if (value instanceof String) editor.putString(key, (String) value);
      else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
      else if (value instanceof Long) editor.putLong(key, (Long) value);
      else if (value instanceof Integer) editor.putInt(key, (Integer) value);
    }
    editor.commit();
    updates.worker.shutdownNow();
    super.tearDown();
  }
  private byte[] asset(String name) throws Exception {
    try (InputStream in = getInstrumentation().getContext().getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[8192]; int count;
      while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
      return out.toByteArray();
    }
  }
  // The TV emulator is ARM64-only. Exercise real archive validation independently,
  // without claiming this emulator can install/run the production ARM32-only package.
  private void verifyArchive(AppUpdate update) throws Exception {
    java.lang.reflect.Method method = AppUpdates.class.getDeclaredMethod("verifyArchive", AppUpdate.class);
    method.setAccessible(true);
    try { method.invoke(updates, update); }
    catch (java.lang.reflect.InvocationTargetException error) { throw (Exception) error.getCause(); }
  }
  public void testDeviceAbiGateIsEnforcedBeforeInstall() throws Exception {
    if (java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("armeabi-v7a")) {
      updates.verify(manifest);
    } else {
      try { updates.verify(manifest); fail("Unsupported ARM32 installation allowed"); }
      catch (IOException expected) { assertTrue(expected.getMessage().contains("ARM32")); }
    }
  }
  public void testValidSameSignerNewerArm32ArchivePasses() throws Exception {
    verifyArchive(manifest);
  }
  public void testTamperedSameSizeArchiveIsRejected() throws Exception {
    try (RandomAccessFile file = new RandomAccessFile(updates.readyFile(), "rw")) {
      file.seek(file.length() - 1); int last = file.read(); file.seek(file.length() - 1); file.write(last ^ 1);
    }
    try { verifyArchive(manifest); fail("Tampered APK accepted"); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("SHA-256")); }
  }
  public void testWrongSignerRejectedBeforeInstallation() throws Exception {
    AppUpdate wrong = new AppUpdate(manifest.json().put("certificateSha256", "a".repeat(64)));
    try { verifyArchive(wrong); fail("Wrong signer accepted"); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("签名")); }
  }
  public void testFalseVersionMetadataRejectedEvenWithCorrectHash() throws Exception {
    AppUpdate wrong = new AppUpdate(manifest.json().put("versionCode", manifest.versionCode + 1));
    try { verifyArchive(wrong); fail("Wrong version accepted"); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("版本")); }
  }
  public void testFileProviderSharesOnlyTheUpdateDirectory() throws Exception {
    Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", updates.readyFile());
    assertEquals("content", uri.getScheme());
    try (InputStream in = context.getContentResolver().openInputStream(uri)) { assertNotNull(in); assertEquals('P', in.read()); }
    try {
      FileProvider.getUriForFile(context, context.getPackageName() + ".updates", new File(context.getFilesDir(), "library.json"));
      fail("Library outside update directory was shareable");
    } catch (IllegalArgumentException expected) { /* protected */ }
  }
  public void testReadyUpdateScreenHasInstallAndBackControls() throws Exception {
    context.getSharedPreferences("app_updates", 0).edit().putString("ready", manifest.json().toString()).commit();
    android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(
        com.yingxia.tv.ui.UpdateActivity.class.getName(), null, false);
    android.app.Activity activity = null;
    try {
      getInstrumentation().runOnMainSync(() -> context.startActivity(new android.content.Intent(context,
          com.yingxia.tv.ui.UpdateActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)));
      activity = monitor.waitForActivityWithTimeout(10000); assertNotNull(activity);
      android.app.Activity target = activity;
      getInstrumentation().waitForIdleSync();
      getInstrumentation().runOnMainSync(() -> {
        assertTrue(hasText(target.getWindow().getDecorView(), "安装更新"));
        assertTrue(hasText(target.getWindow().getDecorView(), "返回"));
      });
      android.os.SystemClock.sleep(800); // Wait for the system activity transition, not just UI idleness.
      android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
      if (screenshot != null) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "update-screen.png"))) {
          screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        screenshot.recycle();
      }
    } finally {
      if (activity != null) { android.app.Activity target = activity; getInstrumentation().runOnMainSync(target::finish); }
      getInstrumentation().removeMonitor(monitor);
    }
  }
  private boolean hasText(android.view.View view, String wanted) {
    if (view instanceof android.widget.TextView && wanted.contentEquals(((android.widget.TextView) view).getText())) return true;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), wanted)) return true;
    }
    return false;
  }
  public void testAutomaticCheckIsThrottledAndCanBeDisabled() {
    assertFalse(updates.shouldAutoCheck());
    updates.setAutoCheck(true);
    assertTrue(updates.shouldAutoCheck());
    assertFalse(updates.shouldAutoCheck());
    updates.defer(manifest); assertTrue(updates.deferred(manifest));
  }
}
