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

import java.sql.Connection;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public abstract class DatabaseDialectProvider {

  /**
   * Information about a JDBC URL.
   *
   * <p>The format of a JDBC URL is defined by the JDBC 4.2 specification as:
   * <pre>
   * jdbc:&lt;subprotocol>:&lt;subname>
   * </pre>
   *
   * <p>where {@code subprotocol} defines the kind of database connectivity mechanism that may be
   * supported by one or more drivers. The contents and syntax of the {@code subname} will depend on
   * the subprotocol.
   */
  public interface JdbcUrlInfo {
    String subprotocol();

    String subname();

    String url();
  }

  private final String name;

  protected DatabaseDialectProvider(String name) {
    assert name != null;
    this.name = name;
  }

  /**
   * The score that can be returned if the dialect does not match the connection.
   */
  public static final int NO_MATCH_SCORE = 0;

  /**
   * The minimum score that should be returned by the {@link #score(JdbcUrlInfo)} method to signal
   * that the DatabaseDialect instance can be used with a connection.
   */
  public static final int MINIMUM_MATCHING_SCORE = 1;

  /**
   * The "average" score that should be returned by the {@link #score(JdbcUrlInfo)} method to signal
   * that the DatabaseDialect instance can be used with a connection and .
   */
  public static final int AVERAGE_MATCHING_SCORE = 10;

  /**
   * The score for a dialect that is an excellent match.
   */
  public static final int PERFECT_MATCHING_SCORE = 1000;

  /**
   * Return the score describing how well this {@link DatabaseDialect} instance works with the given
   * JDBC URL and {@link Connection}. The DatabaseDialect that scores the highest for a given
   * connection will be used.
   *
   * @param jdbcInfo the information about the JDBC connection URL; may not be null
   * @return the score; may be less than {@link #MINIMUM_MATCHING_SCORE} if the dialect does not
   *     apply, or greater than or equal to {@link #MINIMUM_MATCHING_SCORE} if the DatabaseDialect
   *     can be used with this connection.
   */
  public abstract int score(JdbcUrlInfo jdbcInfo);

  /**
   * Create a dialect instance.
   *
   * @param config the connector configuration
   * @return the dialect instance; never null
   */
  public abstract DatabaseDialect create(AbstractConfig config);

  /**
   * Return the name of the dialect.
   *
   * @return the name of the dialect; may not be null
   */
  public String dialectName() {
    return name;
  }

  @Override
  public String toString() {
    return dialectName();
  }

  /**
   * An abstract base class for a provider that will return a
   * {@link DatabaseDialectProvider#PERFECT_MATCHING_SCORE}
   * if any of the supplied JDBC subprotocols match the subprotocol from the JDBC connection URL, or
   * {@link DatabaseDialectProvider#NO_MATCH_SCORE} otherwise.
   */
  public abstract static class SubprotocolBasedProvider extends DatabaseDialectProvider {
    private final Set<String> subprotocols;

    protected SubprotocolBasedProvider(
        String name,
        final String... subprotocols
    ) {
      this(name, Arrays.asList(subprotocols));
    }

    protected SubprotocolBasedProvider(
        String name,
        final Collection<String> subprotocols
    ) {
      super(name);
      this.subprotocols = new HashSet<>(subprotocols);
    }

    @Override
    public int score(JdbcUrlInfo urlInfo) {
      if (urlInfo != null) {
        for (String subprotocol : subprotocols) {
          if (subprotocol.equalsIgnoreCase(urlInfo.subprotocol())) {
            return PERFECT_MATCHING_SCORE;
          }
        }
        // If still no match, see if the URL's subprotocol and subname start with the specified ...
        String combined = urlInfo.subprotocol() + ":" + urlInfo.subname();
        combined = combined.toLowerCase(Locale.getDefault());
        for (String subprotocol : subprotocols) {
          if (combined.startsWith(subprotocol.toLowerCase(Locale.getDefault()))) {
            return PERFECT_MATCHING_SCORE;
          }
        }
      }
      return NO_MATCH_SCORE;
    }
  }

  /**
   * An abstract base class for a provider that will return a fixed score.
   */
  public abstract static class FixedScoreProvider extends DatabaseDialectProvider {
    private final int score;

    protected FixedScoreProvider(
        String name,
        int score
    ) {
      super(name);
      this.score = score;
    }

    @Override
    public int score(JdbcUrlInfo jdbcInfo) {
      return score;
    }
  }
}
