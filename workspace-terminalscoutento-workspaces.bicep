param workspaces_workspace_terminalscoutENto_name string = 'workspace-terminalscoutENto'

resource workspaces_workspace_terminalscoutENto_name_resource 'Microsoft.OperationalInsights/workspaces@2026-03-01' = {
  location: 'uksouth'
  name: workspaces_workspace_terminalscoutENto_name
  properties: {
    features: {
      enableLogAccessUsingOnlyResourcePermissions: true
      legacy: 0
      searchVersion: 1
    }
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
    retentionInDays: 30
    sku: {
      name: 'PerGB2018'
    }
    workspaceCapping: {
      dailyQuotaGb: json('-1')
    }
  }
}
