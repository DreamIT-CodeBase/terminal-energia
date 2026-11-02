param containerapps_labelling_ui_name string = 'labelling-ui'
param managedEnvironments_systemapi_name string = 'systemapi'
param containerImage string
param containerRegistryServer string
param containerRegistryUsername string
param registryPasswordSecretName string = 'acr-auth'
@secure()
param registryPassword string
@secure()
param secretValues object
param entraClientId string
param entraIssuerUrl string
param systemApiUrl string
param authUrl string

var runtimeSecrets = [for secret in items(secretValues): {
  name: secret.key
  value: secret.value
}]

resource containerapps_labelling_ui_name_resource 'Microsoft.App/containerapps@2026-01-01' = {
  identity: {
    type: 'SystemAssigned'
  }
  location: 'UK South'
  name: containerapps_labelling_ui_name
  properties: {
    configuration: {
      activeRevisionsMode: 'Single'
      identitySettings: []
      ingress: {
        allowInsecure: false
        exposedPort: 0
        external: true
        targetPort: 3000
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
              name: 'AUTH_SECRET'
              secretRef: 'auth-secret'
            }
            {
              name: 'AUTH_MICROSOFT_ENTRA_ID_SECRET'
              secretRef: 'entra-client-secret'
            }
            {
              name: 'SYSTEM_API_KEY'
              secretRef: 'system-api-key'
            }
            {
              name: 'AUTH_MICROSOFT_ENTRA_ID_ID'
              value: entraClientId
            }
            {
              name: 'AUTH_MICROSOFT_ENTRA_ID_ISSUER'
              value: entraIssuerUrl
            }
            {
              name: 'ALLOWED_EMAIL'
              value: 'chirag@dreamitcs.com'
            }
            {
              name: 'SYSTEM_API_URL'
              value: systemApiUrl
            }
            {
              name: 'AUTH_URL'
              value: authUrl
            }
            {
              name: 'AUTH_TRUST_HOST'
              value: 'true'
            }
            {
              name: 'NODE_ENV'
              value: 'production'
            }
            {
              name: 'AUTH_DISABLED'
              value: 'true'
            }
            {
              name: 'SHOW_ALL_VIDEOS'
              value: 'true'
            }
            {
              name: 'FLEET_SECRET'
              secretRef: 'fleet-secret'
            }
            {
              name: 'CONSOLE_DEVICE_ID'
              value: 'labelling-console'
            }
          ]
          image: containerImage
          name: containerapps_labelling_ui_name
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
      }
    }
    workloadProfileName: 'Consumption'
  }
}
