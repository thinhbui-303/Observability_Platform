export interface LogEvent {
  eventId: string;
  timestamp: string; // ISO-8601 Date string
  serviceName?: string;
  environment?: string;
  level: string;
  message: string;
  traceId?: string;
  spanId?: string;
  host?: string;
  statusCode?: number;
  durationMs?: number;
  metadata?: Record<string, any>;
}

export interface LogSearchRequest {
  service?: string;
  environment?: string;
  level?: string;
  startTime?: string;
  endTime?: string;
  traceId?: string;
  spanId?: string;
  host?: string;
  statusCode?: number;
  httpMethod?: string;
  endpoint?: string;
  query?: string;
  page?: number;
  size?: number;
  search_after?: string;
}

export interface LogSearchResponseObject {
  content: LogEvent[];
  page?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
  nextSearchAfter?: string;
}

export interface UnifiedResponseLogSearchResponseObject {
  code: string;
  data?: LogSearchResponseObject;
  message?: string;
  timestamp?: string;
  path?: string;
  fieldErrors?: Array<Record<string, string>>;
}
