# Tenet 3 — IMS Database Provisioning via Zowe CLI

## Overview

This workflow covers how Bob automates the **full IMS database provisioning lifecycle** using Zowe CLI — the steps that must be completed before an IMS database can be queried via JDBC. It picks up immediately after a successful DDL + PSB `COMMIT` (the output of Tenet 2) and drives the database through to an online, queryable state.

**Trigger keywords:** provision, allocate, initialize, load, image copy, start database, bring online, set up datasets, DBRC, DFSUPNT0, DFSURGL0, DBFUMIN0

---

## The Problem This Solves

After Tenet 2 generates and submits DDL + PSB, the IMS catalog knows about the database — but the underlying z/OS data sets do not yet exist, the partitions are uninitialized, and no data has been loaded. Attempting a JDBC query at this point fails with a database unavailable error. This workflow automates the nine steps between catalog registration and first query.

---

## IBM IMS 14 Provisioning — Step Mapping

| Step | Task | Automated By |
|------|------|-------------|
| 1 | Generate DDL with IMS Enterprise Suite Explorer | Bob (Tenet 2) |
| 2 | Submit DDL + `COMMIT DDL` | Bob (Tenet 2) — `IMSDBAExecutor.java` |
| **3** | **Allocate database data sets** | `provision-ims-db.sh` — `zowe zos-files create data-set-vsam` |
| 4 | Define DBRC definitions (optional) | `provision-ims-db.sh` — JCL submit (if `--dbrc` flag set) |
| 5 | Define MDA for dynamic allocation (optional) | `provision-ims-db.sh` — JCL submit (if `--mda` flag set) |
| **6** | **Initialize partitions** | `provision-ims-db.sh` — DFSUPNT0 / DBFUMIN0 JCL |
| **7** | **Load IMS database** | `provision-ims-db.sh` — DFSURGL0 JCL |
| 8 | Establish recovery point (Image Copy) | `provision-ims-db.sh` — DFSUDMP0 JCL |
| 9 | Define online resource definitions (`CREATE DB/PGM`) | `provision-ims-db.sh` — `zowe ims create db/pgm` |
| 10 | Issue `IMPORT DEFN SOURCE(CATALOG)` | `provision-ims-db.sh` — `zowe ims issue cmd` |
| 11 | Start IMS resources | `provision-ims-db.sh` — `zowe ims start db/pgm` |
| 12 | Query via IMS JDBC | Bob (Tenet 1) — `IMSQueryExecutor.java` |

---

## Prerequisites

### Zowe CLI Installation

```bash
# Install Zowe CLI
npm install -g @zowe/cli

# Install required plugins
zowe plugins install @zowe/ims-for-zowe-cli

# Verify
zowe --version
zowe plugins list
```

### Zowe Profiles

Two profiles are required:

```bash
# z/OSMF profile (for data set and job operations)
zowe profiles create zosmf <profile-name> \
  --host <zosmf-host> \
  --port 443 \
  --user <userid> \
  --password <password> \
  --reject-unauthorized false

# IMS profile (for IMS-specific commands)
zowe profiles create ims <profile-name> \
  --host <ims-connect-host> \
  --port <ims-connect-port> \
  --ims-connect-host <ims-connect-host> \
  --ims-connect-port <ims-connect-port> \
  --user <userid> \
  --password <password>
```

### Database Type Reference

| DB Type | Init Utility | Load Required | Notes |
|---------|-------------|--------------|-------|
| Full-function HIDAM/HDAM | `DFSUPNT0` | **Yes** — `DFSURGL0` | Cannot open without initial load |
| HALDB | `DFSUPNT0` | **Yes** — `DFSURGL0` | Per-partition initialization |
| Fast Path DEDB | `DBFUMIN0` | No (optional) | Can start empty |
| MSDB | N/A | No | In-memory, no data sets |

---

## Provisioning Script

**Location:** `provision-ims-db.sh` (project root)

### Usage

```bash
# Full-function DB (HISAM/HIDAM/HDAM)
./provision-ims-db.sh <DB_NAME> <PSB_NAME> [--hlq <HLQ>] [--dbrc] [--mda]

# Fast Path DEDB (skip load step)
./provision-ims-db.sh <DB_NAME> <PSB_NAME> --db-type DEDB

# Examples
./provision-ims-db.sh DI23PART DFSSAMT4
./provision-ims-db.sh DI23PART DFSSAMT4 --hlq IBMUSER --dbrc
./provision-ims-db.sh MYDEDB  MYPSBNAM --db-type DEDB
```

