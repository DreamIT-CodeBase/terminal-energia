param components_mltraining5523469937_name string = 'mltraining5523469937'
param registries_caae2e50523bacr_name string = 'caae2e50523bacr'
param storageAccounts_memoryterminalscout_name string = 'memoryterminalscout'
param vaults_mltraining3721646130_name string = 'mltraining3721646130'
param workspaces_mltraining_name string = 'mltraining'

resource workspaces_mltraining_name_resource 'Microsoft.MachineLearningServices/workspaces@2026-05-15-preview' = {
  identity: {
    type: 'SystemAssigned'
  }
  kind: 'Default'
  location: 'canadacentral'
  name: workspaces_mltraining_name
  properties: {
    EnableSoftwareBillOfMaterials: false
    allowRoleAssignmentOnRG: true
    applicationInsights: resourceId('microsoft.insights/components', components_mltraining5523469937_name)
    containerRegistry: resourceId('Microsoft.ContainerRegistry/registries', registries_caae2e50523bacr_name)
    discoveryUrl: 'https://canadacentral.api.azureml.ms/discovery'
    enableDataIsolation: false
    enableServiceSideCMKEncryption: false
    friendlyName: workspaces_mltraining_name
    hbiWorkspace: false
    ipAllowlist: []
    keyVault: resourceId('Microsoft.KeyVault/vaults', vaults_mltraining3721646130_name)
    managedNetwork: {
      enableNetworkMonitor: false
      isolationMode: 'Disabled'
      managedNetworkKind: 'V1'
    }
    networkAcls: {
      defaultAction: 'Allow'
      ipRules: []
    }
    provisionNetworkNow: false
    publicNetworkAccess: 'Enabled'
    storageAccount: resourceId('Microsoft.Storage/storageAccounts', storageAccounts_memoryterminalscout_name)
    systemDatastoresAuthMode: 'accesskey'
    v1LegacyMode: false
  }
  sku: {
    name: 'Basic'
    tier: 'Basic'
  }
}
