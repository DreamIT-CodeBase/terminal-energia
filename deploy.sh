#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Usage: ./deploy.sh <tenant-id> [subscription-id]
# Optional: PARAMETERS_DIR, AZURE_SUBSCRIPTION_ID, RESOURCE_GROUP_LOCATION,
# STORAGE_ACCOUNT_NAME, REGISTRY_NAME, KEY_VAULT_NAME, and MAPS_ACCOUNT_NAME.
#
# Required deployment parameter files (deploymentParameters JSON format):
#   mltraining3721646130-vaults.parameters.json
#     accessPolicyObjectId
#   mltraining5523469937-components.parameters.json
#     workspaces_mltraining9690331438_externalid
#   systemapi-containerapps.parameters.json
#     containerImage, azureClientId, fabricWorkspaceId, fabricLakehouseId,
#     secretValues (object; include fleet-secret, storage-connection-string,
#     system-api-key, azure-client-secret, device-token-secret)
#   labelling-ui-containerapps.parameters.json
#     containerImage, entraClientId, secretValues (object; include
#     entra-client-secret, fleet-secret, system-api-key, auth-secret)
#   failure-anomalies-mltraining5523469937-smartdetectoralertrules.parameters.json
#     actiongroups_application_insights_smart_detection_externalid
#
# Store parameter files securely (for example, chmod 600) and do not commit
# them. Container App secrets are supplied as secureObject parameters; leave
# the "acr-auth" entry out because the script adds the destination ACR password.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PARAMETERS_DIR="${PARAMETERS_DIR:-$SCRIPT_DIR/parameters}"
TENANT_ID="${1:-}"
SUBSCRIPTION_ID="${2:-${AZURE_SUBSCRIPTION_ID:-}}"
RESOURCE_GROUP_NAME="terminal-scout"
RESOURCE_GROUP_LOCATION="${RESOURCE_GROUP_LOCATION:-uksouth}"

LOG_WORKSPACE_NAME="${LOG_WORKSPACE_NAME:-workspace-terminalscoutENto}"
CONTAINER_ENVIRONMENT_NAME="${CONTAINER_ENVIRONMENT_NAME:-systemapi}"
SYSTEM_API_APP_NAME="${SYSTEM_API_APP_NAME:-systemapi}"
LABELLING_UI_APP_NAME="${LABELLING_UI_APP_NAME:-labelling-ui}"
APPLICATION_INSIGHTS_NAME="${APPLICATION_INSIGHTS_NAME:-mltraining5523469937}"
ML_WORKSPACE_NAME="${ML_WORKSPACE_NAME:-mltraining}"
MAPS_ACCOUNT_NAME="${MAPS_ACCOUNT_NAME:-maps-api}"

if [[ -z "$TENANT_ID" || ! "$TENANT_ID" =~ ^[[:xdigit:]-]{36}$ ]]; then
  echo "Usage: $0 <tenant-id> [subscription-id]" >&2
  exit 2
fi
TENANT_ID="${TENANT_ID,,}"

if ! command -v az >/dev/null 2>&1; then
  echo "Azure CLI (az) is required." >&2
  exit 1
fi

required_parameter_files=(
  "mltraining3721646130-vaults.parameters.json"
  "mltraining5523469937-components.parameters.json"
  "systemapi-containerapps.parameters.json"
  "labelling-ui-containerapps.parameters.json"
  "failure-anomalies-mltraining5523469937-smartdetectoralertrules.parameters.json"
)
missing_files=()
for file in "${required_parameter_files[@]}"; do
  if [[ ! -r "$PARAMETERS_DIR/$file" ]]; then
    missing_files+=("$PARAMETERS_DIR/$file")
  fi
