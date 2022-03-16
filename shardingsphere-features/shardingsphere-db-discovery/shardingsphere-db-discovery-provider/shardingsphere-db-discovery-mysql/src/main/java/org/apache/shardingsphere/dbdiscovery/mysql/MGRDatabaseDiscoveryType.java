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

package org.apache.shardingsphere.dbdiscovery.mysql;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.dbdiscovery.spi.DatabaseDiscoveryType;
import org.apache.shardingsphere.infra.config.exception.ShardingSphereConfigurationException;
import org.apache.shardingsphere.infra.eventbus.ShardingSphereEventBus;
import org.apache.shardingsphere.infra.rule.event.impl.DataSourceDisabledEvent;
import org.apache.shardingsphere.infra.rule.event.impl.PrimaryDataSourceChangedEvent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * MGR database discovery type.
 */
@Slf4j
public final class MGRDatabaseDiscoveryType implements DatabaseDiscoveryType {
    
    private static final String PLUGIN_STATUS = "SELECT * FROM information_schema.PLUGINS WHERE PLUGIN_NAME='group_replication'";
    
    private static final String MEMBER_COUNT = "SELECT count(*) FROM performance_schema.replication_group_members";
    
    private static final String GROUP_NAME = "SELECT * FROM performance_schema.global_variables WHERE VARIABLE_NAME='group_replication_group_name'";
    
    private static final String SINGLE_PRIMARY = "SELECT * FROM performance_schema.global_variables WHERE VARIABLE_NAME='group_replication_single_primary_mode'";
    
    private static final String MEMBER_LIST = "SELECT MEMBER_HOST, MEMBER_PORT, MEMBER_STATE FROM performance_schema.replication_group_members";
    
    private String oldPrimaryDataSource;
    
    @Getter
    @Setter
    private Properties props = new Properties();
    
    @Override
    public void checkDatabaseDiscoveryConfiguration(final String schemaName, final Map<String, DataSource> dataSourceMap) throws SQLException {
        try (Connection connection = dataSourceMap.values().stream().findFirst().get().getConnection();
             Statement statement = connection.createStatement()) {
            checkPluginIsActive(statement);
            checkMemberCount(statement);
            checkServerGroupName(statement);
            checkIsSinglePrimaryMode(statement);
            checkDataSourceInReplicationGroup(statement, dataSourceMap);
        }
    }
    
