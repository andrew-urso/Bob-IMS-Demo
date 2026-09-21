# Tenet 2 — IMS Database Creation & Provisioning

## Overview

This workflow covers the **full lifecycle of creating and provisioning an IMS DEDB (Fast Path Data Entry Database)** — from gathering intent and generating DDL, through executing it, defining a PSB, and provisioning the database onto z/OS so it is online and queryable via JDBC.

**This workflow supports DEDB only.** If the user requests any other access method (HISAM, HIDAM, HDAM, HALDB, or any other), Bob must decline and respond:

> "This workflow only supports DEDB databases. Support for other access methods is not available in this version."

**Trigger keywords:** create, build, design, define, make, set up, new database, DDL, provision, allocate, initialize, autocreate, DEDB, DBFUMIN0, bring online

---

## The Four-Phase Approach

1. **Gather** — Bob asks the user for database name, segments, and fields. Nothing is generated yet.
2. **Generate DDL** — Bob produces the DEDB DDL, displays it to the user for review, and saves it. Nothing is executed yet.
3. **Execute DDL** — User explicitly approves the displayed DDL before `IMSDBAExecutor` submits it to IMS.
4. **Provision** — After a successful `COMMIT DDL`, Bob autocreates VSAM ESDS area datasets, initializes them with `DBFUMIN0`, and starts the database via Zowe CLI.

Each phase requires explicit user confirmation before proceeding. Bob never executes DDL, generates a PSB, or submits provisioning jobs without approval.

---

## DDL Reference Patterns

Before generating any DDL, Bob reads all files in `context_and_examples/DDL-examples/` to match patterns.

| File | Pattern covered |
|------|----------------|
| `context_and_examples/DDL-examples/dbd/MYDEDB.ddl` | DEDB database — `ACCESS DEDB`, no TABLESPACE, no OVERFLOW, CHAR + BINARY fields, `COMMIT DDL` |
| `context_and_examples/PSB-DDL-examples/PSBDDL.txt` | PSB DDL — `CREATE PROGRAMVIEW` with SCHEMA, PCB, and SENSEGVIEW per segment |

---

## Phase 1 — Gather Intent

Bob asks the following questions in order. All fields marked **Required** must be answered before generation begins.

### Q1: Database Name
```
What is the name of your DEDB database?
```
- **Required.** IMS convention: uppercase, 1–8 characters (e.g. `MYDEDB`, `TXNDB01`).

### Q2: Root Segment
```
Describe your root segment:
  - Segment name?
  - Fields? (for each: name, data type, length)
  - Which field is the primary key?
```
- **Required.** At least one segment (the root) must be defined.
- Field names: uppercase, up to 8 characters.
- Supported types: `CHAR` (character), `BINARY` (packed/binary).
- The primary key must be the first field listed.

### Q3: Child Segments
```
Do you have child segments under [ROOT_SEGMENT_NAME]?  (yes / no)
```
- If **yes**, for each child Bob asks:
  - Child segment name?
  - Parent segment name?
  - Fields? (name, type, length for each)
  - Which field is the key for this segment?
- Bob repeats until the user confirms the hierarchy is complete.

### Q4: Number of DEDB Areas *(optional)*
```
How many DEDB areas do you need?  (default: 1)
```
- Defaults to **1** if skipped.
- Each area maps to one VSAM ESDS dataset during provisioning.

---

## Phase 2 — Generate DDL

### DEDB DDL Rules

