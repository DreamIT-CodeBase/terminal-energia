#!/usr/bin/env bash
#
# Deploy the labelling system API to Azure Container Apps.
#
#   ./deploy.sh
#
# Builds the image in the existing container registry of the terminal-scout
# resource group, then creates (or updates) a Container Apps environment and
# container app, both named system-api.
#
# Secrets are read from ./.env and stored as Container App secrets, so they
# never appear in the app's plain environment variables.
#
# Overrides:
#   RESOURCE_GROUP=...   target resource group           (default terminal-scout)
#   ACR_NAME=...         registry to use, if the group has more than one
#   ALLOWED_ORIGINS=...  CORS origins for the labelling UI
#   USE_ACR_ADMIN=1      authenticate to the registry with admin credentials
#                        instead of a managed identity. Use this if the
#                        AcrPull role assignment fails for lack of permission.

set -euo pipefail

# ============================================================
# SETTINGS
# ============================================================

RESOURCE_GROUP="${RESOURCE_GROUP:-terminal-scout}"
APP_NAME="systemapi"
ENVIRONMENT_NAME="systemapi"
IMAGE_REPOSITORY="systemapi"
TARGET_PORT=8100

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

# Container Apps secret names: lowercase letters, digits and dashes only.
SECRET_STORAGE="storage-connection-string"
SECRET_API_KEY="system-api-key"
SECRET_CLIENT_SECRET="azure-client-secret"
SECRET_DEVICE_TOKEN="device-token-secret"
SECRET_FLEET="fleet-secret"

USE_ACR_ADMIN="${USE_ACR_ADMIN:-0}"


# ============================================================
# OUTPUT
# ============================================================

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
info() { printf '    %s\n' "$1"; }
fail() { printf '\n\033[1;31mError: %s\033[0m\n' "$1" >&2; exit 1; }

# ============================================================
# AZURE CLI OUTPUT
#
# On Windows the CLI writes CRLF. Command substitution strips
# the trailing newline but leaves the carriage return, which
# then corrupts every value it lands in -- an
# `az acr show --name "myregistry<CR>"` is rejected as an
# invalid registry name. Every tsv read is piped through this.
# ============================================================

strip_cr() { tr -d '\r'; }


# ============================================================
# READ ONE VALUE FROM .env
#
# The storage connection string contains `;` and `=`, so the
# file must be parsed rather than sourced.
# ============================================================

env_value() {
  local key="$1"

  sed -n "s/^[[:space:]]*${key}[[:space:]]*=//p" "$ENV_FILE" \
    | head -n 1 \
    | tr -d '\r' \
    | sed -e 's/^[[:space:]]*//' \
          -e 's/[[:space:]]*$//' \
          -e 's/^"\(.*\)"$/\1/' \
          -e "s/^'\(.*\)'$/\1/"
}


# ============================================================
# PREFLIGHT
# ============================================================

step "Checking prerequisites"

command -v az >/dev/null 2>&1 \
  || fail "Azure CLI not found on PATH."

az account show >/dev/null 2>&1 \
  || fail "Not signed in. Run: az login"

SUBSCRIPTION="$(az account show --query name -o tsv | strip_cr)"
info "Subscription:   ${SUBSCRIPTION}"
info "Resource group: ${RESOURCE_GROUP}"

az group show --name "$RESOURCE_GROUP" >/dev/null 2>&1 \
  || fail "Resource group '${RESOURCE_GROUP}' not found."

[ -f "$ENV_FILE" ] \
  || fail "${ENV_FILE} not found. Copy .env.example to .env and fill it in."

# The containerapp commands live in an extension.
az extension add --name containerapp --upgrade --only-show-errors >/dev/null 2>&1 || true

# Both providers must be registered before an environment can be created.
for provider in Microsoft.App Microsoft.OperationalInsights; do
  state="$(az provider show --namespace "$provider" --query registrationState -o tsv 2>/dev/null | strip_cr || echo NotRegistered)"
  if [ "$state" != "Registered" ]; then
    info "Registering ${provider}..."
    az provider register --namespace "$provider" --wait --only-show-errors
  fi
done


