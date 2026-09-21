package com.ibm.ims.jdbcbob;
import com.ibm.ims.jdbc.IMSDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.io.PrintWriter;
import java.io.FileWriter;


public class MyIMSApplicationSolutions {
    public static void main(String[] args) {
        try {
            // this function runs a SQL statement and prints it out
            executeAndDisplaySqlQuery();

           
        } catch (Exception e) {
            System.out.println("Abnormal error occurred: " + e.getMessage());
            e.printStackTrace();
        }
        
    }


private static Connection createAnImsConnection(int driverType) throws Exception {
        Connection connection = null;
        
        // Exercise 1 - Establishing a distributed IMS connection
        if (driverType == 4) {
            // this is an example connection profile
            // the database name should change based off of user request 
            IMSDataSource ds = new IMSDataSource();
            ds.setHost(IMSConnectionConfig.HOST);
            ds.setPortNumber(IMSConnectionConfig.PORT);
            ds.setDriverType(driverType);
            ds.setUser(IMSConnectionConfig.USER);
            ds.setPassword(IMSConnectionConfig.PASSWORD);
            ds.setDatabaseName("DFSSAMT3");
            connection = ds.getConnection();
            
        }
        
        else {
            throw new Exception("Invalid driver type specified: " + driverType);
        }
        
        
        return connection;
    }

    

    private static void executeAndDisplaySqlQuery() throws Exception {
        // This function creates a connection
        Connection connection = createAnImsConnection(4);

        // This is an IMS SQL Select statements
        // IMS SQL statements consist of the PCB name of the database the program connects to and table name
        // The format is SELECT * FROM "PCB name"."table name"
        String sql = "SELECT * FROM DBPCB01.PARTROOT";

        Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql);

        ResultSetMetaData rsmd = rs.getMetaData();
        int colCount = rsmd.getColumnCount();

        // Export results to CSV
        String csvFile = "partroot-results.csv";
        PrintWriter writer = new PrintWriter(new FileWriter(csvFile));

        // Write CSV header
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= colCount; i++) {
            if (i > 1) header.append(",");
            header.append(rsmd.getColumnName(i));
        }
        writer.println(header);

        // Write rows to CSV and display on console
        System.out.println("\nDisplaying query results");
        int rowCount = 0;
        while (rs.next()) {
            StringBuilder csvRow = new StringBuilder();
            for (int i = 1; i <= colCount; i++) {
                String val = rs.getString(i);
                if (val != null) val = val.trim();
                if (i > 1) csvRow.append(",");
                csvRow.append(val);
                System.out.println(rsmd.getColumnName(i) + ": " + val);
            }
            writer.println(csvRow);
            System.out.println();
            rowCount++;
        }

        writer.close();
        System.out.println("Total rows: " + rowCount);
        System.out.println("Results exported to: " + new java.io.File(csvFile).getAbsolutePath());

        connection.commit();
        connection.close();
    }

    
    

    
}