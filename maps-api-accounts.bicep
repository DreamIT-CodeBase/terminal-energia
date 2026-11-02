param accounts_maps_api_name string = 'maps-api'

resource accounts_maps_api_name_resource 'Microsoft.Maps/accounts@2025-10-01-preview' = {
  identity: {
    type: 'None'
  }
  kind: 'Gen2'
  location: 'westeurope'
  name: accounts_maps_api_name
  properties: {
    cors: {
      corsRules: [
        {
          allowedOrigins: []
        }
      ]
    }
    disableLocalAuth: false
    locations: []
    publicNetworkAccess: 'enabled'
  }
  sku: {
    name: 'G2'
    tier: 'Standard'
  }
}