| Value | Rule |
|-------|------|
| `ACCESS` | Always `DEDB` |
| `RMNAME` | **Required** — always `RMNAME(DBFDBMA0)`. IMS rejects `-9074` without it. `DBFDBMA0` is the standard Fast Path randomizing module |
| `CREATE TABLESPACE` | **Required** — DEDB uses a different tablespace syntax from full-function DBs (see template below). The `ddname` is the **area name**. Use `SIZE`, `UOW`, `ROOT` — never `RECORD`, `OVERFLOW`, or `SIZE SECONDARY` |
| No `OVERFLOW` | DEDB tablespaces never include an `OVERFLOW(...)` clause |
| No `PASSWDNO` / `DATXEXITNO` | These clauses are **invalid** for DEDB — omit them |
| No `INSERT LOGICAL` / `AMBIGUOUS INSERT LAST` | These segment-level DML options are **invalid** for DEDB segments — omit them entirely from `CREATE TABLE` |
| Area name | The `ddname` on `CREATE TABLESPACE` is the area name (1–8 alphanumeric). For a single-area DEDB use the DB name. Must be unique across the IMS system |
| `START` position | Field 1 always starts at 1. Each subsequent field: previous `START` + previous field length |
| `MAXBYTES` | Sum of all field lengths in the segment |
| `VERSION` | Auto-generated at creation time in IMS format `MM/DD/YYYY.HH` |
| `CCSID` | Always `'Cp1047'` |
| `TYPE` | `C` for CHAR, `P` for BINARY |
| `TYPECONVERTER` | `CHAR` for TYPE C, `BINARY` for TYPE P |
| `FOREIGN KEY` | Child segments: `FOREIGN KEY REFERENCES <PARENT_SEGMENT>` at end of column list |
| Parent key in child | Child must include parent's primary key as a column (own `START`, `TYPE`, `INTERNALNAME`, `TYPECONVERTER`, `CCSID`) |
| Child `MAXBYTES` | Sum of child's own fields **plus** the parent key field |
| `COMMIT DDL` | Always the final statement — required by IMS JDBC. Never bare `COMMIT` |

### DEDB DDL Templates

#### `CREATE DATABASE`
```sql
CREATE DATABASE <DBNAME> ACCESS DEDB
    RMNAME(DBFDBMA0)
    VERSION '<MM/DD/YYYY.HH>'
    DATA CAPTURE NONE CCSID 'Cp1047';
```

#### `CREATE TABLESPACE` — DEDB area
```sql
CREATE TABLESPACE <AREA_NAME> IN <DBNAME>
    SIZE PRIMARY <n>
    UOW(100,2)
    ROOT(100,2);
```
- `<AREA_NAME>` — area name (ddname), 1–8 alphanumeric. For a single-area DEDB, use the database name.
- `SIZE PRIMARY` — primary size of the area dataset in KB (e.g. `512`).
- `UOW(100,2)` — verified working default: 100 UOW entries, 2 control intervals each.
- `ROOT(100,2)` — verified working default: 100 root anchor entries, 2 control intervals each.
- **No** `RECORD`, **No** `OVERFLOW`, **No** `SIZE SECONDARY`.

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
    FOREIGN KEY REFERENCES <PARENT_SEGMENTNAME>
    ) IN DATABASE <DBNAME>
    INTERNALNAME <CHILD_SEGMENTNAME>
    MAXBYTES <sum_including_parent_key>
    CCSID 'Cp1047';
```

### PSB DDL Template

Bob reads `context_and_examples/PSB-DDL-examples/` before generating. PSB generation is prompted **after DBD execution succeeds** (Phase 3) — not generated up-front with the DBD DDL.

```sql
CREATE PROGRAMVIEW <PSBNAME> (
    CREATE SCHEMA <PCB_NAME> USING <DBDNAME>
        AS <PCB_NAME> (
            CREATE SENSEGVIEW <ROOT_SEGMENT>,
            CREATE SENSEGVIEW <CHILD_SEGMENT_1>)
        PROCOPT '<PROCOPT>' LISTYES
        POSSNGL SBCOND
) CMPATYES GSROLBOKNO OLICYES LOCKMAX 0 IOASIZE 600 SSASIZE 840
    LANGASSEM;

