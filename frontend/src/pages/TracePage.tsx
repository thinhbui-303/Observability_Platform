import { useParams, Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { logApi } from "@/api/log.api";
import { ArrowLeft, Clock, Server, AlertCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";

export const TracePage = () => {
  const { traceId } = useParams<{ traceId: string }>();

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["logs", "trace", traceId],
    queryFn: () =>
      logApi.searchLogs({
        traceId: traceId,
        size: 500, // Fetch up to 500 logs for the trace
        page: 0,
      }),
    enabled: !!traceId,
  });

  const logs = data?.data?.content || [];

  const getLevelBadgeClass = (level: string) => {
    const lvl = level.toUpperCase();
    if (lvl === "ERROR" || lvl === "FATAL") return "bg-red-600 hover:bg-red-700 text-white border-red-500 shadow-[0_0_10px_rgba(220,38,38,0.5)]";
    if (lvl === "WARN") return "bg-[#FFD800] hover:bg-[#e6c200] text-black border-[#FFD800] font-bold shadow-[0_0_10px_rgba(255,216,0,0.5)]";
    if (lvl === "INFO") return "bg-blue-600 hover:bg-blue-700 text-white border-blue-500 shadow-[0_0_10px_rgba(37,99,235,0.5)]";
    return "bg-gray-800 text-gray-300 border-gray-600";
  };

  return (
    <div className="flex flex-col h-full space-y-6">
      <div className="flex items-center gap-4">
        <Link 
          to="/app/logs"
          className="p-2 rounded-full glass hover:bg-white/10 transition-colors"
        >
          <ArrowLeft className="w-5 h-5 text-white" />
        </Link>
        <div>
          <h2 className="text-2xl font-black uppercase tracking-wider text-white text-glow">
            Trace Timeline
          </h2>
          <p className="text-muted-foreground font-mono text-sm mt-1">
            ID: <span className="text-[#FFD800]">{traceId}</span>
          </p>
        </div>
      </div>

      {isError && (
        <Alert variant="destructive" className="bg-red-950/50 border-red-500 text-white shadow-[0_0_15px_rgba(220,38,38,0.3)]">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Error loading trace</AlertTitle>
          <AlertDescription>
            {error instanceof Error ? error.message : "An unknown error occurred"}
          </AlertDescription>
        </Alert>
      )}

      <div className="flex-1 glass-panel rounded-2xl p-6 overflow-y-auto relative">
        <div className="absolute inset-0 shadow-[inset_0_0_30px_rgba(255,216,0,0.02)] pointer-events-none"></div>
        
        {isLoading ? (
          <div className="space-y-8 pl-4">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex gap-6">
                <div className="flex flex-col items-center">
                  <Skeleton className="h-4 w-4 rounded-full" />
                  <Skeleton className="h-full w-0.5 mt-2" />
                </div>
                <div className="flex-1 pb-8">
                  <Skeleton className="h-24 w-full rounded-xl" />
                </div>
              </div>
            ))}
          </div>
        ) : logs.length === 0 ? (
          <div className="h-full flex items-center justify-center text-muted-foreground">
            No logs found for this trace ID.
          </div>
        ) : (
          <div className="relative pl-4">
            {/* The vertical timeline line */}
            <div className="absolute top-4 bottom-4 left-[23px] w-0.5 bg-gradient-to-b from-[#FFD800]/50 to-[#FFD800]/10 rounded-full shadow-[0_0_8px_rgba(255,216,0,0.5)]"></div>

            <div className="space-y-6">
              {logs.map((log, index) => (
                <div key={`${log.eventId}-${index}`} className="relative flex gap-6 items-start group">
                  {/* Timeline Dot */}
                  <div className="relative z-10 flex flex-col items-center pt-1">
                    <div className="w-3 h-3 rounded-full bg-[#FFD800] border-2 border-black shadow-[0_0_10px_rgba(255,216,0,0.8)] group-hover:scale-125 transition-transform duration-300"></div>
                  </div>

                  {/* Card Content */}
                  <div className="flex-1 bg-black/40 border border-white/10 rounded-xl p-4 hover:border-[#FFD800]/50 hover:shadow-[0_0_15px_rgba(255,216,0,0.15)] transition-all duration-300 backdrop-blur-md">
                    <div className="flex flex-wrap items-center justify-between gap-4 mb-3">
                      <div className="flex items-center gap-3">
                        <Badge className={`font-mono text-xs ${getLevelBadgeClass(log.level)}`}>
                          {log.level}
                        </Badge>
                        <div className="flex items-center text-sm font-mono text-white/80 gap-1.5 bg-white/5 px-2 py-1 rounded">
                          <Server className="w-3.5 h-3.5 text-[#FFD800]" />
                          {log.serviceName || "Unknown Service"}
                        </div>
                      </div>
                      
                      <div className="flex items-center text-xs text-muted-foreground font-mono gap-1.5">
                        <Clock className="w-3.5 h-3.5" />
                        {new Date(log.timestamp).toLocaleString()}
                        {log.spanId && (
                           <span className="ml-2 px-1.5 py-0.5 bg-white/5 rounded border border-white/10">Span: {log.spanId}</span>
                        )}
                      </div>
                    </div>
                    
                    <div className="text-sm text-gray-300 font-mono whitespace-pre-wrap break-all leading-relaxed">
                      {log.message}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