### Script: `provision-ims-db.sh`

```bash
#!/bin/bash
# ============================================================
# IMS Database Provisioning Automation via Zowe CLI
# Covers IBM IMS 14 provisioning Steps 3–11
# Prerequisites: Zowe CLI + @zowe/ims-for-zowe-cli plugin
# Usage: ./provision-ims-db.sh <DB_NAME> <PSB_NAME> [options]
#   Options:
#     --hlq <HLQ>        High-level qualifier (default: IBMUSER)
#     --db-type <TYPE>   FULLFUNCTION | DEDB (default: FULLFUNCTION)
#     --dbrc             Submit optional DBRC definition JCL
#     --mda              Submit optional MDA definition JCL
#     --zosmf <profile>  z/OSMF Zowe profile name (default: zosmf)
#     --ims <profile>    IMS Zowe profile name (default: ims)
# ============================================================

set -euo pipefail

# ── Argument parsing ───────────────────────────────────────
DB_NAME="${1:?ERROR: DB_NAME required. Usage: $0 <DB_NAME> <PSB_NAME>}"
PSB_NAME="${2:?ERROR: PSB_NAME required. Usage: $0 <DB_NAME> <PSB_NAME>}"
shift 2

HLQ="IBMUSER"
DB_TYPE="FULLFUNCTION"
DBRC=false
MDA=false
ZOSMF_PROFILE="zosmf"
IMS_PROFILE="ims"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --hlq)       HLQ="$2";           shift 2 ;;
    --db-type)   DB_TYPE="$2";       shift 2 ;;
    --dbrc)      DBRC=true;          shift   ;;
    --mda)       MDA=true;           shift   ;;
    --zosmf)     ZOSMF_PROFILE="$2"; shift 2 ;;
    --ims)       IMS_PROFILE="$2";   shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
LOG_DIR="ddl-results"
LOG_FILE="${LOG_DIR}/${DB_NAME}_provision_${TIMESTAMP}.log"
mkdir -p "${LOG_DIR}"

log() { echo "$1" | tee -a "${LOG_FILE}"; }

wait_for_job() {
  local JOB_ID="$1"
  local DESC="$2"
  log "  Submitted ${DESC} job: ${JOB_ID}"
  log "  Waiting for completion..."
  zowe zos-jobs wait for-output-status "${JOB_ID}" \
    --profile "${ZOSMF_PROFILE}" >> "${LOG_FILE}" 2>&1
  local RC
  RC=$(zowe zos-jobs view job-status-by-jobid "${JOB_ID}" \
    --profile "${ZOSMF_PROFILE}" \
    --response-format-json 2>/dev/null | jq -r '.data.retcode // "UNKNOWN"')
  if [[ "${RC}" != "CC 0000" && "${RC}" != "UNKNOWN" ]]; then
    log "  ✗ ${DESC} failed with return code: ${RC}"
    exit 1
  fi
  log "  ✓ ${DESC} completed (${RC})."
}

log "========================================================"
log "IMS Provisioning: ${DB_NAME}  PSB: ${PSB_NAME}"
log "HLQ: ${HLQ}  Type: ${DB_TYPE}  Timestamp: ${TIMESTAMP}"
log "========================================================"

# ── STEP 3: Allocate VSAM data sets ───────────────────────
log ""
log "[Step 3] Allocating VSAM data sets..."

zowe zos-files create data-set-vsam "${HLQ}.${DB_NAME}.VSAM.DATA" \
  --data-class STANDARD \
  --profile "${ZOSMF_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ ${HLQ}.${DB_NAME}.VSAM.DATA allocated."

zowe zos-files create data-set-vsam "${HLQ}.${DB_NAME}.VSAM.OVFL" \
  --data-class STANDARD \
  --profile "${ZOSMF_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ ${HLQ}.${DB_NAME}.VSAM.OVFL allocated."

# ── STEP 4 (optional): DBRC definitions ───────────────────
if [[ "${DBRC}" == "true" ]]; then
  log ""
  log "[Step 4] Submitting DBRC definition JCL..."
  DBRC_JOB_ID=$(zowe zos-jobs submit data-set "${HLQ}.JCLLIB(DBRCDEF)" \
    --profile "${ZOSMF_PROFILE}" \
    --response-format-json | jq -r '.data.jobid')
  wait_for_job "${DBRC_JOB_ID}" "DBRC definition"
else
  log ""
  log "[Step 4] Skipping optional DBRC definitions."
fi

# ── STEP 5 (optional): MDA definitions ────────────────────
if [[ "${MDA}" == "true" ]]; then
  log ""
  log "[Step 5] Submitting MDA definition JCL..."
  MDA_JOB_ID=$(zowe zos-jobs submit data-set "${HLQ}.JCLLIB(MDADEF)" \
    --profile "${ZOSMF_PROFILE}" \
    --response-format-json | jq -r '.data.jobid')
  wait_for_job "${MDA_JOB_ID}" "MDA definition"
else
  log ""
  log "[Step 5] Skipping optional MDA definitions."
fi

# ── STEP 6: Initialize partitions ─────────────────────────
log ""
if [[ "${DB_TYPE}" == "DEDB" ]]; then
  log "[Step 6] Initializing Fast Path DEDB partitions (DBFUMIN0)..."
  INIT_PGM="DBFUMIN0"
else
  log "[Step 6] Initializing full-function DB partitions (DFSUPNT0)..."
  INIT_PGM="DFSUPNT0"
fi

INIT_JCL="//IMSINIT  JOB (ACCT),'IMS INIT',CLASS=A,MSGCLASS=X
//STEP1    EXEC PGM=${INIT_PGM}
//STEPLIB  DD DSN=IMS.SDFSRESL,DISP=SHR
//IMS      DD DSN=IMS.PSBLIB,DISP=SHR
//         DD DSN=IMS.DBDLIB,DISP=SHR
//SYSPRINT DD SYSOUT=*
//DFSVSAMP DD DSN=IMS.PROCLIB(DFSVSMHP),DISP=SHR
//DBDBD    DD DSN=${HLQ}.${DB_NAME}.VSAM.DATA,DISP=SHR
//SYSIN    DD *
  ${DB_NAME}
/*"

INIT_JOB_ID=$(echo "${INIT_JCL}" | zowe zos-jobs submit stdin \
  --profile "${ZOSMF_PROFILE}" \
  --response-format-json | jq -r '.data.jobid')
wait_for_job "${INIT_JOB_ID}" "Partition initialization"

# ── STEP 7: Load IMS database (full-function only) ────────
log ""
if [[ "${DB_TYPE}" == "DEDB" ]]; then
  log "[Step 7] Skipping initial load — Fast Path DEDB can start empty."
else
  log "[Step 7] Loading IMS database (DFSURGL0)..."
  LOAD_JCL="//IMSLOAD  JOB (ACCT),'IMS LOAD',CLASS=A,MSGCLASS=X
//STEP1    EXEC PGM=DFSURGL0
//STEPLIB  DD DSN=IMS.SDFSRESL,DISP=SHR
//IMS      DD DSN=IMS.PSBLIB,DISP=SHR
//         DD DSN=IMS.DBDLIB,DISP=SHR
//SYSPRINT DD SYSOUT=*
//DFSVSAMP DD DSN=IMS.PROCLIB(DFSVSMHP),DISP=SHR
//SYSIN    DD DSN=${HLQ}.${DB_NAME}.LOADCARD,DISP=SHR"

  LOAD_JOB_ID=$(echo "${LOAD_JCL}" | zowe zos-jobs submit stdin \
    --profile "${ZOSMF_PROFILE}" \
    --response-format-json | jq -r '.data.jobid')
  wait_for_job "${LOAD_JOB_ID}" "Database load"
fi

# ── STEP 8: Image Copy (DFSUDMP0) ─────────────────────────
log ""
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
  --profile "${ZOSMF_PROFILE}" \
  --response-format-json | jq -r '.data.jobid')
wait_for_job "${IC_JOB_ID}" "Image copy"

# ── STEP 9: Define online resource definitions ─────────────
log ""
log "[Step 9] Creating IMS online resource definitions (DRD)..."

zowe ims create db "${DB_NAME}" \
  --access-type "HISAM" \
  --profile "${IMS_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ DB definition created: ${DB_NAME}"

zowe ims create pgm "${PSB_NAME}" \
  --bmptype N \
  --dopt N \
  --profile "${IMS_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ PGM definition created: ${PSB_NAME}"

# ── STEP 10: Import catalog definitions ───────────────────
log ""
log "[Step 10] Submitting IMPORT DEFN SOURCE(CATALOG) via CATIMP job..."

if ! ../run-catimp.sh >> "${LOG_FILE}" 2>&1; then
  log "  ✗ Catalog import failed. Check log for details."
  exit 1
fi
log "  ✓ Definitions imported from catalog."

# ── STEP 11: Start IMS resources ──────────────────────────
log ""
log "[Step 11] Starting IMS database and program..."

zowe ims start db "${DB_NAME}" \
  --profile "${IMS_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ Database started: ${DB_NAME}"

zowe ims start pgm "${PSB_NAME}" \
  --profile "${IMS_PROFILE}" >> "${LOG_FILE}" 2>&1
log "  ✓ Program started: ${PSB_NAME}"

# ── Summary ───────────────────────────────────────────────
log ""
log "========================================================"
log "✓ Provisioning complete."
log "  Database : ${DB_NAME}"
log "  PSB      : ${PSB_NAME}"
log "  Log      : ${LOG_FILE}"
log "  → Database is online. Ready for JDBC queries."
log "  → Run Tenet 1 to query: IMSQueryExecutor"
log "========================================================"
```