COMMIT DDL;
```

| PSB Value | Rule |
|-----------|------|
| `PROGRAMVIEW` name | PSB name — up to 8 characters |
| `SCHEMA` / `AS` name | PCB name — matches the schema IMS JDBC uses |
| `USING <DBDNAME>` | The database name from `CREATE DATABASE` |
| `SENSEGVIEW` entries | One per segment, root first then children |
| `PROCOPT` | User-chosen; default `AP` |
| Fixed clauses | Always included: `LISTYES POSSNGL SBCOND CMPATYES GSROLBOKNO OLICYES LOCKMAX 0 IOASIZE 600 SSASIZE 840 LANGASSEM` |
| `COMMIT DDL` | Always last — required |

### PSB Gather Questions

After DBD execution succeeds Bob asks: **"Would you like to create a PSB for `<DBNAME>` now?"**

If yes, Bob asks three questions before generating:

1. **PSB name** — `CREATE PROGRAMVIEW` name, up to 8 characters (e.g. `MYDBPSB`)
2. **PCB name** — `CREATE SCHEMA` name, matches the IMS JDBC schema alias (e.g. `PCB01`)
3. **Processing options** — `G` / `GU` / `L` / `LS` / `A` / `AP` (default: `AP`)

---

## Phase 3 — Execute DDL

### IMSDBAExecutor

```bash
cd /Users/andrewpurso/Documents/bob-jdbc-demo/bob-demo/Java_Programs

# Execute database DDL
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSDBAExecutor" \
  -Dexec.args="CREATE ../context_and_examples/ddl-output/<DBNAME>_create_<timestamp>.sql"

# Execute PSB DDL
mvn exec:java -Dexec.mainClass="com.ibm.ims.jdbcbob.IMSDBAExecutor" \
  -Dexec.args="CREATE ../context_and_examples/psb-output/<PSBNAME>_psb_<timestamp>.sql"
```

### DEDB COMMIT DDL Rule

**`COMMIT DDL` must only be issued after ALL of the following have executed successfully in the same session:**

```
1. CREATE DATABASE    (ACCESS DEDB, RMNAME(DBFDBMA0))
2. CREATE TABLESPACE  (DEDB area syntax — SIZE/UOW/ROOT, no RECORD/OVERFLOW)
3. CREATE TABLE       (root segment — at least one)
4. CREATE TABLE       (child segments, in top-down hierarchy order — if any)
```

### Execution Flow

```
1. Connect via JDBC Type-4
2. Parse DDL file — split on ";" — strip any in-file COMMIT DDL from the list
   (the executor issues COMMIT DDL itself after all statements pass)
3. Validate: CREATE DATABASE + CREATE TABLESPACE + at least one CREATE TABLE present
   → Missing → stop, report, do NOT execute
4. Execute: CREATE DATABASE
5. Execute: CREATE TABLESPACE
6. Execute: CREATE TABLE (root)
7. Execute: CREATE TABLE (children, parent-before-child order — if any)
8. All succeeded → executor issues: COMMIT DDL
9. Log to: context_and_examples/ddl-results/<DBNAME>_create_<timestamp>.log
   Any failure at steps 4–7 → stop, do NOT commit, log and report
```

---

## Phase 4 — Provision (DEDB Autocreate)

After a successful `COMMIT DDL` for both the database and PSB, Bob provisions the database onto z/OS using Zowe CLI.

### Prerequisites

```bash
# Install Zowe CLI and IMS plugin
npm install -g @zowe/cli
zowe plugins install @zowe/ims-for-zowe-cli

# z/OSMF profile
zowe profiles create zosmf <profile-name> \
  --host <zosmf-host> --port 443 \
  --user <userid> --password <password> \
  --reject-unauthorized false

# IMS profile
zowe profiles create ims <profile-name> \
  --host <ims-connect-host> --port <ims-connect-port> \
  --ims-connect-host <ims-connect-host> \
  --ims-connect-port <ims-connect-port> \
  --user <userid> --password <password>
