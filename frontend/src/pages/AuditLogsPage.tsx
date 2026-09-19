import { useState } from "react";
import { useRoles } from "@/hooks/useRoles";
import { useAuditLogs } from "@/hooks/useAuditLogs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Info } from "lucide-react";
import { format } from "date-fns";

export function AuditLogsPage() {
  const [action, setAction] = useState<string>("");
  const [username, setUsername] = useState<string>("");
  const [resourceTarget, setResourceTarget] = useState<string>("");

  const { data: response, isLoading } = useAuditLogs(
    action || undefined, 
    username || undefined, 
    resourceTarget || undefined
  );
  
  const logs = response?.data || [];
  
  const roles = useRoles();
  const isAdmin = roles.includes("ADMIN");
  const isDevOps = roles.includes("DEVOPS") && !isAdmin;

  const getResultColor = (status: string) => {
    switch (status) {
      case "SUCCESS": return "text-green-400 border-green-400/50 bg-green-400/10";
      case "FAILED": return "text-red-400 border-red-400/50 bg-red-400/10";
      default: return "text-gray-400 border-gray-400/50 bg-gray-400/10";
    }
  };

  return (
    <div className="flex flex-col h-full w-full gap-6 p-4">
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
        <div>
          <h2 className="text-3xl font-bold uppercase tracking-wider text-white">Audit Logs</h2>
          <p className="text-gray-400 text-sm mt-1">Track system administration and configuration changes</p>
        </div>
        {isDevOps && (
          <div className="flex items-center gap-2 bg-blue-500/10 border border-blue-500/20 text-blue-400 text-sm p-3 rounded-lg">
            <Info className="w-5 h-5 shrink-0" />
            <span>As a DEVOPS user, you only have permission to view audit logs for <strong>services</strong> and <strong>alert-rules</strong>. The backend enforces this restriction.</span>
          </div>
        )}
      </div>

      <div className="flex flex-col md:flex-row gap-4 bg-black/40 p-4 rounded-lg border border-white/10 glass-panel">
        <div className="space-y-1.5 flex-1">
          <label className="text-xs text-gray-400 uppercase tracking-wider">Action</label>
          <Input 
            placeholder="e.g. CREATE_ALERT_RULE" 
            className="bg-white/5 border-white/10" 
            value={action}
            onChange={(e) => setAction(e.target.value)}
          />
        </div>
        <div className="space-y-1.5 flex-1">
          <label className="text-xs text-gray-400 uppercase tracking-wider">Username</label>
          <Input 
            placeholder="e.g. admin_user" 
            className="bg-white/5 border-white/10" 
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="space-y-1.5 flex-1">
          <label className="text-xs text-gray-400 uppercase tracking-wider">Resource Target</label>
          <Input 
            placeholder="e.g. services/my-service" 
            className="bg-white/5 border-white/10" 
            value={resourceTarget}
            onChange={(e) => setResourceTarget(e.target.value)}
          />
        </div>
      </div>

      <div className="glass-panel rounded-lg border border-white/10 bg-black/40 overflow-hidden flex-1 flex flex-col">
        <div className="overflow-x-auto flex-1">
          <Table className="min-w-[800px]">
          <TableHeader className="bg-white/5 sticky top-0">
            <TableRow className="border-b border-white/10 hover:bg-transparent">
              <TableHead className="text-gray-400 w-[180px]">Timestamp</TableHead>
              <TableHead className="text-gray-400">User</TableHead>
              <TableHead className="text-gray-400">Action</TableHead>
              <TableHead className="text-gray-400">Target</TableHead>
              <TableHead className="text-gray-400">Status</TableHead>
              <TableHead className="text-gray-400">IP Address</TableHead>
              <TableHead className="text-gray-400">Details</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={7} className="h-24 text-center text-gray-500">Loading audit logs...</TableCell>
              </TableRow>
            ) : logs.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="h-24 text-center text-gray-500">No audit logs found matching your criteria.</TableCell>
              </TableRow>
            ) : (
              logs.map((log) => (
                <TableRow key={log.id} className="border-b border-white/5 hover:bg-white/5">
                  <TableCell className="text-gray-400 text-xs font-mono">
                    {format(new Date(log.createdAt), "yyyy-MM-dd HH:mm:ss")}
                  </TableCell>
                  <TableCell className="font-medium text-white">{log.username}</TableCell>
                  <TableCell>
                    <Badge variant="outline" className="bg-purple-500/10 text-purple-400 border-purple-500/20 uppercase tracking-wider text-[10px]">
                      {log.action}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-gray-300 font-mono text-xs">{log.resourceTarget}</TableCell>
                  <TableCell>
                    <Badge variant="outline" className={`uppercase text-[10px] tracking-wider ${getResultColor(log.resultStatus)}`}>
                      {log.resultStatus}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-gray-500 text-xs font-mono">{log.ipAddress || "Unknown"}</TableCell>
                  <TableCell className="text-gray-400 text-xs max-w-[200px] truncate" title={log.details}>
                    {log.details || "-"}
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
