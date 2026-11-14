package com.gizmodata.hardcharger.jdbc.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What the Chargewire JDBC driver 2.3.0 fixed, through {@code jdbc:hardcharger://} against a real
 * hardcharger-server: a value without a time zone bound to a TIMESTAMPTZ means what its literal
 * means in the session's zone, and every DuckDB type reads and binds as itself without the
 * application choosing an Arrow mode. The Chargewire driver's own suite has the full matrix; these
 * prove the fixes reach an application that uses this driver.
 */
class TypesAndTimeZonesIT {
  /** Not the zone of any session below, with daylight saving of its own. */
  private static final String JVM_ZONE = "Pacific/Auckland";

  private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
  private static final String HUGEINT_MAX = "170141183460469231731687303715884105727";

  private static HardchargerServer server;
  private static TimeZone jvmZoneBefore;

  @BeforeAll
  static void start() {
    server = HardchargerServer.start();
    jvmZoneBefore = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(JVM_ZONE));
  }

  @AfterAll
  static void stop() {
    TimeZone.setDefault(jvmZoneBefore);
    server.close();
  }

  private static Connection connect(String options) throws SQLException {
    return DriverManager.getConnection(server.url(options), server.properties());
  }

  /** Binds parameter 1 one way. */
  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private static long serverMicros(Statement statement, String expression) throws SQLException {
    try (ResultSet rows = statement.executeQuery("SELECT epoch_us(" + expression + ")")) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static long micros(Instant instant) {
    return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
  }

  /**
   * The wall clocks include the hour New York skips (2026-03-08 02:30) and the one it has twice
   * (2026-11-01 01:30), and one the JVM's own zone skips (2026-09-27 02:30 in Auckland).
   */
  @Test
  void aValueWithoutAZoneMeansWhatItsLiteralMeansInTheSessionsZone() throws SQLException {
    assertEquals(JVM_ZONE, ZoneId.systemDefault().getId());
    int checked = 0;
    for (String zone : List.of("UTC", "America/New_York", "Asia/Kolkata")) {
      try (Connection connection = connect("");
          Statement statement = connection.createStatement()) {
        statement.execute("SET TimeZone = '" + zone + "'");
        statement.execute("CREATE OR REPLACE TEMP TABLE events (id INTEGER, ts TIMESTAMPTZ)");
        try (PreparedStatement cast =
                connection.prepareStatement("SELECT epoch_us(CAST(? AS TIMESTAMPTZ))");
            PreparedStatement insert =
                connection.prepareStatement("INSERT INTO events VALUES (?, ?)");
            PreparedStatement filter =
                connection.prepareStatement(
                    "SELECT count(*) FROM events WHERE id = ? AND ts = ?")) {
          int id = 0;
          for (String wallClock :
              List.of(
                  "2026-01-15 12:34:56.123456",
                  "2026-07-15 23:59:59.999999",
                  "2026-03-08 02:30:00",
                  "2026-11-01 01:30:00",
                  "2026-09-27 02:30:00")) {
            LocalDateTime dateTime = LocalDateTime.parse(wallClock.replace(' ', 'T'));
            LocalDate date = dateTime.toLocalDate();
            long literal = serverMicros(statement, "TIMESTAMP '" + wallClock + "'::TIMESTAMPTZ");
            long dateLiteral = serverMicros(statement, "DATE '" + date + "'::TIMESTAMPTZ");
            Instant instant = dateTime.toInstant(ZoneOffset.ofHoursMinutes(9, 30));
            Timestamp jvmWallClock = Timestamp.valueOf(dateTime);
            Map<String, Object[]> cases = new LinkedHashMap<>();
            cases.put("setObject(LocalDateTime)", bound(s -> s.setObject(1, dateTime), literal));
            cases.put(
                "setObject(LocalDateTime, TIMESTAMP)",
                bound(s -> s.setObject(1, dateTime, Types.TIMESTAMP), literal));
            cases.put("setString(wall clock)", bound(s -> s.setString(1, wallClock), literal));
            cases.put("setObject(LocalDate)", bound(s -> s.setObject(1, date), dateLiteral));
            cases.put(
                "setObject(LocalDate, DATE)",
                bound(s -> s.setObject(1, date, Types.DATE), dateLiteral));
            cases.put("setDate", bound(s -> s.setDate(1, Date.valueOf(date)), dateLiteral));
            // Points in time are that instant, whatever the zones.
            cases.put(
                "setObject(OffsetDateTime)",
                bound(
                    s -> s.setObject(1, instant.atOffset(ZoneOffset.ofHours(-7))),
                    micros(instant)));
            cases.put("setObject(Instant)", bound(s -> s.setObject(1, instant), micros(instant)));
            cases.put(
                "setTimestamp",
                bound(s -> s.setTimestamp(1, jvmWallClock), micros(jvmWallClock.toInstant())));
            for (Map.Entry<String, Object[]> c : cases.entrySet()) {
              String at = c.getKey() + " of " + wallClock + " in a " + zone + " session";
              Binder binder = (Binder) c.getValue()[0];
              long expected = (Long) c.getValue()[1];
              binder.bind(cast);
              try (ResultSet rows = cast.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expected, rows.getLong(1), at);
              }
              // Stored by an INSERT, and found by a filter, as the same instant.
              insert.setInt(1, ++id);
              rebind(binder, insert, 2);
              assertEquals(1, insert.executeUpdate());
              assertEquals(
                  expected,
                  serverMicros(statement, "(SELECT ts FROM events WHERE id = " + id + ")"),
                  at);
              filter.setInt(1, id);
              rebind(binder, filter, 2);
              try (ResultSet rows = filter.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1), "found by " + at);
              }
              checked++;
            }
          }
        }
        // Reading: the instant, shown in the session's zone, never the JVM's.
        try (ResultSet rows =
            statement.executeQuery(
                "SELECT ts, CAST(ts AS TIMESTAMP) AS wall FROM events ORDER BY id")) {
          while (rows.next()) {
            LocalDateTime wall = rows.getObject("wall", LocalDateTime.class);
            assertEquals(wall, rows.getObject("ts", LocalDateTime.class), zone);
            OffsetDateTime offset = rows.getObject("ts", OffsetDateTime.class);
            assertEquals(wall, offset.toLocalDateTime(), zone);
            assertEquals(offset.toInstant(), rows.getTimestamp("ts").toInstant(), zone);
            assertEquals(offset.toInstant(), rows.getObject("ts", Instant.class), zone);
          }
        }
      }
    }
    assertEquals(3 * 5 * 9, checked);
  }

  private static Object[] bound(Binder binder, long expected) {
    return new Object[] {binder, expected};
  }

  /** Binds what {@code binder} binds as parameter 1 to another parameter of another statement. */
  private static void rebind(Binder binder, PreparedStatement statement, int index)
      throws SQLException {
    binder.bind(
        (PreparedStatement)
            java.lang.reflect.Proxy.newProxyInstance(
                TypesAndTimeZonesIT.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                  if (method.getName().startsWith("set")
                      && args != null
                      && args[0] instanceof Integer) {
                    args[0] = index;
                  }
                  try {
                    return method.invoke(statement, args);
                  } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                  }
                }));
  }

  /**
   * The JVM is thirteen hours from UTC here: on the Chargewire driver 2.2.6 this was that far off.
   */
  @Test
  void theJvmsZoneDoesNotDecideAnInstant() throws SQLException {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement();
        PreparedStatement cast =
            connection.prepareStatement("SELECT CAST(? AS TIMESTAMPTZ) AS ts")) {
      statement.execute("SET TimeZone = 'Asia/Kolkata'");
      LocalDateTime midnight = LocalDateTime.of(2026, 1, 1, 0, 0);
      cast.setObject(1, midnight);
      try (ResultSet rows = cast.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(Instant.parse("2025-12-31T18:30:00Z"), rows.getObject(1, Instant.class));
        assertEquals(midnight, rows.getObject(1, LocalDateTime.class));
        assertEquals("2026-01-01 00:00:00+05:30", rows.getString(1));
      }
    }
  }

  /**
   * Without the application choosing anything: UUID is a UUID, TIME WITH TIME ZONE keeps its
   * offset, HUGEINT is exact to its end, BIT is its bits, BIGNUM a number, VARIANT the value it
   * holds, and BOOLEAN a Boolean; each goes back as a parameter and is stored as it was.
   */
  @Test
  void everyDuckDbTypeReadsAndBindsAsItself() throws SQLException {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE OR REPLACE TEMP TABLE kinds (id INTEGER, f BOOLEAN, u UUID, t TIMETZ, h HUGEINT,"
              + " b BIT, n BIGNUM, v VARIANT, j JSON, e ENUM('sad', 'ok'), a INTEGER[3],"
              + " l BOOLEAN[])");
      statement.execute(
          "INSERT INTO kinds VALUES (1, true, '"
              + ID
              + "', TIMETZ '13:14:15.123456+02:00', "
              + HUGEINT_MAX
              + ", '10101'::BIT, -12345678901234567890123456789012345678901234567890::BIGNUM,"
              + " {'a': 1, 'b': [true, false]}::VARIANT, '{\"a\": 1}', 'ok', [1, 2, 3],"
              + " [true, NULL]), (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,"
              + " NULL)");
      Object[] expected = {
        true,
        ID,
        OffsetTime.of(13, 14, 15, 123_456_000, ZoneOffset.ofHours(2)),
        new BigDecimal(HUGEINT_MAX),
        "10101",
        new BigDecimal("-12345678901234567890123456789012345678901234567890"),
        Map.of("a", 1, "b", List.of(true, false)),
        "{\"a\": 1}",
        "ok",
        List.of(1, 2, 3),
        java.util.Arrays.asList(true, null)
      };
      int[] jdbcTypes = {
        Types.BOOLEAN,
        Types.OTHER,
        Types.TIME_WITH_TIMEZONE,
        Types.NUMERIC,
        Types.OTHER,
        Types.NUMERIC,
        Types.OTHER,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.ARRAY,
        Types.ARRAY
      };
      String[] typeNames = {
        "BOOLEAN",
        "UUID",
        "TIME WITH TIME ZONE",
        "HUGEINT",
        "BIT",
        "BIGNUM",
        "VARIANT",
        "JSON",
        "VARCHAR",
        "INTEGER[]",
        "BOOLEAN[]"
      };
      for (String query :
          List.of("SELECT * FROM kinds ORDER BY id", "SELECT * FROM kinds WHERE false")) {
        try (ResultSet rows = statement.executeQuery(query)) {
          ResultSetMetaData metaData = rows.getMetaData();
          for (int i = 0; i < expected.length; i++) {
            assertEquals(
                jdbcTypes[i], metaData.getColumnType(i + 2), metaData.getColumnLabel(i + 2));
            assertEquals(typeNames[i], metaData.getColumnTypeName(i + 2));
          }
          if (query.contains("ORDER BY")) {
            assertTrue(rows.next());
            for (int i = 0; i < expected.length; i++) {
              assertEquals(expected[i], rows.getObject(i + 2), metaData.getColumnLabel(i + 2));
            }
            assertTrue(rows.next());
            for (int i = 0; i < expected.length; i++) {
              assertEquals(null, rows.getObject(i + 2), metaData.getColumnLabel(i + 2));
            }
          }
          assertFalse(rows.next());
        }
      }
      // Back as parameters (a VARIANT object has no parameter form: a scalar goes instead).
      Object[] bound = expected.clone();
      bound[6] = 42;
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO kinds VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        insert.setInt(1, 3);
        for (int i = 0; i < bound.length; i++) {
          insert.setObject(i + 2, bound[i]);
        }
        assertEquals(1, insert.executeUpdate());
        insert.setInt(1, 4);
        insert.setObject(2, "true");
        insert.setObject(3, ID.toString());
        insert.setObject(4, "13:14:15.123456+02:00");
        insert.setObject(5, new BigInteger(HUGEINT_MAX));
        insert.setString(6, "10101");
        insert.setObject(7, "-12345678901234567890123456789012345678901234567890");
        insert.setObject(8, 42);
        insert.setString(9, "{\"a\": 1}");
        insert.setString(10, "ok");
        insert.setObject(11, new Integer[] {1, 2, 3});
        insert.setObject(12, java.util.Arrays.asList(true, null));
        assertEquals(1, insert.executeUpdate());
      }
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT k.id, k.f IS NOT DISTINCT FROM o.f, k.u IS NOT DISTINCT FROM o.u,"
                  + " k.t IS NOT DISTINCT FROM o.t, k.h IS NOT DISTINCT FROM o.h,"
                  + " k.b IS NOT DISTINCT FROM o.b, k.n IS NOT DISTINCT FROM o.n,"
                  + " k.v::VARCHAR = '42', k.j IS NOT DISTINCT FROM o.j,"
                  + " k.e IS NOT DISTINCT FROM o.e, k.a IS NOT DISTINCT FROM o.a,"
                  + " k.l IS NOT DISTINCT FROM o.l"
                  + " FROM kinds k, kinds o WHERE o.id = 1 AND k.id IN (3, 4) ORDER BY k.id")) {
        for (int id = 3; id <= 4; id++) {
          assertTrue(rows.next());
          for (int column = 2; column <= 12; column++) {
            assertTrue(
                rows.getBoolean(column),
                rows.getMetaData().getColumnLabel(column) + " of row " + id + " stored as sent");
          }
        }
      }
    }
  }

  /** {@code arrowLosslessConversion=false}, as any Chargewire option, in a Hardcharger URL. */
  @Test
  void theArrowModeCanBeLeftAsTheServerOpensIt() throws SQLException {
    try (Connection connection = connect("arrowLosslessConversion=false");
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT '"
                    + ID
                    + "'::UUID AS u, TIMETZ '13:14:15+02:00' AS t, '10101'::BIT AS b,"
                    + " 12345::HUGEINT AS h, true AS f")) {
      assertTrue(rows.next());
      assertEquals(ID.toString(), rows.getObject("u"));
      assertEquals(Types.VARCHAR, rows.getMetaData().getColumnType(1));
      assertEquals("13:14:15", rows.getObject("t").toString());
      assertEquals(Types.TIME, rows.getMetaData().getColumnType(2));
      assertArrayEquals(new byte[] {3, (byte) 0xF5}, (byte[]) rows.getObject("b"));
      assertEquals("DECIMAL(38,0)", rows.getMetaData().getColumnTypeName(4));
      assertEquals(true, rows.getObject("f"));
    }
    try (Connection connection = connect("arrowLosslessConversion=false");
        Statement statement = connection.createStatement()) {
      SQLException refused =
          assertThrows(
              SQLException.class,
              () -> statement.executeQuery("SELECT " + HUGEINT_MAX + "::HUGEINT"));
      assertTrue(refused.getMessage().contains("arrow_lossless_conversion"), refused.getMessage());
    }
  }
}
