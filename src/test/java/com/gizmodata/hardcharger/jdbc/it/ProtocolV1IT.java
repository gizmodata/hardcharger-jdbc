package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gizmodata.chargewire.jdbc.ChargewireConnection;
import com.gizmodata.chargewire.jdbc.ChargewireConnection.TransactionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Chargewire 1.0.0 through {@code jdbc:hardcharger://}, against a real hardcharger-server: what the
 * driver reads from the server's own documents.
 */
class ProtocolV1IT {
  /**
   * The session's transaction is the server's view (§5.2), so it sees SQL {@code BEGIN} and {@code
   * ROLLBACK}, which the driver does not track.
   */
  @Test
  void theSessionsTransactionIsTheServers() throws SQLException {
    try (HardchargerServer server = HardchargerServer.start();
        Connection connection = DriverManager.getConnection(server.url(""), server.properties());
        Statement statement = connection.createStatement()) {
      ChargewireConnection chargewire = connection.unwrap(ChargewireConnection.class);
      assertEquals(TransactionState.NONE, chargewire.getTransactionState());
      statement.execute("BEGIN TRANSACTION");
      assertEquals(TransactionState.OPEN, chargewire.getTransactionState());
      statement.execute("ROLLBACK");
      assertEquals(TransactionState.NONE, chargewire.getTransactionState());
    }
  }

  /**
   * {@code DatabaseMetaData.isReadOnly} is the server-info key {@code server_read_only} (§10): true
   * for a server started with {@code --read-only}, false (the twin) for the same database file
   * opened read-write.
   */
  @Test
  void isReadOnlyIsTheServers() throws SQLException, IOException {
    Path database = Files.createTempDirectory("hardcharger-jdbc-ro").resolve("read_only.duckdb");
    try (HardchargerServer server = HardchargerServer.start("--database", database.toString());
        Connection connection = DriverManager.getConnection(server.url(""), server.properties())) {
      assertFalse(connection.getMetaData().isReadOnly());
    }
    try (HardchargerServer server =
            HardchargerServer.start("--database", database.toString(), "--read-only");
        Connection connection = DriverManager.getConnection(server.url(""), server.properties())) {
      assertTrue(connection.getMetaData().isReadOnly());
    }
  }
}
