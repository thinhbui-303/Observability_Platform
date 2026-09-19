import { useState } from "react";
import { useRoles } from "@/hooks/useRoles";
import { useServices, useCreateService, useUpdateServiceStatus } from "@/hooks/useServices";
import type { CreateServiceFormValues } from "@/schemas/service.schema";
import { createServiceSchema } from "@/schemas/service.schema";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Badge } from "@/components/ui/badge";
import { Plus, Copy, Check, Server } from "lucide-react";
import { format } from "date-fns";

export function ServicesPage() {
  const { data: response, isLoading } = useServices();
  const services = response?.data || [];
  
  const roles = useRoles();
  const hasAdminRole = roles.includes("ADMIN");
  const { mutate: updateStatus } = useUpdateServiceStatus();

  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [newApiKey, setNewApiKey] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const form = useForm<CreateServiceFormValues>({
    resolver: zodResolver(createServiceSchema),
    defaultValues: {
      name: "",
      teamOwner: "",
      environment: "",
    },
  });

  const { mutate: createService, isPending: isCreating } = useCreateService((apiKey) => {
    setNewApiKey(apiKey);
    setIsCreateOpen(false);
    form.reset();
  });

  const onSubmit = (data: CreateServiceFormValues) => {
    createService(data);
  };

  const copyToClipboard = () => {
    if (newApiKey) {
      navigator.clipboard.writeText(newApiKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const closeApiKeyModal = () => {
    setNewApiKey(null);
  };

  return (
    <div className="flex flex-col h-full w-full gap-6 p-4">
      <div className="flex justify-between items-center">
        <div>
          <h2 className="text-3xl font-bold uppercase tracking-wider text-white">Services</h2>
          <p className="text-gray-400 text-sm mt-1">Manage observability for registered services</p>
        </div>
        {hasAdminRole && (
          <Dialog open={isCreateOpen} onOpenChange={setIsCreateOpen}>
            <DialogTrigger asChild>
              <Button className="bg-primary/20 text-primary hover:bg-primary/30 border border-primary/50">
                <Plus className="w-4 h-4 mr-2" />
                Register Service
              </Button>
            </DialogTrigger>
            <DialogContent className="sm:max-w-[425px] bg-black/90 border-white/10 text-white">
              <DialogHeader>
                <DialogTitle>Register New Service</DialogTitle>
                <DialogDescription className="text-gray-400">
                  Register a service to ingest logs, metrics, and traces.
                </DialogDescription>
              </DialogHeader>
              <Form {...form}>
                <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
                  <FormField
                    control={form.control}
                    name="name"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Service Name</FormLabel>
                        <FormControl>
                          <Input placeholder="e.g. payment-service" className="bg-white/5 border-white/10" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="teamOwner"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Team Owner</FormLabel>
                        <FormControl>
                          <Input placeholder="e.g. checkout-team" className="bg-white/5 border-white/10" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="environment"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Environment</FormLabel>
                        <FormControl>
                          <Input placeholder="e.g. production" className="bg-white/5 border-white/10" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <DialogFooter className="mt-6">
                    <Button type="button" variant="ghost" onClick={() => setIsCreateOpen(false)}>Cancel</Button>
                    <Button type="submit" disabled={isCreating} className="bg-primary text-black hover:bg-primary/90">
                      {isCreating ? "Registering..." : "Register"}
                    </Button>
                  </DialogFooter>
                </form>
              </Form>
            </DialogContent>
          </Dialog>
        )}
      </div>

      <div className="glass-panel rounded-lg border border-white/10 bg-black/40 overflow-hidden flex-1 flex flex-col">
        <div className="overflow-x-auto flex-1">
          <Table className="min-w-[800px]">
          <TableHeader className="bg-white/5 sticky top-0">
            <TableRow className="border-b border-white/10 hover:bg-transparent">
              <TableHead className="text-gray-400">Service ID</TableHead>
              <TableHead className="text-gray-400">Name</TableHead>
              <TableHead className="text-gray-400">Environment</TableHead>
              <TableHead className="text-gray-400">Owner</TableHead>
              <TableHead className="text-gray-400">Created At</TableHead>
              <TableHead className="text-gray-400">Status</TableHead>
              {hasAdminRole && <TableHead className="text-gray-400 text-right">Actions</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={7} className="h-24 text-center text-gray-500">Loading services...</TableCell>
              </TableRow>
            ) : services.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="h-24 text-center text-gray-500">No services found.</TableCell>
              </TableRow>
            ) : (
              services.map((svc) => (
                <TableRow key={svc.id} className="border-b border-white/5 hover:bg-white/5">
                  <TableCell className="font-mono text-sm text-gray-300">{svc.id}</TableCell>
                  <TableCell className="font-medium text-white">{svc.name}</TableCell>
                  <TableCell>
                    <Badge variant="outline" className="bg-blue-500/10 text-blue-400 border-blue-500/20">{svc.environment}</Badge>
                  </TableCell>
                  <TableCell className="text-gray-400">{svc.teamOwner}</TableCell>
                  <TableCell className="text-gray-400 text-sm">
                    {format(new Date(svc.createdAt), "MMM dd, yyyy HH:mm")}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className={
                      svc.status === 'ACTIVE' ? "bg-green-500/10 text-green-400 border-green-500/20" :
                      svc.status === 'DISABLED' ? "bg-red-500/10 text-red-400 border-red-500/20" :
                      "bg-gray-500/10 text-gray-400 border-gray-500/20"
                    }>
                      {svc.status}
                    </Badge>
                  </TableCell>
                  {hasAdminRole && (
                    <TableCell className="text-right">
                      {svc.status === 'ACTIVE' ? (
                        <Button variant="outline" size="sm" onClick={() => updateStatus({ id: svc.id, status: 'DISABLED' })} className="text-red-400 border-red-400/50 hover:bg-red-400/10 h-8">
                          Disable
                        </Button>
                      ) : (
                        <Button variant="outline" size="sm" onClick={() => updateStatus({ id: svc.id, status: 'ACTIVE' })} className="text-green-400 border-green-400/50 hover:bg-green-400/10 h-8">
                          Enable
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        </div>
      </div>

      {/* API Key Modal */}
      <Dialog open={!!newApiKey} onOpenChange={(open) => !open && closeApiKeyModal()}>
        <DialogContent className="sm:max-w-[500px] bg-black/95 border-red-500/50 text-white">
          <DialogHeader>
            <DialogTitle className="text-red-400 flex items-center gap-2">
              <Server className="w-5 h-5" />
              Service Registered Successfully
            </DialogTitle>
            <DialogDescription className="text-gray-300 pt-2">
              Please copy your API Key now. <strong className="text-red-400">It will only be displayed once.</strong> We do not store this plaintext key.
            </DialogDescription>
          </DialogHeader>
          
          <div className="bg-black/50 border border-white/10 rounded-md p-4 flex items-center justify-between mt-4">
            <code className="text-primary font-mono text-sm break-all">
              {newApiKey}
            </code>
            <Button size="icon" variant="ghost" onClick={copyToClipboard} className="ml-4 shrink-0 text-gray-400 hover:text-white">
              {copied ? <Check className="w-4 h-4 text-green-400" /> : <Copy className="w-4 h-4" />}
            </Button>
          </div>
          
          <DialogFooter className="mt-6">
            <Button type="button" variant="outline" onClick={closeApiKeyModal} className="border-white/20">
              I have saved the key
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
