# Tenet 2 — DDL Generation & Execution

## Overview

This workflow covers how Bob handles requests to **create, design, or define** an IMS database structure. The user describes what they want in natural language; Bob gathers the required details, generates valid IMS DDL using `DDL-examples/` as reference patterns, and optionally executes it via `IMSDBAExecutor`.

**Trigger keywords:** create, build, design, define, make, set up, new database, DDL

---

## The Three-Phase Approach

1. **Generation Phase** — Bob gathers intent and produces DDL. Nothing is executed.
2. **Execution Phase** — User explicitly approves before `IMSDBAExecutor` runs the DDL.
3. **PSB Phase** — After a successful COMMIT DDL, Bob asks whether the user wants to define a PSB for the new database. A PSB is required before any program (including IMS JDBC) can access the database.

These phases are always separate. Bob never executes DDL or generates a PSB without user confirmation.

---

## DDL Reference Patterns

Before generating any DDL, Bob reads all files in `DDL-examples/` to match patterns. As new examples are added to that folder, Bob's generation automatically improves to reflect them.

**Current examples:**
| File | Pattern covered |
|------|----------------|
| `DDL-examples/createDB.txt` | HISAM database, single root segment, CHAR fields |
| `PSB-DDL-examples/PSBDDL.txt` | PSB DDL — PROGRAMVIEW with SCHEMA, PCB, and SENSEGVIEW per segment |

---

## Phase 1 — Gather Intent

Bob asks the following questions in order. Required fields must be answered before generation begins.

### Question 1: Database Name
```
What is the name of your database?
```
- **Required.** No default.
- IMS convention: uppercase, up to 8 characters (e.g. `INVNTRY1`)

### Question 2: Access Method
```
Which access method would you like? (choose a number)

  [1] HISAM  — Hierarchical Indexed Sequential Access Method
               Best for: sequential + direct access, moderate data volumes,
               ordered key access. Most commonly used.

  [2] HIDAM  — Hierarchical Indexed Direct Access Method
               Best for: direct key-based access with guaranteed key ordering.
               Higher performance for random access than HISAM.

  [3] PHIDAM — Partitioned HIDAM (HALDB)
               Best for: very large databases (billions of records),
               online reorganization, direct key access.

  [4] PHDAM  — Partitioned HDAM (HALDB)
               Best for: very large databases with randomized root access,
               high-throughput insert/update workloads.

  [5] HDAM   — Hierarchical Direct Access Method
               Best for: high-volume random access where key ordering
               is not required. Uses a randomizing routine.

  [6] DEDB   — Data Entry Database (Fast Path)
               Best for: extremely high-throughput online transaction
               processing. Requires Fast Path licensed feature.
```
- **Required.** No default. Bob presents the descriptions above and waits for selection.

### Question 3: Root Segment
```
Describe your root segment:
  - Segment name?
  - Fields? (for each field: name, data type, length)
  - Which field is the primary key?
```
- **Required.** At least one segment (the root) must be defined.
- Field names: uppercase, up to 8 characters
- Supported data types in this version: `CHAR` (character), `BINARY` (packed/binary)
- Primary key field must be the first field listed

### Question 4: Child Segments (Hierarchy)
```
Do you have child segments under [ROOT_SEGMENT_NAME]?  (yes / no)
```
- If **yes**, for each child Bob asks:
  ```
  - Child segment name?
  - Parent segment name?  (which segment is directly above this one)
  - Fields? (name, type, length for each)
  - Which field is the key for this segment?
  ```
- Bob repeats until the user confirms the hierarchy is complete.
- Hierarchy can be multiple levels deep (grandchildren, etc.).

### Question 5: Storage Hints (Optional)
```
Do you have specific storage size requirements?
  - PRIMARY size in KB?   (default: 2048)
  - SECONDARY size in KB? (default: 2048)
```
- If skipped, defaults of `2048` are used for both PRIMARY and SECONDARY.
- `RECORD` size is always auto-calculated from field lengths.

---

## Phase 2 — Generate DDL

### Statement Order
Every generated DDL file follows this exact sequence:
1. `CREATE DATABASE`
2. `CREATE TABLESPACE`
3. `CREATE TABLE` — root segment
4. `CREATE TABLE` — child segments, in top-down hierarchy order (parent before child)

### Generation Rules

