# Tenet 4 — Update Existing IMS Databases

## Overview

This workflow covers how Bob handles requests to **alter, modify, or update** the structure of an existing IMS database. The user identifies the target database and describes the change they need; Bob discovers the current metadata, generates the appropriate ALTER DDL, confirms with the user, and executes it.

**Trigger keywords:** update, alter, modify, change, rename, add column, drop column, add segment, drop segment, edit, restructure, extend

**Status:** Active — replaces the placeholder in the routing tree. Database *creation* is handled by Tenet 2.

---

## The Three-Phase Approach

| Phase | What happens |
|-------|-------------|
| **1 — Discover** | Bob queries the IMS Catalog for the current structure of the target database |
| **2 — Gather & Generate** | Bob asks what change is needed, generates the ALTER DDL, and shows it for confirmation |
| **3 — Execute** | User approves; Bob executes the DDL via `IMSDBAExecutor.java` and reports results |

---

## Phase 1 — Discover Existing Structure

Before generating any DDL, Bob **must** inspect the live database structure.

### Step 1a: Discover PCB and Table Names

```bash
cd /Users/andrewpurso/Documents/bob-jdbc-demo/bob-demo
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSMetadataExplorer" \
  -Dexec.args="<DATABASE_NAME>"
```

This reveals:
- All PCB names bound to the database
- All segment/table names and their column definitions
- Hierarchy (root vs. child segments)

### Step 1b: Summarise Current State to the User

After running discovery, Bob presents a concise summary:

```
Database:  <DATABASE_NAME>
PCB(s):    <PCB_NAME_1>, <PCB_NAME_2>, ...
Segments:
  ROOT  → <ROOT_TABLE>   (columns: ...)
  CHILD → <CHILD_TABLE>  (columns: ..., parent: <ROOT_TABLE>)
  ...
```

If discovery returns no results, Bob stops and reports:
> "I could not find a database named `<DATABASE_NAME>` in the IMS Catalog. Please verify the name and try again."

---

## Phase 2 — Gather Intent & Generate ALTER DDL

### Supported Change Types

| Change Type | DDL Statement | Example |
|-------------|--------------|---------|
| Add a column to a segment | `ALTER TABLE … ADD COLUMN …` | Add `PHONE_NUM CHAR(10)` to `CUSTOMER` |
| Drop a column from a segment | `ALTER TABLE … DROP COLUMN …` | Drop `OLD_CODE` from `ORDER` |
| Rename a column | `ALTER TABLE … RENAME COLUMN … TO …` | Rename `ADDR` to `ADDRESS` |
| Modify a column data type | `ALTER TABLE … ALTER COLUMN … SET DATA TYPE …` | Change `AMT` from `INT` to `DECIMAL(11,2)` |
| Add a new child segment (table) | `CREATE TABLE …` with `PARENT` clause | Add `LINEITEM` under `ORDER` |
| Drop a segment (table) | `DROP TABLE …` | Remove `LEGACY_SEG` |

> **Note:** Renaming a *database* or *tablespace* is not supported via IMS JDBC DDL. If the user asks for that, inform them it requires a manual DBDGEN/ACBGEN cycle outside this tool.

---

### Gather Questions (ask only what is needed for the chosen change type)

#### Question A — Target Database
> "Which database do you want to update?"
- Accept the DBD name (e.g. `ORDERSDB`).
- If already known from context, skip this question.

#### Question B — Change Type
> "What change do you need to make? (add column / drop column / rename column / modify column type / add segment / drop segment)"
- If the user described it already, classify and skip.

#### Question C — Target Segment / Table
> "Which segment (table) is being modified?"
- Must be one of the names discovered in Phase 1.
- If the name does not match, show the available list and ask again.

#### Question D — Change Details (varies by type)

| Change type | What to ask |
|-------------|------------|
| Add column | Column name, data type, length/precision, `NOT NULL`?, default value? |
| Drop column | Column name to remove. Confirm: "This is irreversible — confirm you want to drop `<col>`?" |
| Rename column | Old name → new name |
| Modify type | Column name, new data type |
| Add segment | Full segment definition: name, columns, parent segment, PCB sensitivity needed? |
| Drop segment | Segment name. Confirm: "Dropping a segment removes all its data — confirm?" |

---

### DDL Generation Rules

1. **Always** reference DDL patterns in `DDL-examples/` before generating.
2. Use the exact table names discovered in Phase 1 — never invent names.
3. For `ADD COLUMN`: place the new column at the end of the segment definition unless the user specifies a position.
4. For `DROP COLUMN` or `DROP TABLE`: prepend a comment block warning about irreversibility.
5. Append a `COMMIT` statement after every ALTER/CREATE/DROP block.
6. Save the generated DDL to `ddl-output/<DATABASE_NAME>_alter_<timestamp>.sql` **before** execution.

### DDL Templates

#### Add Column

```sql
-- ALTER: Add column to <TABLE_NAME> in database <DATABASE_NAME>
-- Generated: <timestamp>

ALTER TABLE <SCHEMA>.<TABLE_NAME>
  ADD COLUMN <COLUMN_NAME> <DATA_TYPE>(<LENGTH>) [NOT NULL WITH DEFAULT <DEFAULT>];

COMMIT;
```

#### Drop Column

```sql
-- !! WARNING: DROP COLUMN is irreversible. Data in <COLUMN_NAME> will be lost. !!
-- ALTER: Drop column from <TABLE_NAME> in database <DATABASE_NAME>
-- Generated: <timestamp>

ALTER TABLE <SCHEMA>.<TABLE_NAME>
  DROP COLUMN <COLUMN_NAME>;

COMMIT;
```

