# AVNIVO: keyless GitHub Actions → private GCS audit

This is **read-only** and does not deploy, publish, or change any model. No service account JSON key is needed.

## One-time Google Cloud setup (project 792624108019)

1. Enable **IAM Service Account Credentials API**, **Security Token Service API**, and **Cloud Storage API**.
2. Create service account `avnivo-gcs-auditor` in the project. Grant **Storage Object Viewer** scoped to bucket `avnivo-offline-models-792624108019`, not project-wide.
3. Under **IAM & Admin → Workload Identity Federation**, create a pool (e.g. `github-avnivo`) and an **OIDC** provider (e.g. `github`) with issuer `https://token.actions.githubusercontent.com`.
4. Attribute mapping:
   - `google.subject = assertion.sub`
   - `attribute.repository = assertion.repository`
   - `attribute.ref = assertion.ref`
5. Attribute condition (reject other repositories and branches):
   `assertion.repository == 'royaltaxi963-gif/avnivo-sensor-test' && assertion.ref == 'refs/heads/feat/offline-model-safe-installer-20261117'`
6. On the audit service account, grant **Workload Identity User** to the principalSet for **only** this repo:
   `principalSet://iam.googleapis.com/projects/792624108019/locations/global/workloadIdentityPools/github-avnivo/attribute.repository/royaltaxi963-gif/avnivo-sensor-test`
   The exact pool name must match the one actually created. The provider attribute condition above is also required.

## GitHub configuration

Repository **Settings → Secrets and variables → Actions → Variables**:
- `AVNIVO_GCP_WIF_PROVIDER`: `projects/792624108019/locations/global/workloadIdentityPools/github-avnivo/providers/github`
- `AVNIVO_GCP_AUDIT_SA`: the service account email, typically `avnivo-gcs-auditor@<PROJECT_ID>.iam.gserviceaccount.com`. Replace `<PROJECT_ID>` with the actual Google Cloud project ID, not the project number.

**Important:** Workflow dispatch on GitHub generally requires the workflow file to exist on the default branch. Review and merge only the standalone workflow/configuration after checks, or dispatch through an authorized workflow runner. Do not merge the unfinished installer or deploy it merely to trigger an audit.

## Execution and verification

Actions → **AVNIVO private GCS model audit** → **Run workflow**. The workflow reads GCS objects, computes SHA-256 incrementally and uploads a 1-day artifact. It does not configure the production model manifest automatically. Audit output should be reviewed for exact object names and model-family grouping before operator deployment.

If the workflow reports zero models, inspect the actual GCS folder structure and update `translator-api/audit_gcs_models.py` prefixes based on verified objects. Never assume the current prefixes exist. The audit reads entire files and can incur GCS operations/network costs; do not schedule repeatedly without approval.

## Security

Do not store service account private keys in GitHub, commits, chat, or artifacts. Do not make the bucket public. The GCS auditor has no object-write or Cloud Run deployment permissions. GitHub OIDC federation alone does not authenticate the public model-ticket endpoint; that must be reviewed separately.