| Value | Rule |
|-------|------|
| `START` position | Field 1 always starts at 1. Each subsequent field: previous `START` + previous field length |
| `MAXBYTES` | Sum of all field lengths in the segment |
| `RECORD(n,n)` in TABLESPACE | Equals root segment `MAXBYTES` |
| Overflow dataset name | First 7 characters of DB name + `O` (e.g. `DI23PART` → `DI23PARO`, `INVNTRY1` → `INVNTRYО`) |
| `VERSION` timestamp | Auto-generated at DDL creation time in IMS format `MM/DD/YYYY.HH` |
| `CCSID` | Always `'Cp1047'` |
| `TYPE` | `C` for CHAR fields, `P` for packed/BINARY fields |
| `TYPECONVERTER` | `CHAR` for TYPE C fields, `BINARY` for TYPE P fields |
| `FOREIGN KEY` constraint | Child segments require a `FOREIGN KEY (parentKeyField) REFERENCES parentSegment` table-level constraint added at the end of the column list — this is how IMS JDBC DDL models the hierarchy |
| Parent key field | Child segments must include the parent's primary key as a regular column (with its own `START`, `TYPE`, `INTERNALNAME`, `TYPECONVERTER`, `CCSID`) so the `FOREIGN KEY` constraint can reference it |
| Child `MAXBYTES` | Sum of all child's own fields **plus** the parent key field included for the foreign key |

### DDL Templates

#### `CREATE DATABASE`
```sql
CREATE DATABASE <DBNAME> ACCESS <ACCESS_METHOD>
    VERSION '<MM/DD/YYYY.HH>'
    PASSWDNO
    DATXEXITNO
    DATA CAPTURE NONE CCSID 'Cp1047';
```

#### `CREATE TABLESPACE`
```sql
CREATE TABLESPACE <DBNAME> IN <DBNAME>
    RECORD(<root_MAXBYTES>,<root_MAXBYTES>)
    SIZE PRIMARY <n> SIZE SECONDARY <n>
    OVERFLOW(<DBNAME[0..6]>O);
```

#### `CREATE TABLE` — Root segment
```sql
CREATE TABLE <SEGMENTNAME> (
    <KEYFIELD> CHAR(<len>)
        START 1
        TYPE C
        INTERNALNAME <KEYFIELD>
        PRIMARY KEY
        INTERNAL TYPECONVERTER CHAR
        CCSID 'Cp1047',
    <FIELD2> CHAR(<len>)
        START <auto>
        TYPE C
        INTERNALNAME <FIELD2>
        INTERNAL TYPECONVERTER CHAR
        CCSID 'Cp1047'
    ) IN DATABASE <DBNAME>
    INTERNALNAME <SEGMENTNAME>
    MAXBYTES <sum_of_field_lengths>
    INSERT LOGICAL DELETE LOGICAL REPLACE LOGICAL
    AMBIGUOUS INSERT LAST
    CCSID 'Cp1047';
```

#### `CREATE TABLE` — Child segment
```sql
CREATE TABLE <CHILD_SEGMENTNAME> (
    <CHILD_KEYFIELD> CHAR(<len>)
        START 1
        TYPE C
        INTERNALNAME <CHILD_KEYFIELD>
        PRIMARY KEY
        INTERNAL TYPECONVERTER CHAR
        CCSID 'Cp1047',
    <FIELD2> CHAR(<len>)
        START <auto>
        TYPE C
        INTERNALNAME <FIELD2>
        INTERNAL TYPECONVERTER CHAR
        CCSID 'Cp1047',
    <PARENT_KEYFIELD> CHAR(<parent_key_len>)
        START <auto>
        TYPE C
        INTERNALNAME <PARENT_KEYFIELD>
        INTERNAL TYPECONVERTER CHAR
        CCSID 'Cp1047',
    FOREIGN KEY (<PARENT_KEYFIELD>) REFERENCES <PARENT_SEGMENTNAME>
    ) IN DATABASE <DBNAME>
    INTERNALNAME <CHILD_SEGMENTNAME>
    MAXBYTES <sum_of_all_fields_including_parent_key>
    INSERT LOGICAL DELETE LOGICAL REPLACE LOGICAL
    AMBIGUOUS INSERT LAST
    CCSID 'Cp1047';
```
> **Note:** The parent's primary key field must be included as a column in the child segment so the `FOREIGN KEY` constraint can reference it. `MAXBYTES` includes this field's length.

### Worked Example — Auto-calculated Values

User provides:
```
Segment: PARTROOT
Fields:
  PARTKEY  CHAR(17)  ← primary key
  PARTNAME CHAR(12)
  PARTDESC CHAR(21)
```

