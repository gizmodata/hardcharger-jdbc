package com.gizmodata.hardcharger.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Hardcharger index metadata, which Warp SQL has no dedicated metadata endpoint for. */
final class IndexMetadata {
  private IndexMetadata() {}

  static ResultSet read(
      Connection connection, String catalog, String schema, String table, boolean unique)
      throws SQLException {
    // Parse the serialized expression list in DuckDB, not by splitting on commas: quoted column
    // names and index expressions may themselves contain commas. Unescape quoted identifiers for
    // JDBC consumers, which must match COLUMN_NAME against getColumns() without SQL quoting.
    String sql =
        "WITH indexes AS (SELECT *, unnest(expressions::VARCHAR[]) AS expression,"
            + " generate_subscripts(expressions::VARCHAR[], 1) AS position FROM duckdb_indexes()"
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
    PreparedStatement statement = connection.prepareStatement(sql);
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