# ============================================================
# SECRETS FROM .env
# ============================================================

step "Reading configuration from .env"

STORAGE_CONNECTION_STRING="$(env_value AZURE_STORAGE_CONNECTION_STRING)"
SYSTEM_API_KEY="$(env_value SYSTEM_API_KEY)"
SOURCE_CONTAINER="$(env_value SOURCE_CONTAINER)"
ANNOTATED_CONTAINER="$(env_value ANNOTATED_CONTAINER)"

SOURCE_CONTAINER="${SOURCE_CONTAINER:-debugdirectory}"
ANNOTATED_CONTAINER="${ANNOTATED_CONTAINER:-debugdirectoryannotated}"
ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-$(env_value ALLOWED_ORIGINS)}"
ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-http://localhost:3000}"

[ -n "$STORAGE_CONNECTION_STRING" ] \
  || fail "AZURE_STORAGE_CONNECTION_STRING is missing from .env"

[ -n "$SYSTEM_API_KEY" ] \
  || fail "SYSTEM_API_KEY is missing from .env"

case "$STORAGE_CONNECTION_STRING" in
  *REPLACE_WITH_REAL_KEY*)
    fail "AZURE_STORAGE_CONNECTION_STRING in .env still holds the placeholder key."
    ;;
esac

DEVICE_TOKEN_SECRET="$(env_value DEVICE_TOKEN_SECRET)"
FLEET_SECRET="$(env_value FLEET_SECRET)"
DEVICE_TOKEN_TTL_SECONDS="$(env_value DEVICE_TOKEN_TTL_SECONDS)"
ARTIFACT_SAS_TTL_SECONDS="$(env_value ARTIFACT_SAS_TTL_SECONDS)"
MODEL_SAS_TTL_SECONDS="$(env_value MODEL_SAS_TTL_SECONDS)"
MODEL_CONTAINERS="$(env_value MODEL_CONTAINERS)"

AZURE_TENANT_ID="$(env_value AZURE_TENANT_ID)"
AZURE_CLIENT_ID="$(env_value AZURE_CLIENT_ID)"
AZURE_CLIENT_SECRET="$(env_value AZURE_CLIENT_SECRET)"
FABRIC_WORKSPACE="$(env_value FABRIC_WORKSPACE)"
FABRIC_LAKEHOUSE="$(env_value FABRIC_LAKEHOUSE)"
FABRIC_ROOT_PATH="$(env_value FABRIC_ROOT_PATH)"
ONELAKE_ACCOUNT_URL="$(env_value ONELAKE_ACCOUNT_URL)"

FABRIC_ROOT_PATH="${FABRIC_ROOT_PATH:-Files}"
ONELAKE_ACCOUNT_URL="${ONELAKE_ACCOUNT_URL:-https://onelake.dfs.fabric.microsoft.com}"

# The service principal is optional: with none configured, the lakehouse
# writer falls back to the container app's managed identity.
case "$AZURE_CLIENT_SECRET" in
  ""|*REPLACE_WITH*)
    AZURE_TENANT_ID=""
    AZURE_CLIENT_ID=""
    AZURE_CLIENT_SECRET=""
    FABRIC_AUTH="managed identity (no service principal in .env)"
    ;;
  *)
    FABRIC_AUTH="service principal ${AZURE_CLIENT_ID}"
    ;;
esac

info "Source container:    ${SOURCE_CONTAINER}"
info "Annotated container: ${ANNOTATED_CONTAINER}"
info "Allowed origins:     ${ALLOWED_ORIGINS}"
info "Fabric workspace:    ${FABRIC_WORKSPACE:-<default>}"
info "Fabric auth:         ${FABRIC_AUTH}"

case "$DEVICE_TOKEN_SECRET" in
  ""|*REPLACE_WITH*)
    DEVICE_TOKEN_SECRET=""
    info "Device API:          off (no DEVICE_TOKEN_SECRET in .env)"
    ;;
  *)
    info "Device API:          on"
    ;;
esac


# ============================================================
# CONTAINER REGISTRY
# ============================================================

step "Resolving the container registry"