Bob calculates:
| Field | Length | START | Notes |
|-------|--------|-------|-------|
| PARTKEY | 17 | 1 | Always 1 for first field |
| PARTNAME | 12 | 18 | 1 + 17 = 18 |
| PARTDESC | 21 | 30 | 18 + 12 = 30 |

`MAXBYTES` = 17 + 12 + 21 = **50**

---

## Phase 3 — Present & Save

```
1. Bob displays the full generated DDL in the conversation for review.

2. Bob saves the DDL to:
   ddl-output/<DBNAME>_create_<timestamp>.sql

3. Bob asks:
   "Would you like to execute this DDL against IMS now?"

   YES → Run IMSDBAExecutor (see below)
         On success → ask user if they want to run IMPORT DEFN → proceed to Phase 4 (PSB)
   NO  → Done. DDL is saved. User can execute manually later.
```

---

## Phase 4 — PSB Generation (after successful COMMIT DDL)

A PSB (Program Specification Block) is required before any program — including IMS JDBC — can access the database. Without a PSB, the database exists in the catalog but is not queryable.

PSBs are created using **DDL** via `IMSDBAExecutor`, the same executor used for database creation. The PSB DDL uses `CREATE PROGRAMVIEW` syntax and must end with a `COMMIT`.

Before generating, Bob reads all files in `PSB-DDL-examples/` as reference patterns.

After a successful `COMMIT DDL`, Bob asks:

```
Your database '<DBNAME>' has been created successfully.

Would you like to define a PSB for it now?
A PSB is required before the database can be queried.

  YES → Bob gathers PSB details, generates the PSB DDL, and optionally executes it
  NO  → Done. You can define a PSB manually later.
```

### PSB Gather Questions

If the user says YES, Bob asks:

**Question 1: PSB (PROGRAMVIEW) Name**
```
What would you like to name the PSB?
```
- **Required.** No default.
- IMS convention: uppercase, up to 8 characters (e.g. `DI22PSB`)
- This becomes both the `CREATE PROGRAMVIEW` name and the `AS <PCB_NAME>` schema alias

**Question 2: PCB Name**
```
What would you like to name the PCB?
```
- **Required.** No default.
- Becomes the `CREATE SCHEMA <PCB_NAME> USING <DBD_NAME>` and `AS <PCB_NAME>` value
- Convention: matches the name IMS JDBC will use when querying — e.g. `PCB01`

**Question 3: Processing Options**
```
What processing options do you need?

  [G]   GET only (read)
  [GU]  GET UNIQUE (read by key)
  [L]   LOAD
  [LS]  GET + sequential retrieval (most common for queries)
  [A]   ALL operations (GET, INSERT, DELETE, REPLACE)
  [AP]  ALL + path calls (most permissive)
```
- Applied to the entire PSB via `PROCOPT '<option>'`
- **Default: `AP`** (matches the reference example in `PSB-DDL-examples/PSBDDL.txt`)

### PSB DDL Template

Bob reads `PSB-DDL-examples/` before generating and produces one file:

#### PSB DDL — `psb-output/<PSBNAME>_psb_<timestamp>.sql`
```sql
CREATE PROGRAMVIEW <PSBNAME> (
    CREATE SCHEMA <PCB_NAME> USING <DBDNAME>
        AS <PCB_NAME> (
            CREATE SENSEGVIEW <ROOT_SEGMENT>,
            CREATE SENSEGVIEW <CHILD_SEGMENT_1>,
            CREATE SENSEGVIEW <CHILD_SEGMENT_2>)
        PROCOPT '<PROCOPT>' LISTYES
        POSSNGL SBCOND
) CMPATYES GSROLBOKNO OLICYES LOCKMAX 0 IOASIZE 600 SSASIZE 840
    LANGASSEM;

COMMIT DDL;
```
> **Note:** The IMS JDBC driver requires `COMMIT DDL` — bare `COMMIT` is not valid syntax and will be rejected.

### PSB DDL Generation Rules

| Value | Rule |
|-------|------|
| `PROGRAMVIEW` name | The PSB name provided by the user — up to 8 characters |
| `SCHEMA` name | The PCB name provided by the user |
| `USING <DBDNAME>` | The DBD name — matches the database name from the `CREATE DATABASE` statement |
| `AS <PCB_NAME>` | Same as the `SCHEMA` name |
| `SENSEGVIEW` entries | One per segment, in top-down hierarchy order (root first, then children) |
| `PROCOPT` | Processing option chosen by user; default `AP` |
| Fixed clauses | `LISTYES`, `POSSNGL SBCOND`, `CMPATYES`, `GSROLBOKNO`, `OLICYES`, `LOCKMAX 0`, `IOASIZE 600`, `SSASIZE 840`, `LANGASSEM` — always included, taken from reference example |
| `COMMIT DDL` | Always the final statement — required to register the PSB. Must be `COMMIT DDL`, not bare `COMMIT` |

