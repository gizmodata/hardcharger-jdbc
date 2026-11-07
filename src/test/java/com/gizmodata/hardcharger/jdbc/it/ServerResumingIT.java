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

  @Test
  void aStoppedClusterIsConnectedWithoutSigningInAndTheFirstStatementSetsUpTheSession()
      throws Exception {
    standIn.reportStopped(true);
    standIn.resume(TOKEN, 2, "1", Duration.ZERO);
    Properties properties = server.properties();
    // The connection's setup: the session opens in this schema.
    properties.setProperty("schema", "main");
    try (Connection connection = DriverManager.getConnection(standIn.url(""), properties)) {
      // A sign-in or a session would start the cluster, and a pool opens connections nobody uses.
      assertEquals(List.of("GET /chargewire"), standIn.lines(), "connect read discovery only");
      assertTrue(connection.isValid(5));
      assertEquals(List.of("GET /chargewire"), standIn.lines(), "the check sent nothing");
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT current_schema()")) {
        assertTrue(rows.next());
        assertEquals("main", rows.getString(1), "the session was set up first");
      }
    }
    List<String> lines = standIn.lines();
    int firstToken = lines.indexOf(TOKEN);
    int session = lines.indexOf("POST /chargewire/v1/sessions");
    int statement = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).matches("POST /chargewire/v1/sessions/[^/]+/statements")) {
        statement = i;
        break;
      }
    }
    assertEquals(1, firstToken, "the first statement signed in first: " + lines);
    assertEquals(3, standIn.seen(TOKEN).size(), "through the resume: " + lines);
    assertTrue(session > firstToken + 2, "then opened the session: " + lines);
    assertTrue(standIn.seen("POST /chargewire/v1/sessions").get(0).body().contains("\"main\""));
    assertTrue(statement > session, "then ran the statement: " + lines);
  }

  @Test
  void aStoppedClustersRefusedSetupFailsAtTheFirstStatementAsAtConnect() throws Exception {
    Properties properties = server.properties();
    properties.setProperty("schema", "no_such_schema_here");
    SQLException atConnect =
        assertThrows(
            SQLException.class, () -> DriverManager.getConnection(standIn.url(""), properties));
    standIn.reportStopped(true);
    int before = standIn.lines().size();
    try (Connection connection = DriverManager.getConnection(standIn.url(""), properties);
        Statement statement = connection.createStatement()) {
      assertEquals(
          List.of("GET /chargewire"),
          standIn.lines().subList(before, standIn.lines().size()),
          "connect read discovery only");
      SQLException atFirstStatement =
          assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1"));
      assertEquals(atConnect.getSQLState(), atFirstStatement.getSQLState());
      assertEquals(ChargewireProblem.codeOf(atConnect), ChargewireProblem.codeOf(atFirstStatement));
    }
  }

  @Test
  void afterAStopANewConnectionSignsInAgainAndAnOldOneIsRefused() throws Exception {
    try (Connection old = DriverManager.getConnection(standIn.url(""), server.properties());
        Statement before = old.createStatement()) {
      try (ResultSet rows = before.executeQuery("SELECT 1")) {
        assertTrue(rows.next());
      }
      // A person stops the cluster: every token issued so far is revoked, and discovery says so.
      standIn.revokeTokensSeen();
      standIn.reportStopped(true);
      int signIns = standIn.seen(TOKEN).size();
      try (Connection fresh = DriverManager.getConnection(standIn.url(""), server.properties());
          Statement after = fresh.createStatement();
          ResultSet rows = after.executeQuery("SELECT 'new' AS connection")) {
        // The new connection signed in itself at its first statement: no token carried over.
        assertTrue(rows.next());
        assertEquals("new", rows.getString(1));
      }
      assertEquals(signIns + 1, standIn.seen(TOKEN).size(), "the new connection signed in");
      SQLException refused =
          assertThrows(SQLException.class, () -> before.executeQuery("SELECT 1"));
      assertEquals("token_revoked", ChargewireProblem.of(refused).reason());
      assertTrue(
          refused.getMessage().contains(ResumingStandIn.STOPPED_DETAIL), refused.getMessage());
      assertEquals(signIns + 1, standIn.seen(TOKEN).size(), "the old one did not sign in again");
    }
  }

  private static void assertResuming(SQLException error) {
    assertInstanceOf(SQLTransientConnectionException.class, error);
    assertEquals("08001", error.getSQLState());
    assertEquals("server_resuming", ChargewireProblem.codeOf(error));
    assertTrue(error.getMessage().contains("the server is resuming"), error.getMessage());
  }
}