if [ -z "${ACR_NAME:-}" ]; then

  mapfile -t registries < <(
    az acr list --resource-group "$RESOURCE_GROUP" --query "[].name" -o tsv | strip_cr
  )

  case "${#registries[@]}" in
    0) fail "No container registry found in '${RESOURCE_GROUP}'." ;;
    1) ACR_NAME="${registries[0]}" ;;
    *) fail "Several registries found (${registries[*]}). Re-run with ACR_NAME=<name>." ;;
  esac
fi

[ -n "$ACR_NAME" ] \
  || fail "Could not resolve a registry name. Re-run with ACR_NAME=<name>."

info "Registry name: ${ACR_NAME}"

ACR_LOGIN_SERVER="$(az acr show --name "$ACR_NAME" --query loginServer -o tsv | strip_cr)"
ACR_ID="$(az acr show --name "$ACR_NAME" --query id -o tsv | strip_cr)"

info "Registry: ${ACR_LOGIN_SERVER}"


# ============================================================
# BUILD
#
# Built in the registry, so no local Docker daemon is needed.
# ============================================================

step "Building the image"

if git -C "$SCRIPT_DIR" rev-parse --short HEAD >/dev/null 2>&1; then
  TAG="$(git -C "$SCRIPT_DIR" rev-parse --short HEAD)"

  if [ -n "$(git -C "$SCRIPT_DIR" status --porcelain -- "$SCRIPT_DIR")" ]; then
    # Uncommitted work would otherwise reuse the same tag on every build, and
    # Container Apps treats an unchanged image reference as nothing to do.
    TAG="${TAG}-dirty-$(date -u +%Y%m%d%H%M%S)"
  fi
else
  TAG="manual-$(date -u +%Y%m%d%H%M%S)"
fi

IMAGE="${ACR_LOGIN_SERVER}/${IMAGE_REPOSITORY}:${TAG}"

info "Image: ${IMAGE}"

# --no-logs: the CLI streams the remote build log through the console
# encoding, which on Windows is cp1252 and cannot encode pip's output. It
# raises UnicodeEncodeError and aborts the deploy even though the build
# itself succeeded. The command still waits for the build and still fails
# loudly if the build does.
if ! az acr build \
  --registry "$ACR_NAME" \
  --image "${IMAGE_REPOSITORY}:${TAG}" \
  --image "${IMAGE_REPOSITORY}:latest" \
  --file "${SCRIPT_DIR}/Dockerfile" \
  --no-logs \
  --output none \
  "$SCRIPT_DIR"; then

  printf '\n'
  info "Build failed. Show the log with:"
  info "  az acr task logs --registry ${ACR_NAME}"
  fail "Image build failed."
fi

info "Built."


# ============================================================
# CONTAINER APPS ENVIRONMENT
# ============================================================

step "Container Apps environment: ${ENVIRONMENT_NAME}"

if az containerapp env show \
     --name "$ENVIRONMENT_NAME" \
     --resource-group "$RESOURCE_GROUP" >/dev/null 2>&1; then

  info "Already exists, reusing it."

else
  LOCATION="$(az group show --name "$RESOURCE_GROUP" --query location -o tsv | strip_cr)"

  info "Creating in ${LOCATION}..."

  az containerapp env create \
    --name "$ENVIRONMENT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --only-show-errors
fi


# ============================================================
# REGISTRY AUTHENTICATION
# ============================================================

configure_registry_admin() {

  step "Registry authentication: admin credentials"

  az acr update --name "$ACR_NAME" --admin-enabled true --only-show-errors >/dev/null

  ACR_USERNAME="$(az acr credential show --name "$ACR_NAME" --query username -o tsv | strip_cr)"
  ACR_PASSWORD="$(az acr credential show --name "$ACR_NAME" --query 'passwords[0].value' -o tsv | strip_cr)"

  az containerapp registry set \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --server "$ACR_LOGIN_SERVER" \
    --username "$ACR_USERNAME" \
    --password "$ACR_PASSWORD" \
    --only-show-errors >/dev/null
}