### Execution

The PSB DDL is executed via `IMSDBAExecutor` using the same `CREATE` operation, which handles the `COMMIT` at the end automatically:

```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSDBAExecutor" \
  -Dexec.args="CREATE ../psb-output/<PSBNAME>_psb_<timestamp>.sql"
```

Bob presents the generated PSB DDL for review and asks:
```
Would you like to execute this PSB DDL now?
  YES → run IMSDBAExecutor
        On success → remind user to run IMPORT DEFN
  NO  → file saved to psb-output/, execute manually later
```

### PSB Output Directory

```
psb-output/
└── <PSBNAME>_psb_<timestamp>.sql   ← PSB DDL (executed via IMSDBAExecutor)
```

---

## IMSDBAExecutor — Execution

### Invocation
```bash
cd /Users/andrewpurso/Documents/bob-jdbc-demo/bob-demo
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSDBAExecutor" \
  -Dexec.args="CREATE <path_to_ddl_file>"
```

**Example:**
```bash
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSDBAExecutor" \
  -Dexec.args="CREATE ../ddl-output/INVNTRY1_create_20260714_180000.sql"
```

### Supported Operations

| Operation | Description |
|-----------|-------------|
| `CREATE` | Executes CREATE DATABASE, CREATE TABLESPACE, and all CREATE TABLE statements in order — then issues a single COMMIT DDL only after all three are present and successful |
| *(future)* `ALTER` | Execute ALTER DDL statements |
| *(future)* `DROP` | Execute DROP statements |
| *(future)* `VALIDATE` | Parse and validate DDL syntax without executing |

### CRITICAL: COMMIT DDL Dependency Rule

**A `COMMIT DDL` must never be issued unless ALL THREE of the following statements have been executed successfully in the same session, in this order:**

```
1. CREATE DATABASE
2. CREATE TABLESPACE  (must reference the database created in step 1)
3. CREATE TABLE       (at least one; must reference the database created in step 1)
```

These three are interdependent. IMS will not accept a commit if any one of them is missing or failed. If the DDL file does not contain all three, Bob must stop and inform the user rather than attempting a partial commit.

### Execution Flow for CREATE

```
1. Connect via JDBC Type-4 (same pattern as IMSDDL.java)
2. Parse DDL file — split on ";" to get individual statements
3. Validate that the file contains:
     - At least one CREATE DATABASE statement
     - At least one CREATE TABLESPACE statement
     - At least one CREATE TABLE statement
   If any are missing → stop, report to user, do NOT execute anything
4. Execute: CREATE DATABASE
5. Execute: CREATE TABLESPACE
6. Execute: CREATE TABLE (root segment)
7. Execute: CREATE TABLE (child segments, in hierarchy order — parent before child)
8. All statements succeeded → Execute: COMMIT DDL
9. Log results to: ddl-results/<DBNAME>_create_<timestamp>.log
   On any failure at steps 4–7:
     - Stop immediately
     - Report the failing statement and error message
     - Do NOT issue COMMIT DDL
     - Log failure to ddl-results/
```

---

## Bob's Full Decision Tree

