# Bob for Z — IMS DBA Capability Suite

## Overview

This is the master routing document for the IMS DBA capability suite. When a user makes a request, Bob reads this document first, classifies the intent, and follows the appropriate workflow.

Each tenet of the suite lives in its own workflow document. As new capabilities are added, new workflow files are registered here.

---

## Tenet Index

| # | Tenet | Workflow File | Trigger Keywords |
|---|-------|---------------|-----------------|
| 1 | Natural Language SQL Queries | `WORKFLOW_1_NLP_QUERY.md` | query, show, find, select, list, get, retrieve, search, display |
| 2 | DDL Generation & Execution | `WORKFLOW_2_DDL_GENERATION.md` | create, build, design, define, make, set up, new database, DDL |
| 3 | IMS Database Provisioning | `WORKFLOW_3_PROVISIONING.md` | provision, allocate, initialize, load, image copy, start database, bring online, set up datasets, DBRC, DFSUPNT0, DFSURGL0, DBFUMIN0 |
| 4 | Update Existing Databases | `WORKFLOW_4_UPDATE_DATABASE.md` | update, alter, modify, change, rename, add column, drop column, add segment, drop segment, edit, restructure, extend |
| 5 | Database Editor | `WORKFLOW_5_DATABASE_EDITOR.md` | edit fields, edit database, add field, remove field, delete field, change field, update field, describe fields, I want to add, I want to remove, I need a new column, I need to drop a column, copy book, copybook, COBOL layout, here is my copybook, sync from copybook, fields from copybook |

---

## Bob's Intent Classification (Routing Decision Tree)

```
User request received
        ↓
Classify intent:

  Does the request involve READING or QUERYING data?
  (query, show, find, select, list, get, retrieve, search, display)
        ↓ YES
  → Follow WORKFLOW_1_NLP_QUERY.md

  Does the request involve BUILDING or DEFINING a database structure?
  (create, build, design, define, make, set up, new database, DDL)
        ↓ YES
  → Follow WORKFLOW_2_DDL_GENERATION.md

  Does the request involve PROVISIONING, ALLOCATING, INITIALIZING, or
  LOADING a database onto z/OS?
  (provision, allocate, initialize, load, image copy, start database,
   bring online, set up datasets, DBRC, DFSUPNT0, DFSURGL0, DBFUMIN0)
        ↓ YES
  → Follow WORKFLOW_3_PROVISIONING.md

  Does the request involve UPDATING, ALTERING, or MODIFYING an
  EXISTING database structure?
  (update, alter, modify, change, rename, add column, drop column,
   add segment, drop segment, edit, restructure, extend)
        ↓ YES
  → Follow WORKFLOW_4_UPDATE_DATABASE.md

  Does the request involve EDITING FIELDS of an existing database
  using only ALTER or DROP column statements — driven by plain-
  language descriptions OR a COBOL copy book?
  (edit fields, edit database, add field, remove field, delete field,
   change field, update field, describe fields, I want to add/remove
   a column/field, copy book, copybook, COBOL layout, here is my
   copybook, sync from copybook, fields from copybook)
        ↓ YES
  → Follow WORKFLOW_5_DATABASE_EDITOR.md
    (Mode A — NLP description, or Mode B — COBOL copybook)

  ── Future tenets slot in here as new workflow files are added ──

  Intent is ambiguous?
  → Ask: "Are you looking to query data from an existing database,
          or create a new database structure?"
```

---

## Shared Resources

These apply across all tenets.

### JDBC Connection Pattern
All programs connect via **JDBC Type-4 TCP/IP**. Connection properties (host, port, user, password) are configured in each executor program. The base pattern is established in [`IMSDDL.java`](bob-demo/src/main/java/com/ibm/ims/jdbcbob/IMSDDL.java).

### Project Working Directory
```
/Users/andrewpurso/Documents/bob-jdbc-demo/bob-demo
```
All `mvn exec:java` commands are run from this directory.

### Output Directories
| Directory | Purpose |
|-----------|---------|
| `query_results/` | CSV results from NLP queries (Tenet 1) |
| `ddl-output/` | Generated DDL files before execution (Tenet 2) |
| `ddl-results/` | Execution logs from IMSDBAExecutor (Tenet 2) and provisioning logs (Tenet 3) |
| `DDL-examples/` | Reference DDL patterns — Bob reads before generating |
| `psb-output/` | Generated PSB DDL files (Tenet 2) |

### Java Programs
| Program / Script | Tenet | Role |
|-----------------|-------|------|
| `IMSMetadataExplorer.java` | 1 | Discovers PCB and table names |
| `IMSQueryExecutor.java` | 1 | Executes SQL queries, saves CSV |
| `IMSDBAExecutor.java` | 2, 4, 5 | Executes DDL operations (CREATE, ALTER, DROP) |
| `IMSDDLFileExecutor.java` | 2 | Lightweight single-file DDL runner — reads statements from a plain-text file and executes them; no archiving or structured logging |
| `provision-ims-db.sh` | 3 | Allocates data sets, initializes partitions, loads DB, takes image copy, starts IMS resources via Zowe CLI |

---

## Adding New Tenets

When a new IMS DBA capability is designed:
1. Create `WORKFLOW_N_<NAME>.md` in the project root
2. Add a row to the **Tenet Index** above
3. Add trigger keywords to the routing decision tree
4. Register any new Java programs in the **Java Programs** table above
