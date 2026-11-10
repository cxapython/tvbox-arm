package com.yingxia.tv.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public final class LibraryBackupTest {
  @Test
  public void cloudLocationsSurviveButAccountsDoNot() throws Exception {
    JSONObject server =
        Json.obj(
            "id", "server-id",
            "kind", "alist",
            "name", "NAS",
            "address", "https://nas.example/alist",
            "root", "/movies",
            "folderPassword", "folder-secret",
            "username", "account",
            "password", "account-secret",
            "access_token", "access-token",
            "refreshToken", "refresh-token",
            "authorization", "Bearer token",
            "cookie", "session=secret");
    JSONObject history =
        Json.obj(
            "id", "cloud:server-id:/movies/a.mp4",
            "url", "https://cdn.example/signed",
            "header", Json.obj("Authorization", "Bearer token"),
            "cloudServer", server,
            "cloudFile", Json.obj("id", "/movies/a.mp4", "name", "a.mp4"));
    JSONObject state =
        Json.obj(
            "servers", Json.array(java.util.Collections.singletonList(server)),
            "history", Json.array(java.util.Collections.singletonList(history)),
            "favorites", Json.array(java.util.Collections.singletonList(history)),
            "downloads", Json.array(java.util.Collections.singletonList(history)),
            "subscriptions", Json.array(java.util.Collections.singletonList(Json.obj("id", "sub"))));

    JSONObject exported = LibraryBackup.withoutAccounts(state);

    assertFalse(exported.has("servers"));
    assertEquals(1, exported.getJSONArray("subscriptions").length());
    for (String section : new String[] {"history", "favorites", "downloads"}) {
      JSONObject item = exported.getJSONArray(section).getJSONObject(0);
      JSONObject clean = item.getJSONObject("cloudServer");
      assertEquals("server-id", clean.getString("id"));
      assertEquals("alist", clean.getString("kind"));
      assertEquals("https://nas.example/alist", clean.getString("address"));
      assertEquals("/movies", clean.getString("root"));
      assertFalse(clean.has("folderPassword"));
      assertEquals("NAS", clean.getString("name"));
      for (String credential :
          new String[] {
            "username", "password", "folderPassword", "access_token", "refreshToken", "authorization", "cookie"
          }) assertFalse(section + " retained " + credential, clean.has(credential));
      assertFalse(item.has("header"));
      assertFalse(item.has("url"));
      assertEquals("/movies/a.mp4", item.getJSONObject("cloudFile").getString("id"));
    }

    assertEquals("account", state.getJSONArray("history").getJSONObject(0)
        .getJSONObject("cloudServer").getString("username"));
    assertTrue(state.getJSONArray("history").getJSONObject(0).has("url"));
  }

  @Test
  public void ordinaryItemsKeepUrlsAndAllFolderPasswordSpellingsAreRedacted() throws Exception {
    JSONObject state =
        Json.obj(
            "history",
            Json.array(
                java.util.Collections.singletonList(
                    Json.obj(
                        "id", "plain",
                        "url", "https://media.example/file.mp4",
                        "cloudServer",
                        Json.obj("id", "s", "folder-password", "keep", "folderPassword", "also-keep")))));

    JSONObject item = LibraryBackup.withoutAccounts(state).getJSONArray("history").getJSONObject(0);

    assertEquals("https://media.example/file.mp4", item.getString("url"));
    assertFalse(item.getJSONObject("cloudServer").has("folder-password"));
    assertFalse(item.getJSONObject("cloudServer").has("folderPassword"));
  }
}
