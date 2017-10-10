/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.connect.jdbc.dialect;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.confluent.connect.jdbc.dialect.DatabaseDialectProvider.FixedScoreProvider;
import io.confluent.connect.jdbc.sink.JdbcSinkConfig;
import io.confluent.connect.jdbc.sink.JdbcSinkConfig.InsertMode;
import io.confluent.connect.jdbc.sink.JdbcSinkConfig.PrimaryKeyMode;
import io.confluent.connect.jdbc.sink.PreparedStatementBinder;
import io.confluent.connect.jdbc.sink.metadata.FieldsMetadata;
import io.confluent.connect.jdbc.sink.metadata.SchemaPair;
import io.confluent.connect.jdbc.sink.metadata.SinkRecordField;
import io.confluent.connect.jdbc.source.ColumnMapping;
import io.confluent.connect.jdbc.source.JdbcSourceConnectorConfig;
import io.confluent.connect.jdbc.source.JdbcSourceConnectorConfig.NumericMapping;
import io.confluent.connect.jdbc.source.JdbcSourceTaskConfig;
import io.confluent.connect.jdbc.source.TimestampIncrementingCriteria;
import io.confluent.connect.jdbc.util.ColumnDefinition;
import io.confluent.connect.jdbc.util.ColumnDefinition.Mutability;
import io.confluent.connect.jdbc.util.ColumnDefinition.Nullability;
import io.confluent.connect.jdbc.util.ColumnId;
import io.confluent.connect.jdbc.util.ConnectionProvider;
import io.confluent.connect.jdbc.util.DateTimeUtils;
import io.confluent.connect.jdbc.util.ExpressionBuilder;
import io.confluent.connect.jdbc.util.ExpressionBuilder.Transform;
import io.confluent.connect.jdbc.util.GenericConnectionProvider;
import io.confluent.connect.jdbc.util.IdentifierRules;
import io.confluent.connect.jdbc.util.TableDefinition;
import io.confluent.connect.jdbc.util.TableId;

/**
 * A {@link DatabaseDialect} implementation that provides functionality based upon JDBC and SQL.
 *
 * <p>This class is designed to be extended as required to customize the behavior for a specific
 * DBMS. For example, override the {@link #createColumnConverter(ColumnMapping)} method to customize
 * how a column value is converted to a field value for use in a {@link Struct}. To also change the
 * field's type or schema, also override the {@link #addFieldToSchema} method.
 */
public class GenericDatabaseDialect implements DatabaseDialect {

  protected static final int NUMERIC_TYPE_SCALE_LOW = -84;
  protected static final int NUMERIC_TYPE_SCALE_HIGH = 127;
  protected static final int NUMERIC_TYPE_SCALE_UNSET = -127;

  /**
   * The provider for {@link GenericDatabaseDialect}.
   */
  public static class Provider extends FixedScoreProvider {
    public Provider() {
      super(GenericDatabaseDialect.class.getSimpleName(),
            DatabaseDialectProvider.AVERAGE_MATCHING_SCORE
      );
    }

    @Override
    public DatabaseDialect create(AbstractConfig config) {
      return new GenericDatabaseDialect(config);
    }
  }

  protected final Logger log = LoggerFactory.getLogger(getClass());
  protected final AbstractConfig config;

  /**
   * Whether to map {@code NUMERIC} JDBC types by precision.
   */
  protected final NumericMapping mapNumerics;
  protected String catalogPattern;
  protected final String schemaPattern;
  protected final Set<String> tableTypes;
  private final AtomicReference<IdentifierRules> identifierRules = new AtomicReference<>();

  /**
   * Create a new dialect instance with the given connector configuration.
   *
   * @param config the connector configuration; may not be null
   */
  public GenericDatabaseDialect(AbstractConfig config) {
    this(config, null);
  }

  /**
   * Create a new dialect instance with the given connector configuration.
   *
   * @param config          the connector configuration; may not be null
   * @param identifierRules the rules for identifiers; may be null if the rules are to be determined
   *                        from the database metadata
   */
  protected GenericDatabaseDialect(
      AbstractConfig config,
      IdentifierRules identifierRules
  ) {
    this.config = config;
    this.identifierRules.set(identifierRules);
    if (!(config instanceof JdbcSinkConfig)) {
      schemaPattern = config.getString(JdbcSourceTaskConfig.SCHEMA_PATTERN_CONFIG);
      tableTypes = new HashSet<>(config.getList(JdbcSourceTaskConfig.TABLE_TYPE_CONFIG));
    } else {
      schemaPattern = JdbcSourceTaskConfig.SCHEMA_PATTERN_DEFAULT;
      tableTypes = new HashSet<>(Arrays.asList(JdbcSourceTaskConfig.TABLE_TYPE_DEFAULT));
    }
    if (config instanceof JdbcSourceConnectorConfig) {
      mapNumerics = ((JdbcSourceConnectorConfig)config).numericMapping();
    } else {
      mapNumerics = NumericMapping.NONE;
    }
  }

  @Override
  public ConnectionProvider createConnectionProvider() {
    // These config names are the same for both source and sink configs ...
    final String jdbcUrl = config.getString(JdbcSourceConnectorConfig.CONNECTION_URL_CONFIG);
    String username = config.getString(JdbcSourceConnectorConfig.CONNECTION_USER_CONFIG);
    Password dbPassword = config.getPassword(JdbcSourceConnectorConfig.CONNECTION_PASSWORD_CONFIG);
    Properties properties = new Properties();
    if (username != null) {
      properties.setProperty("user", username);
    }
    if (dbPassword != null) {
      properties.setProperty("password", dbPassword.value());
    }
    properties = addConnectionProperties(properties);
    return new GenericConnectionProvider(jdbcUrl, properties);
  }