    private void checkPluginIsActive(final Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(PLUGIN_STATUS)) {
            while (resultSet.next()) {
                if (!"ACTIVE".equals(resultSet.getString("PLUGIN_STATUS"))) {
                    throw new ShardingSphereConfigurationException("MGR plugin is not active.");
                }
            }
        }
    }
    
    private void checkMemberCount(final Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(MEMBER_COUNT)) {
            while (resultSet.next()) {
                if (resultSet.getInt(1) < 1) {
                    throw new ShardingSphereConfigurationException("MGR member count < 1");
                }
            }
        }
    }
    
    private void checkServerGroupName(final Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(GROUP_NAME)) {
            while (resultSet.next()) {
                String serverGroupName = resultSet.getString("VARIABLE_VALUE");
                String ruleGroupName = props.getProperty("group-name");
                if (!serverGroupName.equals(ruleGroupName)) {
                    throw new ShardingSphereConfigurationException("MGR group name is not consistent\n" + "serverGroupName: %s\nruleGroupName: %s", serverGroupName, ruleGroupName);
                }
            }
        }
    }
    
    private void checkIsSinglePrimaryMode(final Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(SINGLE_PRIMARY)) {
            while (resultSet.next()) {
                if (!"ON".equals(resultSet.getString("VARIABLE_VALUE"))) {
                    throw new ShardingSphereConfigurationException("MGR is not in single primary mode");
                }
            }
        }
    }
    
    private void checkDataSourceInReplicationGroup(final Statement statement, final Map<String, DataSource> dataSourceMap) throws SQLException {
        Collection<String> memberDataSourceURLs = new LinkedList<>();
        try (ResultSet resultSet = statement.executeQuery(MEMBER_LIST)) {
            while (resultSet.next()) {
                memberDataSourceURLs.add(String.format("%s:%s", resultSet.getString("MEMBER_HOST"), resultSet.getString("MEMBER_PORT")));
            }
        }
        for (Entry<String, DataSource> entry : dataSourceMap.entrySet()) {
            checkDataSourceExistedWithGroupMember(entry.getKey(), entry.getValue(), memberDataSourceURLs);
        }
    }
    
    private void checkDataSourceExistedWithGroupMember(final String datasourceName, final DataSource dataSource, final Collection<String> memberDataSourceURLs) throws SQLException {
        boolean isExisted = false;
        for (String each : memberDataSourceURLs) {
            if (dataSource.getConnection().getMetaData().getURL().contains(each)) {
                isExisted = true;
                break;
            }
        }
        if (!isExisted) {
            throw new ShardingSphereConfigurationException("%s is not MGR replication group member", datasourceName);
        }
    }
    
    @Override
    public void updatePrimaryDataSource(final String schemaName, final Map<String, DataSource> dataSourceMap, final Collection<String> disabledDataSourceNames, final String groupName) {
        Map<String, DataSource> activeDataSourceMap = new HashMap<>(dataSourceMap);
        if (!disabledDataSourceNames.isEmpty()) {
            activeDataSourceMap.entrySet().removeIf(each -> disabledDataSourceNames.contains(each.getKey()));
        }
        String newPrimaryDataSource = determinePrimaryDataSource(activeDataSourceMap);
        if (newPrimaryDataSource.isEmpty()) {
            return;
        }
        if (!newPrimaryDataSource.equals(oldPrimaryDataSource)) {
            oldPrimaryDataSource = newPrimaryDataSource;
            ShardingSphereEventBus.getInstance().post(new PrimaryDataSourceChangedEvent(schemaName, groupName, newPrimaryDataSource));
        }
    }
    
    private String determinePrimaryDataSource(final Map<String, DataSource> dataSourceMap) {
        String primaryDataSourceURL = findPrimaryDataSourceURL(dataSourceMap);
        return findPrimaryDataSourceName(primaryDataSourceURL, dataSourceMap);
    }
    
    private String findPrimaryDataSourceURL(final Map<String, DataSource> dataSourceMap) {
        String result = "";
        String sql = "SELECT MEMBER_HOST, MEMBER_PORT FROM performance_schema.replication_group_members WHERE MEMBER_ID = "
                + "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'group_replication_primary_member')";
        for (DataSource each : dataSourceMap.values()) {
            try (Connection connection = each.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(sql)) {
                if (resultSet.next()) {
                    return String.format("%s:%s", resultSet.getString("MEMBER_HOST"), resultSet.getString("MEMBER_PORT"));
                }
            } catch (final SQLException ex) {
                log.error("An exception occurred while find primary data source url", ex);
            }
        }
        return result;
    }
    
    private String findPrimaryDataSourceName(final String primaryDataSourceURL, final Map<String, DataSource> dataSourceMap) {
        String result = "";
        for (Entry<String, DataSource> entry : dataSourceMap.entrySet()) {
            String url;
            try (Connection connection = entry.getValue().getConnection()) {
                url = connection.getMetaData().getURL();
                if (null != url && url.contains(primaryDataSourceURL)) {
                    return entry.getKey();
                }
            } catch (final SQLException ex) {
                log.error("An exception occurred while find primary data source name", ex);
            }
        }
        return result;
    }
    
    @Override
    public void updateMemberState(final String schemaName, final Map<String, DataSource> dataSourceMap, final Collection<String> disabledDataSourceNames) {
        Map<String, DataSource> activeDataSourceMap = new HashMap<>(dataSourceMap);
        if (!disabledDataSourceNames.isEmpty()) {
            activeDataSourceMap.entrySet().removeIf(each -> disabledDataSourceNames.contains(each.getKey()));
        }
        List<String> memberDataSourceURLs = findMemberDataSourceURLs(activeDataSourceMap);
        if (memberDataSourceURLs.isEmpty()) {
            return;
        }
        Map<String, String> dataSourceURLs = new HashMap<>(16, 1);
        determineDisabledDataSource(schemaName, activeDataSourceMap, memberDataSourceURLs, dataSourceURLs);
        determineEnabledDataSource(dataSourceMap, schemaName, memberDataSourceURLs, dataSourceURLs);
    }
    
    private List<String> findMemberDataSourceURLs(final Map<String, DataSource> activeDataSourceMap) {
        List<String> result = new LinkedList<>();
        try (Connection connection = activeDataSourceMap.get(oldPrimaryDataSource).getConnection();
             Statement statement = connection.createStatement()) {
            ResultSet resultSet = statement.executeQuery(MEMBER_LIST);
            while (resultSet.next()) {
                if (!"ONLINE".equals(resultSet.getString("MEMBER_STATE"))) {
                    continue;
                }
                result.add(String.format("%s:%s", resultSet.getString("MEMBER_HOST"), resultSet.getString("MEMBER_PORT")));
            }
        } catch (final SQLException ex) {
            log.error("An exception occurred while find member data source urls", ex);
        }
        return result;
    }
    
    private void determineDisabledDataSource(final String schemaName, final Map<String, DataSource> activeDataSourceMap,
                                             final List<String> memberDataSourceURLs, final Map<String, String> dataSourceURLs) {
        for (Entry<String, DataSource> entry : activeDataSourceMap.entrySet()) {
            boolean disable = true;
            String url = null;
            try (Connection connection = entry.getValue().getConnection()) {
                url = connection.getMetaData().getURL();
                for (String each : memberDataSourceURLs) {
                    if (null != url && url.contains(each)) {
                        disable = false;
                        break;
                    }
                }
            } catch (final SQLException ex) {
                log.error("An exception occurred while find data source urls", ex);
            }
            if (disable) {
                ShardingSphereEventBus.getInstance().post(new DataSourceDisabledEvent(schemaName, entry.getKey(), true));
            } else if (!url.isEmpty()) {
                dataSourceURLs.put(entry.getKey(), url);
            }
        }
    }
    
    private void determineEnabledDataSource(final Map<String, DataSource> dataSourceMap, final String schemaName,
                                            final List<String> memberDataSourceURLs, final Map<String, String> dataSourceURLs) {
        for (String each : memberDataSourceURLs) {
            boolean enable = true;
            for (Entry<String, String> entry : dataSourceURLs.entrySet()) {
                if (entry.getValue().contains(each)) {
                    enable = false;
                    break;
                }
            }
            if (!enable) {
                continue;
            }
            for (Entry<String, DataSource> entry : dataSourceMap.entrySet()) {
                String url;
                try (Connection connection = entry.getValue().getConnection()) {
                    url = connection.getMetaData().getURL();
                    if (null != url && url.contains(each)) {
                        ShardingSphereEventBus.getInstance().post(new DataSourceDisabledEvent(schemaName, entry.getKey(), false));
                        break;
                    }
                } catch (final SQLException ex) {
                    log.error("An exception occurred while find enable data source urls", ex);
                }
            }
        }
    }
    
    @Override
    public String getPrimaryDataSource() {
        return oldPrimaryDataSource;
    }
    
    @Override
    public String getType() {
        return "MGR";
    }
}