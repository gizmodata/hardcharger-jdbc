package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gizmodata.chargewire.jdbc.ChargewireConnection;
import com.gizmodata.chargewire.jdbc.ChargewireDriver;
import com.gizmodata.chargewire.jdbc.ChargewireProblem;
import com.gizmodata.hardcharger.jdbc.HardchargerDriver;
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
  void urlOptionsWorkAsInChargewire() throws SQLException {
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
      // The rest of the metadata works as the Chargewire driver's.
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
  void unwrapReachesTheChargewireConnection() throws SQLException {
    try (Connection connection = connect()) {
      assertTrue(connection.isWrapperFor(ChargewireConnection.class));
      ChargewireConnection chargewire = connection.unwrap(ChargewireConnection.class);
      assertNotNull(chargewire.getSessionId());
      assertSame(connection, connection.unwrap(Connection.class));
      assertTrue(connection.getMetaData().isWrapperFor(DatabaseMetaData.class));
    }
  }

  @Test
  void driverManagerPicksTheDriverByScheme() throws SQLException {
    assertInstanceOf(HardchargerDriver.class, DriverManager.getDriver(server.url("")));
    assertInstanceOf(
        ChargewireDriver.class,
        DriverManager.getDriver("jdbc:chargewire://127.0.0.1:" + server.port));
    try (Connection chargewire =
        DriverManager.getConnection(
            "jdbc:chargewire://127.0.0.1:" + server.port, server.properties())) {
      assertEquals("Chargewire JDBC Driver", chargewire.getMetaData().getDriverName());
      assertEquals("Hardcharger", chargewire.getMetaData().getDatabaseProductName());
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
    assertEquals("invalid_option_value", ChargewireProblem.codeOf(error), error.getMessage());
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
      assertEquals("invalid_credentials", ChargewireProblem.codeOf(error));
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

  /**
   * A key that is no connection property is never ignored silently (Chargewire driver 2.3.0):
   * refused in the URL, and in the Properties when it resembles a property or names TLS or signing
   * in; anything else in the Properties connects, with a warning. No message shows a value, or the
   * {@code jdbc:chargewire://} URL this driver makes of the application's.
   */
  @Test
  void anUnknownOptionIsRefusedOrReported() throws SQLException {
    SQLNonTransientConnectionException inUrl =
        assertThrows(
            SQLNonTransientConnectionException.class,
            () ->
                DriverManager.getConnection(
                    server.url("resultPartition=4&pasword=s3cr3t-value"), server.properties()));
    assertEquals("08001", inUrl.getSQLState());
    assertEquals(
        "Unknown connection property 'resultPartition' in the URL: did you mean"
            + " 'resultPartitions'?",
        inUrl.getMessage());

    Properties misspelled = server.properties();
    misspelled.setProperty("tlsRootCert", "/etc/ssl/s3cr3t-value.pem");
    SQLNonTransientConnectionException inProperties =
        assertThrows(
            SQLNonTransientConnectionException.class,
            () -> DriverManager.getConnection(server.url(""), misspelled));
    assertEquals("08001", inProperties.getSQLState());
    assertEquals(
        "Unknown connection property 'tlsRootCert' in the connection's Properties: did you mean"
            + " 'tlsRootCerts'? A key that resembles one of the driver's is refused rather than"
            + " ignored",
        inProperties.getMessage());

    Properties anotherDrivers = server.properties();
    anotherDrivers.setProperty("sslmode", "verify-full");
    SQLNonTransientConnectionException security =
        assertThrows(
            SQLNonTransientConnectionException.class,
            () -> DriverManager.getConnection(server.url(""), anotherDrivers));
    assertTrue(security.getMessage().contains("it names TLS or signing in"), security.getMessage());
    for (SQLException error : new SQLException[] {inUrl, inProperties, security}) {
      assertFalse(error.getMessage().contains("s3cr3t"), error.getMessage());
      assertFalse(error.getMessage().contains("verify-full"), error.getMessage());
      assertFalse(error.getMessage().contains("jdbc:chargewire"), error.getMessage());
    }

    // What a framework puts in the Properties for itself (Spark's read options, here).
    Properties foreign = server.properties();
    foreign.setProperty("dbtable", "t");
    foreign.setProperty("fetchsize", "1000");
    try (Connection connection = DriverManager.getConnection(server.url(""), foreign)) {
      assertEquals("42", scalar(connection, "SELECT 42"));
      java.sql.SQLWarning warning = connection.getWarnings();
      assertNotNull(warning);
      assertEquals("01S00", warning.getSQLState());
      assertEquals(
          "The connection property 'dbtable' is not one of this driver's and was ignored"
              + " (ChargewireProperty lists them)",
          warning.getMessage());
      assertTrue(warning.getNextWarning().getMessage().contains("'fetchsize'"));
    }
    try (Connection connection = connect()) {
      assertEquals(null, connection.getWarnings(), "the driver's own properties raise none");
    }
  }

  /**
   * {@code setReadOnly(true)} is a hint: the session still writes, and since the Chargewire driver
   * 2.3.0 the connection says so in a warning, once. A read-only transaction begun in SQL is what
   * the server enforces.
   */
  @Test
  void setReadOnlyIsAHintAndSaysSo() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      connection.setReadOnly(true);
      assertTrue(connection.isReadOnly());
      java.sql.SQLWarning warning = connection.getWarnings();
      assertNotNull(warning);
      assertEquals("01000", warning.getSQLState());
      assertTrue(
          warning.getMessage().startsWith("setReadOnly(true) is a hint this driver cannot enforce"),
          warning.getMessage());
      statement.execute("CREATE OR REPLACE TABLE read_only_hint (i INTEGER)");
      assertEquals(1, statement.executeUpdate("INSERT INTO read_only_hint VALUES (1)"));
      connection.setReadOnly(true);
      assertEquals(null, connection.getWarnings().getNextWarning(), "said once");
      statement.execute("BEGIN TRANSACTION READ ONLY");
      SQLException refused =
          assertThrows(
              SQLException.class,
              () -> statement.executeUpdate("INSERT INTO read_only_hint VALUES (2)"));
      assertTrue(
          refused.getMessage().contains("transaction is launched in read-only mode"),
          refused.getMessage());
      statement.execute("ROLLBACK");
      assertEquals("1", scalar(connection, "SELECT count(*) FROM read_only_hint"));
      statement.execute("DROP TABLE read_only_hint");
    }
  }

  /**
   * A catalog and a schema given together both land, on every connect: the schema is one of that
   * catalog, so the catalog must be applied first, in whatever order they were written.
   */
  @Test
  void aCatalogAndASchemaGivenTogetherBothLandOnEveryConnect() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute("ATTACH IF NOT EXISTS ':memory:' AS together");
      statement.execute("CREATE SCHEMA IF NOT EXISTS together.reports");
    }
    String base = "jdbc:hardcharger://127.0.0.1:" + server.port;
    Properties schemaFirst = server.properties();
    schemaFirst.setProperty("schema", "reports");
    schemaFirst.setProperty("catalog", "together");
    Object[][] forms = {
      {base + "/together?schema=reports", server.properties()},
      {base + "?schema=reports&catalog=together", server.properties()},
      {base + ";schema=reports;catalog=together", server.properties()},
      {base, schemaFirst},
    };
    for (Object[] form : forms) {
      for (int attempt = 0; attempt < 40; attempt++) {
        try (Connection connection =
            DriverManager.getConnection((String) form[0], (Properties) form[1])) {
          assertEquals(
              "together.reports",
              scalar(connection, "SELECT current_catalog() || '.' || current_schema()"),
              form[0] + ", connect " + attempt);
        }
      }
    }
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
    // Plain HTTP is useEncryption=false, as for jdbc:chargewire://.
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