  /**
   * Add any connection properties based upon the {@link #config configuration}.
   *
   * <p>By default this method does nothing and returns the {@link Properties} object supplied as a
   * parameter, but subclasses can override it to add/remove properties used to create new
   *
   * @param properties the properties that will be passed to the {@link DriverManager}'s {@link
   *                   DriverManager#getConnection(String, Properties) getConnection(...) method};
   *                   never null
   * @return the updated connection properties, or {@code properties} if they are modified or should
   *     be returned; never null
   */
  protected Properties addConnectionProperties(Properties properties) {
    return properties;
  }

  @Override
  public PreparedStatement createPreparedStatement(
      Connection db,
      String query
  ) throws SQLException {
    PreparedStatement stmt = db.prepareStatement(query);
    stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
    return stmt;
  }

  @Override
  public List<TableId> tableNames(
      Connection conn
  ) throws SQLException {
    DatabaseMetaData metadata = conn.getMetaData();
    String[] tableTypes = tableTypes(metadata, this.tableTypes);

    try (ResultSet rs = metadata.getTables(null, schemaPattern, "%", tableTypes)) {
      List<TableId> tableIds = new ArrayList<>();
      while (rs.next()) {
        String catalogName = rs.getString(1);
        String schemaName = rs.getString(2);
        String tableName = rs.getString(3);
        TableId tableId = new TableId(catalogName, schemaName, tableName);
        if (includeTable(tableId)) {
          tableIds.add(tableId);
        }
      }
      return tableIds;
    }
  }

  /**
   * Determine whether the table with the specific name is to be included in the tables.
   *
   * <p>This method can be overridden to exclude certain database tables.
   *
   * @param table the identifier of the table; may be null
   */
  protected boolean includeTable(TableId table) {
    return true;
  }

  /**
   * Find the available table types that are returned by the JDBC driver that case insensitively
   * match the specified types.
   *
   * @param metadata the database metadata; may not be null but may be empty if no table types
   * @param types    the case-independent table types that are desired
   * @return the array of table types take directly from the list of available types returned by the
   *     JDBC driver; never null
   * @throws SQLException if there is an error with the database connection
   */
  protected String[] tableTypes(
      DatabaseMetaData metadata,
      Set<String> types
  ) throws SQLException {
    // Compute the uppercase form of the desired types ...
    Set<String> uppercaseTypes = new HashSet<>();
    for (String type : types) {
      if (type != null) {
        uppercaseTypes.add(type.toUpperCase());
      }
    }
    // Now find out the available table types ...
    Set<String> matchingTableTypes = new HashSet<>();
    try (ResultSet rs = metadata.getTableTypes()) {
      while (rs.next()) {
        String tableType = rs.getString(1);
        if (tableType != null && uppercaseTypes.contains(tableType.toUpperCase())) {
          matchingTableTypes.add(tableType);
        }
      }
    }
    return matchingTableTypes.toArray(new String[matchingTableTypes.size()]);
  }

  @Override
  public IdentifierRules identifierRules() {
    if (identifierRules.get() == null) {
      try (Connection connection = createConnectionProvider().getConnection()) {
        DatabaseMetaData metaData = connection.getMetaData();
        String quoteStr = metaData.getIdentifierQuoteString();
        String separator = metaData.getCatalogSeparator();
        identifierRules.set(new IdentifierRules(separator, quoteStr));
      } catch (SQLException e) {
        throw new ConnectException("Unable to get identifier metadata", e);
      }
    }
    return identifierRules.get();
  }

  @Override
  public ExpressionBuilder expressionBuilder() {
    return identifierRules().expressionBuilder();
  }

  /**
   * Return current time at the database
   *
   * @param conn database connection
   * @param cal  calendar
   * @return the current time at the database
   */
  @Override
  public Timestamp currentTimeOnDB(
      Connection conn,
      Calendar cal
  ) throws SQLException, ConnectException {
    String query = currentTimestampDatabaseQuery();
    assert query != null;
    assert !query.isEmpty();
    try (Statement stmt = conn.createStatement()) {
      log.debug("executing query " + query + " to get current time from database");
      ResultSet rs = stmt.executeQuery(query);
      if (rs.next()) {
        return rs.getTimestamp(1, cal);
      } else {
        throw new ConnectException(
            "Unable to get current time from DB using " + this + " and query '" + query + "'");
      }
    } catch (SQLException e) {
      log.error("Failed to get current time from DB using {} and query '{}'", this, query, e);
      throw e;
    }
  }

  /**
   * Get the query string to determine the current timestamp in the database.
   *
   * @return the query string; never null or empty
   */
  protected String currentTimestampDatabaseQuery() {
    return "SELECT CURRENT_TIMESTAMP;";
  }

  @Override
  public boolean tableExists(
      Connection connection,
      TableId tableId
  ) throws SQLException {
    log.info("Checking {} dialect for existance of table {}", this, tableId);
    try (ResultSet rs = connection.getMetaData().getTables(tableId.catalogName(),
                                                           tableId.schemaName(),
                                                           tableId.tableName(),
                                                           new String[]{"TABLE"}
    )) {
      final boolean exists = rs.next();
      log.info("Using {} dialect table {} {}", this, tableId, exists ? "present" : "absent");
      return exists;
    }
  }

  @Override
  public Map<ColumnId, ColumnDefinition> describeColumns(
      Connection connection,
      String tablePattern,
      String columnPattern
  ) throws SQLException {
    return describeColumns(connection, catalogPattern, schemaPattern, tablePattern, columnPattern);
  }

