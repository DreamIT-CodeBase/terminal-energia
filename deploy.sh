#!/usr/bin/env bash
#
# Deploy the labelling UI to Azure Container Apps.
#
#   ./deploy.sh
#
# Not a Static Web App: this is a Next.js server, not a static site. Microsoft
# sign-in, the session cookie, the `proxy.ts` gate and the route handlers that
# hold SYSTEM_API_KEY all run server-side. Exporting it statically would put
# that key in the browser, which is the thing the system API exists to avoid.
#
# Reuses the Container Apps environment the system API already runs in, so no
# second environment is created. The app itself is separate, and scales to zero
# when idle: a labelling tool sits unused most of the month, so the monthly free
# grant covers it outright. The trade is a cold start of a few seconds on the
# first request after idling, which suits this and would not suit anything
# being monitored.
#
# Overrides:
#   RESOURCE_GROUP=...    target resource group        (default terminal-scout)
#   ENVIRONMENT_NAME=...  Container Apps environment   (default systemapi)
#   ACR_NAME=...          registry, if the group has more than one
#   USE_ACR_ADMIN=1       registry admin credentials instead of an identity

set -euo pipefail

# ============================================================
# SETTINGS
# ============================================================

RESOURCE_GROUP="${RESOURCE_GROUP:-terminal-scout}"
APP_NAME="labelling-ui"

# Shared with the system API: an environment is billed infrastructure, and
# there is no reason for two.
ENVIRONMENT_NAME="${ENVIRONMENT_NAME:-systemapi}"

IMAGE_REPOSITORY="labellingui"
TARGET_PORT=3000

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env.local"

# Container Apps secret names: lowercase letters, digits and dashes only.
SECRET_AUTH="auth-secret"
SECRET_ENTRA_SECRET="entra-client-secret"
SECRET_SYSTEM_API_KEY="system-api-key"

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
# then corrupts every value it lands in.
# ============================================================

strip_cr() { tr -d '\r'; }


# ============================================================
# READ ONE VALUE FROM .env.local
#
# Parsed rather than sourced: values contain `=` and other
# characters the shell would act on.
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
  || fail "${ENV_FILE} not found. Copy .env.example to .env.local and fill it in."

az extension add --name containerapp --upgrade --only-show-errors >/dev/null 2>&1 || true


# ============================================================
# CONFIGURATION
# ============================================================

step "Reading configuration from .env.local"

AUTH_SECRET="$(env_value AUTH_SECRET)"
ENTRA_ID="$(env_value AUTH_MICROSOFT_ENTRA_ID_ID)"
ENTRA_SECRET="$(env_value AUTH_MICROSOFT_ENTRA_ID_SECRET)"
ENTRA_ISSUER="$(env_value AUTH_MICROSOFT_ENTRA_ID_ISSUER)"
ALLOWED_EMAIL="$(env_value ALLOWED_EMAIL)"
SYSTEM_API_URL="$(env_value SYSTEM_API_URL)"
SYSTEM_API_KEY="$(env_value SYSTEM_API_KEY)"

for pair in \
  "AUTH_SECRET:$AUTH_SECRET" \
  "AUTH_MICROSOFT_ENTRA_ID_ID:$ENTRA_ID" \
  "AUTH_MICROSOFT_ENTRA_ID_SECRET:$ENTRA_SECRET" \
  "AUTH_MICROSOFT_ENTRA_ID_ISSUER:$ENTRA_ISSUER" \
  "SYSTEM_API_URL:$SYSTEM_API_URL" \
  "SYSTEM_API_KEY:$SYSTEM_API_KEY"
do
  name="${pair%%:*}"
  value="${pair#*:}"

  case "$value" in
    "")
      fail "${name} is missing from .env.local"
      ;;
    *REPLACE_WITH*)
      fail "${name} in .env.local still holds a placeholder."
      ;;
  esac
done

ALLOWED_EMAIL="${ALLOWED_EMAIL:-chirag@dreamitcs.com}"

info "Allowed account: ${ALLOWED_EMAIL}"
info "System API:      ${SYSTEM_API_URL}"


# ============================================================
# CONTAINER REGISTRY
# ============================================================

step "Resolving the container registry"

if [ -z "${ACR_NAME:-}" ]; then

  mapfile -t registries < <(
    az acr list --resource-group "$RESOURCE_GROUP" --query "[].name" -o tsv \
      | strip_cr | sed '/^[[:space:]]*$/d'
  )

  case "${#registries[@]}" in
    0) fail "No container registry found in '${RESOURCE_GROUP}'." ;;
    1) ACR_NAME="${registries[0]}" ;;
    *) fail "Several registries found (${registries[*]}). Re-run with ACR_NAME=<name>." ;;
  esac