```

### DEDB Autocreate Rules

1. **VSAM datasets are autocreated by the system** — do NOT allocate them manually via Zowe or IDCAMS
2. **Dataset naming convention** — `DSN=(DFSF10.&DBNAME.&AREANAME,TYPEFP)` where `&AREANAME` matches the `ddname` on `CREATE TABLESPACE`. For a single-area DEDB this is the same as the database name (e.g. `DFSF10.DI26PART.DI26PART`)
3. **Area count** — defaults to the value entered in Phase 1 Q4, or `1` if skipped
4. **No load step** — DEDB areas start empty; `DFSURGL0` is never run
5. **No overflow dataset** — DEDB areas do not use overflow
6. **Image copy skipped by default** — pass `--image-copy` to force `DFSUDMP0`
7. **Online resource type** — `zowe ims create db --access-type DEDB`
8. **DBFUMIN0 DSN pattern** — always reference the base cluster: `DFSF10.<DBNAME>.<AREANAME>`
9. **ACBLIB** — `DFSF10.ACBLIB`
10. **DBRC** — use `PARM='DBRC=N'`; no RECON DD statements required

### Provisioning Script

**Script:** `provision-ims-db.sh`  **Location:** project root

```bash
# DEDB autocreate — 1 area (default)
./provision-ims-db.sh <DB_NAME> <PSB_NAME> --db-type DEDB

# DEDB autocreate — multiple areas
./provision-ims-db.sh <DB_NAME> <PSB_NAME> --db-type DEDB --areas 3

# DEDB autocreate — custom LRECL, force image copy
./provision-ims-db.sh <DB_NAME> <PSB_NAME> --db-type DEDB --esds-lrecl 8192 --image-copy

# With custom HLQ
./provision-ims-db.sh MYDEDB MYPSBNAM --db-type DEDB --areas 2 --hlq IBMUSER
```

```bash
#!/bin/bash
# ============================================================
# IMS DEDB Provisioning Automation via Zowe CLI
# Prerequisites: Zowe CLI + @zowe/ims-for-zowe-cli plugin
# Usage: ./provision-ims-db.sh <DB_NAME> <PSB_NAME> [options]
#   Options:
#     --db-type DEDB        Required — only DEDB is supported
#     --hlq <HLQ>           High-level qualifier (default: IBMUSER)
#     --areas <N>           Number of DEDB areas to autocreate (default: 1)
#     --esds-lrecl <N>      LRECL for ESDS datasets (default: 4096)
#     --image-copy          Force image copy step (skipped by default)
#     --dbrc                Submit optional DBRC definition JCL
#     --mda                 Submit optional MDA definition JCL
#     --zosmf <profile>     z/OSMF Zowe profile name (default: zosmf)
#     --ims <profile>       IMS Zowe profile name (default: ims)
# ============================================================

set -euo pipefail

DB_NAME="${1:?ERROR: DB_NAME required}"
PSB_NAME="${2:?ERROR: PSB_NAME required}"
shift 2

HLQ="IBMUSER"
DB_TYPE=""
DEDB_AREAS=1
ESDS_LRECL=4096
IMAGE_COPY=false
DBRC=false
MDA=false
ZOSMF_PROFILE="zosmf"
IMS_PROFILE="ims"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --db-type)     DB_TYPE="$2";       shift 2 ;;
    --hlq)         HLQ="$2";           shift 2 ;;
    --areas)       DEDB_AREAS="$2";    shift 2 ;;
    --esds-lrecl)  ESDS_LRECL="$2";   shift 2 ;;
    --image-copy)  IMAGE_COPY=true;    shift   ;;
    --dbrc)        DBRC=true;          shift   ;;
    --mda)         MDA=true;           shift   ;;
    --zosmf)       ZOSMF_PROFILE="$2"; shift 2 ;;
    --ims)         IMS_PROFILE="$2";   shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ "${DB_TYPE}" != "DEDB" ]]; then
  echo "ERROR: Only --db-type DEDB is supported. Other types are planned for a future release."
  exit 1
