package com.gizmodata.hardcharger.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gizmodata.chargewire.jdbc.ChargewireDriver;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class HardchargerDriverTest {
  private final HardchargerDriver driver = new HardchargerDriver();

  @Test
  void acceptsOnlyHardchargerUrls() {
    assertTrue(driver.acceptsURL("jdbc:hardcharger://127.0.0.1"));
    assertFalse(driver.acceptsURL("jdbc:chargewire://127.0.0.1"));
    assertFalse(driver.acceptsURL("jdbc:hardcharger+http://127.0.0.1"));
  }

  @Test
  void connectAnswersNullForOtherUrls() throws SQLException {
    assertNull(driver.connect("jdbc:chargewire://127.0.0.1:1", new Properties()));
  }

  @Test
  void driverManagerPicksTheDriverByScheme() throws SQLException {
    assertInstanceOf(HardchargerDriver.class, DriverManager.getDriver("jdbc:hardcharger://h"));
    assertInstanceOf(ChargewireDriver.class, DriverManager.getDriver("jdbc:chargewire://h"));
  }

  @Test
  void theVersionIsTheProjects() {
    assertEquals(System.getProperty("project.version"), HardchargerDriver.version());
    assertTrue(
        HardchargerDriver.version().matches("\\d+\\.\\d+\\.\\d+.*"), HardchargerDriver.version());
    String[] parts = HardchargerDriver.version().split("[.-]");
    assertEquals(Integer.parseInt(parts[0]), driver.getMajorVersion());
    assertEquals(Integer.parseInt(parts[1]), driver.getMinorVersion());
  }

  @Test
  void propertiesAreTheChargewireDriversWithTheUrlOptionsApplied() throws SQLException {
    DriverPropertyInfo[] ours =
        driver.getPropertyInfo("jdbc:hardcharger://h?useEncryption=false", new Properties());
    DriverPropertyInfo[] chargewires =
        new ChargewireDriver()
            .getPropertyInfo("jdbc:chargewire://h:31337?useEncryption=false", new Properties());
    assertEquals(
        Arrays.stream(chargewires).map(p -> p.name + "=" + p.value).toList(),
        Arrays.stream(ours).map(p -> p.name + "=" + p.value).toList());
    DriverPropertyInfo encryption =
        Arrays.stream(ours).filter(p -> p.name.equals("useEncryption")).findFirst().orElseThrow();
    assertEquals("false", encryption.value);
  }

  @Test
  void theServiceFileNamesTheDriver() throws Exception {
    try (InputStream in =
        HardchargerDriver.class.getResourceAsStream("/META-INF/services/java.sql.Driver")) {
      assertEquals(
          HardchargerDriver.class.getName(),
          new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
    }
    Driver loaded = DriverManager.getDriver("jdbc:hardcharger://h");
    assertFalse(loaded.jdbcCompliant());
  }
}
