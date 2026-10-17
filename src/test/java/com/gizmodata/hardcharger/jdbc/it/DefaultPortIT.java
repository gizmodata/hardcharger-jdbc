package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A URL without a port reaches Hardcharger's port, 31337. This needs 31337 free on 127.0.0.1: the
 * test fails, rather than skips, when something else holds it.
 */
class DefaultPortIT {
  private static HardchargerServer server;

  @BeforeAll
  static void start() {
    try (ServerSocket probe = new ServerSocket(31337, 1, InetAddress.getByName("127.0.0.1"))) {
      probe.setReuseAddress(true);
    } catch (IOException e) {
      fail("Port 31337 on 127.0.0.1 is in use; the default-port test needs it free: " + e);
    }
    server = HardchargerServer.start(31337, false);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void aUrlWithoutAPortUses31337() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection("jdbc:hardcharger://127.0.0.1", server.properties());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT 'default port'")) {
      assertTrue(rows.next());
      assertEquals("default port", rows.getString(1));
      assertEquals("jdbc:hardcharger://127.0.0.1", connection.getMetaData().getURL());
    }
  }

  @Test
  void theSameUrlWithTheWarpSchemeDoesNot() {
    // jdbc:warp:// without a port means 80 over plain HTTP, where no server listens.
    SQLException error =
        assertThrows(
            SQLException.class,
            () -> DriverManager.getConnection("jdbc:warp://127.0.0.1", server.properties()));
    assertTrue(
        error.getSQLState() != null && error.getSQLState().startsWith("08"),
        error.getSQLState() + " " + error.getMessage());
  }
}
