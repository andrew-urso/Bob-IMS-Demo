# Tenet 4 — Database Editor

## Overview

This workflow covers how Bob handles requests to **edit the fields of an existing IMS database**. It supports two input modes:

| Mode | How the user provides changes |
|------|------------------------------|
| **NLP** | Plain-language description ("add a 10-char phone number field to CUSTOMER") |
| **Copybook** | A COBOL copy book — Bob parses it, diffs it against the live schema, and generates ADD COLUMN DDL for every field that is missing |

In both modes Bob **discovers the live schema first**, generates only ALTER DDL, confirms with the user, and executes via `IMSDBAExecutor`.

**This tenet is scoped to ALTER and DROP statements only.** No `CREATE DATABASE`, `CREATE TABLESPACE`, or `CREATE TABLE` (new segment) statements are issued here. For new segment creation or new database creation use Tenet 2 or Tenet 3.

**Trigger keywords (NLP mode):** edit fields, edit database, add field, remove field, delete field, change field, update field, describe fields, I want to add, I want to remove, I need a new column, I need to drop a column

**Trigger keywords (copybook mode):** copy book, copybook, COBOL layout, here is my copybook, here is my copy book, sync from copybook, fields from copybook

---

## Scope & Restrictions

| Allowed | Not Allowed |
|---------|------------|
| `ALTER TABLE … ADD COLUMN` | `CREATE DATABASE` |
| `ALTER TABLE … DROP COLUMN` | `CREATE TABLESPACE` |
| `ALTER TABLE … RENAME COLUMN … TO …` | `CREATE TABLE` (new segment) |
| `ALTER TABLE … ALTER COLUMN … SET DATA TYPE …` | `DROP TABLE` (entire segment) |
| `DROP COLUMN` (via ALTER TABLE) | `DROP DATABASE` |

> If the user asks for something outside the allowed list, Bob explains the limitation and redirects to the appropriate tenet (Tenet 2 for new databases/segments, Tenet 4 for broader structural changes).

---

## Phase 1 — Discover Existing Structure (Both Modes)

Bob **must** run discovery before processing any input. This ensures START position calculations and gap analysis use the real live schema, not assumed values.

```bash
cd /Users/andrewpurso/Documents/bob-jdbc-demo/bob-demo/Java_Programs
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSMetadataExplorer" \
  -Dexec.args="<DATABASE_NAME>"
```

After running, Bob presents the current schema to the user:

```
Database:  <DATABASE_NAME>
PCB(s):    <PCB_NAME_1>, <PCB_NAME_2>, ...

Segment: <SEGMENT_NAME>  (ROOT / CHILD of <PARENT>)
  Columns:
    <COL1>  CHAR(<len>)  START <n>
    <COL2>  CHAR(<len>)  START <n>
    ...
  Current MAXBYTES: <n>
```

If the database is not found:
> "I could not find a database named `<DATABASE_NAME>` in the IMS Catalog. Please check the name and try again."

Stop — do not proceed to either mode.

---

## Mode A — NLP Input

### Step A1: Collect the User's Field Descriptions

Bob asks:
> "Describe the fields you want to add, change, or remove. For example:
> - 'Add a 10-character phone number field to CUSTOMER'
> - 'Remove the OLD_CODE field from ORDER'
> - 'Rename ADDR to ADDRESS in CUSTOMER'
> - 'Change the AMT field in INVOICE from CHAR(8) to CHAR(11)'"

Bob collects **all requested changes in one pass** before generating anything. Multiple changes to the same segment are batched into a single ALTER statement.

### Step A2: Resolve Ambiguities

Before generating DDL, Bob asks follow-up questions **only for what is missing**:

| Missing information | Question to ask |
|--------------------|----------------|
| Target segment not specified | "Which segment (table) should this change apply to? Available: `<list from discovery>`" |
| New column — data type not specified | "What data type should `<COLUMN_NAME>` be? (e.g. CHAR(10), DECIMAL(9,2))" |
| New column — length not specified | "How many characters should `<COLUMN_NAME>` hold?" |
| Rename — old name unclear | "Which existing column do you want to rename? Available in `<SEGMENT>`: `<list>`" |
| Type change — new type not specified | "What should the new data type for `<COLUMN_NAME>` be?" |
| Drop column — name not found in schema | "I don't see a column named `<NAME>` in `<SEGMENT>`. Did you mean one of these? `<list>`" |

Bob does **not** ask for `START` position, `INTERNALNAME`, `TYPECONVERTER`, or `CCSID` — these are always derived automatically.

