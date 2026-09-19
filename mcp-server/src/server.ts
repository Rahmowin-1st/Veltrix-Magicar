import type { AuthInfo, McpServerFactory } from '@modelcontextprotocol/server';
import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

import { AiBrainRuntime } from './ai-brain-runtime.js';
import { aiProvidersFromEnv } from './ai-planner.js';
import { bindingFromAuthInfo, type TokenBindingRegistry } from './auth.js';
import type { UltronBackend } from './backend.js';
import { MainAssistantOrchestrator, type AuthorizedMemoryRetriever } from './main-assistant-orchestrator.js';
import { safeHealthDetail, safePublicErrorCode } from './safe-errors.js';
import type { SubmissionRateLimiter } from './submission-rate-limit.js';
import type { ControlProfile, PrincipalBinding, SubmitTaskInput } from './types.js';

export const ULTRON_TOOL_NAMES = [
  'ultron.assistant',
  'ultron.submit_task',
  'ultron.get_task',
  'ultron.pause_task',
  'ultron.resume_task',
  'ultron.cancel_task',
  'ultron.list_devices',
  'ultron.device_capabilities',
  'ultron.request_control',
  'ultron.capabilities',
  'ultron.whoami',
  'ultron.health'
] as const;

const taskIdSchema = z.object({ task_id: z.string().min(1) });
const objectiveSchema = z.string().min(1).max(16_000);
const constraintSchema = z.string().max(2_000);
const assistantInputSchema = z.object({ message: z.string().min(1).max(16_000) }).strict();

export function createUltronMcpServer(
  backend: UltronBackend,
  binding: PrincipalBinding,
  submissionLimiter?: SubmissionRateLimiter,
  assistant?: MainAssistantOrchestrator,
  assistantLimiter?: SubmissionRateLimiter,
  mutationLimiter?: SubmissionRateLimiter
): McpServer {
  const server = new McpServer({ name: 'veltrix-ultron', version: '0.1.0' });

  server.registerTool(
    'ultron.assistant',
    {
      description:
        'Ask the bounded Veltrix Main Assistant for an answer or inert typed task proposal. This tool never executes device actions.',
      inputSchema: assistantInputSchema
    },
    async ({ message }) => {
      if (!assistant) return toolFailure('assistant_unavailable');
      try {
        assistantLimiter?.assertAllowed(binding.principalId);
        return toolJson(await assistant.handle(message));
      } catch (error) {
        return toolFailure(safePublicErrorCode(error, 'assistant_failed'));
      }
    }
  );

  server.registerTool(
    'ultron.submit_task',
    {
      description: 'Submit an objective to ULTRON for policy-controlled phone, desktop or cloud execution.',
      inputSchema: z.object({
        objective: objectiveSchema,
        target: z.enum(['auto', 'phone', 'desktop', 'cloud']).default('auto'),
        device_id: z.string().min(1).max(200).optional(),
        required_capabilities: z.array(z.string().min(1).max(200)).max(64).default([]),
        constraints: z.array(constraintSchema).max(64).default([]),
        idempotency_key: z.string().min(1).max(200).optional()
      })
    },
    async args => {
      const denied = requireScope(binding, 'ultron:tasks');
      if (denied) return denied;
      try {
        submissionLimiter?.assertAllowed(binding.principalId);
        const input: SubmitTaskInput = {
          objective: args.objective,
          target: args.target,
          requiredCapabilities: args.required_capabilities,
          constraints: args.constraints,
          ...(args.device_id ? { deviceId: args.device_id } : {}),
          ...(args.idempotency_key ? { idempotencyKey: args.idempotency_key } : {})
        };
        return toolJson(await backend.submitTask(binding, input));
      } catch (error) {
        return toolError(error, 'task_submit_failed');
      }
    }
  );

  server.registerTool(
    'ultron.get_task',
    { description: 'Read the current state and evidence for one task owned by this authenticated principal.', inputSchema: taskIdSchema },
    async ({ task_id }) => {
      const denied = requireScope(binding, 'ultron:tasks');
      if (denied) return denied;
      try {
        const receipt = await backend.getTask(binding, task_id);
        return receipt ? toolJson(receipt) : toolFailure('not_found');
      } catch (error) {
        return toolError(error, 'task_read_failed');
      }
    }
  );

  server.registerTool(
    'ultron.pause_task',
    { description: 'Pause a non-terminal ULTRON task.', inputSchema: taskIdSchema },
    async ({ task_id }) => taskMutation(binding, backend, 'pause', task_id, mutationLimiter)
  );

  server.registerTool(
    'ultron.resume_task',
    { description: 'Resume a paused or waiting ULTRON task.', inputSchema: taskIdSchema },
    async ({ task_id }) => taskMutation(binding, backend, 'resume', task_id, mutationLimiter)
  );

  server.registerTool(
    'ultron.cancel_task',
    { description: 'Cancel a non-completed ULTRON task.', inputSchema: taskIdSchema },
    async ({ task_id }) => taskMutation(binding, backend, 'cancel', task_id, mutationLimiter)
  );

  server.registerTool(
    'ultron.list_devices',
    { description: 'List phone, desktop and cloud devices visible to this authenticated principal.', inputSchema: z.object({}) },
    async () => {
      const denied = requireScope(binding, 'ultron:devices');
      if (denied) return denied;
      try {
        return toolJson(await backend.listDevices(binding));
      } catch (error) {
        return toolError(error, 'device_list_failed');
      }
    }
  );

  server.registerTool(
    'ultron.device_capabilities',
    {
      description: 'Inspect available, granted and effective capabilities of one delegated device.',
      inputSchema: z.object({ device_id: z.string().min(1) })
    },
    async ({ device_id }) => {
      const denied = requireScope(binding, 'ultron:devices');
      if (denied) return denied;
      try {
        const device = await backend.getDevice(binding, device_id);
        return device ? toolJson(device) : toolFailure('not_found_or_denied');
      } catch (error) {
        return toolError(error, 'device_read_failed');
      }
    }
  );

  server.registerTool(
    'ultron.request_control',
    {
      description:
        'Request a device control profile. Remote agents cannot directly elevate to MAX_APPROVED; owner approval remains authoritative.',
      inputSchema: z.object({
        device_id: z.string().min(1),
        profile: z.enum(['READ_ONLY', 'ASK_EACH_ACTION', 'MAX_APPROVED']),
        reason: z.string().min(1).max(1000)
      })
    },
    async ({ device_id, profile, reason }) => {
      const denied = requireScope(binding, 'ultron:control-request');
      if (denied) return denied;
      try {
        mutationLimiter?.assertAllowed(binding.principalId);
        return toolJson(await backend.requestControl(binding, device_id, profile as ControlProfile, reason));
      } catch (error) {
        return toolError(error, 'control_request_failed');
      }
    }
  );

  server.registerTool(
    'ultron.capabilities',
    { description: 'Report ULTRON gateway capabilities and the authenticated tool surface.', inputSchema: z.object({}) },
    async () => {
      try {
        return toolJson({
          mcpServer: 'veltrix-ultron',
          version: '0.1.0',
          tools: ULTRON_TOOL_NAMES,
          rawExecutorExposed: false,
          backend: await backend.capabilities()
        });
      } catch (error) {
        return toolError(error, 'capabilities_failed');
      }
    }
  );

  server.registerTool(
    'ultron.whoami',
    { description: 'Report the authenticated ULTRON principal and delegated device scope.', inputSchema: z.object({}) },
    async () =>
      toolJson({
        clientId: binding.clientId,
        principalId: binding.principalId,
        principalKind: binding.principalKind,
        displayName: binding.displayName,
        deviceOwnerPrincipalId: binding.deviceOwnerPrincipalId,
        allowedDeviceIds: binding.allowedDeviceIds,
        scopes: binding.scopes
      })
  );

  server.registerTool(
    'ultron.health',
    { description: 'Check MCP server/backend readiness without performing a device action.', inputSchema: z.object({}) },
    async () => {
      try {
        const health = await backend.health();
        return toolJson({ ok: health.ok, detail: safeHealthDetail(health.ok) });
      } catch {
        return toolJson({ ok: false, detail: 'unavailable' });
      }
    }
  );

  return server;
}

