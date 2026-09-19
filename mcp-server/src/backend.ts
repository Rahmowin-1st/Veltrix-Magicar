import { randomUUID } from 'node:crypto';

import { assertIdempotencyFingerprint, taskIdempotencyFingerprint } from './idempotency.js';
import type {
  ControlProfile,
  ControlRequestReceipt,
  DeviceInfo,
  PrincipalBinding,
  SubmitTaskInput,
  TaskReceipt,
  TaskState,
  UltronBackendCapabilities
} from './types.js';

export interface UltronBackend {
  submitTask(binding: PrincipalBinding, input: SubmitTaskInput): Promise<TaskReceipt>;
  getTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt | undefined>;
  pauseTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt>;
  resumeTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt>;
  cancelTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt>;
  listDevices(binding: PrincipalBinding): Promise<readonly DeviceInfo[]>;
  getDevice(binding: PrincipalBinding, deviceId: string): Promise<DeviceInfo | undefined>;
  requestControl(
    binding: PrincipalBinding,
    deviceId: string,
    profile: ControlProfile,
    reason: string
  ): Promise<ControlRequestReceipt>;
  capabilities(): Promise<UltronBackendCapabilities>;
  health(): Promise<{ ok: boolean; detail: string }>;
}

interface StoredTask extends TaskReceipt {
  idempotencyKey?: string;
  objective: string;
  target: SubmitTaskInput['target'];
  requiredCapabilities: readonly string[];
}

export class MemoryUltronBackend implements UltronBackend {
  private readonly tasks = new Map<string, StoredTask>();
  private readonly idempotency = new Map<string, { taskId: string; fingerprint: string }>();
  private readonly devices = new Map<string, DeviceInfo>();

  constructor(initialDevices: readonly DeviceInfo[] = []) {
    for (const device of initialDevices) this.devices.set(device.id, { ...device });
  }

  async submitTask(binding: PrincipalBinding, input: SubmitTaskInput): Promise<TaskReceipt> {
    const objective = input.objective.trim();
    if (!objective) throw new Error('objective is required');
    const fingerprint = input.idempotencyKey ? taskIdempotencyFingerprint(binding, input) : undefined;

    if (input.idempotencyKey && fingerprint) {
      const dedupe = `${binding.principalId}|${input.idempotencyKey}`;
      const existing = this.idempotency.get(dedupe);
      if (existing) {
        assertIdempotencyFingerprint(existing.fingerprint, fingerprint);
        return this.requireOwnedTask(binding, existing.taskId);
      }
    }

    const device = this.selectDevice(binding, input);
    const now = new Date().toISOString();
    const taskId = randomUUID();
    const state: TaskState = device?.presence === 'ONLINE' ? 'RECEIVED' : 'WAITING_FOR_DEVICE';
    const task: StoredTask = {
      taskId,
      principalId: binding.principalId,
      state,
      narration: device
        ? device.presence === 'ONLINE'
          ? `Accepted for ${device.displayName}`
          : `Waiting for ${device.displayName}`
        : 'Waiting for a matching delegated device',
      ...(device ? { deviceId: device.id } : {}),
      evidence: [],
      createdAt: now,
      updatedAt: now,
      ...(input.idempotencyKey ? { idempotencyKey: input.idempotencyKey } : {}),
      objective,
      target: input.target,
      requiredCapabilities: [...input.requiredCapabilities]
    };
    this.tasks.set(taskId, task);
    if (input.idempotencyKey && fingerprint) {
      this.idempotency.set(`${binding.principalId}|${input.idempotencyKey}`, { taskId, fingerprint });
    }
    return this.publicReceipt(task);
  }

  async getTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt | undefined> {
    const task = this.tasks.get(taskId);
    if (!task) return undefined;
    this.assertTaskOwner(binding, task);
    return this.publicReceipt(task);
  }

