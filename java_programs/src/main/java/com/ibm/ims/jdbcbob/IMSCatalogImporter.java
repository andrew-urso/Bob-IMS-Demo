package com.ibm.ims.jdbcbob;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.*;

/**
 * IMSCatalogImporter - Submits IBMUSER.DFS.JCLLIB(CATIMP) job via z/OSMF REST API
 * 
 * This utility automates the IMPORT DEFN SOURCE(CATALOG) step required after
 * DDL changes (CREATE/ALTER/DROP) to activate definitions in the IMS runtime.
 * 
 * Usage:
 *   mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSCatalogImporter"
 * 
 * Prerequisites:
 *   - z/OSMF REST API accessible at IMSConnectionConfig.ZOSMF_HOST:ZOSMF_PORT
 *   - IBMUSER.DFS.JCLLIB(CATIMP) job exists on z/OS
 *   - z/OSMF credentials configured in IMSConnectionConfig (ZOSMF_USER / ZOSMF_PASSWORD)
 * 
 * Exit codes:
 *   0 = Success (job completed with CC 0000)
 *   1 = Job submission failed
 *   2 = Job completed with non-zero return code
 *   3 = Connection/authentication error
 */
public class IMSCatalogImporter {
    
    private static final String ZOSMF_HOST = IMSConnectionConfig.ZOSMF_HOST;
    private static final int ZOSMF_PORT = IMSConnectionConfig.ZOSMF_PORT;
    private static final String ZOSMF_USER = IMSConnectionConfig.ZOSMF_USER;
    private static final String ZOSMF_PASSWORD = IMSConnectionConfig.ZOSMF_PASSWORD;
    private static final String JOB_DATASET = "IBMUSER.DFS.JCLLIB(CATIMP)";
    
    /**
     * Trust-all SSL context for z/OSMF self-signed certificates.
     * Installed once at startup; affects all HTTPS connections in this JVM.
     */
    private static void trustAllCerts() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("IMS Catalog Importer");
        System.out.println("Submitting: " + JOB_DATASET);
        System.out.println("========================================\n");
        
        try {
            // Trust z/OSMF self-signed certificate
            trustAllCerts();

            // Validate credentials
            if (ZOSMF_USER == null || ZOSMF_USER.isEmpty() ||
                ZOSMF_PASSWORD == null || ZOSMF_PASSWORD.isEmpty()) {
                System.err.println("ERROR: z/OSMF credentials not set in IMSConnectionConfig.");
                System.exit(3);
            }
            
            // Step 1: Submit the job
            String jobId = submitJob();
            System.out.println("✓ Job submitted: " + jobId);
            System.out.println("  Waiting for completion...\n");
            
            // Step 2: Wait for job completion
            String returnCode = waitForJobCompletion(jobId);
            
            // Step 3: Report results
            if ("CC 0000".equals(returnCode)) {
                System.out.println("========================================");
                System.out.println("✓ IMPORT DEFN SOURCE(CATALOG) completed successfully");
                System.out.println("  Job ID: " + jobId);
                System.out.println("  Return Code: " + returnCode);
                System.out.println("========================================");
                System.exit(0);
            } else {
                System.err.println("========================================");
                System.err.println("✗ Job completed with errors");
                System.err.println("  Job ID: " + jobId);
                System.err.println("  Return Code: " + returnCode);
                System.err.println("========================================");
                System.exit(2);
            }
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Submit JCL job via z/OSMF REST API
     * @return Job ID
     */
    private static String submitJob() throws Exception {
        String urlString = String.format("https://%s:%d/zosmf/restjobs/jobs", 
            ZOSMF_HOST, ZOSMF_PORT);
        
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            // Configure connection
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", getBasicAuth());
            conn.setRequestProperty("X-CSRF-ZOSMF-HEADER", "");
            
            // Request body - submit from dataset
            String requestBody = String.format("{\"file\":\"//'%s'\"}", JOB_DATASET);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }
            
            // Check response
            int responseCode = conn.getResponseCode();
            if (responseCode != 201) {
                throw new IOException("Job submission failed. HTTP " + responseCode + 
                    ": " + readResponse(conn.getErrorStream()));
            }
            
            // Parse job ID from response
            String response = readResponse(conn.getInputStream());
            return extractJobId(response);
            
        } finally {
            conn.disconnect();
        }
    }
    
    /**
     * Wait for job completion and return the return code
     * @param jobId Job ID to monitor
     * @return Return code (e.g., "CC 0000")
     */
    private static String waitForJobCompletion(String jobId) throws Exception {
        String urlString = String.format("https://%s:%d/zosmf/restjobs/jobs/%s", 
            ZOSMF_HOST, ZOSMF_PORT, jobId);
        
        int maxAttempts = 60; // 5 minutes max (5 second intervals)
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            
            try {
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", getBasicAuth());
                conn.setRequestProperty("X-CSRF-ZOSMF-HEADER", "");
                
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new IOException("Job status check failed. HTTP " + responseCode);
                }
                
                String response = readResponse(conn.getInputStream());
                String status = extractStatus(response);
                
                if ("OUTPUT".equals(status)) {
                    // Job completed
                    return extractReturnCode(response);
                }
                
                // Job still running
                Thread.sleep(5000); // Wait 5 seconds
                attempt++;
                
            } finally {
                conn.disconnect();
            }
        }
        
        throw new Exception("Job did not complete within timeout period");
    }
    
    /**
     * Generate Basic Authentication header
     */
    private static String getBasicAuth() {
        String credentials = ZOSMF_USER + ":" + ZOSMF_PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Read HTTP response stream
     */
    private static String readResponse(InputStream is) throws IOException {
        if (is == null) return "";
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
    
    /**
     * Extract job ID from submission response
     */
    private static String extractJobId(String json) {
        Pattern pattern = Pattern.compile("\"jobid\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Could not extract job ID from response");
    }
    
    /**
     * Extract job status from status response
     */
    private static String extractStatus(String json) {
        Pattern pattern = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "UNKNOWN";
    }
    
    /**
     * Extract return code from status response
     */
    private static String extractReturnCode(String json) {
        Pattern pattern = Pattern.compile("\"retcode\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "UNKNOWN";
    }
}
