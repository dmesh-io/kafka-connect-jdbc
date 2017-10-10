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
 * A {@link DatabaseDialect} for Derby and IBM DB2.
 */
public class DerbyDatabaseDialect extends GenericDatabaseDialect {

  /**
   * The provider for {@link DerbyDatabaseDialect}.
   */
  public static class Provider extends SubprotocolBasedProvider {
    public Provider() {
      super(DerbyDatabaseDialect.class.getSimpleName(), "derby");
    }

    @Override
    public DatabaseDialect create(AbstractConfig config) {
      return new DerbyDatabaseDialect(config);
    }
  }

  /**
   * Create a new dialect instance with the given connector configuration.
   *
   * @param config the connector configuration; may not be null
   */
  public DerbyDatabaseDialect(AbstractConfig config) {
    super(config, new IdentifierRules(".", "\"", "\""));
  }

  @Override
  protected String currentTimestampDatabaseQuery() {
    return "values(CURRENT_TIMESTAMP)";
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
          return "DECIMAL(31," + parameters.get(Decimal.SCALE_FIELD) + ")";
        case Date.LOGICAL_NAME:
          return "DATE";
        case Time.LOGICAL_NAME:
          return "TIME";
        case Timestamp.LOGICAL_NAME:
          return "TIMESTAMP";
        default:
          // fall through to normal types
      }
    }
    switch (type) {
      case INT8:
        return "SMALLINT";
      case INT16:
        return "SMALLINT";
      case INT32:
        return "INTEGER";
      case INT64:
        return "BIGINT";
      case FLOAT32:
        return "FLOAT";
      case FLOAT64:
        return "DOUBLE";
      case BOOLEAN:
        return "SMALLINT";
      case STRING:
        return "VARCHAR(32672)";
      case BYTES:
        return "BLOB(64000)";
      default:
        return super.getSqlType(schemaName, parameters, type);
    }
  }

  @Override
  public String buildUpsertQueryStatement(
      final TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns
  ) {
    // http://lpar.ath0.com/2013/08/12/upsert-in-db2/
    ExpressionBuilder builder = expressionBuilder();
    builder.append("merge into ");
    builder.append(table);
    builder.append(" using (values(");
    builder.appendList()
           .delimitedBy(", ")
           .transformedBy(ExpressionBuilder.placeholderInsteadOfColumnNames("?"))
           .of(keyColumns, nonKeyColumns);
    builder.append(")) as DAT(");
    builder.appendList()
           .delimitedBy(", ")
           .transformedBy(ExpressionBuilder.columnNames())
           .of(keyColumns, nonKeyColumns);
    builder.append(") on ");
    builder.appendList()
           .delimitedBy(" and ")
           .transformedBy(new Transform<ColumnId>() {
             @Override
             public void apply(
                 ExpressionBuilder builder,
                 ColumnId col
             ) {
               builder.append(table)
                      .append(".")
                      .appendIdentifierQuoted(col.name())
                      .append("=DAT.")
                      .appendIdentifierQuoted(col.name());
             }
           })
           .of(keyColumns);
    if (nonKeyColumns != null && !nonKeyColumns.isEmpty()) {
      builder.append(" when matched then update set ");
      builder.appendList()
             .delimitedBy(", set ")
             .transformedBy(new Transform<ColumnId>() {
               @Override
               public void apply(
                   ExpressionBuilder builder,
                   ColumnId col
               ) {
                 builder.append(table)
                        .append(".")
                        .appendIdentifierQuoted(col.name())
                        .append("=DAT.")
                        .appendIdentifierQuoted(col.name());
               }
             })
             .of(nonKeyColumns);
    }

    builder.append(" when not matched then insert(");
    builder.appendList().delimitedBy(",").of(nonKeyColumns, keyColumns);
    builder.append(") values(");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNamesWithPrefix("DAT."))
           .of(nonKeyColumns, keyColumns);
    builder.append(")");
    return builder.toString();
  }

}
