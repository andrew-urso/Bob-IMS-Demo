package com.ibm.ims.jdbcbob;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

public class IMSDDL {
    public static void main(String[] args) {
        // Build properties for Type-4 TCP/IP authentication
        Properties props = new Properties();
        props.setProperty("user", "YOUR_MAINFRAME_RACF_USER");
        props.setProperty("password", "YOUR_PASSWORD");
        
        // Define the Type-4 Connection URL targeting the DDL management PSB
        // Structure: jdbc:ims://[host]:[port]/[PSB_Name]:[properties];
        String url = "jdbc:ims://://company.com;";
        
        System.out.println("Connecting to IMS remotely...");
        
        try (Connection conn = DriverManager.getConnection(url, props);
             Statement stmt = conn.createStatement()) {
            
            // 1. Create the new Database structure
            String createDb = "CREATE DATABASE InventoryDB ACCESS PHIDAM OSAM;";
            stmt.execute(createDb);
            System.out.println("Database metadata structure created in staging.");

            // 2. Commit the DDL to the staging catalog so IMS tracks it
            stmt.execute("COMMIT DDL;");
            System.out.println("DDL changes committed to the staging data sets.");
            
        } catch (Exception e) {
            System.err.println("DDL Execution failed!");
            e.printStackTrace();
        }
    }
}

