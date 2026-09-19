import { useAuthStore } from "@/stores/auth-store";
import { useDashboardWebSocket } from "@/hooks/useDashboardWebSocket";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Activity, AlertTriangle, CheckCircle, XCircle, Database, Server, WifiOff, ActivitySquare, Cpu, HardDrive, Clock, ShieldAlert } from "lucide-react";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';


export function DashboardPage() {
  const token = useAuthStore((state) => state.accessToken);
  const { metrics, metricsHistory, alerts, selfHealth, connected } = useDashboardWebSocket(token);

  const getStatusColor = (status: string) => {
    switch (status) {
      case "HEALTHY": return "text-green-400 bg-green-400/10";
      case "DEGRADED": return "text-yellow-400 bg-yellow-400/10";
      case "UNAVAILABLE": return "text-red-400 bg-red-400/10";
      default: return "text-gray-400 bg-gray-400/10";
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case "HEALTHY": return <CheckCircle className="w-5 h-5 text-green-400" />;
      case "DEGRADED": return <AlertTriangle className="w-5 h-5 text-yellow-400" />;
      case "UNAVAILABLE": return <XCircle className="w-5 h-5 text-red-400" />;
      default: return <Server className="w-5 h-5 text-gray-400" />;
    }
  };

  const getSeverityColor = (severity: string) => {
    switch (severity) {
      case "CRITICAL": return "text-red-500 border-red-500/50 bg-red-500/10";
      case "HIGH": return "text-orange-500 border-orange-500/50 bg-orange-500/10";
      case "MEDIUM": return "text-yellow-500 border-yellow-500/50 bg-yellow-500/10";
      case "LOW": return "text-blue-500 border-blue-500/50 bg-blue-500/10";
      default: return "text-gray-500 border-gray-500/50 bg-gray-500/10";
    }
  };

  const totalLogsPerSec = metrics?.services?.reduce((acc, s) => acc + s.logsPerSecond, 0) || 0;
  
  const totalErrors = metrics?.services?.reduce((acc, s) => acc + (s.logsPerSecond * s.errorRate), 0) || 0;
  const avgErrorRate = totalLogsPerSec > 0 ? (totalErrors / totalLogsPerSec) * 100 : 0;
  
  const openAlertsCount = alerts?.filter(a => a.status === 'TRIGGERED' || a.status === 'ACKNOWLEDGED').length || 0;

  return (
    <div className="flex flex-col gap-6 w-full h-full p-2">
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 mb-2">
        <div className="flex items-center gap-4 bg-gradient-to-r from-purple-500/10 to-transparent p-3 pr-8 rounded-xl border border-purple-500/20">
          <img src="/anime_hacker.png" alt="Anime Mascot" className="w-12 h-12 rounded-full object-cover border-2 border-purple-500 shadow-[0_0_15px_rgba(168,85,247,0.4)]" />
          <div>
            <h2 className="text-xl font-bold uppercase tracking-wider text-white text-glow">System Dashboard</h2>
            <p className="text-purple-300/80 text-xs mt-0.5">Real-time observability stream is active.</p>
          </div>
        </div>
        <div className="flex items-center gap-2 self-end md:self-center">
          {connected ? (
            <Badge variant="outline" className="text-green-400 border-green-400/50 bg-green-400/10 uppercase tracking-widest text-xs py-1">
              <Activity className="w-3 h-3 mr-2 animate-pulse" /> Live
            </Badge>
          ) : (
            <Badge variant="outline" className="text-red-400 border-red-400/50 bg-red-400/10 uppercase tracking-widest text-xs py-1">
              <WifiOff className="w-3 h-3 mr-2" /> Reconnecting...
            </Badge>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Core Metrics */}
        <div className="xl:col-span-2 grid grid-cols-1 sm:grid-cols-3 gap-4">
          <Card className="glass-panel border-white/10 bg-black/40">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-gray-400 uppercase tracking-wider flex items-center">
                <Activity className="w-4 h-4 mr-2" />
                Total Logs/sec
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="text-4xl font-bold text-white text-glow">
                {totalLogsPerSec.toFixed(0)}
              </div>
            </CardContent>
          </Card>
          
          <Card className="glass-panel border-white/10 bg-black/40">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-gray-400 uppercase tracking-wider flex items-center">
                <AlertTriangle className="w-4 h-4 mr-2" />
                Avg Error Rate
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="text-4xl font-bold text-white text-glow">
                {avgErrorRate.toFixed(3)}%
              </div>
            </CardContent>
          </Card>

          <Card className="glass-panel border-white/10 bg-black/40">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-gray-400 uppercase tracking-wider flex items-center">
                <Database className="w-4 h-4 mr-2" />
                Open Alerts
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="text-4xl font-bold text-white text-glow text-red-400">
                {openAlertsCount}
              </div>
            </CardContent>
          </Card>

          {/* Time-Series Chart */}
          <Card className="col-span-3 glass-panel border-white/10 bg-black/40">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-gray-400 uppercase tracking-wider flex items-center">
                <ActivitySquare className="w-4 h-4 mr-2" />
                Throughput & Error Rate (Live)
              </CardTitle>
            </CardHeader>
            <CardContent className="h-[250px] mt-2">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={metricsHistory} margin={{ top: 5, right: 5, left: -20, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#333" vertical={false} />
                  <XAxis dataKey="time" stroke="#888" tick={{fontSize: 10}} minTickGap={20} />
                  <YAxis yAxisId="left" stroke="#3b82f6" tick={{fontSize: 10}} />
                  <YAxis yAxisId="right" orientation="right" stroke="#ef4444" tick={{fontSize: 10}} domain={[0, 'auto']} />
                  <Tooltip 
                    contentStyle={{backgroundColor: 'rgba(0,0,0,0.8)', borderColor: 'rgba(255,255,255,0.1)', borderRadius: '8px'}}
                    itemStyle={{color: '#fff'}}
                  />
                  <Legend iconType="circle" wrapperStyle={{fontSize: '12px'}} />
                  <Line yAxisId="left" type="monotone" dataKey="throughput" name="Logs/sec" stroke="#3b82f6" strokeWidth={2} dot={false} activeDot={{ r: 6 }} isAnimationActive={false} />
                  <Line yAxisId="right" type="monotone" dataKey="errorRate" name="Error Rate (%)" stroke="#ef4444" strokeWidth={2} dot={false} activeDot={{ r: 6 }} isAnimationActive={false} />
                </LineChart>
              </ResponsiveContainer>
            </CardContent>
          </Card>

          {/* Service Status List */}
          <Card className="col-span-2 glass-panel border-white/10 bg-black/40 h-[300px] flex flex-col">
            <CardHeader className="pb-2 flex-shrink-0">
              <CardTitle className="text-sm text-gray-400 uppercase tracking-wider">Service Health</CardTitle>
            </CardHeader>
            <CardContent className="flex-1 overflow-auto p-0">
              <ScrollArea className="h-full px-6 pb-4">
                <div className="space-y-4">
                  {!metrics?.services || metrics.services.length === 0 ? (
                    <div className="text-center text-gray-500 py-8">Waiting for metrics...</div>
                  ) : (
                    metrics.services.map((service) => (
                      <div key={service.serviceId} className="flex items-center justify-between p-3 rounded-lg bg-white/5 border border-white/10">
                        <div className="flex items-center gap-3">
                          {getStatusIcon(service.status)}
                          <div>
                            <div className="font-bold text-white">{service.name}</div>
                            <div className="text-xs text-gray-400">{service.serviceId}</div>
                          </div>
                        </div>
                        <div className="flex items-center gap-4 text-right">
                          <div>
                            <div className="text-xs text-gray-400">Error Rate</div>
                            <div className="font-mono text-sm">{(service.errorRate * 100).toFixed(2)}%</div>
                          </div>
                          <div>
                            <div className="text-xs text-gray-400">Logs/s</div>
                            <div className="font-mono text-sm">{service.logsPerSecond.toFixed(1)}</div>
                          </div>
                          <Badge variant="outline" className={`ml-2 uppercase text-[10px] tracking-wider ${getStatusColor(service.status)} border-transparent`}>
                            {service.status}
                          </Badge>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </ScrollArea>
            </CardContent>
          </Card>
        </div>

        {/* Live Alerts Stream */}
        <Card className="glass-panel border-white/10 bg-black/40 h-[420px] flex flex-col">
          <CardHeader className="pb-2 flex-shrink-0">
            <CardTitle className="text-sm text-gray-400 uppercase tracking-wider flex items-center justify-between">
              <span>Alert Stream</span>
              {alerts.length > 0 && (
                <Badge variant="outline" className="bg-red-500/20 text-red-400 border-transparent">
                  {alerts.length} NEW
                </Badge>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex-1 overflow-auto p-0">
            <ScrollArea className="h-full px-6 pb-4">
              <div className="space-y-3">
                {alerts.length === 0 ? (
                  <div className="text-center text-gray-500 py-8">No alerts active</div>
                ) : (
                  alerts.map((alert, i) => (
                    <div key={i} className="p-3 rounded-lg bg-black/50 border border-white/5 flex flex-col gap-2">
                      <div className="flex justify-between items-start">
                        <div className="flex items-center gap-2">
                          <Badge variant="outline" className={`uppercase text-[10px] tracking-wider ${getSeverityColor(alert.severity)}`}>
                            {alert.severity}
                          </Badge>
                          {(alert.occurrenceCount && alert.occurrenceCount > 1) && (
                            <Badge variant="outline" className="bg-yellow-500/20 text-yellow-400 border-transparent text-[10px] tracking-wider px-1.5 py-0">
                              x{alert.occurrenceCount}
                            </Badge>
                          )}
                        </div>
                        <span className="text-[10px] text-gray-500 font-mono">
                          {new Date(alert.triggeredAt).toLocaleTimeString()}
                        </span>
                      </div>
                      <div className="text-sm font-semibold text-white leading-tight">
                        {alert.description}
                      </div>
                      <div className="text-xs text-gray-400">
                        Service: <span className="text-gray-300">{alert.serviceId}</span>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </ScrollArea>
          </CardContent>
        </Card>
      </div>

      {/* Self-Observability Section */}
      {selfHealth && (
        <div className="mt-2 mb-4">
          <h3 className="text-lg font-bold uppercase tracking-wider text-white mb-3 flex items-center">
            <ShieldAlert className="w-5 h-5 mr-2 text-purple-400" />
            Internal System Health
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
            <Card className="glass-panel border-purple-500/20 bg-purple-500/5">
              <CardHeader className="pb-2">
                <CardTitle className="text-xs text-gray-400 uppercase tracking-wider flex items-center">
                  <HardDrive className="w-4 h-4 mr-2" />
                  JVM Memory
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold text-white">
                  {((selfHealth.jvmMemoryUsedPercent || 0) * (selfHealth.jvmMemoryMaxMb || 0)).toFixed(0)} / {(selfHealth.jvmMemoryMaxMb || 0).toFixed(0)} MB
                </div>
                <div className="w-full bg-gray-800 rounded-full h-1.5 mt-2">
                  <div 
                    className={`h-1.5 rounded-full ${(selfHealth.jvmMemoryUsedPercent || 0) > 0.8 ? 'bg-red-500' : 'bg-purple-500'}`} 
                    style={{ width: `${Math.min(100, (selfHealth.jvmMemoryUsedPercent || 0) * 100)}%` }}
                  ></div>
                </div>
              </CardContent>
            </Card>

            <Card className="glass-panel border-purple-500/20 bg-purple-500/5">
              <CardHeader className="pb-2">
                <CardTitle className="text-xs text-gray-400 uppercase tracking-wider flex items-center">
                  <Cpu className="w-4 h-4 mr-2" />
                  CPU Usage
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold text-white">
                  {((selfHealth.cpuUsagePercent || 0) * 100).toFixed(1)}%
                </div>
                <div className="w-full bg-gray-800 rounded-full h-1.5 mt-2">
                  <div 
                    className={`h-1.5 rounded-full ${(selfHealth.cpuUsagePercent || 0) > 0.8 ? 'bg-red-500' : 'bg-purple-500'}`} 
                    style={{ width: `${Math.min(100, (selfHealth.cpuUsagePercent || 0) * 100)}%` }}
                  ></div>
                </div>
              </CardContent>
            </Card>

            <Card className="glass-panel border-purple-500/20 bg-purple-500/5">
              <CardHeader className="pb-2">
                <CardTitle className="text-xs text-gray-400 uppercase tracking-wider flex items-center">
                  <Clock className="w-4 h-4 mr-2" />
                  API P99 Latency
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold text-white">
                  {(selfHealth.apiP99LatencyMs || 0) > 0 ? `${(selfHealth.apiP99LatencyMs || 0).toFixed(1)} ms` : 'N/A'}
                </div>
                <div className="text-xs text-gray-400 mt-1">Error Rate: {(selfHealth.apiErrorRatePercent || 0).toFixed(2)}%</div>
              </CardContent>
            </Card>

            <Card className="glass-panel border-purple-500/20 bg-purple-500/5">
              <CardHeader className="pb-2">
                <CardTitle className="text-xs text-gray-400 uppercase tracking-wider flex items-center">
                  <Database className="w-4 h-4 mr-2" />
                  Kafka Consumer Lag
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="flex flex-col gap-1 max-h-[60px] overflow-auto pr-2">
                  {selfHealth.consumerLag && selfHealth.consumerLag.length > 0 ? (
                    selfHealth.consumerLag.map((lagInfo) => (
                      <div key={lagInfo.groupId} className="flex justify-between items-center text-xs">
                        <span className="text-gray-400 truncate max-w-[120px]" title={lagInfo.groupId}>{lagInfo.groupId}</span>
                        <span className={`font-mono ${lagInfo.lag > 1000 ? 'text-red-400' : lagInfo.lag > 100 ? 'text-yellow-400' : 'text-green-400'}`}>
                          {lagInfo.lag}
                        </span>
                      </div>
                    ))
                  ) : (
                    <div className="text-xs text-gray-500">No lag data</div>
                  )}
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </div>
  );
}
