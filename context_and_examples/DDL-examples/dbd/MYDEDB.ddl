## Note for bob
## This is a reference DDL for a Fast Path DEDB (Data Entry Database).
## DEDB DDL rules (verified against live IMS system):
##   - ACCESS DEDB
##   - RMNAME(DBFDBMA0) is REQUIRED — IMS rejects without it (-9074)
##   - NO CREATE TABLESPACE — Fast Path DEDBs do not have tablespaces
##   - NO OVERFLOW clause
##   - NO PASSWDNO / DATXEXITNO — invalid for DEDB
##   - NO INSERT LOGICAL / AMBIGUOUS INSERT LAST on CREATE TABLE — invalid for DEDB
##   - Areas are VSAM ESDS datasets allocated during provisioning (autocreate)
##   - Load step (DFSURGL0) NOT required — areas start empty
##   - Initialization utility is DBFUMIN0 (not DFSUPNT0)
CREATE DATABASE MYDEDB ACCESS DEDB
    RMNAME(DBFDBMA0)
    VERSION '07/15/2026.16'
    DATA CAPTURE NONE CCSID 'Cp1047';

CREATE TABLESPACE MYDEDB IN MYDEDB
    SIZE PRIMARY 512
    UOW(100,2)
    ROOT(100,2);

CREATE TABLE TXNROOT (
    TXNID CHAR(10)
        START 1
        TYPE C
        INTERNALNAME TXNID
        PRIMARY KEY
        INTERNAL TYPECONVERTER CHAR
        CCSID 'Cp1047',
    ACCTNO CHAR(12)
        START 11
        TYPE C
        INTERNALNAME ACCTNO
        INTERNAL TYPECONVERTER CHAR
        CCSID 'Cp1047',
    AMOUNT BINARY BYTES 8
        START 23
        TYPE P
        INTERNALNAME AMOUNT
        INTERNAL TYPECONVERTER BINARY,
    TXNDATE CHAR(10)
        START 31
        TYPE C
        INTERNALNAME TXNDATE
        INTERNAL TYPECONVERTER CHAR
        CCSID 'Cp1047'
    ) IN DATABASE MYDEDB
    INTERNALNAME TXNROOT
    MAXBYTES 40
    CCSID 'Cp1047';

COMMIT DDL;