configure_registry_identity() {

  step "Registry authentication: managed identity"

  az containerapp identity assign \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --system-assigned \
    --only-show-errors >/dev/null

  PRINCIPAL_ID="$(
    az containerapp identity show \
      --name "$APP_NAME" \
      --resource-group "$RESOURCE_GROUP" \
      --query principalId -o tsv | strip_cr
  )"

  if ! az role assignment create \
         --assignee-object-id "$PRINCIPAL_ID" \
         --assignee-principal-type ServicePrincipal \
         --role AcrPull \
         --scope "$ACR_ID" \
         --only-show-errors >/dev/null 2>&1; then

    info "AcrPull role assignment failed (it may already exist, or you may"
    info "lack permission). Falling back to admin credentials."

    configure_registry_admin
    return
  fi

  # Role assignments take a moment to reach the registry.
  info "Waiting for the role assignment to propagate..."
  sleep 30

  az containerapp registry set \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --server "$ACR_LOGIN_SERVER" \
    --identity system \
    --only-show-errors >/dev/null
}


# ============================================================
# CONTAINER APP
# ============================================================

step "Container app: ${APP_NAME}"

APP_EXISTS=0
az containerapp show \
  --name "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" >/dev/null 2>&1 && APP_EXISTS=1

if [ "$APP_EXISTS" -eq 0 ]; then

  # Created on a public placeholder image first: the app must exist before it
  # can be given an identity and pull rights on the registry.
  info "Creating..."

  az containerapp create \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --environment "$ENVIRONMENT_NAME" \
    --image "mcr.microsoft.com/k8se/quickstart:latest" \
    --target-port "$TARGET_PORT" \
    --ingress external \
    --min-replicas 1 \
    --max-replicas 1 \
    --cpu 0.5 \
    --memory 1.0Gi \
    --only-show-errors >/dev/null

  if [ "$USE_ACR_ADMIN" = "1" ]; then
    configure_registry_admin
  else
    configure_registry_identity
  fi

else
  info "Already exists, updating it."

  if [ "$USE_ACR_ADMIN" = "1" ]; then
    configure_registry_admin
  fi
fi


step "Applying secrets and configuration"

SECRET_ARGS=(
  "${SECRET_STORAGE}=${STORAGE_CONNECTION_STRING}"
  "${SECRET_API_KEY}=${SYSTEM_API_KEY}"
)

FABRIC_ENV_ARGS=(
  "FABRIC_ROOT_PATH=${FABRIC_ROOT_PATH}"
  "ONELAKE_ACCOUNT_URL=${ONELAKE_ACCOUNT_URL}"
)

[ -n "$FABRIC_WORKSPACE" ] && FABRIC_ENV_ARGS+=( "FABRIC_WORKSPACE=${FABRIC_WORKSPACE}" )
[ -n "$FABRIC_LAKEHOUSE" ] && FABRIC_ENV_ARGS+=( "FABRIC_LAKEHOUSE=${FABRIC_LAKEHOUSE}" )

if [ -n "$DEVICE_TOKEN_SECRET" ]; then
  SECRET_ARGS+=( "${SECRET_DEVICE_TOKEN}=${DEVICE_TOKEN_SECRET}" )
  SECRET_ARGS+=( "${SECRET_FLEET}=${FLEET_SECRET}" )
  FABRIC_ENV_ARGS+=(
    "DEVICE_TOKEN_SECRET=secretref:${SECRET_DEVICE_TOKEN}"
    "FLEET_SECRET=secretref:${SECRET_FLEET}"
    "DEVICE_TOKEN_TTL_SECONDS=${DEVICE_TOKEN_TTL_SECONDS:-3600}"
    "ARTIFACT_SAS_TTL_SECONDS=${ARTIFACT_SAS_TTL_SECONDS:-900}"
    "MODEL_SAS_TTL_SECONDS=${MODEL_SAS_TTL_SECONDS:-3600}"
    "MODEL_CONTAINERS=${MODEL_CONTAINERS:-llm,yolo}"
  )
fi