#### Rename Column

```sql
-- ALTER: Rename column in <TABLE_NAME> in database <DATABASE_NAME>
-- Generated: <timestamp>

ALTER TABLE <SCHEMA>.<TABLE_NAME>
  RENAME COLUMN <OLD_NAME> TO <NEW_NAME>;

COMMIT;
```

#### Modify Column Data Type

```sql
-- ALTER: Modify column type in <TABLE_NAME> in database <DATABASE_NAME>
-- Generated: <timestamp>

ALTER TABLE <SCHEMA>.<TABLE_NAME>
  ALTER COLUMN <COLUMN_NAME> SET DATA TYPE <NEW_TYPE>(<LENGTH>);

COMMIT;
```

#### Add Child Segment

```sql
-- CREATE: New child segment <NEW_TABLE> under <PARENT_TABLE>
-- Generated: <timestamp>

CREATE TABLE <SCHEMA>.<NEW_TABLE>
  (<COL1> <TYPE1>(<LEN>) NOT NULL,
   <COL2> <TYPE2>(<LEN>),
   ...)
  IN DATABASE <DATABASE_NAME>
  SEGSIZE <SEGSIZE>
  PARENT <PARENT_TABLE>
  RULES (INSERT LAST, REPLACE FIRST);

COMMIT;
```

#### Drop Segment

```sql
-- !! WARNING: DROP TABLE removes the segment and ALL its data. !!
-- Generated: <timestamp>

DROP TABLE <SCHEMA>.<TABLE_NAME>;

COMMIT;
```

---

## Phase 3 — Present, Confirm & Execute

### Step 3a: Present the DDL

Bob shows the full generated DDL to the user and asks:
> "Here is the ALTER DDL I've generated. Shall I execute it? (yes / let me review first / cancel)"

- **yes** → Proceed to Step 3b.
- **let me review first** → Wait; user may request edits before re-confirming.
- **cancel** → Discard DDL, do not execute, confirm cancellation.

### Step 3b: Save DDL to File

Write the confirmed DDL to:
```
ddl-output/<DATABASE_NAME>_alter_<timestamp>.sql
```

### Step 3c: Execute via IMSDBAExecutor

```bash
cd /Users/andrewpurso/Documents/bob-jdbc-demo/bob-demo
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSDBAExecutor" \
  -Dexec.args="../ddl-output/<DATABASE_NAME>_alter_<timestamp>.sql"
```

### Step 3d: Report Results

- On success: 
  - Confirm which statements committed and display the result log path (`ddl-results/`)
  - Ask: "Would you like me to run IMPORT DEFN SOURCE(CATALOG) now?"
    - YES → Execute: ./run-catimp.sh
      - Success: confirm catalog import completed
      - Failure: report error, user can run manually
    - NO → Inform: "You'll need to run IMPORT DEFN SOURCE(CATALOG) manually to activate the changes"
- On failure: display the error, explain likely cause (e.g. column already exists, segment not found), and offer to correct the DDL and retry.

---

## Bob's Full Decision Tree for Tenet 4

```
User request triggers Tenet 4 (update/alter/modify keywords)
        ↓
Do we know the target database name?
   NO  → Ask Question A
   YES ↓
Run IMSMetadataExplorer to discover current structure
        ↓
Was the database found?
   NO  → Report "not found", stop
   YES → Summarise structure to user
        ↓
Determine change type from user request or ask Question B
        ↓
Ask only the relevant Question C / D for that change type
        ↓
Generate ALTER DDL using the appropriate template
Save draft to ddl-output/<DATABASE_NAME>_alter_<timestamp>.sql
        ↓
Present DDL to user → confirmed?
   NO  → Incorporate edits, re-present
   YES ↓
Execute via IMSDBAExecutor
        ↓
Report success or failure
   FAILURE → Explain error, offer corrected DDL
```

---

## Directory Structure

```
bob-demo/
├── ddl-output/          ← Generated ALTER DDL files saved here before execution
├── ddl-results/         ← Execution logs written here by IMSDBAExecutor
└── DDL-examples/        ← Reference patterns Bob reads before generating DDL
```

---

## Constraints & Guardrails

- Bob **never executes DDL without explicit user confirmation** in Step 3a.
- Destructive operations (`DROP COLUMN`, `DROP TABLE`) require a second confirmation in Question D before DDL is even generated.
- Bob **never modifies the PSB** as part of this workflow unless the user explicitly asks. Adding/removing a segment may require a PSB update — Bob will note this and offer to trigger Tenet 2's PSB phase separately.
- Only operations supported by the IMS JDBC DDL dialect are offered. Unsupported requests are explained and redirected to the appropriate z/OS tooling.

---

## Troubleshooting

| Symptom | Likely Cause | Action |
|---------|-------------|--------|
| `IMSMetadataExplorer` returns no rows | Database name wrong or not in catalog | Verify DBD name; check IMS Catalog is accessible |
| `ALTER TABLE … ADD COLUMN` fails with "column already exists" | Column present in current schema | Discovery step out of date — re-run metadata discovery |
| `DROP COLUMN` fails with "column referenced in index" | IMS secondary index dependency | Inform user; index must be dropped first (manual step) |
| `IMSDBAExecutor` exits with non-zero code | JDBC connection or DDL syntax error | Check `ddl-results/` log; verify connection properties in `IMSDDL.java` |

---

## Future Enhancements

- Rename a segment (requires DBDGEN cycle — out of scope for JDBC DDL today)
- Batch multi-segment updates in a single DDL file
- Diff view: show old vs. new schema side-by-side before confirmation
- Auto-trigger PSB update after adding/removing a segment
