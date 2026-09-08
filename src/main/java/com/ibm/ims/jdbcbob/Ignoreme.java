//please ignore this file, this is additionally functionality I might want to add later

private static void displayMetadata() throws Exception {
        Connection connection = createAnImsConnection(4);
        DatabaseMetaData dbmd = connection.getMetaData();
        
        // Display IMS PCB information
        ResultSet rs = dbmd.getSchemas("DFSSAM09", null);
        ResultSetMetaData rsmd = rs.getMetaData();
        int colCount = rsmd.getColumnCount();

        System.out.println("Displaying IMS PCB metadata");
        while (rs.next()) {
            for (int i = 1; i <= colCount; i++) {
                System.out.println(rsmd.getColumnName(i) + ": " + rs.getString(i));
            }
            System.out.println();
        }

        // Display IMS segment information
        rs = dbmd.getTables("DFSSAM09", "PCB01", null, null);
        rsmd = rs.getMetaData();
        colCount = rsmd.getColumnCount();
        
        System.out.println("\nDisplaying IMS Segment metadata");
        while (rs.next()) {
            for (int i = 1; i <= colCount; i++) {
                System.out.println(rsmd.getColumnName(i) + ": " + rs.getString(i));
            }
            System.out.println();
        }

        // Display IMS field information
        rs = dbmd.getColumns("DFSSAM09", "PCB01", "PARTROOT", null);
        rsmd = rs.getMetaData();
        colCount = rsmd.getColumnCount();
        
        System.out.println("\nDisplaying IMS Field metadata");
        while (rs.next()) {
            for (int i = 1; i <= colCount; i++) {
                System.out.println(rsmd.getColumnName(i) + ": " + rs.getString(i));
            }
            System.out.println();
        }
        
        connection.commit();
        connection.close();
    }