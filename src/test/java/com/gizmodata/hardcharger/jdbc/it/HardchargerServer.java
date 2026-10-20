package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * A hardcharger-server started for the tests, with a fresh administrator password, its own empty
 * home directory and an environment of its own.
 *
 * <p>The binary comes from {@code HARDCHARGER_JDBC_TEST_SERVER_BINARY}. Without it every test that
 * needs a server fails: these tests gate releases, and a skipped gate looks like a passed one.
 */
final class HardchargerServer implements AutoCloseable {
  static final String USER = "hc_test";
  static final String BINARY_VARIABLE = "HARDCHARGER_JDBC_TEST_SERVER_BINARY";

  final int port;

  /**
   * Contains {@code :} and {@code @}: credentials travel as JSON fields, so nothing may split them.
   */
  final String password;

  final boolean tls;
  private final Process process;
  private final Path log;

  private HardchargerServer(int port, String password, boolean tls, Process process, Path log) {
    this.port = port;
    this.password = password;
    this.tls = tls;
    this.process = process;
    this.log = log;
  }

  static Path binary() {
    String configured = System.getenv(BINARY_VARIABLE);
    if (configured == null || configured.isEmpty()) {
      return fail(
          "No Hardcharger server to test against: set "
              + BINARY_VARIABLE
              + " to a hardcharger-server binary");
    }
    Path path = Path.of(configured);
    if (!Files.isExecutable(path)) {
      return fail(BINARY_VARIABLE + "=" + configured + " is not an executable file");
    }
    return path;
  }

  /** Starts a plain-HTTP server on a free port. */
  static HardchargerServer start(String... extraArgs) {
    return start(freePort(), false, extraArgs);
  }

  /**
   * Starts a server on {@code port}; with {@code tls}, the arguments must include the TLS flags.
   */
  static HardchargerServer start(int port, boolean tls, String... extraArgs) {
    return start(port, tls, Map.of(), extraArgs);
  }

  /**
   * Starts a server on {@code port} with more environment variables; {@code {port}} in an argument
   * becomes the server's port.
   */
  static HardchargerServer start(
      int port, boolean tls, Map<String, String> extraEnvironment, String... extraArgs) {
    Path binary = binary();
    byte[] random = new byte[12];
    new SecureRandom().nextBytes(random);
    String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random) + ":p@ss:word";
    try {
      Path home = Files.createTempDirectory("hardcharger-jdbc-server");
      Path log = home.resolve("server.log");
      List<String> command =
          new ArrayList<>(
              List.of(
                  binary.toString(),
                  "--host",
                  "127.0.0.1",
                  "--port",
                  Integer.toString(port),
                  "--health-port",
                  "0",
                  "--admin-username",
                  USER));
      for (String arg : extraArgs) {
        command.add(arg.replace("{port}", Integer.toString(port)));
      }
      ProcessBuilder builder =
          new ProcessBuilder(command)
              .directory(home.toFile())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile());
      // Only what the server needs: it reads HARDCHARGER_* settings from the environment, which a
      // developer's shell may export, and DuckDB reads secrets from the home directory.
      Map<String, String> environment = builder.environment();
      String path = environment.get("PATH");
      environment.clear();
      environment.put("PATH", path == null ? "/usr/bin:/bin" : path);
      environment.put("HOME", home.toString());
      environment.put("TMPDIR", home.toString());
      environment.put("HARDCHARGER_ADMIN_PASSWORD", password);
      environment.putAll(extraEnvironment);
      Process process = builder.start();
      HardchargerServer server = new HardchargerServer(port, password, tls, process, log);
      Runtime.getRuntime().addShutdownHook(new Thread(server::close));
      server.awaitUp();
      return server;
    } catch (IOException e) {
      return fail("Cannot start " + binary + ": " + e.getMessage(), e);
    }
  }

  static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      return fail("No free port: " + e.getMessage(), e);
    }
  }

  private void awaitUp() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    IOException last = null;
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        fail("The server exited with " + process.exitValue() + ": " + logText());
      }
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
        return;
      } catch (IOException e) {
        last = e;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("Interrupted while waiting for the server");
      }
    }
    close();
    fail("No server listened on port " + port + " (" + last + "): " + logText());
  }

  String logText() {
    try {
      return Files.readString(log);
    } catch (IOException e) {
      return "(no log: " + e.getMessage() + ")";
    }
  }

  /** {@code jdbc:hardcharger://127.0.0.1:port}, plus any URL options. */
  String url(String options) {
    String base = "jdbc:hardcharger://127.0.0.1:" + port;
    return options == null || options.isEmpty() ? base : base + "?" + options;
  }

  /** The user and password, plus plain HTTP for a server without TLS. */
  Properties properties() {
    Properties properties = new Properties();
    properties.setProperty("user", USER);
    properties.setProperty("password", password);
    if (!tls) {
      properties.setProperty("useEncryption", "false");
    }
    return properties;
  }

  @Override
  public void close() {
    if (process.isAlive()) {
      // The first SIGTERM drains, the second stops at once.
      process.destroy();
      process.destroy();
      try {
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
  }
}
