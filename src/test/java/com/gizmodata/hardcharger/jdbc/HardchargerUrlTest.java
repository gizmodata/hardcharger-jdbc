package com.gizmodata.hardcharger.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import org.junit.jupiter.api.Test;

class HardchargerUrlTest {
  @Test
  void anOmittedPortIsHardchargers() throws SQLException {
    assertEquals(
        "jdbc:chargewire://db.example:31337",
        HardchargerUrl.toChargewire("jdbc:hardcharger://db.example"));
    assertEquals(
        "jdbc:chargewire://db.example:31337/my?useEncryption=false",
        HardchargerUrl.toChargewire("jdbc:hardcharger://db.example/my?useEncryption=false"));
    assertEquals(
        "jdbc:chargewire://db.example:31337;user=joe",
        HardchargerUrl.toChargewire("jdbc:hardcharger://db.example;user=joe"));
    assertEquals(
        "jdbc:chargewire://[::1]:31337", HardchargerUrl.toChargewire("jdbc:hardcharger://[::1]"));
  }

  @Test
  void anExplicitPortIsKept() throws SQLException {
    assertEquals(
        "jdbc:chargewire://db.example:443",
        HardchargerUrl.toChargewire("jdbc:hardcharger://db.example:443"));
    assertEquals(
        "jdbc:chargewire://127.0.0.1:8080/cat?resultPartitions=4&useEncryption=false",
        HardchargerUrl.toChargewire(
            "jdbc:hardcharger://127.0.0.1:8080/cat?resultPartitions=4&useEncryption=false"));
    assertEquals(
        "jdbc:chargewire://[::1]:9000/",
        HardchargerUrl.toChargewire("jdbc:hardcharger://[::1]:9000/"));
  }

  @Test
  void thePrefixIsCaseInsensitive() throws SQLException {
    assertTrue(HardchargerUrl.accepts("JDBC:Hardcharger://h"));
    assertEquals("jdbc:chargewire://h:31337", HardchargerUrl.toChargewire("JDBC:Hardcharger://h"));
  }

  @Test
  void otherSchemesAreNotAccepted() {
    assertFalse(HardchargerUrl.accepts("jdbc:chargewire://h:31337"));
    assertFalse(HardchargerUrl.accepts("jdbc:hardcharger+http://h"));
    assertFalse(HardchargerUrl.accepts("jdbc:gizmosql://h"));
    assertFalse(HardchargerUrl.accepts(null));
  }

  private static void assertInvalid(String url, String why) {
    SQLNonTransientConnectionException error =
        assertThrows(
            SQLNonTransientConnectionException.class, () -> HardchargerUrl.toChargewire(url));
    assertEquals("08001", error.getSQLState());
    assertTrue(error.getMessage().startsWith("Invalid Hardcharger URL '"), error.getMessage());
    assertTrue(error.getMessage().endsWith(why), error.getMessage());
  }

  @Test
  void malformedUrlsAreRefusedWithTheReason() {
    assertInvalid("jdbc:hardcharger:h:31337", "it must start with jdbc:hardcharger://");
    assertInvalid("jdbc:hardcharger://", "it has no host");
    assertInvalid("jdbc:hardcharger://:31337", "it has no host");
    assertInvalid(
        "jdbc:hardcharger://h:abc", "the port must be a number from 1 to 65535, not 'abc'");
    assertInvalid(
        "jdbc:hardcharger://h:70000", "the port must be a number from 1 to 65535, not '70000'");
    assertInvalid("jdbc:hardcharger://h:0", "the port must be a number from 1 to 65535, not '0'");
    assertInvalid("jdbc:hardcharger://[::1", "an IPv6 address must end with ']'");
    assertInvalid(
        "jdbc:hardcharger://joe:secret@h",
        "credentials do not belong in the URL; use the user and password properties");
  }

  @Test
  void errorsNeverShowAPassword() {
    SQLException error =
        assertThrows(
            SQLException.class,
            () -> HardchargerUrl.toChargewire("jdbc:hardcharger://h:x?password=hunter2"));
    assertFalse(error.getMessage().contains("hunter2"), error.getMessage());
    assertTrue(error.getMessage().contains("password=***"), error.getMessage());
  }

  @Test
  void redactHidesSecretsOnly() {
    assertEquals(
        "jdbc:hardcharger://h?user=joe&password=***;token=***",
        HardchargerUrl.redact("jdbc:hardcharger://h?user=joe&password=a:b@c;token=xyz"));
  }

  @Test
  void restateNamesTheHardchargerUrl() {
    String url = "jdbc:hardcharger://h?bad&password=p";
    String chargewire = "jdbc:chargewire://h:31337?bad&password=p";
    SQLException original =
        new SQLNonTransientConnectionException(
            "Invalid Chargewire URL '"
                + HardchargerUrl.redact(chargewire)
                + "': option 'bad' is not key=value",
            "08001");
    SQLException restated = HardchargerUrl.restate(original, url, chargewire);
    assertEquals(
        "Invalid Hardcharger URL 'jdbc:hardcharger://h?bad&password=***': option 'bad' is not"
            + " key=value",
        restated.getMessage());
    assertEquals("08001", restated.getSQLState());
    assertSame(original, restated.getCause());
    // Other errors pass through as they are.
    SQLException other = new SQLException("Authentication failed", "28000");
    assertSame(other, HardchargerUrl.restate(other, url, chargewire));
  }
}
