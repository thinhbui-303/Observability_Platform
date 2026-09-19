import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { logApi } from "@/api/log.api";
import type { LogSearchRequest } from "@/schemas/log.schema";
import { LogFilterBar } from "@/components/logs/LogFilterBar";
import { LogTable } from "@/components/logs/LogTable";
import { LogPagination } from "@/components/logs/LogPagination";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

export const LogsPage = () => {
  const [filters, setFilters] = useState<LogSearchRequest>({});
  
  // Use standard 0-indexed page for standard pagination
  const [page, setPage] = useState(0);

  const currentFilters = {
    ...filters,
    size: 50,
    page: page,
  };

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["logs", currentFilters],
    queryFn: () => logApi.searchLogs(currentFilters),
  });

  const responseData = data?.data;
  const logs = responseData?.content || [];
  
  // hasNextPage is true if we are not on the last page
  const totalPages = responseData?.totalPages || 0;
  const hasNextPage = page < totalPages - 1;

  const handleSearch = (newFilters: LogSearchRequest) => {
    setFilters(newFilters);
    setPage(0); // Reset to first page on new search
  };

  const handleNextPage = () => {
    if (hasNextPage) {
      setPage((p) => p + 1);
    }
  };

  const handlePrevPage = () => {
    if (page > 0) {
      setPage((p) => p - 1);
    }
  };

  return (
    <div className="flex flex-col h-full space-y-4">
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-3">
          <div className="w-2 h-8 bg-[#FFD800] rounded-full shadow-[0_0_10px_rgba(255,216,0,0.8)]"></div>
          <h2 className="text-3xl font-black uppercase tracking-wider text-white text-glow">Logs Explorer</h2>
        </div>
      </div>
      
      <LogFilterBar onSearch={handleSearch} isLoading={isLoading} />

      {isError && (
        <Alert variant="destructive" className="bg-red-950/50 border-red-500 text-white shadow-[0_0_15px_rgba(220,38,38,0.3)]">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Error fetching logs</AlertTitle>
          <AlertDescription>
            {error instanceof Error ? error.message : "An unknown error occurred"}
          </AlertDescription>
        </Alert>
      )}

      <div className="flex-1 glass-panel rounded-2xl flex flex-col overflow-hidden relative">
        {/* Subtle inner glow */}
        <div className="absolute inset-0 shadow-[inset_0_0_30px_rgba(255,216,0,0.02)] pointer-events-none"></div>
        
        <LogTable logs={logs} isLoading={isLoading} />
        <LogPagination 
          page={page}
          totalPages={responseData?.totalPages}
          totalElements={responseData?.totalElements}
          hasNextPage={hasNextPage}
          onNextPage={handleNextPage}
          onPrevPage={handlePrevPage}
          isLoading={isLoading}
        />
      </div>
    </div>
  );
};
