package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gizmodata.chargewire.jdbc.ChargewireProblem;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A stopped GizmoData Cloud cluster with auto-resume: a stand-in at the server's address answers
 * {@code 503 server_resuming} with {@code Retry-After: 1} while the server starts. Through {@code
 * jdbc:hardcharger://}, the Chargewire driver waits and signs in again, and {@code resumeTimeout}
 * (in the URL or the properties) reaches it.
 */
class ServerResumingIT {
  private static final String TOKEN = "POST /chargewire/v1/auth/token";

  private static HardchargerServer server;
  private ResumingStandIn standIn;

  @BeforeAll
  static void startServer() {
    server = HardchargerServer.start();
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @BeforeEach
  void startStandIn() throws Exception {
    standIn = new ResumingStandIn("http://127.0.0.1:" + server.port);
  }

  @AfterEach
  void stopStandIn() {
    standIn.close();
  }

  @Test
  void theSignInWaitsForTheServerToResume() throws Exception {
    standIn.resume(TOKEN, 2, "1", Duration.ZERO);
    long started = System.nanoTime();
    try (Connection connection = DriverManager.getConnection(standIn.url(""), server.properties());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT 'resumed' AS state")) {
      assertTrue(rows.next());
      assertEquals("resumed", rows.getString(1));
    }
    long waited = Duration.ofNanos(System.nanoTime() - started).toMillis();
    assertTrue(waited >= 2_000, "waited " + waited + " ms");
    List<ResumingStandIn.Seen> tokens = standIn.seen(TOKEN);
    assertEquals(3, tokens.size());
    assertEquals(tokens.get(0).body(), tokens.get(2).body(), "the same sign-in, sent again");
  }

  @Test
  void resumeTimeoutInTheUrlReachesTheDriver() throws Exception {
    standIn.resume(TOKEN, 1, "1", Duration.ZERO);
    SQLException error =
        assertThrows(
            SQLException.class,
            () -> DriverManager.getConnection(standIn.url("resumeTimeout=0"), server.properties()));
    assertResuming(error);
    assertEquals(1, standIn.seen(TOKEN).size(), "resumeTimeout=0: no second sign-in");
  }

  @Test
  void resumeTimeoutInThePropertiesReachesTheDriver() throws Exception {
    standIn.resume(TOKEN, 1, "1", Duration.ZERO);
    Properties properties = server.properties();
    properties.setProperty("resumeTimeout", "0");
    SQLException error =
        assertThrows(
            SQLException.class, () -> DriverManager.getConnection(standIn.url(""), properties));
    assertResuming(error);
    assertEquals(1, standIn.seen(TOKEN).size(), "resumeTimeout=0: no second sign-in");
  }

  private static void assertResuming(SQLException error) {
    assertInstanceOf(SQLTransientConnectionException.class, error);
    assertEquals("08001", error.getSQLState());
    assertEquals("server_resuming", ChargewireProblem.codeOf(error));
    assertTrue(error.getMessage().contains("the server is resuming"), error.getMessage());
  }
}
