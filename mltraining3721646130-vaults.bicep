param vaults_mltraining3721646130_name string = 'mltraining3721646130'
param tenantId string
param accessPolicyObjectId string

resource vaults_mltraining3721646130_name_resource 'Microsoft.KeyVault/vaults@2026-03-01-preview' = {
  location: 'uksouth'
  name: vaults_mltraining3721646130_name
  properties: {
    accessPolicies: [
      {
        objectId: accessPolicyObjectId
        permissions: {
          certificates: [
            'all'
          ]
          keys: [
            'all'
          ]
          secrets: [
            'all'
          ]
          storage: []
        }
        tenantId: tenantId
      }
    ]
    enableRbacAuthorization: false
    enableSoftDelete: true
    enabledForDeployment: false
    publicNetworkAccess: 'Enabled'
    sku: {
      family: 'A'
      name: 'standard'
    }
    tenantId: tenantId
  }
}
