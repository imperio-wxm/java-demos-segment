package com.wxmimperio.cassandra.connection;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.QueryOptions;
import com.sun.deploy.util.SessionProperties;

/**
 * Created by weiximing.imperio on 2016/9/7.
 */
public class CassandraConn {

    public static Cluster getCluster() {
        Cluster cluster = null;
        try {
            PoolingOptions poolingOptions = new PoolingOptions();
            poolingOptions
                    .setCoreConnectionsPerHost(HostDistance.LOCAL, 4)
                    .setMaxConnectionsPerHost(HostDistance.LOCAL, 10)
                    .setCoreConnectionsPerHost(HostDistance.REMOTE, 2)
                    .setMaxConnectionsPerHost(HostDistance.REMOTE, 4)
                    .setIdleTimeoutSeconds(900);

            QueryOptions queryOptions = new QueryOptions();

            cluster = Cluster.builder()
                    .addContactPoints("10.128.74.81")
                    //.addContactPoints("10.128.113.56")
                    .withPoolingOptions(poolingOptions)
                    .build();

            /*cluster = Cluster.builder()
                    .addContactPoint("10.1.11.226")
                    .build();*/

        } catch (Exception e) {
            e.printStackTrace();
        }
        return cluster;
    }
}
