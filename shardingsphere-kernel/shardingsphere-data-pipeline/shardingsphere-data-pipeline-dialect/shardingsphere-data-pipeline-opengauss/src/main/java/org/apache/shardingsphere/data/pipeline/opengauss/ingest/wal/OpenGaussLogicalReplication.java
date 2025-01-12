/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.opengauss.ingest.wal;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.impl.StandardPipelineDataSourceConfiguration;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.yaml.YamlJdbcConfiguration;
import org.apache.shardingsphere.data.pipeline.postgresql.ingest.wal.decode.BaseLogSequenceNumber;
import org.opengauss.PGProperty;
import org.opengauss.jdbc.PgConnection;
import org.opengauss.replication.LogSequenceNumber;
import org.opengauss.replication.PGReplicationStream;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Logical replication for openGauss.
 */
@Slf4j
public final class OpenGaussLogicalReplication {
    
    /**
     * Create connection.
     *
     * @param pipelineDataSourceConfig pipeline data source configuration
     * @return connection
     * @throws SQLException SQL exception
     */
    public Connection createConnection(final StandardPipelineDataSourceConfiguration pipelineDataSourceConfig) throws SQLException {
        Properties props = new Properties();
        YamlJdbcConfiguration jdbcConfig = pipelineDataSourceConfig.getJdbcConfig();
        PGProperty.USER.set(props, jdbcConfig.getUsername());
        PGProperty.PASSWORD.set(props, jdbcConfig.getPassword());
        PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
        PGProperty.REPLICATION.set(props, "database");
        PGProperty.PREFER_QUERY_MODE.set(props, "simple");
        return DriverManager.getConnection(jdbcConfig.getJdbcUrl(), props);
    }
    
    /**
     * Create OpenGauss replication stream.
     *
     * @param connection connection
     * @param startPosition start position
     * @param slotName slot name
     * @return replication stream
     * @throws SQLException SQL exception
     */
    public PGReplicationStream createReplicationStream(final PgConnection connection, final BaseLogSequenceNumber startPosition, final String slotName) throws SQLException {
        return connection.getReplicationAPI()
                .replicationStream()
                .logical()
                .withSlotName(slotName)
                .withSlotOption("include-xids", true)
                .withSlotOption("skip-empty-xacts", true)
                .withStartPosition((LogSequenceNumber) startPosition.get())
                .start();
    }
}
