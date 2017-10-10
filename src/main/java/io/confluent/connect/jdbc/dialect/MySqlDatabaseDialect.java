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
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;

import java.util.Collection;
import java.util.Map;

import io.confluent.connect.jdbc.dialect.DatabaseDialectProvider.SubprotocolBasedProvider;
import io.confluent.connect.jdbc.util.ColumnId;
import io.confluent.connect.jdbc.util.ExpressionBuilder;
import io.confluent.connect.jdbc.util.ExpressionBuilder.Transform;
import io.confluent.connect.jdbc.util.IdentifierRules;
import io.confluent.connect.jdbc.util.TableId;

/**
 * A {@link DatabaseDialect} for MySQL.
 */
public class MySqlDatabaseDialect extends GenericDatabaseDialect {
  /**
   * The provider for {@link MySqlDatabaseDialect}.
   */
  public static class Provider extends SubprotocolBasedProvider {
    public Provider() {
      super(MySqlDatabaseDialect.class.getSimpleName(), "mariadb", "mysql");
    }

    @Override
    public DatabaseDialect create(AbstractConfig config) {
      return new MySqlDatabaseDialect(config);
    }
  }

  /**
   * Create a new dialect instance with the given connector configuration.
   *
   * @param config the connector configuration; may not be null
   */
  public MySqlDatabaseDialect(AbstractConfig config) {
    super(config, new IdentifierRules(".", "`", "`"));
  }

  @Override
  protected String getSqlType(
      String schemaName,
      Map<String, String> parameters,
      Schema.Type type
  ) {
    if (schemaName != null) {
      switch (schemaName) {
        case Decimal.LOGICAL_NAME:
          // Maximum precision supported by MySQL is 65
          return "DECIMAL(65," + Integer.parseInt(parameters.get(Decimal.SCALE_FIELD)) + ")";
        case Date.LOGICAL_NAME:
          return "DATE";
        case Time.LOGICAL_NAME:
          return "TIME(3)";
        case Timestamp.LOGICAL_NAME:
          return "DATETIME(3)";
        default:
          // pass through to primitive types
      }
    }
    switch (type) {
      case INT8:
        return "TINYINT";
      case INT16:
        return "SMALLINT";
      case INT32:
        return "INT";
      case INT64:
        return "BIGINT";
      case FLOAT32:
        return "FLOAT";
      case FLOAT64:
        return "DOUBLE";
      case BOOLEAN:
        return "TINYINT";
      case STRING:
        return "VARCHAR(256)";
      case BYTES:
        return "VARBINARY(1024)";
      default:
        return super.getSqlType(schemaName, parameters, type);
    }
  }

  @Override
  public String buildUpsertQueryStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns
  ) {
    //MySql doesn't support SQL 2003:merge so here how the upsert is handled
    final Transform<ColumnId> transform = new Transform<ColumnId>() {
      @Override
      public void apply(
          ExpressionBuilder builder,
          ColumnId col
      ) {
        builder.appendIdentifierQuoted(col.name());
        builder.append("=values(");
        builder.appendIdentifierQuoted(col.name());
        builder.append(")");
      }
    };

    ExpressionBuilder builder = expressionBuilder();
    builder.append("insert into ");
    builder.append(table);
    builder.append("(");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNames())
           .of(keyColumns, nonKeyColumns);
    builder.append(") values(");
    builder.appendMultiple(",", "?", keyColumns.size() + nonKeyColumns.size());
    builder.append(") on duplicate key update ");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(transform)
           .of(nonKeyColumns.isEmpty() ? keyColumns : nonKeyColumns);
    return builder.toString();
  }

}