done
if ((${#missing_files[@]})); then
  printf 'Missing required parameter files:\n' >&2
  printf '  %s\n' "${missing_files[@]}" >&2
  echo "See the parameter requirements at the top of deploy.sh." >&2
  exit 1
fi

current_tenant="$(az account show --query tenantId --output tsv 2>/dev/null || true)"
if [[ "${current_tenant,,}" != "${TENANT_ID,,}" ]]; then
  echo "Signing in to tenant $TENANT_ID ..."
  az login --tenant "$TENANT_ID" --output none
fi

if [[ -z "$SUBSCRIPTION_ID" ]]; then
  tenant_subscriptions="$(az account list --all \
    --query "[?tenantId=='$TENANT_ID' && state=='Enabled'].id" \
    --output tsv)"
  subscription_count="$(printf '%s\n' "$tenant_subscriptions" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
  if [[ "$subscription_count" != "1" ]]; then
    echo "Set AZURE_SUBSCRIPTION_ID or pass a subscription ID as the second argument." >&2
    echo "Tenant $TENANT_ID has $subscription_count enabled subscriptions visible to this login." >&2
    exit 1
  fi
  SUBSCRIPTION_ID="$tenant_subscriptions"
fi

az account set --subscription "$SUBSCRIPTION_ID"
active_tenant="$(az account show --query tenantId --output tsv)"
if [[ "${active_tenant,,}" != "${TENANT_ID,,}" ]]; then
  echo "Subscription $SUBSCRIPTION_ID belongs to tenant $active_tenant, not $TENANT_ID." >&2
  exit 1
fi

subscription_suffix="${SUBSCRIPTION_ID//-/}"
subscription_suffix="${subscription_suffix:0:10}"
STORAGE_ACCOUNT_NAME="${STORAGE_ACCOUNT_NAME:-ts${subscription_suffix}}"
REGISTRY_NAME="${REGISTRY_NAME:-tsacr${subscription_suffix}}"
KEY_VAULT_NAME="${KEY_VAULT_NAME:-ts-kv-${subscription_suffix:0:8}}"

for provider in \
  Microsoft.Storage \
  Microsoft.ContainerRegistry \
  Microsoft.OperationalInsights \
  Microsoft.App \
  Microsoft.KeyVault \
  Microsoft.Insights \
  Microsoft.MachineLearningServices \
  Microsoft.AlertsManagement \
  Microsoft.Compute \
  Microsoft.Maps; do
  az provider register --namespace "$provider" --wait --only-show-errors
done

az group create \
  --name "$RESOURCE_GROUP_NAME" \
  --location "$RESOURCE_GROUP_LOCATION" \
  --only-show-errors \
  --output none

TEMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT

deploy_template() {
  local template="$1"
  local parameter_file="${2:-}"
  local deployment_name="$3"
  shift 3

  local args=(
    --resource-group "$RESOURCE_GROUP_NAME"
    --name "$deployment_name"
    --template-file "$SCRIPT_DIR/$template"
    --only-show-errors
    --output none
  )
  local parameters=()
  if [[ -n "$parameter_file" ]]; then
    parameters+=("@$parameter_file")
  fi
  parameters+=("$@")
  if ((${#parameters[@]})); then
    args+=(--parameters "${parameters[@]}")
  fi

  echo "Deploying $template ..."
  az deployment group create "${args[@]}"
}

deploy_template \
  "workspace-terminalscoutento-workspaces.bicep" "" \
  "terminal-scout-log-workspace" \
  "workspaces_workspace_terminalscoutENto_name=$LOG_WORKSPACE_NAME"
deploy_template \
  "memoryterminalscout-storageaccounts.bicep" "" \
  "terminal-scout-storage" \
  "storageAccounts_memoryterminalscout_name=$STORAGE_ACCOUNT_NAME"
deploy_template \
  "caae2e50523bacr-registries.bicep" "" \
  "terminal-scout-container-registry" \
  "registries_caae2e50523bacr_name=$REGISTRY_NAME"
deploy_template \
  "mltraining3721646130-vaults.bicep" \
  "$PARAMETERS_DIR/mltraining3721646130-vaults.parameters.json" \
  "terminal-scout-key-vault" \
  "vaults_mltraining3721646130_name=$KEY_VAULT_NAME" \
  "tenantId=$TENANT_ID"
deploy_template \
  "mltraining5523469937-components.bicep" \
  "$PARAMETERS_DIR/mltraining5523469937-components.parameters.json" \
  "terminal-scout-app-insights" \
  "components_mltraining5523469937_name=$APPLICATION_INSIGHTS_NAME"

log_analytics_customer_id="$(az monitor log-analytics workspace show \
  --resource-group "$RESOURCE_GROUP_NAME" \
  --workspace-name "$LOG_WORKSPACE_NAME" \
  --query customerId --output tsv)"
log_analytics_shared_key="$(az monitor log-analytics workspace get-shared-keys \
  --resource-group "$RESOURCE_GROUP_NAME" \
  --workspace-name "$LOG_WORKSPACE_NAME" \
  --query primarySharedKey --output tsv)"
if [[ -z "$log_analytics_customer_id" || -z "$log_analytics_shared_key" ]]; then
  echo "Could not retrieve the Log Analytics workspace ID and shared key." >&2
  exit 1
fi
printf '{"$schema":"https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#","contentVersion":"1.0.0.0","parameters":{"logAnalyticsCustomerId":{"value":"%s"},"logAnalyticsSharedKey":{"value":"%s"}}}\n' \
  "$log_analytics_customer_id" "$log_analytics_shared_key" \
  > "$TEMP_DIR/log-analytics.parameters.json"
deploy_template \
  "systemapi-managedenvironments.bicep" \
  "$TEMP_DIR/log-analytics.parameters.json" \
  "terminal-scout-container-environment" \
  "managedEnvironments_systemapi_name=$CONTAINER_ENVIRONMENT_NAME"

deploy_template \
  "mltraining-workspaces.bicep" "" \
  "terminal-scout-machine-learning" \
  "workspaces_mltraining_name=$ML_WORKSPACE_NAME" \
  "storageAccounts_memoryterminalscout_name=$STORAGE_ACCOUNT_NAME" \
  "registries_caae2e50523bacr_name=$REGISTRY_NAME" \
  "vaults_mltraining3721646130_name=$KEY_VAULT_NAME" \
  "components_mltraining5523469937_name=$APPLICATION_INSIGHTS_NAME"
deploy_template \
  "failure-anomalies-mltraining5523469937-smartdetectoralertrules.bicep" \
  "$PARAMETERS_DIR/failure-anomalies-mltraining5523469937-smartdetectoralertrules.parameters.json" \
  "terminal-scout-smart-alert" \
  "components_mltraining5523469937_name=$APPLICATION_INSIGHTS_NAME"
deploy_template \
  "terminalscoutmltrainingssh-sshpublickeys.bicep" "" \
  "terminal-scout-ssh-key-one"
deploy_template \
  "terminal-scout-training-sshpublickeys.bicep" "" \
  "terminal-scout-ssh-key-two"
deploy_template \
  "maps-api-accounts.bicep" "" \
  "terminal-scout-maps" \
  "accounts_maps_api_name=$MAPS_ACCOUNT_NAME"

registry_username="$(az acr credential show --name "$REGISTRY_NAME" \
  --query username --output tsv)"
registry_password="$(az acr credential show --name "$REGISTRY_NAME" \
  --query 'passwords[0].value' --output tsv)"
if [[ -z "$registry_username" || -z "$registry_password" ]]; then
  echo "Could not retrieve the Container Registry admin credentials." >&2
  exit 1
fi
printf '{"$schema":"https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#","contentVersion":"1.0.0.0","parameters":{"containerRegistryServer":{"value":"%s.azurecr.io"},"containerRegistryUsername":{"value":"%s"},"registryPassword":{"value":"%s"}}}\n' \
  "$REGISTRY_NAME" "$registry_username" "$registry_password" \
  > "$TEMP_DIR/container-registry.parameters.json"

deploy_template \
  "systemapi-containerapps.bicep" \
  "$TEMP_DIR/container-registry.parameters.json" \
  "terminal-scout-system-api" \
  "@$PARAMETERS_DIR/systemapi-containerapps.parameters.json" \
  "containerapps_systemapi_name=$SYSTEM_API_APP_NAME" \
  "managedEnvironments_systemapi_name=$CONTAINER_ENVIRONMENT_NAME" \
  "tenantId=$TENANT_ID"

system_api_fqdn="$(az containerapp show \
  --name "$SYSTEM_API_APP_NAME" \
  --resource-group "$RESOURCE_GROUP_NAME" \
  --query properties.configuration.ingress.fqdn --output tsv)"
environment_domain="$(az containerapp env show \
  --name "$CONTAINER_ENVIRONMENT_NAME" \
  --resource-group "$RESOURCE_GROUP_NAME" \
  --query properties.defaultDomain --output tsv)"
if [[ -z "$system_api_fqdn" || -z "$environment_domain" ]]; then
  echo "Could not resolve Container Apps ingress URLs." >&2
  exit 1
fi

deploy_template \
  "labelling-ui-containerapps.bicep" \
  "$TEMP_DIR/container-registry.parameters.json" \
  "terminal-scout-labelling-ui" \
  "@$PARAMETERS_DIR/labelling-ui-containerapps.parameters.json" \
  "containerapps_labelling_ui_name=$LABELLING_UI_APP_NAME" \
  "managedEnvironments_systemapi_name=$CONTAINER_ENVIRONMENT_NAME" \
  "entraIssuerUrl=https://login.microsoftonline.com/$TENANT_ID/v2.0" \
  "systemApiUrl=https://$system_api_fqdn" \
  "authUrl=https://$LABELLING_UI_APP_NAME.$environment_domain"

echo "Deployment completed in resource group '$RESOURCE_GROUP_NAME' (tenant $TENANT_ID)."
