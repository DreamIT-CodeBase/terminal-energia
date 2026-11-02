param components_mltraining5523469937_name string = 'mltraining5523469937'
param workspaces_mltraining9690331438_externalid string

resource components_mltraining5523469937_name_resource 'microsoft.insights/components@2020-02-02' = {
  kind: 'web'
  location: 'uksouth'
  name: components_mltraining5523469937_name
  properties: {
    Application_Type: 'web'
    Flow_Type: 'Redfield'
    IngestionMode: 'LogAnalytics'
    Request_Source: 'IbizaMachineLearningExtension'
    RetentionInDays: 90
    WorkspaceResourceId: workspaces_mltraining9690331438_externalid
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}
