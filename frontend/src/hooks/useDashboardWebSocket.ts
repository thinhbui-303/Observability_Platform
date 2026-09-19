import { useEffect, useState } from 'react';
import { Client } from '@stomp/stompjs';

export interface ServiceMetrics {
  serviceId: string;
  name: string;
  status: 'ACTIVE' | 'DEGRADED' | 'UNAVAILABLE';
  logsPerSecond: number;
  errorRate: number;
  lastSeen: string;
}

export interface KafkaMetrics {
  indexerLag: number;
  analyticsLag: number;
}

export interface DashboardMetricsPayload {
  timestamp: string;
  services: ServiceMetrics[];
  kafka: KafkaMetrics;
}

export interface DashboardAlertPayload {
  alertId: string;
  ruleId: number;
  serviceId: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
  description: string;
  triggeredAt: string;
  occurrenceCount?: number;
}

export interface ConsumerLag {
  groupId: string;
  lag: number;
}

export interface SelfHealthPayload {
  timestamp: string;
  jvmMemoryUsedPercent: number;
  jvmMemoryMaxMb: number;
  cpuUsagePercent: number;
  apiP99LatencyMs: number;
  apiErrorRatePercent: number;
  consumerLag: ConsumerLag[];
}

export interface MetricsHistoryData {
  time: string;
  throughput: number;
  errorRate: number;
}

export function useDashboardWebSocket(token: string | null) {
  const [metrics, setMetrics] = useState<DashboardMetricsPayload | null>(null);
  const [metricsHistory, setMetricsHistory] = useState<MetricsHistoryData[]>([]);
  const [alerts, setAlerts] = useState<DashboardAlertPayload[]>([]);
  const [selfHealth, setSelfHealth] = useState<SelfHealthPayload | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!token) return;

    // Use STOMP over WebSockets
    // We connect to the STOMP endpoint configured in Spring (e.g. /ws)
    const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
    // Replace http:// or https:// with ws:// or wss://
    const brokerURL = baseUrl.replace(/^http/, 'ws') + '/ws';

    const client = new Client({
      brokerURL,
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      debug: function (str) {
        console.log('STOMP: ' + str);
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
    });

    client.onConnect = () => {
      setConnected(true);

      // Subscribe to metrics
      client.subscribe('/topic/dashboard/metrics', (message) => {
        if (message.body) {
          try {
            const payload = JSON.parse(message.body) as DashboardMetricsPayload;
            setMetrics(payload);
            
            const totalLogsPerSec = payload.services?.reduce((acc, s) => acc + s.logsPerSecond, 0) || 0;
            const totalErrors = payload.services?.reduce((acc, s) => acc + (s.logsPerSecond * s.errorRate), 0) || 0;
            const avgErrorRate = totalLogsPerSec > 0 ? (totalErrors / totalLogsPerSec) * 100 : 0;
            
            const timeStr = new Date(payload.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
            
            setMetricsHistory(prev => {
               const newHist = [...prev, { time: timeStr, throughput: parseFloat(totalLogsPerSec.toFixed(0)), errorRate: parseFloat(avgErrorRate.toFixed(3)) }];
               return newHist.slice(-30);
            });
          } catch (e) {
            console.error('Failed to parse metrics payload', e);
          }
        }
      });

      // Subscribe to alerts
      client.subscribe('/topic/dashboard/alerts', (message) => {
        if (message.body) {
          try {
            const payload = JSON.parse(message.body) as DashboardAlertPayload;
            setAlerts((prev) => {
              const existingIndex = prev.findIndex(a => a.ruleId === payload.ruleId && a.serviceId === payload.serviceId);
              if (existingIndex >= 0) {
                const existing = prev[existingIndex];
                const updated = {
                  ...payload,
                  occurrenceCount: (existing.occurrenceCount || 1) + 1
                };
                const newAlerts = [...prev];
                newAlerts.splice(existingIndex, 1);
                return [updated, ...newAlerts].slice(0, 50);
              }
              return [{ ...payload, occurrenceCount: 1 }, ...prev].slice(0, 50);
            });
          } catch (e) {
            console.error('Failed to parse alert payload', e);
          }
        }
      });

      // Subscribe to self-health
      client.subscribe('/topic/dashboard/self-health', (message) => {
        if (message.body) {
          try {
            const payload = JSON.parse(message.body) as SelfHealthPayload;
            setSelfHealth(payload);
          } catch (e) {
            console.error('Failed to parse self health payload', e);
          }
        }
      });
    };

    client.onStompError = (frame) => {
      console.error('Broker reported error: ' + frame.headers['message']);
      console.error('Additional details: ' + frame.body);
    };

    client.onDisconnect = () => {
      setConnected(false);
    };

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [token]);

  return { metrics, metricsHistory, alerts, selfHealth, connected };
}
