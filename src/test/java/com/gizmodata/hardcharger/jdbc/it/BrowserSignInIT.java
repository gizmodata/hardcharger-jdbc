package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Browser sign-in through {@code jdbc:hardcharger://}: the Chargewire driver's {@code authType}
 * ({@code oauth_browser}, alias {@code external}), {@code oauthBrowser} and {@code oauthTimeout}
 * reach it unchanged, from the {@code Properties} and from the URL, against a hardcharger-server
 * whose identity provider is {@link MockIdentityProvider}. The "browser" is {@code curl}, which
 * follows the provider's redirect back to the server's callback, as a browser does once the person
 * has signed in.
 */
class BrowserSignInIT {
  private static final String IDENTITY = "alice@example.com";

  private static MockIdentityProvider idp;
  private static HardchargerServer server;
  private static Path browser;
  private static Path idleBrowser;

  @BeforeAll
  static void start() throws Exception {
    idp = new MockIdentityProvider();
    server =
        HardchargerServer.start(
            HardchargerServer.freePort(),
            false,
            Map.of("HARDCHARGER_OAUTH_CLIENT_SECRET", idp.clientSecret),
            "--token-allowed-issuer",
            idp.issuer(),
            "--token-allowed-audience",
            idp.clientId,
            "--token-default-role",
            "admin",
            "--token-authorized-emails",
            IDENTITY,
            "--oauth-client-id",
            idp.clientId,
            "--oauth-base-url",
            "http://127.0.0.1:{port}",
            "--oauth-provider-name",
            "Mock");
    browser = script("#!/bin/sh\nexec curl -s -L -o /dev/null \"$1\"\n");
    // Opens nothing: the person never signs in.
    idleBrowser = script("#!/bin/sh\nexit 0\n");
  }

  private static Path script(String text) throws Exception {
    Path path = Files.createTempFile("hardcharger-jdbc-browser", ".sh");
    Files.writeString(path, text);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    return path;
  }

  @AfterAll
  static void stop() throws Exception {
    if (server != null) {
      server.close();
    }
    if (idp != null) {
      idp.close();
    }
    for (Path path : new Path[] {browser, idleBrowser}) {
      if (path != null) {
        Files.deleteIfExists(path);
      }
    }
  }

  @AfterEach
  void reset() {
    idp.email = IDENTITY;
    idp.deny = false;
  }

  private static Properties browserSignIn(String authType, Path command, int timeoutSeconds) {
    Properties properties = new Properties();
    properties.setProperty("useEncryption", "false");
    properties.setProperty("authType", authType);
    properties.setProperty("oauthBrowser", command.toString());
    properties.setProperty("oauthTimeout", Integer.toString(timeoutSeconds));
    return properties;
  }

  private static String signedInUser(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT HARDCHARGER_USER()")) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  @Test
  void signsInThroughTheBrowserWithEitherName() throws SQLException {
    for (String authType : List.of("oauth_browser", "external")) {
      try (Connection connection =
          DriverManager.getConnection(server.url(""), browserSignIn(authType, browser, 30))) {
        assertEquals(IDENTITY, signedInUser(connection), authType);
      }
    }
  }

  @Test
  void theSettingsAlsoWorkInTheUrl() throws SQLException {
    String options =
        "useEncryption=false&authType=external&oauthTimeout=30&oauthBrowser="
            + URLEncoder.encode(browser.toString(), StandardCharsets.UTF_8);
    try (Connection connection = DriverManager.getConnection(server.url(options))) {
      assertEquals(IDENTITY, signedInUser(connection));
    }
  }

  @Test
  void aRefusalAtTheProviderIsAnAuthorizationError() {
    idp.deny = true;
    SQLException e =
        assertThrows(
            SQLInvalidAuthorizationSpecException.class,
            () ->
                DriverManager.getConnection(
                    server.url(""), browserSignIn("oauth_browser", browser, 30)));
    assertEquals("28000", e.getSQLState());
    assertTrue(e.getMessage().contains("access_denied"), e.getMessage());
  }

  @Test
  void aUserTheServerDoesNotAdmitIsRefused() {
    idp.email = "mallory@example.org";
    SQLException e =
        assertThrows(
            SQLInvalidAuthorizationSpecException.class,
            () ->
                DriverManager.getConnection(
                    server.url(""), browserSignIn("oauth_browser", browser, 30)));
    assertTrue(e.getMessage().contains("invalid_credentials"), e.getMessage());
  }

  /** {@code oauthTimeout} reaches the Chargewire driver: it gives up after that many seconds. */
  @Test
  void givesUpAfterTheTimeout() {
    long started = System.nanoTime();
    SQLException e =
        assertThrows(
            SQLTimeoutException.class,
            () ->
                DriverManager.getConnection(
                    server.url(""), browserSignIn("oauth_browser", idleBrowser, 2)));
    assertTrue(e.getMessage().contains("not completed within 2 s"), e.getMessage());
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(elapsedMillis >= 1_500 && elapsedMillis < 15_000, elapsedMillis + " ms");
  }

  @Test
  void aServerWithoutBrowserSignInSaysSo() {
    try (HardchargerServer plain = HardchargerServer.start()) {
      SQLException e =
          assertThrows(
              SQLInvalidAuthorizationSpecException.class,
              () ->
                  DriverManager.getConnection(
                      plain.url(""), browserSignIn("oauth_browser", browser, 30)));
      assertTrue(e.getMessage().contains("no capability oauth_browser"), e.getMessage());
    }
  }
}
