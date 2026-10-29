package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gizmodata.chargewire.jdbc.ChargewireDriver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What users had before the protocol was renamed Chargewire keeps working: the {@code jdbc:warp:}
 * URLs the standalone jar used to register a driver for, {@code basePath=/warp} in a {@code
 * jdbc:hardcharger:} URL, and servers released before the rename (Hardcharger 2.2.0 and earlier),
 * which answer only the former spelling.
 *
 * <p>The former server comes from {@code HARDCHARGER_JDBC_TEST_LEGACY_SERVER} ({@code host:port} of
 * a plain-HTTP server, e.g. the {@code ghcr.io/gizmodata/hardcharger:2.2.0} image) and {@code
 * HARDCHARGER_JDBC_TEST_LEGACY_SERVER_PASSWORD} (user {@code hardcharger}, the image's
 * administrator, unless {@code HARDCHARGER_JDBC_TEST_LEGACY_SERVER_USER} says otherwise). Without
 * them those tests fail: they gate releases.
 */
class LegacyCompatibilityIT {
  static final String LEGACY_SERVER_VARIABLE = "HARDCHARGER_JDBC_TEST_LEGACY_SERVER";
  static final String LEGACY_PASSWORD_VARIABLE = "HARDCHARGER_JDBC_TEST_LEGACY_SERVER_PASSWORD";
  static final String LEGACY_USER_VARIABLE = "HARDCHARGER_JDBC_TEST_LEGACY_SERVER_USER";

  private static HardchargerServer server;

  @BeforeAll
  static void start() {
    server = HardchargerServer.start();
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  /** {@code host:port} of the former server. */
  private static String legacyAddress() {
    String address = System.getenv(LEGACY_SERVER_VARIABLE);
    if (address == null || address.isEmpty()) {
      return fail(
          "No server from before the rename to test against: set "
              + LEGACY_SERVER_VARIABLE
              + " to host:port of one (ghcr.io/gizmodata/hardcharger:2.2.0) and "
              + LEGACY_PASSWORD_VARIABLE);
    }
    return address;
  }

  private static Properties legacyProperties() {
    String password = System.getenv(LEGACY_PASSWORD_VARIABLE);
    if (password == null || password.isEmpty()) {
      return fail(LEGACY_SERVER_VARIABLE + " is set, but " + LEGACY_PASSWORD_VARIABLE + " is not");
    }
    String user = System.getenv(LEGACY_USER_VARIABLE);
    Properties properties = new Properties();
    properties.setProperty("user", user == null || user.isEmpty() ? "hardcharger" : user);
    properties.setProperty("password", password);
    properties.setProperty("useEncryption", "false");
    return properties;
  }

  /**
   * Checks that the configured server is one from before the rename, which answers 404 at today's
   * root: against a current server these tests would pass without any fallback.
   */
  private static void assertLegacyServer() {
    URI discovery = URI.create("http://" + legacyAddress() + "/chargewire");
    try {
      HttpResponse<Void> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(discovery).GET().build(),
                  HttpResponse.BodyHandlers.discarding());
      assertEquals(
          404,
          response.statusCode(),
          LEGACY_SERVER_VARIABLE + " must name a server from before the rename: " + discovery);
    } catch (IOException e) {
      fail("No server answers at " + discovery + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Interrupted", e);
    }
  }

  private static Connection connectToLegacy(String url) throws SQLException {
    assertLegacyServer();
    try {
      return DriverManager.getConnection(url, legacyProperties());
    } catch (SQLException e) {
      throw new AssertionError(
          "Could not connect to the server from before the rename at "
              + legacyAddress()
              + " (the driver did not fall back to its spelling?): "
              + e.getMessage(),
          e);
    }
  }

  @Test
  void theFormerUrlPrefixReachesTheChargewireDriver() throws SQLException {
    String url = "jdbc:warp://127.0.0.1:" + server.port;
    assertInstanceOf(ChargewireDriver.class, DriverManager.getDriver(url));
    try (Connection connection = DriverManager.getConnection(url, server.properties())) {
      assertEquals("Chargewire JDBC Driver", connection.getMetaData().getDriverName());
      assertEquals("42", scalar(connection, "SELECT 42"));
    }
  }

  @Test
  void theFormerBasePathStillConnects() throws SQLException {
    try (Connection connection =
        DriverManager.getConnection(server.url("basePath=/warp"), server.properties())) {
      assertEquals("Hardcharger JDBC Driver", connection.getMetaData().getDriverName());
      assertEquals("42", scalar(connection, "SELECT 42"));
    }
  }

  @Test
  void aServerFromBeforeTheRenameAnswers() throws SQLException {
    String url = "jdbc:hardcharger://" + legacyAddress();
    try (Connection connection = connectToLegacy(url)) {
      DatabaseMetaData metaData = connection.getMetaData();
      assertEquals("Hardcharger JDBC Driver", metaData.getDriverName());
      assertEquals("Hardcharger", metaData.getDatabaseProductName());
      assertEquals("42", scalar(connection, "SELECT 42"));
      assertEquals("3", scalar(connection, "SELECT count(*) FROM (VALUES (1), (2), (3)) AS t (x)"));
      try (PreparedStatement statement = connection.prepareStatement("SELECT ? || ' world'")) {
        statement.setString(1, "hello");
        try (ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals("hello world", rows.getString(1));
        }
      }
    }
  }

  @Test
  void aServerFromBeforeTheRenameTakesWritesAndIndexes() throws SQLException {
    String url = "jdbc:hardcharger://" + legacyAddress();
    String table = "legacy_" + Long.toHexString(System.nanoTime());
    try (Connection connection = connectToLegacy(url);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + table + " (id INTEGER, name VARCHAR)");
      try {
        try (PreparedStatement insert =
            connection.prepareStatement("INSERT INTO " + table + " VALUES (?, ?)")) {
          for (int i = 1; i <= 3; i++) {
            insert.setInt(1, i);
            insert.setString(2, "row " + i);
            insert.addBatch();
          }
          // One parameter array: the server reports one total, so each row's count is unknown.
          int unknown = Statement.SUCCESS_NO_INFO;
          assertArrayEquals(new int[] {unknown, unknown, unknown}, insert.executeBatch());
        }
        statement.execute("CREATE INDEX " + table + "_id ON " + table + " (id)");
        List<String> names = new ArrayList<>();
        try (ResultSet rows =
            statement.executeQuery("SELECT name FROM " + table + " ORDER BY id")) {
          while (rows.next()) {
            names.add(rows.getString(1));
          }
        }
        assertEquals(List.of("row 1", "row 2", "row 3"), names);
        try (ResultSet indexes =
            connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
          assertTrue(indexes.next(), "the index on " + table);
          assertEquals(table + "_id", indexes.getString("INDEX_NAME"));
          assertEquals("id", indexes.getString("COLUMN_NAME"));
        }
      } finally {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  @Test
  void theFormerUrlPrefixReachesAServerFromBeforeTheRename() throws SQLException {
    try (Connection connection = connectToLegacy("jdbc:warp://" + legacyAddress())) {
      assertEquals("Chargewire JDBC Driver", connection.getMetaData().getDriverName());
      assertEquals("42", scalar(connection, "SELECT 42"));
    }
  }
}