```
User describes a database to create
        ↓
Phase 1: Gather Intent
  ├─ DB name?           → ask (required)
  ├─ Access method?     → present 6 options with descriptions, ask (required)
  ├─ Root segment?      → ask for name + fields (required)
  └─ Child segments?
       ├─ YES → gather name, parent, fields; repeat until hierarchy complete
       └─ NO  → proceed
        ↓
Phase 2: Generate DDL
  ├─ Read all files in DDL-examples/ for reference patterns
  ├─ Apply generation rules (START, MAXBYTES, OVERFLOW, VERSION)
  ├─ Order: CREATE DB → TABLESPACE → TABLE(root) → TABLE(children, top-down)
  ├─ Every generated file ALWAYS contains all three statement types
  └─ Save to ddl-output/<DBNAME>_create_<timestamp>.sql
        ↓
Phase 3: Present & Gate
  ├─ Display full DDL to user for review
  └─ Ask: "Execute now?"
         ├─ YES → IMSDBAExecutor CREATE <file>
         │         ├─ Validate: file has CREATE DB + TABLESPACE + TABLE
         │         ├─ Execute all statements in order
         │         ├─ COMMIT DDL only after all succeed
         │         ├─ Failure: stop at failing statement, do not commit, log and report
         │         └─ Success: log to ddl-results/
         │                   ↓
         │                   Ask: "Would you like me to run IMPORT DEFN SOURCE(CATALOG) now?"
         │                   ├─ YES → Execute: ./run-catimp.sh
         │                   │         ├─ Success: confirm catalog import completed
         │                   │         └─ Failure: report error, user can run manually
         │                   └─ NO  → Inform: "You'll need to run IMPORT DEFN SOURCE(CATALOG) manually"
         │                   ↓
         │                   → proceed to Phase 4
         └─ NO  → Done, file saved at ddl-output/
        ↓
Phase 4: PSB (triggered only after successful COMMIT DDL)
  └─ Ask: "Would you like to define a PSB for <DBNAME>?"
         ├─ YES → gather PSB details:
         │         ├─ PSB (PROGRAMVIEW) name?  (required)
         │         ├─ PCB name?                (required)
         │         └─ Processing options?      (default: AP)
         │         ↓
         │         Read PSB-DDL-examples/ for reference patterns
         │         Generate PSB DDL using CREATE PROGRAMVIEW template
         │         Save to: psb-output/<PSBNAME>_psb_<timestamp>.sql
         │         Display DDL to user for review
         │         Ask: "Execute now?"
         │           ├─ YES → IMSDBAExecutor CREATE <psb_file>
         │           │         ├─ Failure: report error, do not commit
         │           │         └─ Success: PSB registered
         │           │                   ↓
         │           │                   Ask: "Would you like me to run IMPORT DEFN SOURCE(CATALOG) now?"
         │           │                   ├─ YES → Execute: ./run-catimp.sh
         │           │                   │         ├─ Success: confirm catalog import completed
         │           │                   │         └─ Failure: report error, user can run manually
         │           │                   └─ NO  → Inform: "You'll need to run IMPORT DEFN SOURCE(CATALOG) manually"
         │           └─ NO  → Done, file saved at psb-output/
         └─ NO  → Done. PSB can be defined manually later.
```

---

## Directory Structure

```
bob-jdbc-demo/
├── DDL-examples/                            ← Reference patterns (add new examples here)
│   └── createDB.txt
├── ddl-output/                              ← Bob writes generated DDL here
│   └── <DBNAME>_create_<timestamp>.sql
├── ddl-results/                             ← IMSDBAExecutor writes execution logs here
│   └── <DBNAME>_create_<timestamp>.log
├── psb-output/                              ← Bob writes generated PSB DDL here
│   └── <PSBNAME>_psb_<timestamp>.sql
└── bob-demo/src/main/java/com/ibm/ims/jdbcbob/
    └── IMSDBAExecutor.java                  ← All DBA DDL execution
```

---

## Troubleshooting

| Error | Cause | Solution |
|-------|-------|---------|
| `PCB or PSB not found` | DDL executed against wrong datastore or PSB | Verify connection URL targets the correct IMS region |
| `Segment already exists` | Database or segment with that name already defined | Choose a different database/segment name, or use ALTER (future) |
| `COMMIT DDL failed` | One or more prior statements failed | Check log in `ddl-results/` for the failing statement |
| `Invalid START position` | Manually edited DDL with wrong offset | Re-generate DDL and let Bob auto-calculate START values |
| Database queryable in catalog but not at runtime | `IMPORT DEFN SOURCE(CATALOG)` was not run after COMMIT DDL | Bob can execute ./run-catimp.sh or user can run manually via IMS MTO console |

---

## Future Enhancements

1. **ALTER support** — Modify existing database structures via `IMSDBAExecutor ALTER`
2. **DROP support** — Remove database structures via `IMSDBAExecutor DROP`
3. **VALIDATE mode** — Syntax-check DDL without executing against IMS
4. **Additional DDL examples** — As new examples are added to `DDL-examples/`, Bob refines generation patterns for non-HISAM access methods and hierarchical structures
5. **Hierarchy examples** — Dedicated examples for multi-level segment hierarchies to guide generation
6. **Multiple PCBs per PSB** — Allow user to define more than one `CREATE SCHEMA` block within a single `CREATE PROGRAMVIEW` (e.g. one read-only PCB, one update PCB)
7. **PSB-only mode** — Allow user to generate a PSB DDL for an existing database without going through the full CREATE flow
