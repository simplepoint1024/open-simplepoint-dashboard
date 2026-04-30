package org.simplepoint.data.datasource;

import org.simplepoint.data.datasource.context.DataSourceContextHolder;
import org.simplepoint.data.datasource.properties.SimpleDataSourceConfigProperties;
import org.simplepoint.data.datasource.properties.SimpleDataSourceProperties;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/**
 * A simple routing DataSource that routes to different DataSources
 * based on the current context held in DataSourceContextHolder.
 * It initializes by registering all data source configurations
 * without creating actual DataSource instances.
 */
public class SimpleRoutingDataSource extends AbstractDataSource {

    private final SimpleDataSourceConfigProperties properties;

    /**
     * Constructor that initializes the routing data source with the given configuration properties.
     * It registers all data source configurations without creating actual DataSource instances.
     *
     * @param properties the configuration properties for the data sources
     */
    public SimpleRoutingDataSource(SimpleDataSourceConfigProperties properties) {
        this.properties = properties;
        DataSourceContextHolder.setDefaultDataSourceKey(properties.getDefaultName());
        Set<SimpleDataSourceProperties> dataSourceProperties = properties.getList();
        if (dataSourceProperties == null || dataSourceProperties.isEmpty()) {
            throw new IllegalArgumentException("At least one data source configuration is required");
        }
        dataSourceProperties.forEach(props ->
                DataSourceContextHolder.putProperties(props.getName(), props)
        );
    }

    @Override
    public Connection getConnection() throws SQLException {
        return determineTargetDataSource().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return determineTargetDataSource().getConnection(username, password);
    }

    private DataSource determineTargetDataSource() {
        String key = DataSourceContextHolder.get();
        if (key != null) {
            DataSource ds = DataSourceContextHolder.getDataSource(key);
            if (ds != null) {
                return ds;
            }
        }
        // 默认数据源
        return DataSourceContextHolder.getDefaultDataSource();
    }
}

