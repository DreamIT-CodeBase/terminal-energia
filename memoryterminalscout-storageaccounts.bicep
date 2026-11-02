param storageAccounts_memoryterminalscout_name string = 'memoryterminalscout'

resource storageAccounts_memoryterminalscout_name_resource 'Microsoft.Storage/storageAccounts@2026-06-01' = {
  kind: 'StorageV2'
  location: 'uksouth'
  name: storageAccounts_memoryterminalscout_name
  properties: {
    accessTier: 'Hot'
    allowBlobPublicAccess: false
    allowCrossTenantDelegationSas: false
    allowCrossTenantReplication: false
    allowSharedKeyAccess: true
    defaultToOAuthAuthentication: false
    dnsEndpointType: 'Standard'
    dualStackEndpointPreference: {
      publishIpv6Endpoint: false
    }
    encryption: {
      keySource: 'Microsoft.Storage'
      requireInfrastructureEncryption: false
      services: {
        blob: {
          enabled: true
          keyType: 'Account'
        }
        file: {
          enabled: true
          keyType: 'Account'
        }
      }
    }
    minimumTlsVersion: 'TLS1_2'
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: 'Allow'
      ipRules: []
      ipv6Rules: []
      virtualNetworkRules: []
    }
    publicNetworkAccess: 'Enabled'
    supportsHttpsTrafficOnly: true
  }
  sku: {
    name: 'Standard_RAGRS'
    tier: 'Standard'
  }
}