---

## Bob's Decision Tree

```
Provisioning request received
        ↓
Has Tenet 2 completed successfully?
(DDL + PSB COMMIT logged in ddl-results/)
        ↓ NO  → Run Tenet 2 first
        ↓ YES
What database type?
  ┌─────────────────────────────────────────────┐
  │ FULLFUNCTION (HIDAM/HISAM/HDAM/HALDB)       │
  │   Step 3: Allocate VSAM data sets            │
  │   Step 6: Initialize with DFSUPNT0           │
  │   Step 7: Load with DFSURGL0 ← REQUIRED      │
  │   Step 8: Image Copy (DFSUDMP0)              │
  └─────────────────────────────────────────────┘
  ┌─────────────────────────────────────────────┐
  │ DEDB (Fast Path)                             │
  │   Step 3: Allocate VSAM data sets            │
  │   Step 6: Initialize with DBFUMIN0           │
  │   Step 7: Skip (DEDB can start empty)        │
  │   Step 8: Image Copy (DFSUDMP0)              │
  └─────────────────────────────────────────────┘
        ↓
Steps 4-5: Apply DBRC / MDA if --dbrc / --mda flags set
        ↓
Steps 9-10: Create DB+PGM definitions → IMPORT DEFN SOURCE(CATALOG)
        ↓
Step 11: Start DB + PGM
        ↓
Log result to ddl-results/<DB>_provision_<timestamp>.log
        ↓
→ Handoff to Tenet 1 for JDBC queries
```

