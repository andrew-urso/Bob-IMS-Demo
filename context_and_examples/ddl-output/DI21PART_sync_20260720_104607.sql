-- Catalog import: sync IMS catalog after ALTER TABLE on DI21PART/STOKSTAT
-- Run after COMMIT DDL to make new columns visible to DL/I and JDBC queries.

SYNC DDL;
