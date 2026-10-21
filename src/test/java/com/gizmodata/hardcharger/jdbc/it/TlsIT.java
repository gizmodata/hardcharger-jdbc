package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * TLS is on by default for jdbc:hardcharger://, as for jdbc:warp://. The certificates are
 * warp-sql-jdbc's throwaway test certificates (for 127.0.0.1 and localhost, signed by a test CA).
 */
class TlsIT {
  private static HardchargerServer server;

  static String cert(String name) {
    return Path.of("src", "test", "resources", "certs", name).toAbsolutePath().toString();
  }

  @BeforeAll
  static void start() {
    server =
        HardchargerServer.start(
            HardchargerServer.freePort(),
            true,
            "--tls-cert",
            cert("server.pem"),
            "--tls-key",
            cert("server-key.pem"));
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  @Test
  void connectsOverTlsByDefault() throws SQLException {
    Properties properties = server.properties();
    properties.setProperty("tlsRootCerts", cert("ca.pem"));
    try (Connection connection = DriverManager.getConnection(server.url(""), properties);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT 'over TLS'")) {
      assertTrue(rows.next());
      assertEquals("over TLS", rows.getString(1));
    }
  }

  @Test
  void explicitlyDisabledCertificateVerificationStillAuthenticatesAndReadsTables()
      throws SQLException {
    Properties properties = server.properties();
    properties.setProperty("disableCertificateVerification", "true");
    properties.setProperty("useSystemTrustStore", "false");
    try (Connection connection = DriverManager.getConnection(server.url(""), properties);
        ResultSet tables = connection.getMetaData().getTables(null, null, "%", null)) {
      while (tables.next()) {
        assertTrue(tables.getString("TABLE_NAME") != null);
      }
    }
  }

  @Test
  void anUntrustedCertificateFails() {
    Properties properties = server.properties();
    properties.setProperty("tlsRootCerts", cert("other-ca.pem"));
    properties.setProperty("useSystemTrustStore", "false");
    SQLNonTransientConnectionException error =
        assertThrows(
            SQLNonTransientConnectionException.class,
            () -> DriverManager.getConnection(server.url(""), properties));
    assertEquals("08001", error.getSQLState());
    assertTrue(error.getMessage().contains("TLS"), error.getMessage());
  }

  @Test
  void plainHttpToATlsServerFails() {
    Properties properties = server.properties();
    properties.setProperty("useEncryption", "false");
    SQLException error =
        assertThrows(
            SQLException.class, () -> DriverManager.getConnection(server.url(""), properties));
    assertTrue(
        error.getSQLState() != null && error.getSQLState().startsWith("08"),
        error.getSQLState() + " " + error.getMessage());
  }
}
