package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Regression cases from the GizmoSQL DBeaver driver review (dbeaver/dbeaver#40508). */
class DBeaverCompatibilityIT {
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

  @Test
  void missingAndIncorrectCredentialsFailBeforeMetadataReads() {
    List<Properties> attempts = new ArrayList<>();
    Properties noCredentials = server.properties();
    noCredentials.remove("user");
    noCredentials.remove("password");
    attempts.add(noCredentials);
    for (String key : List.of("user", "password")) {
      Properties missing = server.properties();
      missing.remove(key);
      attempts.add(missing);
      Properties empty = server.properties();
      empty.setProperty(key, "");
      attempts.add(empty);
    }
    Properties wrongUser = server.properties();
    wrongUser.setProperty("user", "hardcharger");
    attempts.add(wrongUser);
    Properties wrongPassword = server.properties();
    wrongPassword.setProperty("password", server.password + "wrong");
    attempts.add(wrongPassword);
    for (Properties properties : attempts) {
      SQLException error =
          assertThrows(
              SQLException.class, () -> DriverManager.getConnection(server.url(""), properties));
      assertEquals("28000", error.getSQLState(), error.getMessage());
      assertFalse(error.getMessage().contains("Invalid Authorization Header"));
    }
  }

  @Test
  void plainHttpAuthenticatesAndReadsMetadataWithEitherCertificateVerificationSetting()
      throws SQLException {
    for (String flag : List.of("false", "true")) {
      Properties properties = server.properties();
      properties.setProperty("disableCertificateVerification", flag);
      try (Connection connection = DriverManager.getConnection(server.url(""), properties);
          Statement statement = connection.createStatement()) {
        try (ResultSet rows = statement.executeQuery("SELECT HARDCHARGER_USER()")) {
          assertTrue(rows.next());
          assertEquals(HardchargerServer.USER, rows.getString(1));
        }
        try (ResultSet rows = connection.getMetaData().getTables(null, "main", "%", null)) {
          while (rows.next()) {
            assertNotNull(rows.getString("TABLE_NAME"));
          }
        }
      }
    }
  }

  @Test
  void primaryKeysAllowEditingAndDmlReportsUpdateCounts() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE pk_qa(id INTEGER PRIMARY KEY, name VARCHAR)");
      try (ResultSet keys =
          connection.getMetaData().getPrimaryKeys(connection.getCatalog(), "main", "pk_qa")) {
        assertTrue(keys.next());
        assertEquals("id", keys.getString("COLUMN_NAME"));
        assertEquals(1, keys.getShort("KEY_SEQ"));
        assertFalse(keys.next());
      }
      assertFalse(statement.execute("INSERT INTO pk_qa VALUES (1, 'before')"));
      assertEquals(1, statement.getUpdateCount());
      try (PreparedStatement update =
          connection.prepareStatement("UPDATE pk_qa SET name = ? WHERE id = ?")) {
        update.setString(1, "after");
        update.setInt(2, 1);
        assertEquals(1, update.executeUpdate());
        update.setInt(2, 2);
        assertEquals(0, update.executeUpdate());
      }
      assertFalse(statement.execute("DELETE FROM pk_qa WHERE id = 1"));
      assertEquals(1, statement.getLargeUpdateCount());
      assertFalse(statement.getMoreResults());
      assertEquals(-1, statement.getUpdateCount());
    }
  }

  @Test
  void decimalTimeBooleanAndBinaryEditsKeepTheConnectionUsable() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE types_qa(id INTEGER PRIMARY KEY, amount DECIMAL(38,10), t TIME, b BOOLEAN, data BLOB)");
      BigDecimal value = new BigDecimal("1234567890123456789012345678.1234567890");
      try (PreparedStatement insert =
          connection.prepareStatement("INSERT INTO types_qa VALUES (?, ?, ?, ?, ?)")) {
        insert.setInt(1, 1);
        insert.setBigDecimal(2, value);
        insert.setTime(3, Time.valueOf("12:34:56"));
        insert.setBoolean(4, true);
        insert.setBytes(5, new byte[] {0, 1, -1});
        assertEquals(1, insert.executeUpdate());
      }
      try (ResultSet rows =
          statement.executeQuery("SELECT amount,t,b,data FROM types_qa WHERE id=1")) {
        assertTrue(rows.next());
        assertEquals(value, rows.getBigDecimal(1));
        assertEquals(Time.valueOf("12:34:56"), rows.getTime(2));
        assertTrue(rows.getBoolean(3));
        assertEquals(3, rows.getBytes(4).length);
      }
      try (PreparedStatement update =
          connection.prepareStatement("UPDATE types_qa SET amount=?, t=?, b=? WHERE id=?")) {
        update.setObject(1, new BigDecimal("0.0000000001"), Types.DECIMAL);
        update.setTime(2, Time.valueOf("23:59:59"));
        update.setObject(3, false, Types.BIT);
        update.setInt(4, 1);
        assertEquals(1, update.executeUpdate());
      }
      try (ResultSet rows = statement.executeQuery("SELECT amount,t,b FROM types_qa WHERE id=1")) {
        assertTrue(rows.next());
        assertEquals(new BigDecimal("0.0000000001"), rows.getBigDecimal(1));
        assertEquals(Time.valueOf("23:59:59"), rows.getTime(2));
        assertFalse(rows.getBoolean(3));
      }
    }
  }

  @Test
  void indexesRemainVisibleAfterRefreshAndCanBeDropped() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE indexes_qa(id INTEGER PRIMARY KEY, name VARCHAR, \"comma,a\" INTEGER, \"quote\"\"col\" INTEGER)");
      statement.execute("CREATE INDEX idx_qa ON memory.main.indexes_qa(name, id)");
      statement.execute(
          "CREATE UNIQUE INDEX idx_unique_qa ON indexes_qa(\"comma,a\", \"quote\"\"col\")");
      DatabaseMetaData metadata = connection.getMetaData();
      for (int refresh = 0; refresh < 2; refresh++) {
        List<String> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getIndexInfo("memory", "main", "indexes_qa", false, true)) {
          assertEquals(13, rows.getMetaData().getColumnCount());
          while (rows.next()) {
            assertEquals("memory", rows.getString("TABLE_CAT"));
            assertEquals("main", rows.getString("TABLE_SCHEM"));
            assertEquals("indexes_qa", rows.getString("TABLE_NAME"));
            assertEquals(DatabaseMetaData.tableIndexOther, rows.getShort("TYPE"));
            columns.add(
                rows.getString("INDEX_NAME")
                    + ":"
                    + rows.getInt("ORDINAL_POSITION")
                    + ":"
                    + rows.getString("COLUMN_NAME"));
          }
        }
        assertEquals(
            List.of(
                "idx_unique_qa:1:comma,a",
                "idx_unique_qa:2:quote\"col",
                "idx_qa:1:name",
                "idx_qa:2:id"),
            columns);
      }
      try (ResultSet rows = metadata.getIndexInfo(null, null, "indexes_qa", true, false)) {
        assertTrue(rows.next());
        assertEquals("idx_unique_qa", rows.getString("INDEX_NAME"));
        assertFalse(rows.getBoolean("NON_UNIQUE"));
        assertTrue(rows.next());
        assertFalse(rows.next());
      }
      for (String[] filter :
          new String[][] {
            {"other", "main", "indexes_qa"},
            {"memory", "other", "indexes_qa"},
            {"memory", "main", "other"}
          }) {
        try (ResultSet rows = metadata.getIndexInfo(filter[0], filter[1], filter[2], false, true)) {
          assertFalse(rows.next());
        }
      }
      ResultSet rows = metadata.getIndexInfo("memory", "main", "indexes_qa", false, true);
      Statement metadataStatement = rows.getStatement();
      assertNotNull(metadataStatement);
      rows.close();
      assertTrue(metadataStatement.isClosed());
      try (ResultSet all = metadata.getIndexInfo("memory", "main", null, false, true)) {
        assertTrue(all.next(), "DBeaver's schema-wide index refresh must also find the indexes");
      }
      statement.execute("DROP INDEX memory.main.idx_qa");
      try (ResultSet remaining =
          metadata.getIndexInfo("memory", "main", "indexes_qa", false, true)) {
        assertTrue(remaining.next());
        assertEquals("idx_unique_qa", remaining.getString("INDEX_NAME"));
      }
    }
  }

  @Test
  void indexFiltersTreatNamesLiterallyAndKeepSchemasSeparate() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA index_schema_qa");
      statement.execute("CREATE TABLE main.\"index_%_qa\"(id INTEGER)");
      statement.execute("CREATE TABLE index_schema_qa.\"index_%_qa\"(id INTEGER)");
      statement.execute("CREATE INDEX idx_main_qa ON main.\"index_%_qa\"(id)");
      statement.execute("CREATE INDEX idx_schema_qa ON index_schema_qa.\"index_%_qa\"(id)");
      for (String schema : List.of("main", "index_schema_qa")) {
        try (ResultSet indexes =
            connection.getMetaData().getIndexInfo("memory", schema, "index_%_qa", false, false)) {
          assertTrue(indexes.next());
          assertEquals(schema, indexes.getString("TABLE_SCHEM"));
          assertEquals("index_%_qa", indexes.getString("TABLE_NAME"));
          assertFalse(indexes.next());
        }
      }
      try (ResultSet indexes =
          connection
              .getMetaData()
              .getIndexInfo("memory", "main", "index_' OR true --", false, true)) {
        assertFalse(indexes.next());
      }
    }
  }
}
