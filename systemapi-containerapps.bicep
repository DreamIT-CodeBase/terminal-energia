param containerapps_systemapi_name string = 'systemapi'
param managedEnvironments_systemapi_name string = 'systemapi'
param containerImage string
param containerRegistryServer string
param containerRegistryUsername string
param registryPasswordSecretName string = 'acr-auth'
@secure()
param registryPassword string
@secure()
param secretValues object
param tenantId string
param azureClientId string
param fabricWorkspaceId string
param fabricLakehouseId string

var runtimeSecrets = [for secret in items(secretValues): {
  name: secret.key
  value: secret.value
}]

resource containerapps_systemapi_name_resource 'Microsoft.App/containerapps@2026-01-01' = {
  identity: {
    type: 'SystemAssigned'
  }
  location: 'UK South'
  name: containerapps_systemapi_name
  properties: {
    configuration: {
      activeRevisionsMode: 'Single'
      identitySettings: []
      ingress: {
        allowInsecure: false
        exposedPort: 0
        external: true
        targetPort: 8100
        traffic: [
          {
            latestRevision: true
            weight: 100
          }
        ]
        transport: 'Auto'
      }
      maxInactiveRevisions: 100
      registries: [
        {
          passwordSecretRef: registryPasswordSecretName
          server: containerRegistryServer
          username: containerRegistryUsername
        }
      ]
      secrets: concat(
        runtimeSecrets,
        [
          {
            name: registryPasswordSecretName
            value: registryPassword
          }
        ]
      )
    }
    environmentId: resourceId('Microsoft.App/managedEnvironments', managedEnvironments_systemapi_name)
    managedEnvironmentId: resourceId('Microsoft.App/managedEnvironments', managedEnvironments_systemapi_name)
    template: {
      containers: [
        {
          env: [
            {
              name: 'AZURE_STORAGE_CONNECTION_STRING'
              secretRef: 'storage-connection-string'
            }
            {
              name: 'SYSTEM_API_KEY'
              secretRef: 'system-api-key'
            }
            {
              name: 'SOURCE_CONTAINER'
              value: 'debugdirectory'
            }
            {
              name: 'ANNOTATED_CONTAINER'
              value: 'debugdirectoryannotated'
            }
            {
              name: 'ALLOWED_ORIGINS'
              value: 'http://localhost:3000'
            }
            {
              name: 'FABRIC_ROOT_PATH'
              value: 'Files'
            }
            {
              name: 'ONELAKE_ACCOUNT_URL'
              value: 'https://onelake.dfs.fabric.microsoft.com'
            }
            {
              name: 'FABRIC_WORKSPACE'
              value: fabricWorkspaceId
            }
            {
              name: 'FABRIC_LAKEHOUSE'
              value: fabricLakehouseId
            }
            {
              name: 'AZURE_TENANT_ID'
              value: tenantId
            }
            {
              name: 'AZURE_CLIENT_ID'
              value: azureClientId
            }
            {
              name: 'AZURE_CLIENT_SECRET'
              secretRef: 'azure-client-secret'
            }
            {
              name: 'DEVICE_TOKEN_SECRET'
              secretRef: 'device-token-secret'
            }
            {
              name: 'DEVICE_TOKEN_TTL_SECONDS'
              value: '3600'
            }
            {
              name: 'ARTIFACT_SAS_TTL_SECONDS'
              value: '900'
            }
            {
              name: 'MODEL_SAS_TTL_SECONDS'
              value: '3600'
            }
            {
              name: 'FLEET_SECRET'
              secretRef: 'fleet-secret'
            }
            {
              name: 'MODEL_CONTAINERS'
              value: 'llm,yolo'
            }
            {
              name: 'DEVICE_CONFIG_CONTAINER'
              value: 'configurations'
            }
            {
              name: 'DEVICE_CONFIG_BLOB'
              value: 'device-config/device-config.default.json'
            }
            {
              name: 'STATIONS_CONTAINER'
              value: 'configurations'
            }
            {
              name: 'STATIONS_BLOB'
              value: 'stations-data/stations.json'
            }
            {
              name: 'STATIONS_CACHE_TTL_SECONDS'
              value: '60'
            }
            {
              name: 'STATIONS_WRITE_ATTEMPTS'
              value: '5'
            }
            {
              name: 'STATIONS_ENRICHED_BLOB'
              value: 'stations-data/stations-enriched.json'
            }
          ]
          image: containerImage
          name: containerapps_systemapi_name
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
        }
      ]
      scale: {
        cooldownPeriod: 300
        maxReplicas: 1
        minReplicas: 0
        pollingInterval: 30
        rules: [
          {
            http: {
              metadata: {
                concurrentRequests: '10'
              }
            }
            name: 'http-scaler'
          }
        ]
      }
    }
    workloadProfileName: 'Consumption'
  }
}