export function createHttpMcpFactory(
  backend: UltronBackend,
  registry: TokenBindingRegistry,
  submissionLimiter?: SubmissionRateLimiter,
  assistant?: MainAssistantOrchestrator,
  assistantLimiter?: SubmissionRateLimiter,
  mutationLimiter?: SubmissionRateLimiter
): McpServerFactory {
  const effectiveAssistant = assistant ?? createProductionMainAssistant();
  return ctx => {
    const binding = requireHttpBinding(registry, ctx.authInfo);
    return createUltronMcpServer(
      backend,
      binding,
      submissionLimiter,
      effectiveAssistant,
      assistantLimiter,
      mutationLimiter
    );
  };
}

function createProductionMainAssistant(): MainAssistantOrchestrator {
  const unavailableMemory: AuthorizedMemoryRetriever = {
    async search(): Promise<never> {
      throw new Error('authorized memory retrieval is not configured');
    }
  };
  return new MainAssistantOrchestrator(
    new AiBrainRuntime(aiProvidersFromEnv()),
    unavailableMemory
  );
}

function requireHttpBinding(registry: TokenBindingRegistry, authInfo: AuthInfo | undefined): PrincipalBinding {
  const binding = bindingFromAuthInfo(registry, authInfo);
  if (!binding) throw new Error('Authenticated MCP principal binding is missing');
  return binding;
}

async function taskMutation(
  binding: PrincipalBinding,
  backend: UltronBackend,
  operation: 'pause' | 'resume' | 'cancel',
  taskId: string,
  mutationLimiter?: SubmissionRateLimiter
) {
  const denied = requireScope(binding, 'ultron:tasks');
  if (denied) return denied;
  try {
    mutationLimiter?.assertAllowed(binding.principalId);
    const receipt =
      operation === 'pause'
        ? await backend.pauseTask(binding, taskId)
        : operation === 'resume'
          ? await backend.resumeTask(binding, taskId)
          : await backend.cancelTask(binding, taskId);
    return toolJson(receipt);
  } catch (error) {
    return toolError(error, 'task_mutation_failed');
  }
}

function requireScope(binding: PrincipalBinding, scope: string) {
  if (binding.scopes.includes(scope)) return undefined;
  return toolFailure(`insufficient_scope: requires ${scope}`);
}

function toolJson(value: unknown) {
  return {
    content: [{ type: 'text' as const, text: JSON.stringify(value) }]
  };
}

function toolFailure(message: string) {
  return {
    isError: true as const,
    content: [{ type: 'text' as const, text: message }]
  };
}

function toolError(error: unknown, fallback = 'operation_failed') {
  return toolFailure(safePublicErrorCode(error, fallback));
}
