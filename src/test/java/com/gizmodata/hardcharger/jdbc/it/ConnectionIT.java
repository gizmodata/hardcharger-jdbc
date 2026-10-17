package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gizmodata.hardcharger.jdbc.HardchargerDriver;
import com.gizmodata.warpsql.jdbc.WarpConnection;
import com.gizmodata.warpsql.jdbc.WarpDriver;
import com.gizmodata.warpsql.jdbc.WarpProblem;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** jdbc:hardcharger:// against a real plain-HTTP hardcharger-server. */
class ConnectionIT {
  private static HardchargerServer server;

  @BeforeAll
  static void start() {
    server = HardchargerServer.start();
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(server.url(""), server.properties());
  }

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  @Test
  void connectsAndQueries() throws SQLException {
    try (Connection connection = connect()) {
      assertEquals("42", scalar(connection, "SELECT 42 AS answer"));
      assertEquals(HardchargerServer.USER, scalar(connection, "SELECT HARDCHARGER_USER()"));
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
  void urlOptionsWorkAsInWarpSql() throws SQLException {
    // useEncryption in the URL instead of the properties.
    Properties properties = server.properties();
    properties.remove("useEncryption");
    try (Connection connection =
        DriverManager.getConnection(server.url("useEncryption=false"), properties)) {
      assertEquals("1", scalar(connection, "SELECT 1"));
    }
  }

  @Test
  void theMetaDataNamesHardcharger() throws SQLException {
    try (Connection connection = connect()) {
      DatabaseMetaData metaData = connection.getMetaData();
      assertEquals("Hardcharger JDBC Driver", metaData.getDriverName());
      assertEquals(System.getProperty("project.version"), metaData.getDriverVersion());
      assertEquals(HardchargerDriver.version(), metaData.getDriverVersion());
      assertEquals(new HardchargerDriver().getMajorVersion(), metaData.getDriverMajorVersion());
      assertEquals(new HardchargerDriver().getMinorVersion(), metaData.getDriverMinorVersion());
      // The product is the server's own answer.
      assertEquals("Hardcharger", metaData.getDatabaseProductName());
      assertTrue(
          metaData.getDatabaseProductVersion().matches("\\d+\\.\\d+\\.\\d+.*"),
          metaData.getDatabaseProductVersion());
      assertEquals(server.url(""), metaData.getURL());
      assertSame(connection, metaData.getConnection());
      assertEquals(HardchargerServer.USER, metaData.getUserName());
      // The rest of the metadata works as the Warp SQL driver's.
      try (ResultSet catalogs = metaData.getCatalogs()) {
        assertTrue(catalogs.next());
      }
    }
  }

  @Test
  void theUrlInTheMetaDataHidesThePassword() throws SQLException {
    Properties properties = server.properties();
    properties.remove("password");
    String url = server.url("useEncryption=false&password=" + server.password.replace("@", "%40"));
    try (Connection connection = DriverManager.getConnection(url, properties)) {
      String reported = connection.getMetaData().getURL();
      assertEquals(server.url("useEncryption=false&password=***"), reported);
      assertFalse(reported.contains(server.password.split(":")[0]), reported);
    }
  }

  @Test
  void unwrapReachesTheWarpSqlConnection() throws SQLException {
    try (Connection connection = connect()) {
      assertTrue(connection.isWrapperFor(WarpConnection.class));
      WarpConnection warp = connection.unwrap(WarpConnection.class);
      assertNotNull(warp.getSessionId());
      assertSame(connection, connection.unwrap(Connection.class));
      assertTrue(connection.getMetaData().isWrapperFor(DatabaseMetaData.class));
    }
  }

  @Test
  void driverManagerPicksTheDriverByScheme() throws SQLException {
    assertInstanceOf(HardchargerDriver.class, DriverManager.getDriver(server.url("")));
    assertInstanceOf(
        WarpDriver.class, DriverManager.getDriver("jdbc:warp://127.0.0.1:" + server.port));
    try (Connection warp =
        DriverManager.getConnection("jdbc:warp://127.0.0.1:" + server.port, server.properties())) {
      assertEquals("Warp SQL JDBC Driver", warp.getMetaData().getDriverName());
      assertEquals("Hardcharger", warp.getMetaData().getDatabaseProductName());
    }
    try (Connection ours = connect()) {
      assertEquals("Hardcharger JDBC Driver", ours.getMetaData().getDriverName());
    }
  }

  @Test
  void theUrlPathNamesTheCatalog() throws SQLException {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:hardcharger://127.0.0.1:" + server.port + "/memory", server.properties())) {
      assertEquals("memory", connection.getCatalog());
    }
  }

  @Test
  void aMissingCatalogFailsToConnect() {
    SQLException error =
        assertThrows(
            SQLException.class,
            () ->
                DriverManager.getConnection(
                    "jdbc:hardcharger://127.0.0.1:" + server.port + "/no_such_catalog",
                    server.properties()));
    assertEquals("invalid_option_value", WarpProblem.codeOf(error), error.getMessage());
  }

  @Test
  void aWrongPasswordIsRefused() {
    for (String password :
        new String[] {server.password + "x", server.password.split(":")[0], ""}) {
      Properties properties = server.properties();
      properties.setProperty("password", password);
      SQLInvalidAuthorizationSpecException error =
          assertThrows(
              SQLInvalidAuthorizationSpecException.class,
              () -> DriverManager.getConnection(server.url(""), properties));
      assertEquals("28000", error.getSQLState());
      assertEquals("invalid_credentials", WarpProblem.codeOf(error));
    }
  }

  @Test
  void aMalformedOptionIsReportedWithTheHardchargerUrl() {
    SQLNonTransientConnectionException error =
        assertThrows(
            SQLNonTransientConnectionException.class,
            () -> DriverManager.getConnection(server.url("bogus"), server.properties()));
    assertEquals("08001", error.getSQLState());
    assertEquals(
        "Invalid Hardcharger URL '" + server.url("bogus") + "': option 'bogus' is not key=value",
        error.getMessage());
  }

  @Test
  void anUnknownOptionValueIsRefused() {
    SQLNonTransientConnectionException error =
        assertThrows(
            SQLNonTransientConnectionException.class,
            () ->
                DriverManager.getConnection(
                    server.url("resultOrder=sideways"), server.properties()));
    assertEquals("08001", error.getSQLState());
    assertEquals(
        "Invalid value 'sideways' for resultOrder: expected preserve or any", error.getMessage());
  }

  @Test
  void aValidOptionValueIsAccepted() throws SQLException {
    try (Connection connection =
        DriverManager.getConnection(server.url("resultOrder=any"), server.properties())) {
      assertEquals("7", scalar(connection, "SELECT 7"));
    }
  }

  @Test
  void hardchargerPlusHttpIsNotAScheme() {
    // Plain HTTP is useEncryption=false, as for jdbc:warp://.
    SQLException error =
        assertThrows(
            SQLException.class,
            () ->
                DriverManager.getConnection(
                    "jdbc:hardcharger+http://127.0.0.1:" + server.port, server.properties()));
    assertEquals("08001", error.getSQLState());
    assertTrue(error.getMessage().startsWith("No suitable driver"), error.getMessage());
  }
}
