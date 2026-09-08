package com.ibm.ims.jdbcbob;

import com.ibm.ims.jdbc.IMSDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

public class ListPCBs {
    public static void main(String[] args) throws Exception {
        IMSDataSource ds = new IMSDataSource();
        ds.setHost(IMSConnectionConfig.HOST);
        ds.setPortNumber(IMSConnectionConfig.PORT);
        ds.setDriverType(4);
        ds.setDatabaseName("DFSSAMT3");
        ds.setUser(IMSConnectionConfig.USER);
        ds.setPassword(IMSConnectionConfig.PASSWORD);

        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet schemas = meta.getSchemas();
            System.out.println("=== PCBs (Schemas) in PSB DFSSAMT3 ===");
            while (schemas.next()) {
                System.out.println("PCB: " + schemas.getString(1));
            }
            schemas.close();
            conn.commit();
        }
    }
}