fi

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
LOG_FILE="context_and_examples/ddl-results/${DB_NAME}_provision_${TIMESTAMP}.log"
mkdir -p context_and_examples/ddl-results

log() { echo "$1" | tee -a "${LOG_FILE}"; }

wait_for_job() {
  local JOB_ID="$1" DESC="$2"
  log "  Submitted ${DESC}: ${JOB_ID}"
  zowe zos-jobs wait for-output-status "${JOB_ID}" --profile "${ZOSMF_PROFILE}" >> "${LOG_FILE}" 2>&1
  local RC
  RC=$(zowe zos-jobs view job-status-by-jobid "${JOB_ID}" \
    --profile "${ZOSMF_PROFILE}" --response-format-json 2>/dev/null \
    | jq -r '.data.retcode // "UNKNOWN"')
  [[ "${RC}" == "CC 0000" || "${RC}" == "UNKNOWN" ]] || { log "  ✗ ${DESC} failed (${RC})"; exit 1; }
  log "  ✓ ${DESC} completed (${RC})."
}

log "========================================================"
log "IMS DEDB Provisioning: ${DB_NAME}  PSB: ${PSB_NAME}"
log "HLQ: ${HLQ}  Areas: ${DEDB_AREAS}  LRECL: ${ESDS_LRECL}"
log "========================================================"

# ── STEP 3: VSAM ESDS area datasets (autocreated by system) ──
log ""
log "[Step 3] VSAM ESDS area datasets are autocreated by the system via TYPEFP."
log "  DSN pattern: DFSF10.${DB_NAME}.${DB_NAME} (single-area DEDB)"
log "  No manual allocation required."

# ── STEP 4 (optional): DBRC definitions ───────────────────
log ""
if [[ "${DBRC}" == "true" ]]; then
  log "[Step 4] Submitting DBRC definition JCL..."
  DBRC_JOB_ID=$(zowe zos-jobs submit data-set "${HLQ}.JCLLIB(DBRCDEF)" \
    --profile "${ZOSMF_PROFILE}" --response-format-json | jq -r '.data.jobid')
  wait_for_job "${DBRC_JOB_ID}" "DBRC definition"
else
  log "[Step 4] Skipping optional DBRC definitions."
fi

# ── STEP 5 (optional): MDA definitions ────────────────────
log ""
if [[ "${MDA}" == "true" ]]; then
  log "[Step 5] Submitting MDA definition JCL..."
  MDA_JOB_ID=$(zowe zos-jobs submit data-set "${HLQ}.JCLLIB(MDADEF)" \
    --profile "${ZOSMF_PROFILE}" --response-format-json | jq -r '.data.jobid')
  wait_for_job "${MDA_JOB_ID}" "MDA definition"
else
  log "[Step 5] Skipping optional MDA definitions."
fi

# ── STEP 6: Initialize each area with DBFUMIN0 ────────────
log ""
log "[Step 6] Initializing DEDB areas with DBFUMIN0..."
for (( i=1; i<=DEDB_AREAS; i++ )); do
  AREA_NAME="${DB_NAME}"
  AREA_DSN="DFSF10.${DB_NAME}.${AREA_NAME}"
  INIT_JCL="//UMIN$(printf '%-4s' "${DB_NAME}")  JOB (ACCT),'IMS DEDB INIT',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//STEP1    EXEC PGM=DBFUMIN0,REGION=0M,PARM='DBRC=N'