fi

[ -n "$ACR_NAME" ] \
  || fail "Could not resolve a registry name. Re-run with ACR_NAME=<name>."

ACR_LOGIN_SERVER="$(az acr show --name "$ACR_NAME" --query loginServer -o tsv | strip_cr)"
ACR_ID="$(az acr show --name "$ACR_NAME" --query id -o tsv | strip_cr)"

info "Registry: ${ACR_LOGIN_SERVER}"


# ============================================================
# BUILD
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
# encoding, which on Windows cannot encode npm's output and aborts the deploy
# even though the build succeeded.
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
  # can be given an identity and pull rights on the registry, and before its
  # hostname is known.
  info "Creating..."

  az containerapp create \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --environment "$ENVIRONMENT_NAME" \
    --image "mcr.microsoft.com/k8se/quickstart:latest" \
    --target-port "$TARGET_PORT" \
    --ingress external \
    --min-replicas 0 \
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


# ============================================================
# CONFIGURATION
#
# AUTH_URL has to be the app's own public hostname, which is
# only known once the app exists, so the env vars are applied
# after creation rather than as part of it.
# ============================================================

FQDN="$(
  az containerapp show \
    --name "$APP_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --query properties.configuration.ingress.fqdn -o tsv | strip_cr
)"

BASE_URL="https://${FQDN}"

REDIRECT_URI="${BASE_URL}/api/auth/callback/microsoft-entra-id"

step "Applying secrets and configuration"

info "Public URL: ${BASE_URL}"

az containerapp secret set \
  --name "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --secrets \
    "${SECRET_AUTH}=${AUTH_SECRET}" \
    "${SECRET_ENTRA_SECRET}=${ENTRA_SECRET}" \
    "${SECRET_SYSTEM_API_KEY}=${SYSTEM_API_KEY}" \
  --only-show-errors >/dev/null

az containerapp update \
  --name "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --image "$IMAGE" \
  --set-env-vars \
    "AUTH_SECRET=secretref:${SECRET_AUTH}" \
    "AUTH_MICROSOFT_ENTRA_ID_SECRET=secretref:${SECRET_ENTRA_SECRET}" \
    "SYSTEM_API_KEY=secretref:${SECRET_SYSTEM_API_KEY}" \
    "AUTH_MICROSOFT_ENTRA_ID_ID=${ENTRA_ID}" \
    "AUTH_MICROSOFT_ENTRA_ID_ISSUER=${ENTRA_ISSUER}" \
    "ALLOWED_EMAIL=${ALLOWED_EMAIL}" \
    "SYSTEM_API_URL=${SYSTEM_API_URL}" \
    "AUTH_URL=${BASE_URL}" \
    "AUTH_TRUST_HOST=true" \
    "NODE_ENV=production" \
  --min-replicas 0 \
  --max-replicas 1 \
  --only-show-errors >/dev/null


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

# The first request also wakes the app from zero, so this doubles as a
# cold start check.
info "Waking the app and waiting for the sign-in page..."

HEALTHY=0

for _ in $(seq 1 36); do
  if curl -fsS --max-time 8 "$BASE_URL/" >/dev/null 2>&1; then
    HEALTHY=1
    break
  fi
  sleep 5
done

printf '\n'

if [ "$HEALTHY" -eq 1 ]; then
  printf '\033[1;32mDeployed.\033[0m\n\n'
else
  printf '\033[1;33mDeployed, but the sign-in page did not answer within 180s.\033[0m\n'
  printf 'Check the logs:\n'
  printf '  az containerapp logs show -n %s -g %s --follow\n\n' "$APP_NAME" "$RESOURCE_GROUP"
fi

printf '  URL:   %s\n' "$BASE_URL"
printf '  Image: %s\n\n' "$IMAGE"

printf 'Sign-in will fail until this redirect URI is registered on the Entra app:\n\n'
printf '  %s\n\n' "$REDIRECT_URI"

printf 'Add it without disturbing the existing URIs:\n\n'
printf '  EXISTING=$(az ad app show --id %s --query "web.redirectUris" -o tsv | tr "\\r" " ")\n' "$ENTRA_ID"
printf '  az ad app update --id %s --web-redirect-uris $EXISTING %s\n\n' "$ENTRA_ID" "$REDIRECT_URI"
