export interface UnifiedResponse<T> {
  code: string;
  message?: string;
  data: T;
}

export interface ServiceResponse {
  id: string;
  name: string;
  teamOwner: string;
  environment: string;
  status: string;
  createdAt: string;
}

export interface ServiceCreateResponse extends ServiceResponse {
  plaintextApiKey: string;
}

export interface AlertRuleResponse {
  id: number;
  ruleName: string;
  serviceId: string;
  environment: string;
  conditionType: string;
  thresholdValue?: number;
  windowSeconds?: number;
  conditionValue?: string;
  severity: string;
  isEnabled: boolean;
  createdAt: string;
}

export interface AlertResponse {
  id: string;
  ruleId: number;
  ruleName: string;
  serviceId: string;
  environment: string;
  severity: string;
  description: string;
  status: string;
  triggeredAt: string;
  acknowledgedAt?: string;
  acknowledgedBy?: string;
  resolvedAt?: string;
  resolvedBy?: string;
}

export interface AuditLogResponse {
  id: number;
  createdAt: string;
  username: string;
  action: string;
  resourceTarget: string;
  resultStatus: string;
  ipAddress?: string;
  details?: string;
}
