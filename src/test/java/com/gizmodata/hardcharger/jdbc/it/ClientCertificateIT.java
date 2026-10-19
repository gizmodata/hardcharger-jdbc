package com.gizmodata.hardcharger.jdbc.it;

import static com.gizmodata.hardcharger.jdbc.it.TlsIT.cert;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gizmodata.warpsql.jdbc.WarpProblem;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Signing in with the mutual-TLS client certificate ({@code authType=client_certificate}, Warp SQL
 * §4.4) through {@code jdbc:hardcharger://}, against a server with {@code --mtls-ca-cert} and
 * {@code --mtls-certificate-sign-in}. The test client certificate's identity is its e-mail SAN,
 * {@code client@example.com}; the server is configured with {@value #IDENTITY}, which matches it
 * ignoring case, and is the session's user.
 */
class ClientCertificateIT {
  private static final String IDENTITY = "CLIENT@example.com";
  private static final Pattern TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

  private static HardchargerServer server;

  @BeforeAll
  static void start() {
    server = HardchargerServer.start(HardchargerServer.freePort(), true, mtls(true));
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  /** The TLS and client-CA flags, with certificate sign-in on or off. */
  private static String[] mtls(boolean signIn) {
    String[] tls = {
      "--tls-cert",
      cert("server.pem"),
      "--tls-key",
      cert("server-key.pem"),
      "--mtls-ca-cert",
      cert("ca.pem")
    };
    if (!signIn) {
      return tls;
    }
    String[] args = java.util.Arrays.copyOf(tls, tls.length + 3);
    args[tls.length] = "--mtls-certificate-sign-in";
    args[tls.length + 1] = "--mtls-admin-identities";
    args[tls.length + 2] = IDENTITY;
    return args;
  }

  /** TLS trusting the test CA and presenting {@code client} (its file name prefix). */
  private static Properties presenting(String client) {
    Properties properties = new Properties();
    properties.setProperty("tlsRootCerts", cert("ca.pem"));
    properties.setProperty("clientCertificate", cert(client + ".pem"));
    properties.setProperty("clientKey", cert(client + "-key.pem"));
    return properties;
  }

  private static Properties signingInAs(String client) {
    Properties properties = presenting(client);
    properties.setProperty("authType", "client_certificate");
    return properties;
  }

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  @Test
  void signsInWithTheCertificateAlone() throws SQLException {
    for (String authType : new String[] {"client_certificate", "mtls"}) {
      Properties properties = presenting("client");
      properties.setProperty("authType", authType);
      try (Connection connection = DriverManager.getConnection(server.url(""), properties)) {
        assertEquals(IDENTITY, scalar(connection, "SELECT HARDCHARGER_USER()"));
        assertEquals(
            "client_certificate",
            scalar(connection, "SELECT auth_method FROM hardcharger_sessions() WHERE is_current"));
      }
    }
  }

  @Test
  void theUrlCanCarryEverything() throws SQLException {
    // The same options in the URL, as the hardcharger client's authType=mtls.
    String url =
        server.url(
            "authType=mtls&tlsRootCerts="
                + cert("ca.pem")
                + "&clientCertificate="
                + cert("client.pem")
                + "&clientKey="
                + cert("client-key.pem"));
    try (Connection connection = DriverManager.getConnection(url, new Properties())) {
      assertEquals(IDENTITY, scalar(connection, "SELECT HARDCHARGER_USER()"));
      assertEquals(url, connection.getMetaData().getURL());
    }
  }

  @Test
  void aSplitResultIsReadWithTheBoundToken() throws SQLException {
    // Every partition reader opens its own connection, and each presents the certificate.
    try (Connection connection =
        DriverManager.getConnection(server.url("resultPartitions=4"), signingInAs("client"))) {
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT i FROM range(1000000) t(i) ORDER BY i")) {
        long count = 0;
        long sum = 0;
        while (rows.next()) {
          assertEquals(count, rows.getLong(1));
          count++;
          sum += rows.getLong(1);
        }
        assertEquals(1_000_000, count);
        assertEquals(1_000_000L * 999_999 / 2, sum);
      }
    }
  }

  @Test
  void anIdentityTheServerDoesNotKnowIsRefused() throws SQLException {
    SQLInvalidAuthorizationSpecException error =
        assertThrows(
            SQLInvalidAuthorizationSpecException.class,
            () -> DriverManager.getConnection(server.url(""), signingInAs("other-client")));
    assertEquals("28000", error.getSQLState());
    assertEquals("unknown_certificate_identity", WarpProblem.codeOf(error));
    assertTrue(error.getMessage().contains("other@example.com"), error.getMessage());
    // The twin: a known identity from the same CA signs in.
    try (Connection connection =
        DriverManager.getConnection(server.url(""), signingInAs("client"))) {
      assertEquals("1", scalar(connection, "SELECT 1"));
    }
  }

  @Test
  void noCertificateIsCertificateRequired() throws SQLException {
    // The server refuses the handshake (TLS alert certificate_required): that is the refusal
    // certificate_required (it was client_certificate_required), not an unreachable server.
    Properties none = new Properties();
    none.setProperty("tlsRootCerts", cert("ca.pem"));
    none.setProperty("user", HardchargerServer.USER);
    none.setProperty("password", server.password);
    SQLInvalidAuthorizationSpecException error =
        assertThrows(
            SQLInvalidAuthorizationSpecException.class,
            () -> DriverManager.getConnection(server.url(""), none));
    assertEquals("28000", error.getSQLState());
    assertEquals("certificate_required", WarpProblem.codeOf(error));
    // The twin: the same password sign-in over a certificate.
    Properties presented = presenting("client");
    presented.setProperty("user", HardchargerServer.USER);
    presented.setProperty("password", server.password);
    try (Connection connection = DriverManager.getConnection(server.url(""), presented)) {
      assertEquals(HardchargerServer.USER, scalar(connection, "SELECT HARDCHARGER_USER()"));
    }
  }

  @Test
  void aPasswordBesideTheCertificateIsRefused() {
    Properties properties = signingInAs("client");
    properties.setProperty("user", HardchargerServer.USER);
    properties.setProperty("password", server.password);
    SQLException error =
        assertThrows(
            SQLException.class, () -> DriverManager.getConnection(server.url(""), properties));
    assertEquals("28000", error.getSQLState());
    assertTrue(error.getMessage().contains("client_certificate"), error.getMessage());
    assertTrue(error.getMessage().contains("user"), error.getMessage());
  }

  @Test
  void aBoundTokenFailsOverAnotherCertificate() throws Exception {
    String token = certificateToken("client.p12");
    Properties other = presenting("other-client");
    other.setProperty("token", token);
    SQLInvalidAuthorizationSpecException error =
        assertThrows(
            SQLInvalidAuthorizationSpecException.class,
            () -> DriverManager.getConnection(server.url(""), other));
    assertEquals("certificate_mismatch", WarpProblem.codeOf(error));
    // The twin: the same token over the certificate it is bound to.
    Properties same = presenting("client");
    same.setProperty("token", token);
    try (Connection connection = DriverManager.getConnection(server.url(""), same)) {
      assertEquals(IDENTITY, scalar(connection, "SELECT HARDCHARGER_USER()"));
    }
  }

  @Test
  void aServerWithCertificateSignInOffIsRefusedClearly() throws SQLException {
    // --mtls-ca-cert alone: the certificate admits the connection and signs no one in.
    try (HardchargerServer off =
        HardchargerServer.start(HardchargerServer.freePort(), true, mtls(false))) {
      SQLInvalidAuthorizationSpecException error =
          assertThrows(
              SQLInvalidAuthorizationSpecException.class,
              () -> DriverManager.getConnection(off.url(""), signingInAs("client")));
      assertTrue(error.getMessage().contains("mtls_identity"), error.getMessage());
      // The twin: the same server and certificate with the password.
      Properties password = presenting("client");
      password.setProperty("user", HardchargerServer.USER);
      password.setProperty("password", off.password);
      try (Connection connection = DriverManager.getConnection(off.url(""), password)) {
        assertEquals(HardchargerServer.USER, scalar(connection, "SELECT HARDCHARGER_USER()"));
      }
    }
  }

  /** A token from the client_certificate grant, over a client key store's certificate. */
  private static String certificateToken(String keyStore) throws Exception {
    char[] password = "changeit".toCharArray();
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(Path.of(cert(keyStore)))) {
      keys.load(in, password);
    }
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keys, password);
    KeyStore trusted = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(Path.of(cert("truststore.p12")))) {
      trusted.load(in, password);
    }
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trusted);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
    HttpClient http = HttpClient.newBuilder().sslContext(context).build();
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("https://127.0.0.1:" + server.port + "/warp/v1/auth/token"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString("{\"grant_type\":\"client_certificate\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher matcher = TOKEN.matcher(response.body());
    assertTrue(matcher.find(), response.statusCode() + " " + response.body());
    return matcher.group(1);
  }
}