---

## Output

Provisioning run logs are written to the shared `ddl-results/` directory:

```
ddl-results/
├── DI23PART_create_20260715_145821.log       ← Tenet 2 DDL log
├── DFSSAMT4_psb_20260715_145940.log          ← Tenet 2 PSB log
└── DI23PART_provision_20260715_150500.log    ← Tenet 3 provisioning log  ← NEW
```

---

## Error Handling

| Failure Point | Likely Cause | Recovery |
|--------------|-------------|---------|
| Step 3 — data set already exists | Previous partial run | Delete with `zowe zos-files delete data-set` then rerun |
| Step 6 — init job fails (RC > 0) | DBD not in catalog yet | Verify Tenet 2 COMMIT was successful (`ddl-results/`) |
| Step 7 — load job fails | `LOADCARD` data set missing or empty | Stage load input at `${HLQ}.${DB_NAME}.LOADCARD` |
| Step 7 — load job fails | Fast Path DB needs no load | Rerun with `--db-type DEDB` |
| Step 8 — image copy fails | DBRC already has a recovery point | Safe to skip — DBRC rejects duplicate IC registration |
| Step 10 — IMPORT DEFN fails | DRD not enabled on system | Use static system definition macros instead (manual) |
| Step 11 — START DB fails | Database not registered | Confirm Step 9 `zowe ims create db` succeeded |

---

## Future Enhancements

1. **HALDB partition loop** — Iterate Step 3/6/7 per partition for multi-partition HALDB databases
2. **DBRC automation** — Auto-register image copies in DBRC via RECON update JCL
3. **Load data generation** — Bob generates DFSURGL0 load input from CSV or JSON source data
4. **Status check command** — `zowe ims query db` wrapper to confirm database is online before handing off to Tenet 1
5. **Rollback script** — Companion `deprovision-ims-db.sh` to stop, delete definitions, and remove data sets