  async pauseTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    const task = this.requireOwnedTask(binding, taskId);
    if (task.state === 'VERIFIED_DONE' || task.state === 'FAILED' || task.state === 'CANCELLED') {
      throw new Error(`Cannot pause terminal task in state ${task.state}`);
    }
    return this.updateTask(task, 'PAUSED', 'Paused by authenticated principal');
  }

  async resumeTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    const task = this.requireOwnedTask(binding, taskId);
    if (task.state !== 'PAUSED' && task.state !== 'WAITING_FOR_USER' && task.state !== 'WAITING_FOR_DEVICE') {
      throw new Error(`Cannot resume task in state ${task.state}`);
    }
    const device = task.deviceId ? this.devices.get(task.deviceId) : undefined;
    const state: TaskState = device?.presence === 'ONLINE' ? 'RECEIVED' : 'WAITING_FOR_DEVICE';
    return this.updateTask(task, state, state === 'RECEIVED' ? 'Resumed' : 'Waiting for device');
  }

  async cancelTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    const task = this.requireOwnedTask(binding, taskId);
    if (task.state === 'VERIFIED_DONE') throw new Error('Verified task cannot be cancelled');
    return this.updateTask(task, 'CANCELLED', 'Cancelled by authenticated principal');
  }

  async listDevices(binding: PrincipalBinding): Promise<readonly DeviceInfo[]> {
    return [...this.devices.values()].filter(device => this.deviceVisible(binding, device));
  }

  async getDevice(binding: PrincipalBinding, deviceId: string): Promise<DeviceInfo | undefined> {
    const device = this.devices.get(deviceId);
    if (!device || !this.deviceVisible(binding, device)) return undefined;
    return { ...device };
  }

  async requestControl(
    binding: PrincipalBinding,
    deviceId: string,
    profile: ControlProfile,
    _reason: string
  ): Promise<ControlRequestReceipt> {
    const device = this.devices.get(deviceId);
    if (!device || !this.deviceVisible(binding, device)) {
      return {
        deviceId,
        state: 'DENIED',
        effectiveProfile: 'READ_ONLY',
        ownerApprovalRequired: false,
        message: 'Device is outside authenticated scope'
      };
    }

    if (binding.principalId !== device.ownerPrincipalId) {
      return {
        deviceId,
        state: 'WAITING_FOR_OWNER',
        effectiveProfile: device.controlProfile,
        ownerApprovalRequired: true,
        message: 'Owner approval required; remote principal cannot elevate device control'
      };
    }

    const updated: DeviceInfo = { ...device, controlProfile: profile };
    this.devices.set(deviceId, updated);
    return {
      deviceId,
      state: 'UPDATED',
      effectiveProfile: profile,
      ownerApprovalRequired: false,
      message: 'Control profile updated by device owner'
    };
  }

  async capabilities(): Promise<UltronBackendCapabilities> {
    return {
      supportsPhone: true,
      supportsDesktop: true,
      supportsCloud: true,
      supportsLongRunningTasks: true,
      supportsEvidence: true,
      supportsPauseResume: true,
      backend: 'memory'
    };
  }

  async health(): Promise<{ ok: boolean; detail: string }> {
    return { ok: true, detail: 'memory backend ready' };
  }

  /** Self-test/development hook only. */
  upsertDevice(device: DeviceInfo): void {
    this.devices.set(device.id, { ...device });
  }

  private selectDevice(binding: PrincipalBinding, input: SubmitTaskInput): DeviceInfo | undefined {
    const visible = [...this.devices.values()].filter(device => this.deviceVisible(binding, device));
    const required = new Set(input.requiredCapabilities);
    const capable = visible.filter(device =>
      [...required].every(capability => device.effectiveCapabilities.includes(capability))
    );

    if (input.deviceId) {
      const explicit = capable.find(device => device.id === input.deviceId);
      if (!explicit) throw new Error('Requested device is outside scope or lacks required granted capabilities');
      return explicit;
    }

    const kind = input.target === 'phone' ? 'PHONE' : input.target === 'desktop' ? 'DESKTOP' : input.target === 'cloud' ? 'CLOUD' : undefined;
    const candidates = kind ? capable.filter(device => device.kind === kind) : capable;
    return candidates.find(device => device.presence === 'ONLINE') ?? candidates[0];
  }

  private deviceVisible(binding: PrincipalBinding, device: DeviceInfo): boolean {
    return (
      device.ownerPrincipalId === binding.deviceOwnerPrincipalId &&
      (binding.allowedDeviceIds.length === 0 || binding.allowedDeviceIds.includes(device.id))
    );
  }

  private requireOwnedTask(binding: PrincipalBinding, taskId: string): StoredTask {
    const task = this.tasks.get(taskId);
    if (!task) throw new Error('Task not found');
    this.assertTaskOwner(binding, task);
    return task;
  }

  private assertTaskOwner(binding: PrincipalBinding, task: StoredTask): void {
    if (task.principalId !== binding.principalId) throw new Error('Task belongs to another authenticated principal');
  }

  private updateTask(task: StoredTask, state: TaskState, narration: string): TaskReceipt {
    const next: StoredTask = { ...task, state, narration, updatedAt: new Date().toISOString() };
    this.tasks.set(task.taskId, next);
    return this.publicReceipt(next);
  }

  private publicReceipt(task: StoredTask): TaskReceipt {
    return {
      taskId: task.taskId,
      principalId: task.principalId,
      state: task.state,
      narration: task.narration,
      ...(task.deviceId ? { deviceId: task.deviceId } : {}),
      evidence: [...task.evidence],
      createdAt: task.createdAt,
      updatedAt: task.updatedAt
    };
  }
}

