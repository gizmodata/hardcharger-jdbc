package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gizmodata.warpsql.jdbc.WarpStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The driver inherits warp-sql-jdbc's default of reading a large result in up to 32 partitions at
 * once (spec §6.10; the server sizes the split by the result), and a result read that way keeps its
 * rows and order.
 */
class PartitionsIT {
  private static HardchargerServer server;

  @BeforeAll
  static void start() {
    server = HardchargerServer.start();
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  private static int resultPartitions(String options) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(server.url(options), server.properties());
        Statement statement = connection.createStatement()) {
      return statement.unwrap(WarpStatement.class).getResultPartitions();
    }
  }

  @Test
  void resultsAreReadInThirtyTwoPartitionsByDefault() throws SQLException {
    assertEquals(32, resultPartitions(""));
  }

  @Test
  void onePartitionWhenAsked() throws SQLException {
    assertEquals(1, resultPartitions("resultPartitions=1"));
  }

  @Test
  void aLargeSplitResultKeepsEveryRowInOrder() throws SQLException {
    try (Connection connection = DriverManager.getConnection(server.url(""), server.properties());
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT i, i * 2 AS j FROM range(2000000) t(i) ORDER BY i")) {
      long expected = 0;
      while (rows.next()) {
        assertEquals(expected, rows.getLong(1));
        assertEquals(expected * 2, rows.getLong(2));
        expected++;
      }
      assertEquals(2_000_000, expected);
    }
  }
}