//STEPLIB  DD DSN=IMS.SDFSRESL,DISP=SHR
//ACBLIB   DD DSN=DFSF10.ACBLIB,DISP=SHR
//SYSPRINT DD SYSOUT=*
//SYSUDUMP DD SYSOUT=*
//${AREA_NAME} DD DSN=${AREA_DSN},DISP=OLD
//CONTROL  DD *
AREA=${AREA_NAME}
/*"
  INIT_JOB_ID=$(echo "${INIT_JCL}" | zowe zos-jobs submit stdin \
    --profile "${ZOSMF_PROFILE}" --response-format-json | jq -r '.data.jobid')
  wait_for_job "${INIT_JOB_ID}" "DEDB area ${i} init (${AREA_DSN})"
done

# ── STEP 7: Load — skipped for DEDB ───────────────────────
log ""
log "[Step 7] Skipping load — DEDB areas start empty."

# ── STEP 8: Image Copy — optional for DEDB ────────────────
log ""
if [[ "${IMAGE_COPY}" == "true" ]]; then
  log "[Step 8] Taking initial image copy (DFSUDMP0)..."
  IC_JCL="//IMSIC    JOB (ACCT),'IMS IC',CLASS=A,MSGCLASS=X
//STEP1    EXEC PGM=DFSUDMP0
//STEPLIB  DD DSN=IMS.SDFSRESL,DISP=SHR
//IMS      DD DSN=IMS.PSBLIB,DISP=SHR
//         DD DSN=IMS.DBDLIB,DISP=SHR
//SYSPRINT DD SYSOUT=*
//DFSVSAMP DD DSN=IMS.PROCLIB(DFSVSMHP),DISP=SHR
//DFSUDUMP DD DSN=${HLQ}.${DB_NAME}.ICOPY(+1),
//            DISP=(NEW,CATLG),UNIT=SYSDA,SPACE=(CYL,(10,5))
//SYSIN    DD *
  ${DB_NAME}
/*"
  IC_JOB_ID=$(echo "${IC_JCL}" | zowe zos-jobs submit stdin \
    --profile "${ZOSMF_PROFILE}" --response-format-json | jq -r '.data.jobid')
  wait_for_job "${IC_JOB_ID}" "Image copy"
else
  log "[Step 8] Skipping image copy (pass --image-copy to force)."
fi

# ── STEP 9: Define online resource definitions ─────────────
log ""
log "[Step 9] Creating IMS online resource definitions..."
zowe ims create db "${DB_NAME}" \
  --access-type DEDB \
  --profile "${IMS_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ DB definition created: ${DB_NAME}"

zowe ims create pgm "${PSB_NAME}" \
  --bmptype N --dopt N \
  --profile "${IMS_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ PGM definition created: ${PSB_NAME}"

# ── STEP 10: Import catalog definitions ───────────────────
log ""
log "[Step 10] Running IMPORT DEFN SOURCE(CATALOG)..."
if ! ../run-catimp.sh >> "${LOG_FILE}" 2>&1; then
  log "  ✗ Catalog import failed. Check log for details."; exit 1
fi
log "  ✓ Definitions imported from catalog."

# ── STEP 11: Start IMS resources ──────────────────────────
log ""
log "[Step 11] Starting IMS database and program..."
zowe ims start db "${DB_NAME}" --profile "${IMS_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ Database started: ${DB_NAME}"
zowe ims start pgm "${PSB_NAME}" --profile "${IMS_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ Program started: ${PSB_NAME}"

log ""
log "========================================================"
log "✓ Provisioning complete."
log "  Database : ${DB_NAME}  (DEDB, ${DEDB_AREAS} area(s))"
log "  PSB      : ${PSB_NAME}"
log "  Log      : ${LOG_FILE}"
log "  → Ready for JDBC queries via Tenet 1 (IMSQueryExecutor)"
log "========================================================"
```

---

## Bob's Full Decision Tree

```
User requests a new database
        ↓
Access method check
  ├─ DEDB (or unspecified) → continue
  └─ Any other type (HISAM, HIDAM, HDAM, HALDB, etc.)
         → stop: "This workflow only supports DEDB databases."
        ↓
Phase 1: Gather Intent
  ├─ DB name?              → ask (required)
  ├─ Root segment?         → name + fields (required)
  ├─ Child segments?       → repeat until hierarchy complete
  └─ Number of areas?      → default 1
        ↓
Phase 2: Generate DDL
  ├─ Read context_and_examples/DDL-examples/dbd/MYDEDB.ddl for DEDB pattern
  ├─ Apply DEDB rules: ACCESS DEDB, RMNAME(DBFDBMA0), DEDB area tablespace
  │    (no PASSWDNO/DATXEXITNO, no INSERT LOGICAL/AMBIGUOUS INSERT LAST)
  ├─ Auto-calculate: START positions, MAXBYTES, VERSION timestamp
  ├─ Order: CREATE DATABASE → CREATE TABLESPACE (area) → CREATE TABLE(root) → CREATE TABLE(children)
  ├─ Save DBD DDL to: context_and_examples/ddl-output/<DBNAME>_create_<timestamp>.sql
  └─ Display full DDL to user in a code block for review
        ↓
Phase 3: Execute DDL
  ├─ Ask "Execute this DDL now?"
  │    YES → IMSDBAExecutor CREATE <dbd_file>
  │           ├─ Validate: CREATE DATABASE + CREATE TABLE present
  │           ├─ Execute in order; COMMIT DDL only after all succeed
  │           ├─ Failure → stop, report, do NOT commit
  │           └─ Success → log to context_and_examples/ddl-results/
  │                      → ask "Run IMPORT DEFN SOURCE(CATALOG) now?"
  │                           YES → ./run-catimp.sh
  │                           NO  → remind user to run manually
  │                      → ask "Would you like to create a PSB for <DBNAME> now?"
  │                           YES → proceed to PSB generation (below)
  │                           NO  → skip to Phase 4
  │    NO  → file saved, ask "Would you like to create a PSB for <DBNAME> now?"
  │                YES → proceed to PSB generation (below)
  │                NO  → skip to Phase 4
  │
  ├─ PSB generation
  │    ├─ Read context_and_examples/PSB-DDL-examples/ for PSB pattern
  │    ├─ Ask: PSB name (up to 8 chars, e.g. <DBNAME>PSB)
  │    ├─ Ask: PCB name (e.g. PCB01)
  │    ├─ Ask: PROCOPT (default AP)
  │    ├─ Generate PSB DDL using CREATE PROGRAMVIEW template
  │    │    (SENSEGVIEW for root first, then each child in hierarchy order)
  │    ├─ Save PSB DDL to: context_and_examples/psb-output/<PSBNAME>_psb_<timestamp>.sql
  │    └─ Display full PSB DDL to user in a code block for review
  │
  ├─ Ask "Execute this PSB DDL now?"
  │    YES → IMSDBAExecutor CREATE <psb_file>
  │           ├─ Failure → report error, do NOT commit
  │           └─ Success → log to context_and_examples/ddl-results/
  │                      → ask "Run IMPORT DEFN SOURCE(CATALOG) now?"
  │    NO  → file saved at context_and_examples/psb-output/
        ↓
Phase 4: Provision (DEDB Autocreate)
  Ask: "Would you like to provision <DBNAME> onto z/OS now?"
    NO  → Done. Run provision-ims-db.sh manually when ready.
    YES →
      Step 3: Autocreate VSAM ESDS per area → <HLQ>.<DB>.AREA1..<N>
      Step 4: DBRC (if --dbrc)
      Step 5: MDA  (if --mda)
      Step 6: DBFUMIN0 per area
      Step 7: Skip (DEDB starts empty)
      Step 8: DFSUDMP0 (only if --image-copy)
      Step 9: zowe ims create db --access-type DEDB + create pgm
      Step 10: IMPORT DEFN SOURCE(CATALOG) via run-catimp.sh
      Step 11: zowe ims start db + start pgm
              ↓
      Log to: context_and_examples/ddl-results/<DB>_provision_<timestamp>.log
              ↓
      → Handoff to Tenet 1 (IMSQueryExecutor) for JDBC queries
```

---

## Output Directories

```
bob-demo/
├── Java_Programs/                           ← Maven project / Java source
└── context_and_examples/
    ├── DDL-examples/dbd/MYDEDB.ddl          ← DEDB reference pattern
    ├── ddl-output/<DBNAME>_create_<ts>.sql  ← Generated database DDL
    ├── psb-output/<PSBNAME>_psb_<ts>.sql    ← Generated PSB DDL
    └── ddl-results/
        ├── <DBNAME>_create_<ts>.log         ← DDL execution log
        ├── <PSBNAME>_psb_<ts>.log           ← PSB execution log
        └── <DBNAME>_provision_<ts>.log      ← Provisioning log
```

---

## Error Handling

| Failure Point | Likely Cause | Recovery |
|--------------|-------------|---------|
| `COMMIT DDL failed` | A prior CREATE statement failed | Check `context_and_examples/ddl-results/` for the failing statement |
| `COMMIT DDL -9000: TABLESPACE not found` | `CREATE TABLESPACE` was missing or used wrong syntax | Ensure DEDB DDL includes `CREATE TABLESPACE <area> IN <db> SIZE PRIMARY <n> UOW(100,2) ROOT(100,2)` |
| `-9005: TABLESPACE invalid` | Missing `UOW`/`ROOT` parameters or used full-function syntax (`RECORD`, `OVERFLOW`) | Use DEDB area syntax — `SIZE PRIMARY <n> UOW(100,2) ROOT(100,2)`, no `RECORD` or `OVERFLOW` |
| `-644: Invalid value specified for UOW` | `UOW`/`ROOT` values are out of range for this IMS system | Use `UOW(100,2) ROOT(100,2)` — verified working defaults |
| `-8005: AIB RC 108 reason 71C` | DEDB ACBLIB not available for catalog commit — IMS system admin issue | Ensure Fast Path ACBLIB/IMSDALIB is available on the IMS region; contact IMS system administrator |
| `Segment already exists` | DB name already defined in IMS | Choose a different database name |
| Step 3 — dataset already exists | Previous partial run | `zowe zos-files delete data-set` then rerun |
| Step 3 — ESDS allocation fails | Insufficient DASD or duplicate name | Verify HLQ; delete partial datasets and rerun |
| Step 6 — DBFUMIN0 fails | DBD not yet in catalog | Confirm COMMIT DDL succeeded in `context_and_examples/ddl-results/` |
| Step 6 — DBFUMIN0 fails | Area dataset not found | Confirm `<HLQ>.<DB>.AREA<n>` was allocated in Step 3 |
| Step 9 — `--access-type DEDB` rejected | IMS Fast Path not licensed | Confirm Fast Path feature is enabled on the IMS region |
| Step 10 — IMPORT DEFN fails | DRD not enabled | Use static system definition macros (manual) |
| Step 11 — START DB fails | DB not registered | Confirm Step 9 `zowe ims create db` succeeded |
| DB queryable in catalog but not at runtime | IMPORT DEFN not run | Run `./run-catimp.sh` or issue via IMS MTO console |

---

## Future Enhancements

1. **DEDB UOW/ROOT defaults** — Research and document correct default values for `UOW` and `ROOT` parameters for common DEDB workloads
2. **Full-function DB support** — HISAM, HIDAM, HDAM (requires TABLESPACE, OVERFLOW, DFSUPNT0, DFSURGL0 load step)
3. **HALDB support** — PHIDAM / PHDAM with per-partition initialization loop
4. **DEDB area count from DDL** — Auto-detect area count from `context_and_examples/DDL-examples/` instead of asking the user
5. **ALTER support** — Modify existing DEDB structures via `IMSDBAExecutor ALTER`
6. **DROP support** — Remove database structures via `IMSDBAExecutor DROP`
7. **Deprovision script** — `deprovision-ims-db.sh` to stop, delete definitions, and remove datasets
8. **Multiple PCBs per PSB** — More than one `CREATE SCHEMA` block per `CREATE PROGRAMVIEW`
9. **PSB-only mode** — Generate a PSB for an existing database without the full CREATE flow
