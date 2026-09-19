import { useState } from "react";
import { useRoles } from "@/hooks/useRoles";
import { 
  useAlertRules, 
  useCreateAlertRule, 
  useUpdateAlertRule, 
  useToggleAlertRule, 
  useDeleteAlertRule 
} from "@/hooks/useAlertRules";
import type { AlertRuleFormValues } from "@/schemas/alert-rule.schema";
import { alertRuleSchema } from "@/schemas/alert-rule.schema";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, useFieldArray } from "react-hook-form";
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { Switch } from "@/components/ui/switch";
import { Plus, Edit2, Trash2 } from "lucide-react";
import { format } from "date-fns";

export function AlertRulesPage() {
  const { data: response, isLoading } = useAlertRules();
  const rules = response?.data || [];
  
  const roles = useRoles();
  const hasWriteRole = roles.some((r: string) => ["ADMIN", "DEVOPS"].includes(r));
  
  const { mutate: toggleRule } = useToggleAlertRule();
  const { mutate: deleteRule } = useDeleteAlertRule();

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingRuleId, setEditingRuleId] = useState<number | null>(null);

  const form = useForm<AlertRuleFormValues>({
    resolver: zodResolver(alertRuleSchema),
    defaultValues: {
      ruleName: "",
      serviceId: "",
      environment: "",
      conditionType: "ERROR_SPIKE",
      thresholdValue: 10,
      windowSeconds: 60,
      conditionValue: "",
      severity: "HIGH",
      notificationChannels: [],
    },
  });

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "notificationChannels",
  });

  const watchConditionType = form.watch("conditionType");

  const { mutate: createRule, isPending: isCreating } = useCreateAlertRule(() => setIsModalOpen(false));
  const { mutate: updateRule, isPending: isUpdating } = useUpdateAlertRule(() => setIsModalOpen(false));

  const onSubmit = (data: AlertRuleFormValues) => {
    if (editingRuleId) {
      updateRule({ id: editingRuleId, payload: data });
    } else {
      createRule(data);
    }
  };

  const openCreateModal = () => {
    setEditingRuleId(null);
    form.reset({
      ruleName: "",
      serviceId: "",
      environment: "",
      conditionType: "ERROR_SPIKE",
      thresholdValue: 10,
      windowSeconds: 60,
      conditionValue: "",
      severity: "HIGH",
      notificationChannels: [],
    });
    setIsModalOpen(true);
  };

  const openEditModal = (rule: any) => {
    setEditingRuleId(rule.id);
    form.reset({
      ruleName: rule.ruleName,
      serviceId: rule.serviceId,
      environment: rule.environment,
      conditionType: rule.conditionType,
      thresholdValue: rule.thresholdValue || 10,
      windowSeconds: rule.windowSeconds || 60,
      conditionValue: rule.conditionValue || "",
      severity: rule.severity,
      notificationChannels: rule.notificationChannels || [],
    });
    setIsModalOpen(true);
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

  return (
    <div className="flex flex-col h-full w-full gap-6 p-4">
      <div className="flex justify-between items-center">
        <div>
          <h2 className="text-3xl font-bold uppercase tracking-wider text-white">Alert Rules</h2>
          <p className="text-gray-400 text-sm mt-1">Configure automated alert triggers for log events</p>
        </div>
        {hasWriteRole && (
          <Button onClick={openCreateModal} className="bg-primary/20 text-primary hover:bg-primary/30 border border-primary/50">
            <Plus className="w-4 h-4 mr-2" />
            Create Rule
          </Button>
        )}
      </div>

      <div className="glass-panel rounded-lg border border-white/10 bg-black/40 overflow-hidden flex-1 flex flex-col">
        <div className="overflow-x-auto flex-1">
          <Table className="min-w-[800px]">
          <TableHeader className="bg-white/5 sticky top-0">
            <TableRow className="border-b border-white/10 hover:bg-transparent">
              <TableHead className="text-gray-400">Name</TableHead>
              <TableHead className="text-gray-400">Target</TableHead>
              <TableHead className="text-gray-400">Condition</TableHead>
              <TableHead className="text-gray-400">Severity</TableHead>
              <TableHead className="text-gray-400">Created</TableHead>
              <TableHead className="text-gray-400">Status</TableHead>
              {hasWriteRole && <TableHead className="text-gray-400 text-right">Actions</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={7} className="h-24 text-center text-gray-500">Loading alert rules...</TableCell>
              </TableRow>
            ) : rules.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="h-24 text-center text-gray-500">No alert rules found.</TableCell>
              </TableRow>
            ) : (
              rules.map((rule) => (
                <TableRow key={rule.id} className="border-b border-white/5 hover:bg-white/5">
                  <TableCell className="font-medium text-white">
                    {rule.ruleName}
                  </TableCell>
                  <TableCell>
                    <div className="text-sm text-gray-300">{rule.serviceId}</div>
                    <div className="text-xs text-gray-500">{rule.environment}</div>
                  </TableCell>
                  <TableCell>
                    <div className="text-xs text-gray-400 font-mono">
                      {rule.conditionType === 'ERROR_SPIKE' && `Errors >= ${rule.thresholdValue} in ${rule.windowSeconds}s`}
                      {rule.conditionType === 'PATTERN_MATCH' && `Pattern == "${rule.conditionValue}"`}
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className={`uppercase text-[10px] tracking-wider ${getSeverityColor(rule.severity)}`}>
                      {rule.severity}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-gray-400 text-xs">
                    {format(new Date(rule.createdAt), "MMM dd, yyyy HH:mm")}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center space-x-2">
                      <Switch 
                        checked={rule.isEnabled} 
                        disabled={!hasWriteRole}
                        onCheckedChange={(checked) => toggleRule({ id: rule.id, enabled: checked })} 
                      />
                      <span className="text-xs text-gray-400">{rule.isEnabled ? 'Enabled' : 'Disabled'}</span>
                    </div>
                  </TableCell>
                  {hasWriteRole && (
                    <TableCell className="text-right space-x-2">
                      <Button variant="ghost" size="icon" onClick={() => openEditModal(rule)} className="text-blue-400 hover:text-blue-300 hover:bg-blue-400/10">
                        <Edit2 className="w-4 h-4" />
                      </Button>
                      <Button variant="ghost" size="icon" onClick={() => {
                        if (confirm("Are you sure you want to delete this rule?")) {
                          deleteRule(rule.id);
                        }
                      }} className="text-red-400 hover:text-red-300 hover:bg-red-400/10">
                        <Trash2 className="w-4 h-4" />
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        </div>
      </div>

      <Dialog open={isModalOpen} onOpenChange={setIsModalOpen}>
        <DialogContent className="sm:max-w-[500px] bg-black/90 border-white/10 text-white max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{editingRuleId ? 'Edit Alert Rule' : 'Create Alert Rule'}</DialogTitle>
            <DialogDescription className="text-gray-400">
              Configure conditions to automatically trigger alerts.
            </DialogDescription>
          </DialogHeader>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
              <FormField
                control={form.control}
                name="ruleName"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Rule Name</FormLabel>
                    <FormControl>
                      <Input placeholder="e.g. Prod Database Error" className="bg-white/5 border-white/10" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <div className="grid grid-cols-2 gap-4">
                <FormField
                  control={form.control}
                  name="serviceId"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Service ID</FormLabel>
                      <FormControl>
                        <Input placeholder="e.g. payment-service" className="bg-white/5 border-white/10" {...field} />
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
              </div>
              <div className="grid grid-cols-2 gap-4">
                <FormField
                  control={form.control}
                  name="severity"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Severity</FormLabel>
                      <Select onValueChange={field.onChange} defaultValue={field.value}>
                        <FormControl>
                          <SelectTrigger className="bg-white/5 border-white/10">
                            <SelectValue placeholder="Select severity" />
                          </SelectTrigger>
                        </FormControl>
                        <SelectContent className="bg-black border-white/10 text-white">
                          <SelectItem value="CRITICAL">CRITICAL</SelectItem>
                          <SelectItem value="HIGH">HIGH</SelectItem>
                          <SelectItem value="MEDIUM">MEDIUM</SelectItem>
                          <SelectItem value="LOW">LOW</SelectItem>
                        </SelectContent>
                      </Select>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <FormField
                control={form.control}
                name="conditionType"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Condition Type</FormLabel>
                    <Select onValueChange={field.onChange} defaultValue={field.value}>
                      <FormControl>
                        <SelectTrigger className="bg-white/5 border-white/10">
                          <SelectValue placeholder="Select type" />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent className="bg-black border-white/10 text-white">
                        <SelectItem value="ERROR_SPIKE">Error Count Spike</SelectItem>
                        <SelectItem value="PATTERN_MATCH">Pattern Match (Message contains)</SelectItem>
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />
              {watchConditionType === 'PATTERN_MATCH' && (
                <FormField
                  control={form.control}
                  name="conditionValue"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Condition Value (Keyword)</FormLabel>
                      <FormControl>
                        <Input placeholder="e.g. NullPointerException" className="bg-white/5 border-white/10" {...field} value={field.value || ""} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              )}
              {watchConditionType === 'ERROR_SPIKE' && (
                <div className="grid grid-cols-2 gap-4">
                  <FormField
                    control={form.control}
                    name="thresholdValue"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Threshold Value</FormLabel>
                        <FormControl>
                          <Input type="number" className="bg-white/5 border-white/10" {...field} onChange={e => field.onChange(parseInt(e.target.value) || 0)} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="windowSeconds"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Window Seconds</FormLabel>
                        <FormControl>
                          <Input type="number" className="bg-white/5 border-white/10" {...field} onChange={e => field.onChange(parseInt(e.target.value) || 0)} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              )}
              
              <div className="space-y-4 pt-2 border-t border-white/10">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-medium leading-none">Notification Channels</label>
                  <Button type="button" variant="outline" size="sm" onClick={() => append({ channelType: 'WEBHOOK', target: '', enabled: true })} className="h-8 border-white/10 bg-white/5">
                    <Plus className="w-3 h-3 mr-2" /> Add Channel
                  </Button>
                </div>
                {fields.map((field, index) => (
                  <div key={field.id} className="flex gap-2 items-start">
                    <FormField
                      control={form.control}
                      name={`notificationChannels.${index}.channelType`}
                      render={({ field: f }) => (
                        <FormItem className="w-1/3">
                          <Select onValueChange={f.onChange} defaultValue={f.value}>
                            <FormControl>
                              <SelectTrigger className="bg-white/5 border-white/10 h-10">
                                <SelectValue placeholder="Type" />
                              </SelectTrigger>
                            </FormControl>
                            <SelectContent className="bg-black border-white/10 text-white">
                              <SelectItem value="WEBHOOK">Webhook</SelectItem>
                              <SelectItem value="SLACK">Slack</SelectItem>
                              <SelectItem value="TELEGRAM">Telegram</SelectItem>
                              <SelectItem value="EMAIL">Email</SelectItem>
                            </SelectContent>
                          </Select>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                    <FormField
                      control={form.control}
                      name={`notificationChannels.${index}.target`}
                      render={({ field: f }) => (
                        <FormItem className="flex-1">
                          <FormControl>
                            <Input placeholder="URL or ID" className="bg-white/5 border-white/10 h-10" {...f} />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                    <Button type="button" variant="ghost" size="icon" onClick={() => remove(index)} className="h-10 w-10 text-red-400 hover:text-red-300 hover:bg-red-400/10">
                      <Trash2 className="w-4 h-4" />
                    </Button>
                  </div>
                ))}
              </div>

              <DialogFooter className="mt-6">
                <Button type="button" variant="ghost" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                <Button type="submit" disabled={isCreating || isUpdating} className="bg-primary text-black hover:bg-primary/90">
                  {editingRuleId ? (isUpdating ? "Saving..." : "Save Changes") : (isCreating ? "Creating..." : "Create Rule")}
                </Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
