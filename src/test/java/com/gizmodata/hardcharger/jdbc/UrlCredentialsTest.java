package com.gizmodata.hardcharger.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * No part of a password or token written in a {@code jdbc:hardcharger://} URL is ever shown: not
 * when it holds characters that should have been percent-encoded, which is when the URL is refused
 * and its text is in the message. The Chargewire driver has the same test for its own URLs; this
 * one goes through this driver's rewriting of the URL and its restating of that driver's errors.
 */
class UrlCredentialsTest {
  /** The two halves of the password or token of every URL here; neither may ever be shown. */
  private static final String HEAD = "Zq7Kv";

  private static final String TAIL = "Xw9Jp";

  /** Characters a generated password holds and a URL gives a meaning to, or refuses. */
  private static final String[] SPECIAL = {
    "#", "&", ";", "?", "=", "/", "@", ":", "%", " ", "^", "|", "{", "}", "\"", "<", ">", "\\", "`",
    "[", "]", "+", "\t", "é", "%zz", "&x=", ";x=", "?x=", "#x=", "&&", "&=", "=&", "@/", "/@", "//"
  };

  /** Where a password or token is written; {@code $} is its place. */
  private static final String[] PLACES = {
    "jdbc:hardcharger://nohost.invalid:1/db?password=$",
    "jdbc:hardcharger://nohost.invalid/db?user=joe&password=$&useEncryption=false",
    "jdbc:hardcharger://nohost.invalid:1/db;user=joe;password=$",
    "jdbc:hardcharger://nohost.invalid:1/db?useEncryption=false;password=$;user=joe",
    "jdbc:hardcharger://nohost.invalid:1?token=$",
    "jdbc:hardcharger://nohost.invalid:1?authType=oauth_browser&subjectToken=$",
    "jdbc:hardcharger://nohost.invalid:1/db?trustStore=/nonexistent.p12&trustStorePassword=$",
    "jdbc:hardcharger://nohost.invalid:1/db?pasword=$",
    "jdbc:hardcharger://nohost.invalid:1/db?user=joe&Password=$&catalog=lake",
    "jdbc:hardcharger://joe:$@nohost.invalid:1/db",
    "jdbc:hardcharger://joe:$@nohost.invalid/db?useEncryption=false",
    "jdbc:hardcharger://$@nohost.invalid:1",
    "jdbc:hardcharger://[::1]:1/db?password=$",
  };

  /** Every place with every form of the password: whole, and with each character inside it. */
  private static List<String> urls() {
    List<String> urls = new ArrayList<>();
    for (String place : PLACES) {
      urls.add(place.replace("$", HEAD + TAIL));
      for (String special : SPECIAL) {
        urls.add(place.replace("$", HEAD + special + TAIL));
        urls.add(place.replace("$", special + HEAD + TAIL));
        urls.add(place.replace("$", HEAD + TAIL + special));
        urls.add(place.replace("$", HEAD + special + TAIL + special + HEAD));
      }
    }
    return urls;
  }

  private static void assertHidden(String shown, String url) {
    assertFalse(shown.contains(HEAD), () -> url + " is shown as " + shown);
    assertFalse(shown.contains(TAIL), () -> url + " is shown as " + shown);
  }

  /** Every message of a failure: its causes and the suppressed ones. */
  private static String messages(Throwable failure) {
    StringBuilder all = new StringBuilder();
    for (Throwable each = failure; each != null; each = each.getCause()) {
      all.append(each).append('\n');
      for (Throwable suppressed : each.getSuppressed()) {
        all.append(suppressed).append('\n');
      }
    }
    return all.toString();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = ' ',
      value = {
        "jdbc:hardcharger://h/db?user=joe&useEncryption=false"
            + " jdbc:hardcharger://h/db?user=joe&useEncryption=false",
        "jdbc:hardcharger://h/db?user=joe&password=p&useEncryption=false&sslmode=x&novalue"
            + " jdbc:hardcharger://h/db?user=joe&password=***&useEncryption=false&***&***",
        "jdbc:hardcharger://h/db?password=Zq7Kv#Xw9Jp jdbc:hardcharger://h/db?password=***#***",
        "jdbc:hardcharger://h/db?password=Zq7Kv&Xw9Jp jdbc:hardcharger://h/db?password=***&***",
        "jdbc:hardcharger://h/db;password=Zq7Kv;Xw9Jp=1 jdbc:hardcharger://h/db;password=***;***",
        "jdbc:hardcharger://joe:Zq7Kv@h/db?user=joe jdbc:hardcharger://***@h/db?user=joe",
        "jdbc:hardcharger://joe:Zq7Kv/Xw9Jp@h/db jdbc:hardcharger://***",
        "jdbc:hardcharger://joe:Zq7Kv?Xw9Jp@h/db jdbc:hardcharger://***",
      })
  void aUrlIsShownAsTheChargewireDriverShowsIts(String url, String shown) {
    assertEquals(shown, HardchargerUrl.redact(url));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        // This driver's own refusals.
        "jdbc:hardcharger://joe:Zq7KvXw9Jp@h/db"
            + "|Invalid Hardcharger URL 'jdbc:hardcharger://***@h/db': credentials do not belong"
            + " in the URL; use the user and password properties",
        "jdbc:hardcharger://joe:Zq7Kv/Xw9Jp@h/db"
            + "|Invalid Hardcharger URL 'jdbc:hardcharger://***': the port must be a number from"
            + " 1 to 65535",
        "jdbc:hardcharger://h:Zq7KvXw9Jp"
            + "|Invalid Hardcharger URL 'jdbc:hardcharger://***': the port must be a number from"
            + " 1 to 65535",
        // The Chargewire driver's, restated for the URL as it was given.
        "jdbc:hardcharger://h/db?password=Zq7Kv#Xw9Jp"
            + "|Invalid Hardcharger URL 'jdbc:hardcharger://h/db?password=***#***': it has a"
            + " fragment ('#'); percent-encode '#' in option values",
        "jdbc:hardcharger://h/db?password=Zq7Kv Xw9Jp"
            + "|Invalid Hardcharger URL 'jdbc:hardcharger://h/db?password=***': Illegal character"
            + " in query (in a value, every character but letters, digits and -._~ is"
            + " percent-encoded: a space is %20, a literal % is %25)",
        "jdbc:hardcharger://h/db?password=Zq7Kv&Xw9Jp"
            + "|Invalid Hardcharger URL 'jdbc:hardcharger://h/db?password=***&***': an option is"
            + " not key=value; an '&' or ';' inside a value is written %26 or %3B",
      })
  void aRefusedUrlShowsNoPartOfItsPassword(String url, String message) {
    SQLException error =
        assertThrows(
            SQLNonTransientConnectionException.class,
            () -> new HardchargerDriver().connect(url, new Properties()));
    assertEquals("08001", error.getSQLState());
    assertEquals(message, error.getMessage());
  }

  /**
   * Every place and form of the password through what this driver does with a URL before the
   * network: it rewrites the URL for the Chargewire driver, which reads it. Whatever is refused is
   * refused with a {@code SQLException}, and no message of it holds either half of the password.
   */
  @Test
  void noRefusalShowsAnyPartOfAPassword() {
    HardchargerDriver driver = new HardchargerDriver();
    Map<String, Integer> kinds = new TreeMap<>();
    for (String url : urls()) {
      assertHidden(HardchargerUrl.redact(url), url);
      try {
        driver.getPropertyInfo(url, new Properties());
        kinds.merge("read", 1, Integer::sum);
      } catch (SQLException e) {
        assertEquals("08001", e.getSQLState(), url);
        String messages = messages(e);
        assertHidden(messages, url);
        kinds.merge(messages.replaceAll("^[^:]*: |[':(\n].*", "").trim(), 1, Integer::sum);
      }
    }
    assertEquals(
        java.util.Set.of("read", "Invalid Hardcharger URL", "Invalid Chargewire URL"),
        kinds.keySet(),
        kinds.toString());
  }

  /**
   * The same through whole connection attempts, to a port nothing listens on, for the password as
   * it is and with a character in it that a URL takes in a value.
   */
  @Test
  void noFailureToConnectShowsAnyPartOfAPassword() throws Exception {
    int port;
    try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
      port = free.getLocalPort();
    }
    HardchargerDriver driver = new HardchargerDriver();
    Properties properties = new Properties();
    properties.setProperty("user", "joe");
    properties.setProperty("password", TAIL + HEAD + TAIL);
    properties.setProperty("connectTimeout", "2000");
    int unreachable = 0;
    for (String place : PLACES) {
      for (String password : List.of(HEAD + TAIL, HEAD + "@" + TAIL + "=" + HEAD)) {
        String url =
            place
                .replace("$", password)
                .replace("nohost.invalid:1", "127.0.0.1:" + port)
                .replace("nohost.invalid", "127.0.0.1:" + port)
                .replace("[::1]:1", "127.0.0.1:" + port);
        SQLException error =
            assertThrows(SQLException.class, () -> driver.connect(url, properties).close(), url);
        String messages = messages(error);
        assertHidden(messages, url);
        if (messages.contains("127.0.0.1:" + port)) {
          unreachable++;
        }
      }
    }
    assertTrue(unreachable >= 10, "connections that got as far as the network: " + unreachable);
  }
}