### Step A3: Generate DDL → go to Phase 3 (Present, Confirm & Execute)

---

## Mode B — COBOL Copybook Input

### Step B1: Receive the Copybook

The user pastes or attaches a COBOL copy book. Bob accepts it in-line in the chat or as a file path. A valid copybook contains one or more level-number field definitions, for example:

```cobol
       01  CUSTOMER-RECORD.
           05  CUST-KEY        PIC X(10).
           05  CUST-NAME       PIC X(30).
           05  CUST-PHONE      PIC X(10).
           05  CUST-EMAIL      PIC X(40).
           05  CUST-BALANCE    PIC 9(7)V99 COMP-3.
```

### Step B2: Identify the Target Segment

If the user did not say which segment this copybook maps to, Bob asks:
> "Which segment in `<DATABASE_NAME>` does this copy book describe? Available segments: `<list from discovery>`"

### Step B3: Parse the Copybook

Bob reads every **level-05** (or the first non-01 level) field definition. For each field Bob extracts:

| Copybook attribute | How Bob reads it |
|-------------------|-----------------|
| Field name | The data-name after the level number. Hyphens are converted to underscores for the IMS column name (e.g. `CUST-PHONE` → `CUST_PHONE`) |
| Data type | Derived from the PICTURE clause (see COBOL Type Mapping table below) |
| Length | Derived from the PICTURE clause (see below) |
| Usage | `COMP-3` / `COMPUTATIONAL-3` → `TYPE P`, `TYPECONVERTER BINARY`; all others → `TYPE C`, `TYPECONVERTER CHAR` |

Bob **ignores**:
- The level-01 group item itself (it maps to the segment, not a column)
- Filler fields (`FILLER`)
- Redefine clauses (`REDEFINES`) — note these to the user and skip them
- Nested group items below level 05 — flatten them and note any that were skipped

#### COBOL Type Mapping

| PICTURE clause | IMS DDL data type | `TYPE` | `TYPECONVERTER` | Length calculation |
|---------------|------------------|--------|-----------------|-------------------|
| `PIC X(n)` | `CHAR(n)` | `C` | `CHAR` | n |
| `PIC A(n)` | `CHAR(n)` | `C` | `CHAR` | n |
| `PIC 9(n)` | `CHAR(n)` | `C` | `CHAR` | n |
| `PIC 9(n)V9(m)` (no COMP) | `CHAR(n+m)` | `C` | `CHAR` | n + m |
| `PIC 9(n) COMP-3` / `COMPUTATIONAL-3` | `CHAR(⌈(n+1)/2⌉)` | `P` | `BINARY` | packed length = ⌈(digits+1)/2⌉ |
| `PIC S9(n) COMP-3` | `CHAR(⌈(n+1)/2⌉)` | `P` | `BINARY` | same as above |
| Any unrecognised PICTURE | Flag to user — ask for the intended type and length before continuing |

> If Bob cannot confidently derive the type and length from the PICTURE clause, it flags the field, states what it found, and asks the user to confirm before including it in the DDL.

### Step B4: Diff Against Live Schema

Bob compares the parsed copybook field list against the columns already present in the target segment (from Phase 1 discovery):

- **Match** (name and type align) → no action needed, note as "already present"
- **Missing from database** (field exists in copybook but not in segment) → generate `ADD COLUMN`
- **Present in database but not in copybook** → **do not drop** — Bob lists these and asks: "These columns exist in the database but are not in the copybook. Do you want to keep them or remove them?" Never drop silently.
- **Type mismatch** (field exists in both but PICTURE-derived type differs from live type) → flag the mismatch, show both types, ask whether to generate an `ALTER COLUMN … SET DATA TYPE` or leave it

Bob presents the diff to the user before generating any DDL:

```
Copybook diff for segment <SEGMENT_NAME> in <DATABASE_NAME>:

  ✓ Already present (no change needed):
      CUST_KEY      CHAR(10)
      CUST_NAME     CHAR(30)

  + Missing — will ADD:
      CUST_PHONE    CHAR(10)    START <auto>
      CUST_EMAIL    CHAR(40)    START <auto>
      CUST_BALANCE  CHAR(5)     START <auto>   [TYPE P — packed COMP-3]

  ? In database but not in copybook (no action taken unless you confirm):
      OLD_FIELD     CHAR(8)

  ⚠ Type mismatch:
      (none)

Proceed with ADD COLUMN DDL for the 3 missing fields? (yes / review / cancel)
```