if [ -n "$AZURE_CLIENT_SECRET" ]; then
  SECRET_ARGS+=( "${SECRET_CLIENT_SECRET}=${AZURE_CLIENT_SECRET}" )
  FABRIC_ENV_ARGS+=(
    "AZURE_TENANT_ID=${AZURE_TENANT_ID}"
    "AZURE_CLIENT_ID=${AZURE_CLIENT_ID}"
    "AZURE_CLIENT_SECRET=secretref:${SECRET_CLIENT_SECRET}"
  )
fi

az containerapp secret set \
  --name "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --secrets "${SECRET_ARGS[@]}" \
  --only-show-errors >/dev/null

az containerapp update \
  --name "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --image "$IMAGE" \
  --set-env-vars \
    "AZURE_STORAGE_CONNECTION_STRING=secretref:${SECRET_STORAGE}" \
    "SYSTEM_API_KEY=secretref:${SECRET_API_KEY}" \
    "SOURCE_CONTAINER=${SOURCE_CONTAINER}" \
    "ANNOTATED_CONTAINER=${ANNOTATED_CONTAINER}" \
    "ALLOWED_ORIGINS=${ALLOWED_ORIGINS}" \
    "${FABRIC_ENV_ARGS[@]}" \
  --only-show-errors >/dev/null


# ============================================================
# RETIRED SETTINGS
#
# `--set-env-vars` only adds and updates, so a variable that is
# no longer set in .env lingers on the app forever. Names that
# have been retired are removed explicitly.
# ============================================================

RETIRED_ENV_VARS="DEVICE_KEYS MODELS_CONTAINER"
RETIRED_SECRETS="device-keys"

CURRENT_ENV="$(
  az containerapp show \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --query "properties.template.containers[0].env[].name" -o tsv | strip_cr
)"

STALE=""

for name in $RETIRED_ENV_VARS; do
  if printf '%s
' "$CURRENT_ENV" | grep -qx "$name"; then
    STALE="${STALE} ${name}"
  fi
done

if [ -n "$STALE" ]; then

  step "Removing retired settings"

  info "Environment variables:${STALE}"

  # shellcheck disable=SC2086
  az containerapp update \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --remove-env-vars $STALE \
    --only-show-errors >/dev/null

  # A secret cannot be removed while an environment variable still
  # references it, so this follows the removal above.
  for secret in $RETIRED_SECRETS; do
    az containerapp secret remove \
      --name "$APP_NAME" \
      --resource-group "$RESOURCE_GROUP" \
      --secret-names "$secret" \
      --only-show-errors >/dev/null 2>&1 || true
  done

  info "Removed."
fi


# ============================================================
# VERIFY
# ============================================================

step "Verifying the deployment"

RUNNING_IMAGE="$(
  az containerapp show \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --query "properties.template.containers[0].image" -o tsv | strip_cr
)"

if [ "$RUNNING_IMAGE" != "$IMAGE" ]; then
  info "Warning: the app reports image ${RUNNING_IMAGE}, not ${IMAGE}."
fi

FQDN="$(
  az containerapp show \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --query properties.configuration.ingress.fqdn -o tsv | strip_cr
)"

BASE_URL="https://${FQDN}"

info "Waiting for the new revision to serve traffic..."

HEALTHY=0

for _ in $(seq 1 30); do
  if curl -fsS --max-time 5 "${BASE_URL}/health" >/dev/null 2>&1; then
    HEALTHY=1
    break
  fi
  sleep 5
done

printf '\n'

if [ "$HEALTHY" -eq 1 ]; then
  printf '\033[1;32mDeployed.\033[0m\n\n'
else
  printf '\033[1;33mDeployed, but /health did not answer within 150s.\033[0m\n'
  printf 'Check the logs:\n'
  printf '  az containerapp logs show -n %s -g %s --follow\n\n' "$APP_NAME" "$RESOURCE_GROUP"
fi

printf '  URL:    %s\n' "$BASE_URL"
printf '  Docs:   %s/docs\n' "$BASE_URL"
printf '  Image:  %s\n\n' "$IMAGE"

printf 'Point the labelling UI at it:\n'
printf '  SYSTEM_API_URL=%s\n\n' "$BASE_URL"

printf 'Once the UI is deployed, allow its origin:\n'
printf '  ALLOWED_ORIGINS=https://<labelling-ui-host> ./deploy.sh\n\n'
