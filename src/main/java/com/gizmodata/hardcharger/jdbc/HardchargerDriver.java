package com.gizmodata.hardcharger.jdbc;

import com.gizmodata.chargewire.jdbc.ChargewireDriver;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The Hardcharger JDBC driver: {@code jdbc:hardcharger://host[:port][/catalog][?key=value&...]}.
 *
 * <p>Hardcharger speaks Chargewire, so this driver hands every connection to the Chargewire JDBC
 * driver ({@link ChargewireDriver}), with the URL rewritten to {@code jdbc:chargewire://} and the
 * port defaulting to {@value HardchargerUrl#DEFAULT_PORT}. The connection properties, TLS included,
 * are the Chargewire driver's: TLS is on unless {@code useEncryption=false}. Connections report
 * this driver's name and version in {@link java.sql.DatabaseMetaData}, and the URL the application
 * gave.
 *
 * <p>It registers itself with {@link DriverManager} when loaded, and through {@code
 * META-INF/services/java.sql.Driver}. It does not accept {@code jdbc:chargewire:} URLs; the
 * Chargewire driver, which registers itself as well, does.
 */
public final class HardchargerDriver implements Driver {
  /** The driver's name, as {@link java.sql.DatabaseMetaData#getDriverName()} reports it. */
  public static final String NAME = "Hardcharger JDBC Driver";

  private static final String VERSION = readVersion();

  static {
    try {
      DriverManager.registerDriver(new HardchargerDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final ChargewireDriver chargewire = new ChargewireDriver();

  /** Creates the driver; applications normally let {@link DriverManager} do this. */
  public HardchargerDriver() {}

  private static String readVersion() {
    try (InputStream in = HardchargerDriver.class.getResourceAsStream("driver.properties")) {
      Properties properties = new Properties();
      if (in != null) {
        properties.load(in);
      }
      return properties.getProperty("version", "0.0.0");
    } catch (IOException e) {
      return "0.0.0";
    }
  }

  /**
   * Returns the driver's version.
   *
   * @return for example {@code 0.1.0}
   */
  public static String version() {
    return VERSION;
  }

  static int versionPart(int index) {
    String[] parts = VERSION.split("[.-]");
    try {
      return index < parts.length ? Integer.parseInt(parts[index]) : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    String chargewireUrl = HardchargerUrl.toChargewire(url);
    Connection connection;
    try {
      connection = chargewire.connect(chargewireUrl, info);
    } catch (SQLException e) {
      throw HardchargerUrl.restate(e, url, chargewireUrl);
    }
    return Wrappers.connection(connection, url);
  }

  @Override
  public boolean acceptsURL(String url) {
    return HardchargerUrl.accepts(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    return chargewire.getPropertyInfo(
        acceptsURL(url) ? HardchargerUrl.toChargewire(url) : null, info);
  }

  @Override
  public int getMajorVersion() {
    return versionPart(0);
  }

  @Override
  public int getMinorVersion() {
    return versionPart(1);
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("The driver does not log through java.util.logging");
  }
}
