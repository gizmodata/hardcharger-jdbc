package com.gizmodata.hardcharger.jdbc;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;

/**
 * A {@code jdbc:hardcharger://host[:port][/catalog][?key=value&...][;key=value;...]} URL, and the
 * {@code jdbc:chargewire://} URL the Chargewire driver is given for it.
 *
 * <p>Everything but the scheme and the port is the Chargewire driver's URL syntax, passed on as it
 * is: the same options, the same TLS default ({@code useEncryption=false} for plain HTTP). The port
 * defaults to {@value #DEFAULT_PORT}, Hardcharger's, where a {@code jdbc:chargewire://} URL without
 * one means 443 or 80.
 */
final class HardchargerUrl {
  /** The URL prefix this driver accepts (case-insensitive). */
  static final String PREFIX = "jdbc:hardcharger:";

  /** Hardcharger's Chargewire port, used when the URL names none. */
  static final int DEFAULT_PORT = 31337;

  private static final String CHARGEWIRE_PREFIX = "jdbc:chargewire:";

  private HardchargerUrl() {}

  static boolean accepts(String url) {
    return url != null && url.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
  }

  /**
   * Rewrites a {@code jdbc:hardcharger://} URL to the {@code jdbc:chargewire://} URL with the same
   * meaning, with the port made explicit.
   *
   * @param url a URL this driver accepts
   * @return the Chargewire URL
   * @throws SQLException (SQLState 08001) if the URL has no {@code //}, no host or a bad port
   */
  static String toChargewire(String url) throws SQLException {
    if (!accepts(url)) {
      throw invalid(url, "it must start with " + PREFIX + "//");
    }
    String rest = url.substring(PREFIX.length());
    if (!rest.startsWith("//")) {
      throw invalid(url, "it must start with " + PREFIX + "//");
    }
    rest = rest.substring(2);
    int end = rest.length();
    for (char c : new char[] {'/', '?', ';', '#'}) {
      int at = rest.indexOf(c);
      if (at >= 0 && at < end) {
        end = at;
      }
    }
    String authority = rest.substring(0, end);
    String tail = rest.substring(end);
    if (authority.contains("@")) {
      throw invalid(
          url, "credentials do not belong in the URL; use the user and password properties");
    }
    String host;
    String port;
    if (authority.startsWith("[")) {
      int close = authority.indexOf(']');
      if (close < 0) {
        throw invalid(url, "an IPv6 address must end with ']'");
      }
      host = authority.substring(0, close + 1);
      String after = authority.substring(close + 1);
      if (!after.isEmpty() && !after.startsWith(":")) {
        throw invalid(url, "unexpected text after the IPv6 address");
      }
      port = after.isEmpty() ? "" : after.substring(1);
    } else {
      int colon = authority.lastIndexOf(':');
      host = colon < 0 ? authority : authority.substring(0, colon);
      port = colon < 0 ? "" : authority.substring(colon + 1);
    }
    if (host.isEmpty() || "[]".equals(host)) {
      throw invalid(url, "it has no host");
    }
    if (port.isEmpty()) {
      port = Integer.toString(DEFAULT_PORT);
    } else if (!port.chars().allMatch(Character::isDigit)
        || port.length() > 5
        || Integer.parseInt(port) < 1
        || Integer.parseInt(port) > 65535) {
      throw invalid(url, "the port must be a number from 1 to 65535, not '" + port + "'");
    }
    return CHARGEWIRE_PREFIX + "//" + host + ":" + port + tail;
  }

  /** Hides password and token values, so a URL can appear in messages and logs. */
  static String redact(String url) {
    if (url == null) {
      return null;
    }
    return url.replaceAll(
        "(?i)((?:password|token|subjecttoken|truststorepassword)=)[^&;]*", "$1***");
  }

  /**
   * Restates a Chargewire driver error about the rewritten URL in terms of the URL the application
   * gave.
   */
  static SQLException restate(SQLException error, String url, String chargewireUrl) {
    String message = error.getMessage();
    if (message == null || !message.startsWith("Invalid Chargewire URL")) {
      return error;
    }
    String restated =
        message
            .replace(redact(chargewireUrl), redact(url))
            .replaceFirst("^Invalid Chargewire URL", "Invalid Hardcharger URL");
    return new SQLNonTransientConnectionException(
        restated, error.getSQLState(), error.getErrorCode(), error);
  }

  private static SQLException invalid(String url, String why) {
    return new SQLNonTransientConnectionException(
        "Invalid Hardcharger URL '" + redact(url) + "': " + why, "08001");
  }
}
