#!/bin/bash
# ============================================================
# IMS Catalog Import (CATIMP) Job Submission
# Submits IBMUSER.DFS.JCLLIB(CATIMP) via z/OSMF REST API
# 
# Usage: ./run-catimp.sh
# 
# Prerequisites:
#   - z/OSMF credentials in environment variables:
#     ZOSMF_USER and ZOSMF_PASSWORD
#   - curl installed
#   - jq installed (for JSON parsing)
# ============================================================

set -euo pipefail

# Load credentials from .env if present (never needs to be set manually)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${SCRIPT_DIR}/.env" ]]; then
    # shellcheck source=.env
    source "${SCRIPT_DIR}/.env"
fi

# Configuration
ZOSMF_HOST="${ZOSMF_HOST:-172.19.3.3}"
ZOSMF_PORT="${ZOSMF_PORT:-10443}"
JOB_DATASET="IBMUSER.DFS.JCLLIB(CATIMP)"

# Validate credentials
if [[ -z "${ZOSMF_USER:-}" ]] || [[ -z "${ZOSMF_PASSWORD:-}" ]]; then
    echo "ERROR: z/OSMF credentials not set."
    echo "Set environment variables: ZOSMF_USER and ZOSMF_PASSWORD"
    exit 3
fi

echo "========================================"
echo "IMS Catalog Importer"
echo "Submitting: ${JOB_DATASET}"
echo "========================================"
echo ""

# Step 1: Submit the job
echo "Submitting job..."
SUBMIT_RESPONSE=$(curl -sk -X PUT \
    "https://${ZOSMF_HOST}:${ZOSMF_PORT}/zosmf/restjobs/jobs" \
    -H "Content-Type: application/json" \
    -H "X-CSRF-ZOSMF-HEADER: Bob" \
    -u "${ZOSMF_USER}:${ZOSMF_PASSWORD}" \
    -d "{\"file\":\"//'${JOB_DATASET}'\"}")

# Extract job ID
JOB_ID=$(echo "${SUBMIT_RESPONSE}" | jq -r '.jobid // empty')

if [[ -z "${JOB_ID}" ]]; then
    echo "ERROR: Job submission failed"
    echo "Response: ${SUBMIT_RESPONSE}"
    exit 1
fi

echo "✓ Job submitted: ${JOB_ID}"
echo "  Waiting for completion..."
echo ""

# Step 2: Wait for job completion
MAX_ATTEMPTS=60  # 5 minutes max (5 second intervals)
ATTEMPT=0

while [[ ${ATTEMPT} -lt ${MAX_ATTEMPTS} ]]; do
    STATUS_RESPONSE=$(curl -sk -X GET \
        "https://${ZOSMF_HOST}:${ZOSMF_PORT}/zosmf/restjobs/jobs?jobid=${JOB_ID}&owner=*" \
        -H "X-CSRF-ZOSMF-HEADER: Bob" \
        -u "${ZOSMF_USER}:${ZOSMF_PASSWORD}")
    
    STATUS=$(echo "${STATUS_RESPONSE}" | jq -r '.[0].status // empty')
    
    if [[ "${STATUS}" == "OUTPUT" ]]; then
        # Job completed
        RETCODE=$(echo "${STATUS_RESPONSE}" | jq -r '.[0].retcode // "UNKNOWN"')
        
        # CC 0000 or CC 0008 (warnings) are both success for IXIMPCAT
        if [[ "${RETCODE}" == "CC 0000" || "${RETCODE}" == "CC 0008" ]]; then
            echo "========================================"
            echo "✓ IMPORT DEFN SOURCE(CATALOG) completed successfully"
            echo "  Job ID: ${JOB_ID}"
            echo "  Return Code: ${RETCODE}"
            echo "========================================"
            exit 0
        else
            echo "========================================"
            echo "✗ Job completed with errors"
            echo "  Job ID: ${JOB_ID}"
            echo "  Return Code: ${RETCODE}"
            echo "========================================"
            exit 2
        fi
    fi
    
    # Job still running
    sleep 5
    ATTEMPT=$((ATTEMPT + 1))
done

echo "ERROR: Job did not complete within timeout period"
exit 1