interface HttpBackendOptions {
  baseUrl: string;
  serviceToken: string;
}

export class HttpUltronBackend implements UltronBackend {
  private readonly baseUrl: string;
  private readonly serviceToken: string;

  constructor(options: HttpBackendOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, '');
    this.serviceToken = options.serviceToken;
    if (!this.baseUrl) throw new Error('ULTRON Task API URL is required');
    if (!this.serviceToken) throw new Error('ULTRON Task API service token is required');
  }

  submitTask(binding: PrincipalBinding, input: SubmitTaskInput): Promise<TaskReceipt> {
    return this.request(binding, '/v1/tasks', { method: 'POST', body: input });
  }

  getTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt | undefined> {
    return this.requestOptional(binding, `/v1/tasks/${encodeURIComponent(taskId)}`);
  }

  pauseTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    return this.request(binding, `/v1/tasks/${encodeURIComponent(taskId)}/pause`, { method: 'POST' });
  }

  resumeTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    return this.request(binding, `/v1/tasks/${encodeURIComponent(taskId)}/resume`, { method: 'POST' });
  }

  cancelTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    return this.request(binding, `/v1/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' });
  }

  listDevices(binding: PrincipalBinding): Promise<readonly DeviceInfo[]> {
    return this.request(binding, '/v1/devices');
  }

  getDevice(binding: PrincipalBinding, deviceId: string): Promise<DeviceInfo | undefined> {
    return this.requestOptional(binding, `/v1/devices/${encodeURIComponent(deviceId)}`);
  }

  requestControl(
    binding: PrincipalBinding,
    deviceId: string,
    profile: ControlProfile,
    reason: string
  ): Promise<ControlRequestReceipt> {
    return this.request(binding, `/v1/devices/${encodeURIComponent(deviceId)}/control-requests`, {
      method: 'POST',
      body: { profile, reason }
    });
  }

  capabilities(): Promise<UltronBackendCapabilities> {
    return this.publicRequest('/v1/capabilities');
  }

  health(): Promise<{ ok: boolean; detail: string }> {
    return this.publicRequest('/health');
  }

  private async request<T>(
    binding: PrincipalBinding,
    path: string,
    options: { method?: string; body?: unknown } = {}
  ): Promise<T> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method: options.method ?? 'GET',
      headers: {
        authorization: `Bearer ${this.serviceToken}`,
        'content-type': 'application/json',
        'x-ultron-principal-id': binding.principalId,
        'x-ultron-principal-kind': binding.principalKind,
        'x-ultron-device-owner': binding.deviceOwnerPrincipalId,
        'x-ultron-device-scope': binding.allowedDeviceIds.join(',')
      },
      ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) })
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`ULTRON Task API ${response.status}: ${text.slice(0, 300)}`);
    }
    return (await response.json()) as T;
  }

  private async requestOptional<T>(binding: PrincipalBinding, path: string): Promise<T | undefined> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      headers: {
        authorization: `Bearer ${this.serviceToken}`,
        'x-ultron-principal-id': binding.principalId,
        'x-ultron-principal-kind': binding.principalKind,
        'x-ultron-device-owner': binding.deviceOwnerPrincipalId,
        'x-ultron-device-scope': binding.allowedDeviceIds.join(',')
      }
    });
    if (response.status === 404) return undefined;
    if (!response.ok) throw new Error(`ULTRON Task API ${response.status}`);
    return (await response.json()) as T;
  }

  private async publicRequest<T>(path: string): Promise<T> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      headers: { authorization: `Bearer ${this.serviceToken}` }
    });
    if (!response.ok) throw new Error(`ULTRON Task API ${response.status}`);
    return (await response.json()) as T;
  }
}

export function backendFromEnv(): UltronBackend {
  const mode = (process.env.ULTRON_BACKEND ?? '').trim().toLowerCase();
  const url = process.env.ULTRON_TASK_API_URL?.trim();
  if (mode === 'http' || url) {
    return new HttpUltronBackend({
      baseUrl: url ?? '',
      serviceToken: process.env.ULTRON_TASK_API_TOKEN?.trim() ?? ''
    });
  }
  return new MemoryUltronBackend();
}
