param actiongroups_application_insights_smart_detection_externalid string
param components_mltraining5523469937_name string = 'mltraining5523469937'
param smartdetectoralertrules_failure_anomalies_mltraining5523469937_name string = 'Failure Anomalies - mltraining5523469937'

resource smartdetectoralertrules_failure_anomalies_mltraining5523469937_name_resource 'microsoft.alertsmanagement/smartdetectoralertrules@2021-04-01' = {
  location: 'global'
  name: smartdetectoralertrules_failure_anomalies_mltraining5523469937_name
  properties: {
    actionGroups: {
      groupIds: [
        actiongroups_application_insights_smart_detection_externalid
      ]
    }
    description: 'Failure Anomalies notifies you of an unusual rise in the rate of failed HTTP requests or dependency calls.'
    detector: {
      id: 'FailureAnomaliesDetector'
    }
    frequency: 'PT1M'
    scope: [
      resourceId('microsoft.insights/components', components_mltraining5523469937_name)
    ]
    severity: 'Sev3'
    state: 'Enabled'
  }
}
