# Tenet 1 — Natural Language SQL Queries

## Overview

This workflow covers how Bob handles requests to **query, retrieve, or search data** from an existing IMS database. The user describes what they want in natural language; Bob discovers the correct metadata and executes the appropriate SQL query.

**Trigger keywords:** query, show, find, select, list, get, retrieve, search, display

---

## The Problem This Solves

Guessing PCB names (e.g. `PCB01`, `IVPCB`) leads to errors. This workflow uses a **two-program solution** that always discovers correct names before executing:

1. **IMSMetadataExplorer.java** — Discovers correct PCB and table names from the IMS Catalog
2. **IMSQueryExecutor.java** — Executes SQL queries using discovered metadata, saves results to CSV

---

## Complete Workflow

### Step 1: Discover Database Metadata

Before running any query, Bob must discover the correct PCB and table names:

```bash
cd /Users/andrewpurso/Documents/bob-jdbc-demo/bob-demo/Java_Programs
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSMetadataExplorer" \
  -Dexec.args="<DATABASE_NAME>"
```

**Example:**
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSMetadataExplorer" \
  -Dexec.args="DFSIVP37"
```

**Output shows:**
```
PCB #1:
  TABLE_SCHEM: PHONEAP          ← This is the PCB name
  DBD_NAME: IVPDB2

Table/Segment #1:
  TABLE_NAME: PHONEBOOK         ← This is the table name
```

### Step 2: Execute Query with Correct Metadata

Using the discovered PCB and table names, construct and run the SQL query:

```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSQueryExecutor" \
  -Dexec.args="<DATABASE> \"<SQL_QUERY>\""
```

**Example:**
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSQueryExecutor" \
  -Dexec.args="DFSIVP37 \"SELECT * FROM PHONEAP.PHONEBOOK WHERE LASTNAME = 'Smith'\""
```

---

## Bob's Decision Tree

```
User query request received
        ↓
Is this a known database (metadata already cached below)?
        ↓
YES → Use cached PCB and table names
        ↓
NO  → Run IMSMetadataExplorer
        ↓
        Discover PCB name
        ↓
        Discover table names
        ↓
        Discover column names
        ↓
Construct SQL with correct PCB.TABLE syntax
        ↓
Run IMSQueryExecutor
        ↓
Save results to timestamped CSV in Java_Programs/query_results/
```

---

## Known Databases (Cached Metadata)

### DFSIVP37
- **PCB:** `PHONEAP`
- **Table:** `PHONEBOOK`
- **Columns:** `LASTNAME`, `FIRSTNAME`, `EXTENSION`, `ZIPCODE`
- **Example Query:** `SELECT * FROM PHONEAP.PHONEBOOK WHERE LASTNAME = 'Smith'`

### DFSSAM09
- **DBD:** `DI21PART`
- **PCB:** `PCB01`
- **Tables:** `PARTROOT`, `BACKORDR`, `CYCCOUNT`, `STANINFO`, `STOKSTAT`
- **Columns (PARTROOT):** `PARTKEY`, `PARTNAME`, `PARTDESC`
- **Example Query:** `SELECT * FROM PCB01.PARTROOT`

### DFSSAMT4
- **DBD:** `DI23PART`
- **PCB:** `DBPCB01`
- **Tables:** `PARTROOT`
- **Columns (PARTROOT):** `PARTKEY`
- **Example Query:** `SELECT * FROM DBPCB01.PARTROOT`

### dfssamt3
- **PCB:** `DBPCB01`
- **Table:** `PARTROOT`
- **Columns:** `PARTKEY`, `PARTNAME`, `PARTDESC`
- **Example Query:** `SELECT * FROM DBPCB01.PARTROOT`

---

## Bob's Response Template

```
I'll help you query the [DATABASE] database. Let me first discover the correct
PCB and table names.

Step 1: Discovering metadata...
[Run IMSMetadataExplorer]

Step 2: Found the following:
- PCB Name: [PCB]
- Table Name: [TABLE]
- Columns: [COL1], [COL2], ...

Step 3: Executing your query...
[Run IMSQueryExecutor with correct SQL]

Results:
- Total records: [N]
- CSV saved to: Java_Programs/query_results/[DATABASE]_query_[timestamp].csv
```

---

## Worked Example

**User Request:** "Show me all entries in database DFSIVP37 with last name Smith"

**1. Discover metadata:**
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSMetadataExplorer" \
  -Dexec.args="DFSIVP37"
```

**2. Analyze output:**
- PCB Name: `PHONEAP`
- Table Name: `PHONEBOOK`
- Columns: `LASTNAME`, `FIRSTNAME`, `EXTENSION`, `ZIPCODE`

**3. Construct SQL:**
```sql
SELECT * FROM PHONEAP.PHONEBOOK WHERE LASTNAME = 'Smith'
```

**4. Execute:**
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSQueryExecutor" \
  -Dexec.args="DFSIVP37 \"SELECT * FROM PHONEAP.PHONEBOOK WHERE LASTNAME = 'Smith'\""
```

---

## Query Patterns

### All records
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSQueryExecutor" \
  -Dexec.args="DFSIVP37 \"SELECT * FROM PHONEAP.PHONEBOOK\""
```

### Filtered by field value
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSQueryExecutor" \
  -Dexec.args="DFSIVP37 \"SELECT * FROM PHONEAP.PHONEBOOK WHERE ZIPCODE = '12345'\""
```

### Count records
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSQueryExecutor" \
  -Dexec.args="DFSIVP37 \"SELECT COUNT(*) FROM PHONEAP.PHONEBOOK\""
```

### Specific columns only
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSQueryExecutor" \
  -Dexec.args="DFSIVP37 \"SELECT FIRSTNAME, LASTNAME FROM PHONEAP.PHONEBOOK\""
```

---

## Error Prevention

### ❌ Common Mistakes
```sql
-- Wrong: Guessing PCB name
SELECT * FROM PCB01.CUSTOMER WHERE LASTNAME = 'Smith'
→ Error: PCB name PCB01 was not found

-- Wrong: Guessing table name
SELECT * FROM IVPCB.CUSTOMER WHERE LASTNAME = 'Smith'
→ Error: PCB name IVPCB was not found
```

### ✅ Correct Approach
Always run `IMSMetadataExplorer` first for any unknown database. Never guess PCB or table names.

---

## Troubleshooting

| Error | Cause | Solution |
|-------|-------|---------|
| `PCB name not found` | Wrong or guessed PCB name | Run `IMSMetadataExplorer` to discover correct PCB |
| `Table not found` | Wrong segment/table name | Check `IMSMetadataExplorer` output for correct table name |
| `Column not found` | Wrong column name | Verify column names from `IMSMetadataExplorer` output |
| `Unable to retrieve metadata for DBD` | IMS Catalog XML is malformed | IMS administrator must rebuild the catalog entry for the DBD |

---

## Future Enhancements

1. **Column Discovery** — Add column metadata output to `IMSMetadataExplorer`
2. **Metadata Caching** — Cache discovered metadata to avoid repeated lookups per session
3. **Interactive Mode** — Prompt user to select from a list of discovered PCBs/tables
4. **Query Builder** — Generate more complex SQL (joins, aggregates) from natural language