  @Override
  public Map<ColumnId, ColumnDefinition> describeColumns(
      Connection connection,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      String columnPattern
  ) throws SQLException {
    log.info("Querying {} dialect column metadata for catalog:{} schema:{} table:{}", this,
             catalogPattern, schemaPattern, tablePattern
    );

    // Get the primary keys of the table(s) ...
    final Set<ColumnId> pkColumns = primaryKeyColumns(connection, catalogPattern, schemaPattern,
                                                      tablePattern
    );
    Map<ColumnId, ColumnDefinition> results = new HashMap<>();
    try (ResultSet rs = connection.getMetaData().getColumns(catalogPattern, schemaPattern,
                                                            tablePattern, columnPattern
    )) {
      final int rsColumnCount = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        final String catalogName = rs.getString(1);
        final String schemaName = rs.getString(2);
        final String tableName = rs.getString(3);
        final TableId tableId = new TableId(catalogName, schemaName, tableName);
        final String columnName = rs.getString(4);
        final ColumnId columnId = new ColumnId(tableId, columnName, null);
        final int jdbcType = rs.getInt(5);
        final String typeName = rs.getString(6);
        final int precision = rs.getInt(7);
        final Integer scale = rs.getInt(9);
        final String typeClassName = null;
        Nullability nullability = Nullability.UNKNOWN;
        final int nullableValue = rs.getInt(11);
        switch (nullableValue) {
          case DatabaseMetaData.columnNoNulls:
            nullability = Nullability.NOT_NULL;
            break;
          case DatabaseMetaData.columnNullable:
            nullability = Nullability.NULL;
            break;
          case DatabaseMetaData.columnNullableUnknown:
          default:
            nullability = Nullability.UNKNOWN;
            break;
        }
        Boolean autoIncremented = null;
        if (rsColumnCount >= 23) {
          // Not all drivers include all columns ...
          String isAutoIncremented = rs.getString(23);
          if ("yes".equalsIgnoreCase(isAutoIncremented)) {
            autoIncremented = Boolean.TRUE;
          } else if ("no".equalsIgnoreCase(isAutoIncremented)) {
            autoIncremented = Boolean.FALSE;
          }
        }
        Boolean signed = null;
        Boolean caseSensitive = null;
        Boolean searchable = null;
        Boolean currency = null;
        Integer displaySize = null;
        boolean isPrimaryKey = pkColumns.contains(columnId);
        if (isPrimaryKey) {
          // Some DBMSes report pks as null
          nullability = Nullability.NOT_NULL;
        }
        ColumnDefinition defn = columnDefinition(rs, columnId, jdbcType, typeName, typeClassName,
                                                 nullability, Mutability.UNKNOWN, precision,
                                                 scale != null ? scale.intValue() : 0, signed,
                                                 displaySize, autoIncremented, caseSensitive,
                                                 searchable, currency, isPrimaryKey
        );
        results.put(columnId, defn);
      }
      return results;
    }
  }

  @Override
  public Map<ColumnId, ColumnDefinition> describeColumns(ResultSetMetaData rsMetadata) throws
      SQLException {
    Map<ColumnId, ColumnDefinition> result = new LinkedHashMap<>();
    for (int i = 1; i <= rsMetadata.getColumnCount(); ++i) {
      ColumnDefinition defn = describeColumn(rsMetadata, i);
      result.put(defn.id(), defn);
    }
    return result;
  }

  /**
   * Create a definition for the specified column in the result set.
   *
   * @param rsMetadata the result set metadata; may not be null
   * @param column     the column number, starting at 1 for the first column
   * @return the column definition; never null
   * @throws SQLException if there is an error accessing the result set metadata
   */
  protected ColumnDefinition describeColumn(
      ResultSetMetaData rsMetadata,
      int column
  ) throws SQLException {
    String catalog = rsMetadata.getCatalogName(column);
    String schema = rsMetadata.getSchemaName(column);
    String tableName = rsMetadata.getTableName(column);
    TableId tableId = new TableId(catalog, schema, tableName);
    String name = rsMetadata.getColumnName(column);
    String alias = rsMetadata.getColumnLabel(column);
    ColumnId id = new ColumnId(tableId, name, alias);
    Nullability nullability = Nullability.UNKNOWN;
    switch (rsMetadata.isNullable(column)) {
      case ResultSetMetaData.columnNullable:
        nullability = Nullability.NULL;
        break;
      case ResultSetMetaData.columnNoNulls:
        nullability = Nullability.NOT_NULL;
        break;
      case ResultSetMetaData.columnNullableUnknown:
      default:
        nullability = Nullability.UNKNOWN;
        break;
    }
    Mutability mutability = Mutability.MAYBE_WRITABLE;
    if (rsMetadata.isReadOnly(column)) {
      mutability = Mutability.READ_ONLY;
    } else if (rsMetadata.isWritable(column)) {
      mutability = Mutability.MAYBE_WRITABLE;
    } else if (rsMetadata.isDefinitelyWritable(column)) {
      mutability = Mutability.WRITABLE;
    }
    return new ColumnDefinition(id, rsMetadata.getColumnType(column),
                                rsMetadata.getColumnTypeName(column),
                                rsMetadata.getColumnClassName(column), nullability, mutability,
                                rsMetadata.getPrecision(column), rsMetadata.getScale(column),
                                rsMetadata.isSigned(column),
                                rsMetadata.getColumnDisplaySize(column),
                                rsMetadata.isAutoIncrement(column),
                                rsMetadata.isCaseSensitive(column), rsMetadata.isSearchable(column),
                                rsMetadata.isCurrency(column), false
    );
  }

  protected Set<ColumnId> primaryKeyColumns(
      Connection connection,
      String catalogPattern,
      String schemaPattern,
      String tablePattern
  ) throws SQLException {

    // Get the primary keys of the table(s) ...
    final Set<ColumnId> pkColumns = new HashSet<>();
    try (ResultSet rs = connection.getMetaData().getPrimaryKeys(
        catalogPattern, schemaPattern, tablePattern)) {
      while (rs.next()) {
        String catalogName = rs.getString(1);
        String schemaName = rs.getString(2);
        String tableName = rs.getString(3);
        TableId tableId = new TableId(catalogName, schemaName, tableName);
        final String colName = rs.getString(4);
        ColumnId columnId = new ColumnId(tableId, colName);
        pkColumns.add(columnId);
      }
    }
    return pkColumns;
  }

  @Override
  public Map<ColumnId, ColumnDefinition> describeColumnsByQuerying(
      Connection db,
      String name
  ) throws SQLException {
    String queryStr = "SELECT * FROM {} LIMIT 1";
    String quotedName = expressionBuilder().appendIdentifierQuoted(name).toString();
    try (PreparedStatement stmt = db.prepareStatement(queryStr)) {
      stmt.setString(1, quotedName);
      ResultSet rs = stmt.executeQuery();
      ResultSetMetaData rsmd = rs.getMetaData();
      return describeColumns(rsmd);
    }
  }

  @Override
  public TableDefinition describeTable(
      Connection connection,
      TableId tableId
  ) throws SQLException {
    Map<ColumnId, ColumnDefinition> columnDefns = describeColumns(connection, tableId.catalogName(),
                                                                  tableId.schemaName(),
                                                                  tableId.tableName(), null
    );
    if (columnDefns.isEmpty()) {
      return null;
    }
    return new TableDefinition(tableId, columnDefns.values());
  }

  /**
   * Create a ColumnDefinition with supplied values and the result set from the {@link
   * DatabaseMetaData#getColumns(String, String, String, String)} call. By default that method does
   * not describe whether the column is signed, case sensitive, searchable, currency, or the
   * preferred display size.
   *
   * <p>Subclasses can override this method to extract additional non-standard characteristics from
   * the result set, and override the characteristics determined using the standard JDBC metadata
   * columns and supplied as parameters.
   *
   * @param resultSet        the result set
   * @param id               the column identifier
   * @param jdbcType         the JDBC type of the column
   * @param typeName         the name of the column's type
   * @param classNameForType the name of the class used as instances of the value when {@link
   *                         ResultSet#getObject(int)} is called
   * @param nullability      the nullability of the column
   * @param mutability       the mutability of the column
   * @param precision        the precision of the column for numeric values, or the length for
   *                         non-numeric values
   * @param scale            the scale of the column for numeric values; ignored for other values
   * @param signedNumbers    true if the column holds signed numeric values; null if not known
   * @param displaySize      the preferred display size for the column values; null if not known
   * @param autoIncremented  true if the column is auto-incremented; null if not known
   * @param caseSensitive    true if the column values are case-sensitive; null if not known
   * @param searchable       true if the column is searchable; null if no; null if not known known
   * @param currency         true if the column is a currency value
   * @param isPrimaryKey     true if the column is part of the primary key; null if not known known
   * @return the column definition; never null
   */
  protected ColumnDefinition columnDefinition(
      ResultSet resultSet,
      ColumnId id,
      int jdbcType,
      String typeName,
      String classNameForType,
      Nullability nullability,
      Mutability mutability,
      int precision,
      int scale,
      Boolean signedNumbers,
      Integer displaySize,
      Boolean autoIncremented,
      Boolean caseSensitive,
      Boolean searchable,
      Boolean currency,
      Boolean isPrimaryKey
  ) {
    return new ColumnDefinition(id, jdbcType, typeName, classNameForType, nullability, mutability,
                                precision, scale,
                                signedNumbers != null ? signedNumbers.booleanValue() : false,
                                displaySize != null ? displaySize.intValue() : 0,
                                autoIncremented != null ? autoIncremented.booleanValue() : false,
                                caseSensitive != null ? caseSensitive.booleanValue() : false,
                                searchable != null ? searchable.booleanValue() : false,
                                currency != null ? currency.booleanValue() : false,
                                isPrimaryKey != null ? isPrimaryKey.booleanValue() : false
    );
  }

  @Override
  public TimestampIncrementingCriteria criteriaFor(
      ColumnId incrementingColumn,
      List<ColumnId> timestampColumns
  ) {
    return new TimestampIncrementingCriteria(incrementingColumn, timestampColumns);
  }

  /**
   * Determine the name of the field. By default this is the column alias or name.
   *
   * @param columnDefinition the column definition; never null
   * @return the field name; never null
   */
  protected String fieldNameFor(ColumnDefinition columnDefinition) {
    return columnDefinition.id().aliasOrName();
  }

  @Override
  public String addFieldToSchema(
      ColumnDefinition columnDefn,
      SchemaBuilder builder
  ) {
    final String fieldName = fieldNameFor(columnDefn);
    final int sqlType = columnDefn.type();
    boolean optional = columnDefn.isOptional();
    int precision = columnDefn.precision();
    int scale = columnDefn.scale();

    switch (sqlType) {
      case Types.NULL: {
        log.warn("JDBC type {} not currently supported", sqlType);
        return null;
      }

      case Types.BOOLEAN: {
        if (optional) {
          builder.field(fieldName, Schema.OPTIONAL_BOOLEAN_SCHEMA);
        } else {
          builder.field(fieldName, Schema.BOOLEAN_SCHEMA);
        }
        break;
      }

      // ints <= 8 bits
      case Types.BIT: {
        if (optional) {
          builder.field(fieldName, Schema.OPTIONAL_INT8_SCHEMA);
        } else {
          builder.field(fieldName, Schema.INT8_SCHEMA);
        }
        break;
      }

      case Types.TINYINT: {
        if (optional) {
          if (columnDefn.isSignedNumber()) {
            builder.field(fieldName, Schema.OPTIONAL_INT8_SCHEMA);
          } else {
            builder.field(fieldName, Schema.OPTIONAL_INT16_SCHEMA);
          }
        } else {
          if (columnDefn.isSignedNumber()) {
            builder.field(fieldName, Schema.INT8_SCHEMA);
          } else {
            builder.field(fieldName, Schema.INT16_SCHEMA);
          }
        }
        break;
      }

      // 16 bit ints
      case Types.SMALLINT: {
        if (optional) {
          if (columnDefn.isSignedNumber()) {
            builder.field(fieldName, Schema.OPTIONAL_INT16_SCHEMA);
          } else {
            builder.field(fieldName, Schema.OPTIONAL_INT32_SCHEMA);
          }
        } else {
          if (columnDefn.isSignedNumber()) {
            builder.field(fieldName, Schema.INT16_SCHEMA);
          } else {
            builder.field(fieldName, Schema.INT32_SCHEMA);
          }
        }
        break;
      }

      // 32 bit ints
      case Types.INTEGER: {
        if (optional) {
          if (columnDefn.isSignedNumber()) {
            builder.field(fieldName, Schema.OPTIONAL_INT32_SCHEMA);
          } else {
            builder.field(fieldName, Schema.OPTIONAL_INT64_SCHEMA);
          }
        } else {
          if (columnDefn.isSignedNumber()) {
            builder.field(fieldName, Schema.INT32_SCHEMA);
          } else {
            builder.field(fieldName, Schema.INT64_SCHEMA);
          }
        }
        break;
      }

      // 64 bit ints
      case Types.BIGINT: {
        if (optional) {
          builder.field(fieldName, Schema.OPTIONAL_INT64_SCHEMA);
        } else {
          builder.field(fieldName, Schema.INT64_SCHEMA);
        }
        break;
      }

      // REAL is a single precision floating point value, i.e. a Java float
      case Types.REAL: {
        if (optional) {
          builder.field(fieldName, Schema.OPTIONAL_FLOAT32_SCHEMA);
        } else {
          builder.field(fieldName, Schema.FLOAT32_SCHEMA);
        }
        break;
      }

      // FLOAT is, confusingly, double precision and effectively the same as DOUBLE. See REAL
      // for single precision
      case Types.FLOAT:
      case Types.DOUBLE: {
        if (optional) {
          builder.field(fieldName, Schema.OPTIONAL_FLOAT64_SCHEMA);
        } else {
          builder.field(fieldName, Schema.FLOAT64_SCHEMA);
        }
        break;
      }

      case Types.NUMERIC:
        if (mapNumerics == NumericMapping.PRECISION_ONLY) {
          log.debug("NUMERIC with precision: '{}' and scale: '{}'", precision, scale);
          if (scale == 0 && precision < 19) { // integer
            Schema schema;
            if (precision > 9) {
              schema = (optional) ? Schema.OPTIONAL_INT64_SCHEMA : Schema.INT64_SCHEMA;
            } else if (precision > 4) {
              schema = (optional) ? Schema.OPTIONAL_INT32_SCHEMA : Schema.INT32_SCHEMA;
            } else if (precision > 2) {
              schema = (optional) ? Schema.OPTIONAL_INT16_SCHEMA : Schema.INT16_SCHEMA;
            } else {
              schema = (optional) ? Schema.OPTIONAL_INT8_SCHEMA : Schema.INT8_SCHEMA;
            }
            builder.field(fieldName, schema);
            break;
          }
        } else if (mapNumerics == NumericMapping.BEST_FIT) {
          log.debug("NUMERIC with precision: '{}' and scale: '{}'", precision, scale);
          if (precision < 19) { // fits in primitive data types.
            if (scale < 1 && scale >= NUMERIC_TYPE_SCALE_LOW) { // integer
              Schema schema;
              if (precision > 9) {
                schema = (optional) ? Schema.OPTIONAL_INT64_SCHEMA : Schema.INT64_SCHEMA;
              } else if (precision > 4) {
                schema = (optional) ? Schema.OPTIONAL_INT32_SCHEMA : Schema.INT32_SCHEMA;
              } else if (precision > 2) {
                schema = (optional) ? Schema.OPTIONAL_INT16_SCHEMA : Schema.INT16_SCHEMA;
              } else {
                schema = (optional) ? Schema.OPTIONAL_INT8_SCHEMA : Schema.INT8_SCHEMA;
              }
              builder.field(fieldName, schema);
              break;
            } else if (scale > 0) { // floating point - use double in all cases
              Schema schema = (optional) ? Schema.OPTIONAL_FLOAT64_SCHEMA : Schema.FLOAT64_SCHEMA;
              builder.field(fieldName, schema);
              break;
            }
          }
        }
        // fallthrough

      case Types.DECIMAL: {
        log.debug("DECIMAL with precision: '{}' and scale: '{}'", precision, scale);
        scale = decimalScale(columnDefn);
        SchemaBuilder fieldBuilder = Decimal.builder(scale);
        if (optional) {
          fieldBuilder.optional();
        }
        builder.field(fieldName, fieldBuilder.build());
        break;
      }

      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
      case Types.NCHAR:
      case Types.NVARCHAR:
      case Types.LONGNVARCHAR:
      case Types.CLOB:
      case Types.NCLOB:
      case Types.DATALINK:
      case Types.SQLXML: {
        // Some of these types will have fixed size, but we drop this from the schema conversion
        // since only fixed byte arrays can have a fixed size
        if (optional) {
          builder.field(fieldName, Schema.OPTIONAL_STRING_SCHEMA);
        } else {
          builder.field(fieldName, Schema.STRING_SCHEMA);
        }
        break;
      }

      // Binary == fixed bytes
      // BLOB, VARBINARY, LONGVARBINARY == bytes
      case Types.BINARY:
      case Types.BLOB:
      case Types.VARBINARY:
      case Types.LONGVARBINARY: {
        if (optional) {
          builder.field(fieldName, Schema.OPTIONAL_BYTES_SCHEMA);
        } else {
          builder.field(fieldName, Schema.BYTES_SCHEMA);
        }
        break;
      }

      // Date is day + moth + year
      case Types.DATE: {
        SchemaBuilder dateSchemaBuilder = Date.builder();
        if (optional) {
          dateSchemaBuilder.optional();
        }
        builder.field(fieldName, dateSchemaBuilder.build());
        break;
      }

      // Time is a time of day -- hour, minute, seconds, nanoseconds
      case Types.TIME: {
        SchemaBuilder timeSchemaBuilder = Time.builder();
        if (optional) {
          timeSchemaBuilder.optional();
        }
        builder.field(fieldName, timeSchemaBuilder.build());
        break;
      }

      // Timestamp is a date + time
      case Types.TIMESTAMP: {
        SchemaBuilder tsSchemaBuilder = org.apache.kafka.connect.data.Timestamp.builder();
        if (optional) {
          tsSchemaBuilder.optional();
        }
        builder.field(fieldName, tsSchemaBuilder.build());
        break;
      }

      case Types.ARRAY:
      case Types.JAVA_OBJECT:
      case Types.OTHER:
      case Types.DISTINCT:
      case Types.STRUCT:
      case Types.REF:
      case Types.ROWID:
      default: {
        log.warn("JDBC type {} ({}) not currently supported", sqlType, columnDefn.typeName());
        return null;
      }
    }
    return fieldName;
  }

  @Override
  public ColumnConverter createColumnConverter(
      ColumnMapping mapping
  ) {
    final int col = mapping.columnNumber();
    final ColumnDefinition defn = mapping.columnDefn();

    switch (mapping.columnDefn().type()) {

      case Types.BOOLEAN: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getBoolean(col);
          }
        };
      }

      case Types.BIT: {
        /**
         * BIT should be either 0 or 1.
         * TODO: Postgres handles this differently, returning a string "t" or "f". See the
         * elasticsearch-jdbc plugin for an example of how this is handled
         */
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getByte(col);
          }
        };
      }

      // 8 bits int
      case Types.TINYINT: {
        if (defn.isSignedNumber()) {
          return new ColumnConverter() {
            @Override
            public Object convert(ResultSet rs) throws SQLException {
              return rs.getByte(col);
            }
          };
        } else {
          return new ColumnConverter() {
            @Override
            public Object convert(ResultSet rs) throws SQLException {
              return rs.getShort(col);
            }
          };
        }
      }

      // 16 bits int
      case Types.SMALLINT: {
        if (defn.isSignedNumber()) {
          return new ColumnConverter() {
            @Override
            public Object convert(ResultSet rs) throws SQLException {
              return rs.getShort(col);
            }
          };
        } else {
          return new ColumnConverter() {
            @Override
            public Object convert(ResultSet rs) throws SQLException {
              return rs.getInt(col);
            }
          };
        }
      }

      // 32 bits int
      case Types.INTEGER: {
        if (defn.isSignedNumber()) {
          return new ColumnConverter() {
            @Override
            public Object convert(ResultSet rs) throws SQLException {
              return rs.getInt(col);
            }
          };
        } else {
          return new ColumnConverter() {
            @Override
            public Object convert(ResultSet rs) throws SQLException {
              return rs.getLong(col);
            }
          };
        }
      }

      // 64 bits int
      case Types.BIGINT: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getLong(col);
          }
        };
      }

      // REAL is a single precision floating point value, i.e. a Java float
      case Types.REAL: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getFloat(col);
          }
        };
      }

      // FLOAT is, confusingly, double precision and effectively the same as DOUBLE. See REAL
      // for single precision
      case Types.FLOAT:
      case Types.DOUBLE: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getDouble(col);
          }
        };
      }

      case Types.NUMERIC:
        if (mapNumerics == NumericMapping.PRECISION_ONLY) {
          int precision = defn.precision();
          int scale = defn.scale();
          log.trace("NUMERIC with precision: '{}' and scale: '{}'", precision, scale);
          if (scale == 0 && precision < 19) { // integer
            if (precision > 9) {
              return new ColumnConverter() {
                @Override
                public Object convert(ResultSet rs) throws SQLException {
                  return rs.getLong(col);
                }
              };
            } else if (precision > 4) {
              return new ColumnConverter() {
                @Override
                public Object convert(ResultSet rs) throws SQLException {
                  return rs.getInt(col);
                }
              };
            } else if (precision > 2) {
              return new ColumnConverter() {
                @Override
                public Object convert(ResultSet rs) throws SQLException {
                  return rs.getShort(col);
                }
              };
            } else {
              return new ColumnConverter() {
                @Override
                public Object convert(ResultSet rs) throws SQLException {
                  return rs.getByte(col);
                }
              };
            }
          }
        } else if (mapNumerics == NumericMapping.BEST_FIT) {
          int precision = defn.precision();
          int scale = defn.scale();
          log.trace("NUMERIC with precision: '{}' and scale: '{}'", precision, scale);
          if (precision < 19) { // fits in primitive data types.
            if (scale < 1 && scale >= NUMERIC_TYPE_SCALE_LOW) { // integer
              if (precision > 9) {
                return new ColumnConverter() {
                  @Override
                  public Object convert(ResultSet rs) throws SQLException {
                    return rs.getLong(col);
                  }
                };
              } else if (precision > 4) {
                return new ColumnConverter() {
                  @Override
                  public Object convert(ResultSet rs) throws SQLException {
                    return rs.getInt(col);
                  }
                };
              } else if (precision > 2) {
                return new ColumnConverter() {
                  @Override
                  public Object convert(ResultSet rs) throws SQLException {
                    return rs.getShort(col);
                  }
                };
              } else {
                return new ColumnConverter() {
                  @Override
                  public Object convert(ResultSet rs) throws SQLException {
                    return rs.getByte(col);
                  }
                };
              }
            } else if (scale > 0) { // floating point - use double in all cases
              return new ColumnConverter() {
                @Override
                public Object convert(ResultSet rs) throws SQLException {
                  return rs.getDouble(col);
                }
              };
            }
          }
        }
        // fallthrough

      case Types.DECIMAL: {
        final int precision = defn.precision();
        log.debug("DECIMAL with precision: '{}' and scale: '{}'", precision, defn.scale());
        final int scale = decimalScale(defn);
        return new ColumnConverter() {
          @Override
          @SuppressWarnings("deprecation")
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getBigDecimal(col, scale);
          }
        };
      }

      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getString(col);
          }
        };
      }

      case Types.NCHAR:
      case Types.NVARCHAR:
      case Types.LONGNVARCHAR: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getNString(col);
          }
        };
      }

      // Binary == fixed, VARBINARY and LONGVARBINARY == bytes
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getBytes(col);
          }
        };
      }

      // Date is day + moth + year
      case Types.DATE: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getDate(col, DateTimeUtils.UTC_CALENDAR.get());
          }
        };
      }

      // Time is a time of day -- hour, minute, seconds, nanoseconds
      case Types.TIME: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getTime(col, DateTimeUtils.UTC_CALENDAR.get());
          }
        };
      }

      // Timestamp is a date + time
      case Types.TIMESTAMP: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            return rs.getTimestamp(col, DateTimeUtils.UTC_CALENDAR.get());
          }
        };
      }

      // Datalink is basically a URL -> string
      case Types.DATALINK: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            URL url = rs.getURL(col);
            return (url != null ? url.toString() : null);
          }
        };
      }

      // BLOB == fixed
      case Types.BLOB: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException, IOException {
            Blob blob = rs.getBlob(col);
            if (blob == null) {
              return null;
            } else {
              try {
                if (blob.length() > Integer.MAX_VALUE) {
                  throw new IOException("Can't process BLOBs longer than " + Integer.MAX_VALUE);
                }
                return blob.getBytes(1, (int) blob.length());
              } finally {
                blob.free();
              }
            }
          }
        };
      }
      case Types.CLOB:
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException, IOException {
            Clob clob = rs.getClob(col);
            if (clob == null) {
              return null;
            } else {
              try {
                if (clob.length() > Integer.MAX_VALUE) {
                  throw new IOException("Can't process CLOBs longer than " + Integer.MAX_VALUE);
                }
                return clob.getSubString(1, (int) clob.length());
              } finally {
                clob.free();
              }
            }
          }
        };
      case Types.NCLOB: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException, IOException {
            Clob clob = rs.getNClob(col);
            if (clob == null) {
              return null;
            } else {
              try {
                if (clob.length() > Integer.MAX_VALUE) {
                  throw new IOException("Can't process NCLOBs longer than " + Integer.MAX_VALUE);
                }
                return clob.getSubString(1, (int) clob.length());
              } finally {
                clob.free();
              }
            }
          }
        };
      }

      // XML -> string
      case Types.SQLXML: {
        return new ColumnConverter() {
          @Override
          public Object convert(ResultSet rs) throws SQLException {
            SQLXML xml = rs.getSQLXML(col);
            return (xml != null ? xml.getString() : null);
          }
        };
      }

      case Types.NULL:
      case Types.ARRAY:
      case Types.JAVA_OBJECT:
      case Types.OTHER:
      case Types.DISTINCT:
      case Types.STRUCT:
      case Types.REF:
      case Types.ROWID:
      default: {
        // These are not currently supported, but we don't want to log something for every single
        // record we translate. There will already be errors logged for the schema translation
        break;
      }
    }
    return null;
  }

  protected int decimalScale(ColumnDefinition defn) {
    return defn.scale() == NUMERIC_TYPE_SCALE_UNSET ? NUMERIC_TYPE_SCALE_HIGH : defn.scale();
  }

  @Override
  public String buildInsertStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns
  ) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("INSERT INTO ");
    builder.append(table);
    builder.append("(");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNames())
           .of(keyColumns, nonKeyColumns);
    builder.append(") VALUES(");
    builder.appendMultiple(",", "?", keyColumns.size() + nonKeyColumns.size());
    builder.append(")");
    return builder.toString();
  }

  @Override
  public String buildUpdateStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns
  ) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("UPDATE ");
    builder.append(table);
    builder.append(" SET ");
    builder.appendList()
           .delimitedBy(", ")
           .transformedBy(ExpressionBuilder.columnNamesWith(" = ?"))
           .of(nonKeyColumns);
    if (!keyColumns.isEmpty()) {
      builder.append(" WHERE ");
      builder.appendList()
             .delimitedBy(", ")
             .transformedBy(ExpressionBuilder.columnNamesWith(" = ?"))
             .of(keyColumns);
    }
    return builder.toString();
  }

  @Override
  public String buildUpsertQueryStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns
  ) {
    throw new UnsupportedOperationException();
  }

  @Override
  public StatementBinder statementBinder(
      PreparedStatement statement,
      PrimaryKeyMode pkMode,
      SchemaPair schemaPair,
      FieldsMetadata fieldsMetadata,
      InsertMode insertMode
  ) {
    return new PreparedStatementBinder(statement, pkMode, schemaPair, fieldsMetadata, insertMode);
  }

  @Override
  public String buildCreateQuery(
      TableId table,
      Collection<SinkRecordField> fields
  ) {
    ExpressionBuilder builder = expressionBuilder();

    final List<String> pkFieldNames = extractPrimaryKeyFieldNames(fields);
    builder.append("CREATE TABLE ");
    builder.append(table);
    builder.append(" (");
    writeColumnsSpec(builder, fields);
    if (!pkFieldNames.isEmpty()) {
      builder.append(",");
      builder.append(System.lineSeparator());
      builder.append("PRIMARY KEY(");
      builder.appendList()
             .delimitedBy(",")
             .transformedBy(ExpressionBuilder.quote())
             .of(pkFieldNames);
      builder.append(")");
    }
    builder.append(")");
    return builder.toString();
  }

  @Override
  public List<String> buildAlterTable(
      TableId table,
      Collection<SinkRecordField> fields
  ) {
    final boolean newlines = fields.size() > 1;

    ExpressionBuilder builder = expressionBuilder();
    builder.append("ALTER TABLE ");
    builder.append(table);
    builder.append(" ");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(new Transform<SinkRecordField>() {
             @Override
             public void apply(
                 ExpressionBuilder builder,
                 SinkRecordField f
             ) {
               if (newlines) {
                 builder.appendNewLine();
               }
               builder.append("ADD ");
               writeColumnSpec(builder, f);
             }
           }).of(fields);
    return Collections.singletonList(builder.toString());
  }

  protected List<String> extractPrimaryKeyFieldNames(Collection<SinkRecordField> fields) {
    final List<String> pks = new ArrayList<>();
    for (SinkRecordField f : fields) {
      if (f.isPrimaryKey()) {
        pks.add(f.name());
      }
    }
    return pks;
  }

  protected void writeColumnsSpec(
      ExpressionBuilder builder,
      Collection<SinkRecordField> fields
  ) {
    Transform<SinkRecordField> transform = new Transform<SinkRecordField>() {
      @Override
      public void apply(
          ExpressionBuilder builder,
          SinkRecordField field
      ) {
        builder.append(System.lineSeparator());
        writeColumnSpec(builder, field);
      }
    };
    builder.appendList().delimitedBy(",").transformedBy(transform).of(fields);
  }

  protected void writeColumnSpec(
      ExpressionBuilder builder,
      SinkRecordField f
  ) {
    builder.appendIdentifierQuoted(f.name());
    builder.append(" ");
    builder.append(getSqlType(f.schemaName(), f.schemaParameters(), f.schemaType()));
    if (f.defaultValue() != null) {
      builder.append(" DEFAULT ");
      formatColumnValue(builder, f.schemaName(), f.schemaParameters(), f.schemaType(),
                        f.defaultValue()
      );
    } else if (f.isOptional()) {
      builder.append(" NULL");
    } else {
      builder.append(" NOT NULL");
    }
  }

  protected void formatColumnValue(
      ExpressionBuilder builder,
      String schemaName,
      Map<String, String> schemaParameters,
      Schema.Type type,
      Object value
  ) {
    if (schemaName != null) {
      switch (schemaName) {
        case Decimal.LOGICAL_NAME:
          builder.append(value);
          return;
        case Date.LOGICAL_NAME:
          builder.appendStringQuoted(DateTimeUtils.formatUtcDate((java.util.Date) value));
          return;
        case Time.LOGICAL_NAME:
          builder.appendStringQuoted(DateTimeUtils.formatUtcTime((java.util.Date) value));
          return;
        case org.apache.kafka.connect.data.Timestamp.LOGICAL_NAME:
          builder.appendStringQuoted(DateTimeUtils.formatUtcTimestamp((java.util.Date) value));
          return;
        default:
          // fall through to regular types
          break;
      }
    }
    switch (type) {
      case INT8:
      case INT16:
      case INT32:
      case INT64:
      case FLOAT32:
      case FLOAT64:
        // no escaping required
        builder.append(value);
        break;
      case BOOLEAN:
        // 1 & 0 for boolean is more portable rather than TRUE/FALSE
        builder.append((Boolean) value ? '1' : '0');
        break;
      case STRING:
        builder.appendStringQuoted(value);
        break;
      case BYTES:
        final byte[] bytes;
        if (value instanceof ByteBuffer) {
          final ByteBuffer buffer = ((ByteBuffer) value).slice();
          bytes = new byte[buffer.remaining()];
          buffer.get(bytes);
        } else {
          bytes = (byte[]) value;
        }
        builder.appendBinaryLiteral(bytes);
        break;
      default:
        throw new ConnectException("Unsupported type for column value: " + type);
    }
  }

  protected String getSqlType(
      String schemaName,
      Map<String, String> parameters,
      Schema.Type type
  ) {
    throw new ConnectException(String.format(
        "%s (%s) type doesn't have a mapping to the SQL database column type", schemaName, type));
  }

  @Override
  public String toString() {
    return getClass().getSimpleName().replace("DatabaseDialect", "");
  }
}
