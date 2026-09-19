import { useState } from "react";
import { useDlqMessages, useProcessDlq } from "@/hooks/useDlq";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogFooter,
  DialogClose,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { RefreshCw, Trash2, Info } from "lucide-react";
import { format } from "date-fns";
import { toast } from "sonner";
import { ScrollArea } from "@/components/ui/scroll-area";

export function DlqPage() {
  const { data: response, isLoading } = useDlqMessages(20);
  const processMutation = useProcessDlq();
  const logs = response?.data || [];

  const [isDiscardModalOpen, setIsDiscardModalOpen] = useState(false);
  const [confirmText, setConfirmText] = useState("");

  const handleRetry = () => {
    if (logs.length === 0) return;
    processMutation.mutate(
      { action: "RETRY", limit: logs.length },
      {
        onSuccess: () => toast.success(`Retrying ${logs.length} messages`),
        onError: (err: any) => toast.error(err.response?.data?.message || "Retry failed"),
      }
    );
  };

  const handleDiscard = () => {
    if (logs.length === 0) return;
    if (confirmText !== logs.length.toString()) {
      toast.error(`Please type "${logs.length}" to confirm.`);
      return;
    }
    
    processMutation.mutate(
      { action: "DISCARD", limit: logs.length },
      {
        onSuccess: () => {
          toast.success(`Discarded ${logs.length} messages`);
          setIsDiscardModalOpen(false);
          setConfirmText("");
        },
        onError: (err: any) => toast.error(err.response?.data?.message || "Discard failed"),
      }
    );
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-2">
        <h2 className="text-3xl font-bold tracking-tight uppercase text-glow text-white">Dead Letter Queue</h2>
        <p className="text-gray-400">Manage failed log events that exceeded retry limits.</p>
      </div>

      <div className="flex justify-between items-center bg-black/20 p-4 rounded-xl border border-white/5">
        <div className="text-sm text-gray-400">
          Showing up to <span className="text-white font-bold">{logs.length}</span> oldest messages in queue.
        </div>
        <div className="flex gap-3">
          <Button 
            onClick={handleRetry} 
            disabled={logs.length === 0 || processMutation.isPending}
            className="bg-[#FFD800] text-black hover:bg-[#FFD800]/80 font-bold"
          >
            <RefreshCw className="w-4 h-4 mr-2" />
            Retry All Visible
          </Button>

          <Dialog open={isDiscardModalOpen} onOpenChange={setIsDiscardModalOpen}>
            <DialogTrigger asChild>
              <Button 
                variant="destructive" 
                disabled={logs.length === 0 || processMutation.isPending}
                className="font-bold"
              >
                <Trash2 className="w-4 h-4 mr-2" />
                Discard All Visible
              </Button>
            </DialogTrigger>
            <DialogContent className="sm:max-w-[425px] bg-[#0d1410] text-white border-white/10">
              <DialogHeader>
                <DialogTitle className="text-red-500">Confirm Discard</DialogTitle>
                <DialogDescription className="text-gray-400">
                  This action cannot be undone. This will permanently delete {logs.length} messages from the queue.
                  To confirm, please type <strong>{logs.length}</strong> below.
                </DialogDescription>
              </DialogHeader>
              <div className="py-4">
                <Input
                  value={confirmText}
                  onChange={(e) => setConfirmText(e.target.value)}
                  placeholder="Type number of messages"
                  className="bg-black/50 border-white/10 text-white"
                />
              </div>
              <DialogFooter>
                <DialogClose asChild>
                  <Button variant="ghost" onClick={() => setConfirmText("")}>Cancel</Button>
                </DialogClose>
                <Button 
                  variant="destructive" 
                  onClick={handleDiscard}
                  disabled={confirmText !== logs.length.toString() || processMutation.isPending}
                >
                  Confirm Discard
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>
      </div>

      <div className="rounded-xl border border-white/5 overflow-hidden glass-panel">
        <div className="overflow-x-auto flex-1">
          <Table className="min-w-[800px]">
          <TableHeader className="bg-black/40 hover:bg-black/40">
            <TableRow className="border-b border-white/5 hover:bg-transparent">
              <TableHead className="text-gray-400">Offset</TableHead>
              <TableHead className="text-gray-400">Event ID (Key)</TableHead>
              <TableHead className="text-gray-400">Failed At</TableHead>
              <TableHead className="text-gray-400">Error Reason</TableHead>
              <TableHead className="text-right text-gray-400">Details</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={5} className="text-center py-8 text-gray-400">
                  Loading DLQ...
                </TableCell>
              </TableRow>
            ) : logs.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="text-center py-8 text-gray-400">
                  No messages in DLQ.
                </TableCell>
              </TableRow>
            ) : (
              logs.map((log) => (
                <TableRow key={`${log.partition}-${log.offset}`} className="border-b border-white/5 hover:bg-white/5">
                  <TableCell className="font-mono text-xs">{log.partition}:{log.offset}</TableCell>
                  <TableCell className="font-medium text-[#FFD800]">{log.key}</TableCell>
                  <TableCell className="text-gray-300">
                    {log.firstFailedAt ? format(new Date(Number(log.firstFailedAt)), "yyyy-MM-dd HH:mm:ss") : "Unknown"}
                  </TableCell>
                  <TableCell>
                    <span className="text-red-400 text-sm">{log.errorReason}</span>
                  </TableCell>
                  <TableCell className="text-right">
                    <Dialog>
                      <DialogTrigger asChild>
                        <Button variant="ghost" size="icon" className="hover:text-white">
                          <Info className="h-4 w-4" />
                        </Button>
                      </DialogTrigger>
                      <DialogContent className="max-w-2xl bg-[#0d1410] text-white border-white/10">
                        <DialogHeader>
                          <DialogTitle>Message Details</DialogTitle>
                        </DialogHeader>
                        <div className="space-y-4">
                          <div>
                            <h4 className="text-sm font-semibold text-[#FFD800] mb-2">Headers</h4>
                            <ScrollArea className="h-32 w-full rounded-md border border-white/10 bg-black/50 p-4">
                              <pre className="text-xs text-gray-300">
                                {JSON.stringify(log.headers, null, 2)}
                              </pre>
                            </ScrollArea>
                          </div>
                          <div>
                            <h4 className="text-sm font-semibold text-[#FFD800] mb-2">Payload</h4>
                            <ScrollArea className="h-64 w-full rounded-md border border-white/10 bg-black/50 p-4">
                              <pre className="text-xs text-gray-300 whitespace-pre-wrap">
                                {log.payload}
                              </pre>
                            </ScrollArea>
                          </div>
                        </div>
                      </DialogContent>
                    </Dialog>
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
