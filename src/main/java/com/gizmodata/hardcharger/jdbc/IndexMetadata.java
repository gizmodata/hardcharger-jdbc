package com.gizmodata.hardcharger.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Hardcharger index metadata, which Chargewire has no dedicated metadata endpoint for. */
final class IndexMetadata {
  private IndexMetadata() {}

  /**
   * Every schema by catalog and oid, named as Chargewire's metadata names it (spec §10, Chargewire
   * 2.1.0): its own name, or for a schema nested in another (DuckDB 2.0's {@code CREATE SCHEMA
   * a.b}) its path from the top, each part quoted when it contains a dot or a double quote.
   * DuckDB's listings give a schema's leaf name and its parent's oid only, so the path is built
   * from the root down, as the server builds it for its own metadata calls.
   */
  private static final String SCHEMA_PATHS =
      "WITH RECURSIVE schemas AS MATERIALIZED (SELECT database_name, oid, parent_schema_oid,"
          + " CASE WHEN schema_name LIKE '%.%' OR schema_name LIKE '%\"%'"
          + " THEN '\"' || replace(schema_name, '\"', '\"\"') || '\"' ELSE schema_name END AS part"
          + " FROM duckdb_schemas()),"
          + " schema_paths AS (SELECT database_name, oid, part AS path FROM schemas"
          + " WHERE parent_schema_oid IS NULL"
          + " UNION ALL SELECT s.database_name, s.oid, p.path || '.' || s.part FROM schemas s"
          + " JOIN schema_paths p ON s.database_name = p.database_name"
          + " AND s.parent_schema_oid = p.oid),"
          + " listed AS (SELECT i.* REPLACE (p.path AS schema_name) FROM duckdb_indexes() i"
          + " JOIN schema_paths p ON i.database_name = p.database_name AND i.schema_oid = p.oid),";

  // Parse the serialized expression list in DuckDB, not by splitting on commas: quoted column
  // names and index expressions may themselves contain commas. Unescape quoted identifiers for
  // JDBC consumers, which must match COLUMN_NAME against getColumns() without SQL quoting.
  private static final String SQL =
      SCHEMA_PATHS
          + " indexes AS (SELECT *, unnest(expressions::VARCHAR[]) AS expression,"
          + " generate_subscripts(expressions::VARCHAR[], 1) AS position FROM listed"
          + " WHERE (? IS NULL OR database_name = ?) AND (? IS NULL OR schema_name = ?)"
          + " AND (? IS NULL OR table_name = ?) AND (NOT ? OR is_unique))"
          + " SELECT database_name AS TABLE_CAT, schema_name AS TABLE_SCHEM, table_name AS TABLE_NAME,"
          + " NOT is_unique AS NON_UNIQUE, NULL::VARCHAR AS INDEX_QUALIFIER, index_name AS INDEX_NAME,"
          + " "
          + DatabaseMetaData.tableIndexOther
          + "::SMALLINT AS TYPE, position::SMALLINT AS ORDINAL_POSITION,"
          + " CASE WHEN expression LIKE '\"%\"'"
          + " THEN replace(substr(expression, 2, length(expression) - 2), '\"\"', '\"')"
          + " ELSE expression END AS COLUMN_NAME, 'A' AS ASC_OR_DESC, 0::BIGINT AS CARDINALITY,"
          + " 0::BIGINT AS PAGES, NULL::VARCHAR AS FILTER_CONDITION"
          + " FROM indexes ORDER BY NON_UNIQUE, TYPE, INDEX_NAME, ORDINAL_POSITION";

  static ResultSet read(
      Connection connection, String catalog, String schema, String table, boolean unique)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(SQL);
    try {
      statement.setString(1, catalog);
      statement.setString(2, catalog);
      statement.setString(3, schema);
      statement.setString(4, schema);
      statement.setString(5, table);
      statement.setString(6, table);
      statement.setBoolean(7, unique);
      // The metadata result owns its statement, including when the caller closes it early.
      statement.closeOnCompletion();
      return statement.executeQuery();
    } catch (SQLException | RuntimeException e) {
      try {
        statement.close();
      } catch (SQLException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }
}