### Step B5: Generate DDL for Missing Fields

For each missing field, Bob generates an `ADD COLUMN` clause following all Tenet 2 DDL rules. START positions are calculated by appending after the last known column in the live schema.

Multiple missing fields for the same segment are batched into a single ALTER statement:

```sql
-- EDITOR (copybook): Add missing fields to <TABLE_NAME> in <DATABASE_NAME>
-- Source copybook: <copybook_name_or_"inline">
-- Generated: <timestamp>

ALTER TABLE <SCHEMA>.<TABLE_NAME>
  ADD COLUMN CUST_PHONE CHAR(10)
    START <auto>
    TYPE C
    INTERNALNAME CUST_PHONE
    INTERNAL TYPECONVERTER CHAR
    CCSID 'Cp1047',
  ADD COLUMN CUST_EMAIL CHAR(40)
    START <auto>
    TYPE C
    INTERNALNAME CUST_EMAIL
    INTERNAL TYPECONVERTER CHAR
    CCSID 'Cp1047',
  ADD COLUMN CUST_BALANCE CHAR(5)
    START <auto>
    TYPE P
    INTERNALNAME CUST_BALANCE
    INTERNAL TYPECONVERTER BINARY
    CCSID 'Cp1047';

COMMIT;
```

### Step B6: Generate DDL for User-Confirmed Drops (Optional)

If the user confirmed in Step B4 that they want to remove columns that are absent from the copybook, Bob generates DROP COLUMN clauses **after** the ADD COLUMN batch, in a separate ALTER block, preceded by the irreversibility warning:

```sql
-- !! WARNING: DROP COLUMN is irreversible. All data in the dropped columns will be lost. !!
-- EDITOR (copybook): Drop columns confirmed absent from copybook
-- Generated: <timestamp>

ALTER TABLE <SCHEMA>.<TABLE_NAME>
  DROP COLUMN OLD_FIELD;

COMMIT;
```

### Step B7: Generate DDL for User-Confirmed Type Changes (Optional)

If the user confirmed type mismatches should be resolved:

```sql
-- EDITOR (copybook): Correct type mismatch in <TABLE_NAME>
-- Generated: <timestamp>

ALTER TABLE <SCHEMA>.<TABLE_NAME>
  ALTER COLUMN <COLUMN_NAME> SET DATA TYPE <NEW_TYPE>(<LEN>);

COMMIT;
```

### Step B8: Present full DDL → go to Phase 3 (Present, Confirm & Execute)

---

## Phase 3 — Present, Confirm & Execute (Both Modes)

### Step 3a: Present the DDL

Bob displays the full generated DDL and asks:
> "Here is the DDL I've generated. Shall I execute it? (yes / let me review first / cancel)"

- **yes** → Proceed to Step 3b.
- **let me review first** → Wait; user may request edits before re-confirming.
- **cancel** → Discard DDL, confirm cancellation, stop.

### Step 3b: Save DDL to File

Write the confirmed DDL to:
```
context_and_examples/ddl-output/<DATABASE_NAME>_editor_<timestamp>.sql
```

### Step 3c: Execute via IMSDBAExecutor

```bash
cd /Users/andrewpurso/Documents/bob-jdbc-demo/bob-demo/Java_Programs
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSDBAExecutor" \
  -Dexec.args="../context_and_examples/ddl-output/<DATABASE_NAME>_editor_<timestamp>.sql"
```

### Step 3d: Report Results

- **Success:** Confirm which columns were added/dropped/renamed, display the updated segment structure, and give the log path (`context_and_examples/ddl-results/`).
- **Failure:** Display the error, explain the likely cause (see Troubleshooting), and offer a corrected DDL for re-execution.

---

## DDL Generation Rules (Both Modes)

All DDL generated in this tenet must follow the **same column-level rules as Tenet 2** (see [`WORKFLOW_2_DATABASE_CREATION.md`](WORKFLOW_2_DATABASE_CREATION.md) — Generation Rules section). Key rules restated here:

| Rule | Detail |
|------|--------|
| `START` position | For `ADD COLUMN`: `START` = last existing field's `START` + last existing field's length. Recalculated from live discovery. When adding multiple columns in one batch, each successive column's `START` follows the previous added column. |
| `INTERNALNAME` | Always equals the IMS column name exactly (hyphens already converted to underscores for copybook fields) |
| `TYPE` | `C` for CHAR/alphanumeric; `P` for packed COMP-3 |
| `TYPECONVERTER` | `CHAR` for TYPE C; `BINARY` for TYPE P |
| `CCSID` | Always `'Cp1047'` |
| `COMMIT` | One `COMMIT;` at the end of the full DDL file — never mid-batch |
| Reference patterns | Always check `context_and_examples/DDL-examples/createDB.txt` before generating for exact column definition style |

