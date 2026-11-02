param managedEnvironments_systemapi_name string = 'systemapi'
param logAnalyticsCustomerId string
@secure()
param logAnalyticsSharedKey string

resource managedEnvironments_systemapi_name_resource 'Microsoft.App/managedEnvironments@2026-01-01' = {
  location: 'UK South'
  name: managedEnvironments_systemapi_name
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logAnalyticsCustomerId
        sharedKey: logAnalyticsSharedKey
      }
    }
    customDomainConfiguration: {}
    daprConfiguration: {}
    kedaConfiguration: {}
    peerAuthentication: {
      mtls: {
        enabled: false
      }
    }
    peerTrafficConfiguration: {
      encryption: {
        enabled: false
      }
    }
    publicNetworkAccess: 'Enabled'
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    zoneRedundant: false
  }
}
