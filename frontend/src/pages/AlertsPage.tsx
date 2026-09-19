import { useState } from "react";
import { useRoles } from "@/hooks/useRoles";
import { useAlerts, useAcknowledgeAlert, useResolveAlert } from "@/hooks/useAlerts";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { CheckCircle2, CheckSquare } from "lucide-react";
import { format } from "date-fns";

export function AlertsPage() {
  const [serviceId, setServiceId] = useState<string>("");
  const [environment, setEnvironment] = useState<string>("");
  const [status, setStatus] = useState<string>("ALL");

  const { data: response, isLoading } = useAlerts(
    serviceId || undefined, 
    environment || undefined, 
    status !== "ALL" ? status : undefined
  );
  const alerts = response?.data || [];
  
  const roles = useRoles();
  const canAcknowledge = roles.some((r: string) => ["ADMIN", "DEVOPS", "DEVELOPER"].includes(r));
  const canResolve = roles.some((r: string) => ["ADMIN", "DEVOPS"].includes(r));
  
  const { mutate: acknowledge, isPending: isAcknowledging } = useAcknowledgeAlert();
  const { mutate: resolve, isPending: isResolving } = useResolveAlert();

  const getSeverityColor = (severity: string) => {
    switch (severity) {
      case "CRITICAL": return "text-red-500 border-red-500/50 bg-red-500/10";
      case "HIGH": return "text-orange-500 border-orange-500/50 bg-orange-500/10";
      case "MEDIUM": return "text-yellow-500 border-yellow-500/50 bg-yellow-500/10";
      case "LOW": return "text-blue-500 border-blue-500/50 bg-blue-500/10";
      default: return "text-gray-500 border-gray-500/50 bg-gray-500/10";
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case "TRIGGERED": return "text-red-400 border-red-400/50 bg-red-400/10";
      case "ACKNOWLEDGED": return "text-yellow-400 border-yellow-400/50 bg-yellow-400/10";
      case "RESOLVED": return "text-green-400 border-green-400/50 bg-green-400/10";
      default: return "text-gray-400 border-gray-400/50 bg-gray-400/10";
    }
  };

  return (
    <div className="flex flex-col h-full w-full gap-6 p-4">
      <div>
        <h2 className="text-3xl font-bold uppercase tracking-wider text-white">Alerts</h2>
        <p className="text-gray-400 text-sm mt-1">Review and manage triggered system alerts</p>
      </div>

      <div className="flex gap-4 items-end bg-black/40 p-4 rounded-lg border border-white/10 glass-panel">
        <div className="space-y-1.5 flex-1">
          <label className="text-xs text-gray-400 uppercase tracking-wider">Service ID</label>
          <Input 
            placeholder="Filter by service..." 
            className="bg-white/5 border-white/10" 
            value={serviceId}
            onChange={(e) => setServiceId(e.target.value)}
          />
        </div>
        <div className="space-y-1.5 flex-1">
          <label className="text-xs text-gray-400 uppercase tracking-wider">Environment</label>
          <Input 
            placeholder="Filter by environment..." 
            className="bg-white/5 border-white/10" 
            value={environment}
            onChange={(e) => setEnvironment(e.target.value)}
          />
        </div>
        <div className="space-y-1.5 flex-1">
          <label className="text-xs text-gray-400 uppercase tracking-wider">Status</label>
          <Select value={status} onValueChange={setStatus}>
            <SelectTrigger className="bg-white/5 border-white/10">
              <SelectValue placeholder="All Statuses" />
            </SelectTrigger>
            <SelectContent className="bg-black border-white/10 text-white">
              <SelectItem value="ALL">All Statuses</SelectItem>
              <SelectItem value="TRIGGERED">TRIGGERED</SelectItem>
              <SelectItem value="ACKNOWLEDGED">ACKNOWLEDGED</SelectItem>
              <SelectItem value="RESOLVED">RESOLVED</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>

      <div className="glass-panel rounded-lg border border-white/10 bg-black/40 overflow-hidden flex-1 flex flex-col">
        <div className="overflow-x-auto flex-1">
          <Table className="min-w-[800px]">
          <TableHeader className="bg-white/5 sticky top-0">
            <TableRow className="border-b border-white/10 hover:bg-transparent">
              <TableHead className="text-gray-400">Severity</TableHead>
              <TableHead className="text-gray-400 w-[30%]">Alert Details</TableHead>
              <TableHead className="text-gray-400">Target</TableHead>
              <TableHead className="text-gray-400">Status</TableHead>
              <TableHead className="text-gray-400">Timeline</TableHead>
              <TableHead className="text-gray-400 text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={6} className="h-24 text-center text-gray-500">Loading alerts...</TableCell>
              </TableRow>
            ) : alerts.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="h-24 text-center text-gray-500">No alerts found matching your criteria.</TableCell>
              </TableRow>
            ) : (
              alerts.map((alert) => (
                <TableRow key={alert.id} className="border-b border-white/5 hover:bg-white/5">
                  <TableCell>
                    <Badge variant="outline" className={`uppercase text-[10px] tracking-wider ${getSeverityColor(alert.severity)}`}>
                      {alert.severity}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="font-medium text-white">{alert.description}</div>
                    <div className="text-xs text-gray-500 mt-1">Rule: <span className="text-gray-400">{alert.ruleName}</span></div>
                  </TableCell>
                  <TableCell>
                    <div className="text-sm text-gray-300">{alert.serviceId}</div>
                    <div className="text-xs text-gray-500">{alert.environment}</div>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className={`uppercase text-[10px] tracking-wider ${getStatusColor(alert.status)}`}>
                      {alert.status}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="text-xs space-y-1">
                      <div className="text-gray-400">
                        <span className="text-gray-500 inline-block w-16">Triggered:</span> 
                        {format(new Date(alert.triggeredAt), "MMM dd HH:mm:ss")}
                      </div>
                      {alert.acknowledgedAt && (
                        <div className="text-yellow-400/80">
                          <span className="text-yellow-500/50 inline-block w-16">Acked:</span> 
                          {format(new Date(alert.acknowledgedAt), "MMM dd HH:mm:ss")} ({alert.acknowledgedBy})
                        </div>
                      )}
                      {alert.resolvedAt && (
                        <div className="text-green-400/80">
                          <span className="text-green-500/50 inline-block w-16">Resolved:</span> 
                          {format(new Date(alert.resolvedAt), "MMM dd HH:mm:ss")} ({alert.resolvedBy})
                        </div>
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="text-right space-x-2">
                    {canAcknowledge && alert.status === 'TRIGGERED' && (
                      <Button 
                        variant="outline" 
                        size="sm" 
                        disabled={isAcknowledging}
                        onClick={() => acknowledge(alert.id)} 
                        className="text-yellow-400 border-yellow-400/50 hover:bg-yellow-400/10 h-8"
                      >
                        <CheckSquare className="w-4 h-4 mr-1" />
                        Ack
                      </Button>
                    )}
                    {canResolve && (alert.status === 'TRIGGERED' || alert.status === 'ACKNOWLEDGED') && (
                      <Button 
                        variant="outline" 
                        size="sm" 
                        disabled={isResolving}
                        onClick={() => resolve(alert.id)} 
                        className="text-green-400 border-green-400/50 hover:bg-green-400/10 h-8"
                      >
                        <CheckCircle2 className="w-4 h-4 mr-1" />
                        Resolve
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        </div>
      </div>
    </div>
  );
}