> **No `CREATE DATABASE`, `CREATE TABLESPACE`, or full `CREATE TABLE` statements are ever generated in this tenet.** The Tenet 2 COMMIT DDL dependency rule does not apply here.

---

## Bob's Full Decision Tree for Tenet 4

```
User request triggers Tenet 4
        ↓
Do we know the target database name?
   NO  → Ask: "Which database do you want to edit?"
   YES ↓
Run IMSMetadataExplorer — discover current structure
        ↓
Database found?
   NO  → Report "not found", stop
   YES → Present current schema to user
        ↓
Did the user provide a COBOL copy book?
   YES → MODE B (Copybook)
          ↓
         Parse copybook fields (level-05 items)
         Map PICTURE clauses to IMS types/lengths
         Diff copybook vs. live schema
         Present diff to user:
           + Missing fields  → queue ADD COLUMN
           ? DB-only fields  → ask user: keep or drop?
           ⚠ Type mismatches → ask user: fix or leave?
          ↓
         Generate batched ALTER DDL for confirmed changes
          ↓
         → Phase 3
   NO  → MODE A (NLP)
          ↓
         Collect field change descriptions
         Resolve ambiguities
         Generate ALTER DDL
          ↓
         → Phase 3
        ↓
Phase 3: Present DDL → confirmed?
   NO  → Incorporate edits, re-present
   YES ↓
Save to context_and_examples/ddl-output/<DATABASE_NAME>_editor_<timestamp>.sql
Execute via IMSDBAExecutor
        ↓
Report success or failure
   FAILURE → Explain error, offer corrected DDL
```

---

## Guardrails

- Bob **never executes DDL without explicit user confirmation** in Step 3a.
- `DROP COLUMN` (in either mode) requires explicit user confirmation before DDL is generated. Bob never drops a column silently.
- In copybook mode, Bob **never drops** columns that are absent from the copybook unless the user explicitly says to remove them in the diff review step.
- Bob **never generates** `CREATE DATABASE`, `CREATE TABLESPACE`, `CREATE TABLE` (new segment), `DROP TABLE`, or `DROP DATABASE` in this workflow.
- Bob **never assumes** field lengths or data types — in NLP mode it asks; in copybook mode it derives from PICTURE clauses and flags anything it cannot resolve.
- Copybook fields with `REDEFINES` or nested group items below the mapped level are **skipped and reported** to the user.

---

## Restrictions

*(To be filled in by the user — restrictions and limitations will be added here once confirmed.)*

---

## Directory Structure

```
bob-demo/
├── Java_Programs/       ← Maven project / Java source
└── context_and_examples/
    ├── ddl-output/      ← Generated editor DDL files: <DATABASE_NAME>_editor_<timestamp>.sql
    ├── ddl-results/     ← Execution logs written by IMSDBAExecutor
    └── DDL-examples/    ← Reference column definition patterns (createDB.txt)
```

---

## Troubleshooting

| Symptom | Likely Cause | Action |
|---------|-------------|--------|
| `IMSMetadataExplorer` returns no rows | Database name wrong or catalog unreachable | Verify DBD name; check JDBC connection in `IMSDDL.java` |
| `ADD COLUMN` fails — "column already exists" | Column name collision with existing field | Re-run discovery; column may have been added since last check |
| `DROP COLUMN` fails — "column referenced in index" | IMS secondary index depends on this column | Inform user; index must be dropped manually first |
| `RENAME COLUMN` fails | Column name not found or dialect restriction | Verify exact column name from discovery output |
| `IMSDBAExecutor` exits with non-zero code | JDBC or DDL syntax error | Check `context_and_examples/ddl-results/` log; verify `IMSDDL.java` connection props |
| START position collision after ADD COLUMN | Stale discovery used for calculation | Re-run `IMSMetadataExplorer` to get current offsets before generating |
| Copybook field skipped — "unrecognised PICTURE" | Exotic PICTURE clause Bob could not parse | Bob flags it; user provides type and length manually |
| Copybook `REDEFINES` field ignored | REDEFINES not supported in this tenet | Note to user; handle manually or via Tenet 3 |
