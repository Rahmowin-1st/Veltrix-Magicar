import { randomUUID } from 'node:crypto';

import type { UltronBackend } from './backend.js';
import type { DeviceBinding } from './device-auth.js';
import { assertIdempotencyFingerprint, taskIdempotencyFingerprint } from './idempotency.js';
import type {
  ControlProfile,
  ControlRequestReceipt,
  DeviceInfo,
  PrincipalBinding,
  PrincipalKind,
  SubmitTaskInput,
  TaskReceipt,
  TaskState,
  UltronBackendCapabilities
} from './types.js';

interface BridgeTask extends TaskReceipt {
  principalKind: PrincipalKind;
  principalDisplayName: string;
  objective: string;
  target: SubmitTaskInput['target'];
  requiredCapabilities: readonly string[];
  constraints: readonly string[];
  deviceOwnerPrincipalId: string;
  allowedDeviceIds: readonly string[];
}

interface DeviceRuntime {
  info: DeviceInfo;
  lastSeenAt: number;
}

export interface DeviceHeartbeatInput {
  platform: string;
  availableCapabilities: readonly string[];
  grantedCapabilities: readonly string[];
}

export interface DeviceTaskLease {
  taskId: string;
  principalId: string;
  principalKind: PrincipalKind;
  principalDisplayName: string;
  objective: string;
  constraints: readonly string[];
  requiredCapabilities: readonly string[];
  controlProfile: ControlProfile;
  state: TaskState;
  createdAt: string;
  updatedAt: string;
}

export interface DeviceTaskControl {
  taskId: string;
  state: TaskState;
  narration: string;
  updatedAt: string;
}

export interface DeviceTaskUpdate {
  state: Extract<TaskState, 'RUNNING' | 'WAITING_FOR_USER' | 'PAUSED' | 'VERIFIED_DONE' | 'FAILED' | 'CANCELLED'>;
  narration: string;
  evidence?: readonly string[];
}

/**
 * Single-process bridge for MCP -> device execution.
 * Remote principals submit objectives only; Android remains authoritative for
 * per-action policy, owner approval, local execution and proof-of-done.
 */
export class BridgeUltronBackend implements UltronBackend {
  private readonly tasks = new Map<string, BridgeTask>();
  private readonly idempotency = new Map<string, { taskId: string; fingerprint: string }>();
  private readonly devices = new Map<string, DeviceRuntime>();

  async submitTask(binding: PrincipalBinding, input: SubmitTaskInput): Promise<TaskReceipt> {
    const objective = input.objective.trim();
    if (!objective) throw new Error('objective is required');
    const fingerprint = input.idempotencyKey ? taskIdempotencyFingerprint(binding, input) : undefined;
    if (input.idempotencyKey && fingerprint) {
      const dedupeKey = `${binding.principalId}|${input.idempotencyKey}`;
      const existing = this.idempotency.get(dedupeKey);
      if (existing) {
        assertIdempotencyFingerprint(existing.fingerprint, fingerprint);
        return this.requireOwnedTask(binding, existing.taskId);
      }
    }

    const device = this.selectDevice(binding, input);
    const now = new Date().toISOString();
    const task: BridgeTask = {
      taskId: randomUUID(),
      principalId: binding.principalId,
      principalKind: binding.principalKind,
      principalDisplayName: binding.displayName,
      state: device && this.withPresence(device).presence === 'ONLINE' ? 'RECEIVED' : 'WAITING_FOR_DEVICE',
      narration: device
        ? this.withPresence(device).presence === 'ONLINE'
          ? `Accepted for ${device.info.displayName}`
          : `Waiting for ${device.info.displayName}`
        : 'Waiting for a matching delegated device',
      ...(device ? { deviceId: device.info.id } : {}),
      evidence: [],
      createdAt: now,
      updatedAt: now,
      objective,
      target: input.target,
      requiredCapabilities: [...input.requiredCapabilities],
      constraints: [...input.constraints],
      deviceOwnerPrincipalId: binding.deviceOwnerPrincipalId,
      allowedDeviceIds: [...binding.allowedDeviceIds]
    };
    this.tasks.set(task.taskId, task);
    if (input.idempotencyKey && fingerprint) {
      this.idempotency.set(`${binding.principalId}|${input.idempotencyKey}`, { taskId: task.taskId, fingerprint });
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
    if (isTerminal(task.state)) throw new Error(`Cannot pause terminal task in state ${task.state}`);
    return this.updateTask(task, 'PAUSED', 'Paused by authenticated principal');
  }

  async resumeTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    const task = this.requireOwnedTask(binding, taskId);
    if (task.state !== 'PAUSED' && task.state !== 'WAITING_FOR_USER' && task.state !== 'WAITING_FOR_DEVICE') {
      throw new Error(`Cannot resume task in state ${task.state}`);
    }
    const runtime = task.deviceId ? this.devices.get(task.deviceId) : undefined;
    const online = runtime && this.withPresence(runtime).presence === 'ONLINE';
    const state: TaskState = online && task.deviceId ? 'RUNNING' : 'WAITING_FOR_DEVICE';
    return this.updateTask(task, state, state === 'RUNNING' ? 'Resume requested on device' : 'Waiting for device');
  }

  async cancelTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    const task = this.requireOwnedTask(binding, taskId);
    if (task.state === 'VERIFIED_DONE') throw new Error('Verified task cannot be cancelled');
    return this.updateTask(task, 'CANCELLED', 'Cancelled by authenticated principal');
  }

  async listDevices(binding: PrincipalBinding): Promise<readonly DeviceInfo[]> {
    return [...this.devices.values()]
      .filter(runtime => this.deviceVisible(binding, runtime.info))
      .map(runtime => this.withPresence(runtime));
  }

  async getDevice(binding: PrincipalBinding, deviceId: string): Promise<DeviceInfo | undefined> {
    const runtime = this.devices.get(deviceId);
    if (!runtime || !this.deviceVisible(binding, runtime.info)) return undefined;
    return this.withPresence(runtime);
  }

  async requestControl(
    binding: PrincipalBinding,
    deviceId: string,
    profile: ControlProfile,
    _reason: string
  ): Promise<ControlRequestReceipt> {
    const runtime = this.devices.get(deviceId);
    if (!runtime || !this.deviceVisible(binding, runtime.info)) {
      return { deviceId, state: 'DENIED', effectiveProfile: 'READ_ONLY', ownerApprovalRequired: false, message: 'Device is outside authenticated scope' };
    }
    if (binding.principalId !== runtime.info.ownerPrincipalId) {
      return {
        deviceId,
        state: 'WAITING_FOR_OWNER',
        effectiveProfile: runtime.info.controlProfile,
        ownerApprovalRequired: true,
        message: 'Owner approval required; remote principal cannot elevate device control'
      };
    }
    runtime.info = { ...runtime.info, controlProfile: profile };
    return { deviceId, state: 'UPDATED', effectiveProfile: profile, ownerApprovalRequired: false, message: 'Control profile updated by device owner' };
  }

  async capabilities(): Promise<UltronBackendCapabilities> {
    return {
      supportsPhone: true,
      supportsDesktop: true,
      supportsCloud: true,
      supportsLongRunningTasks: true,
      supportsEvidence: true,
      supportsPauseResume: true,
      backend: 'bridge'
    };
  }

  async health(): Promise<{ ok: boolean; detail: string }> {
    return { ok: true, detail: 'bridge ready' };
  }

  deviceHeartbeat(binding: DeviceBinding, input: DeviceHeartbeatInput): DeviceInfo {
    const available = uniqueStrings(input.availableCapabilities);
    const granted = uniqueStrings(input.grantedCapabilities).filter(capability => available.includes(capability));
    const effective = granted.filter(capability => binding.allowedCapabilities.includes(capability));
    const existing = this.devices.get(binding.deviceId);
    const info: DeviceInfo = {
      id: binding.deviceId,
      ownerPrincipalId: binding.ownerPrincipalId,
      kind: binding.kind,
      platform: input.platform.trim() || existing?.info.platform || 'UNKNOWN',
      displayName: binding.displayName,
      presence: 'ONLINE',
      controlProfile: existing?.info.controlProfile ?? binding.controlProfile,
      availableCapabilities: available,
      grantedCapabilities: granted,
      effectiveCapabilities: effective
    };
    const runtime: DeviceRuntime = { info, lastSeenAt: Date.now() };
    this.devices.set(binding.deviceId, runtime);
    this.assignWaitingTasks(runtime);
    return this.withPresence(runtime);
  }

  deviceClaimNext(binding: DeviceBinding): DeviceTaskLease | undefined {
    const runtime = this.requireDevice(binding);
    runtime.lastSeenAt = Date.now();
    this.assignWaitingTasks(runtime);
    const task = [...this.tasks.values()].find(candidate => candidate.deviceId === binding.deviceId && candidate.state === 'RECEIVED');
    if (!task) return undefined;
    const running = this.updateTaskInternal(task, 'RUNNING', `Claimed by ${runtime.info.displayName}`);
    return {
      taskId: running.taskId,
      principalId: running.principalId,
      principalKind: running.principalKind,
      principalDisplayName: running.principalDisplayName,
      objective: running.objective,
      constraints: [...running.constraints],
      requiredCapabilities: [...running.requiredCapabilities],
      controlProfile: runtime.info.controlProfile,
      state: running.state,
      createdAt: running.createdAt,
      updatedAt: running.updatedAt
    };
  }

  deviceInspectTask(binding: DeviceBinding, taskId: string): DeviceTaskControl {
    const runtime = this.requireDevice(binding);
    runtime.lastSeenAt = Date.now();
    const task = this.tasks.get(taskId);
    if (!task || task.deviceId !== binding.deviceId) throw new Error('Task is not assigned to this device');
    return { taskId, state: task.state, narration: task.narration, updatedAt: task.updatedAt };
  }

  deviceUpdateTask(binding: DeviceBinding, taskId: string, update: DeviceTaskUpdate): TaskReceipt {
    const runtime = this.requireDevice(binding);
    runtime.lastSeenAt = Date.now();
    const task = this.tasks.get(taskId);
    if (!task || task.deviceId !== binding.deviceId) throw new Error('Task is not assigned to this device');
    if (isTerminal(task.state)) throw new Error(`Cannot update terminal task in state ${task.state}`);
    if (task.state === 'PAUSED' && update.state !== 'PAUSED' && update.state !== 'CANCELLED') {
      throw new Error('Paused task must be resumed by the authenticated principal or cancelled before device progress');
    }
    const evidence = uniqueStrings(update.evidence ?? []);
    if (update.state === 'VERIFIED_DONE' && evidence.length === 0) throw new Error('VERIFIED_DONE requires evidence');
    const next: BridgeTask = {
      ...task,
      state: update.state,
      narration: update.narration.trim() || update.state,
      evidence: evidence.length > 0 ? evidence : [...task.evidence],
      updatedAt: new Date().toISOString()
    };
    this.tasks.set(taskId, next);
    return this.publicReceipt(next);
  }

  private selectDevice(binding: PrincipalBinding, input: SubmitTaskInput): DeviceRuntime | undefined {
    const required = new Set(input.requiredCapabilities);
    const visible = [...this.devices.values()].filter(runtime => this.deviceVisible(binding, runtime.info));
    const capable = visible.filter(runtime => [...required].every(capability => runtime.info.effectiveCapabilities.includes(capability)));
    if (input.deviceId) {
      const explicit = capable.find(runtime => runtime.info.id === input.deviceId);
      if (!explicit) throw new Error('Requested device is outside scope or lacks required granted capabilities');
      return explicit;
    }
    const kind = input.target === 'phone' ? 'PHONE' : input.target === 'desktop' ? 'DESKTOP' : input.target === 'cloud' ? 'CLOUD' : undefined;
    const candidates = kind ? capable.filter(runtime => runtime.info.kind === kind) : capable;
    return candidates.find(runtime => this.withPresence(runtime).presence === 'ONLINE') ?? candidates[0];
  }

  private assignWaitingTasks(runtime: DeviceRuntime): void {
    for (const task of this.tasks.values()) {
      if (task.state !== 'WAITING_FOR_DEVICE') continue;
      if (task.deviceOwnerPrincipalId !== runtime.info.ownerPrincipalId) continue;
      if (task.allowedDeviceIds.length > 0 && !task.allowedDeviceIds.includes(runtime.info.id)) continue;
      if (task.target === 'phone' && runtime.info.kind !== 'PHONE') continue;
      if (task.target === 'desktop' && runtime.info.kind !== 'DESKTOP') continue;
      if (task.target === 'cloud' && runtime.info.kind !== 'CLOUD') continue;
      if (!task.requiredCapabilities.every(capability => runtime.info.effectiveCapabilities.includes(capability))) continue;
      const next: BridgeTask = {
        ...task,
        deviceId: runtime.info.id,
        state: 'RECEIVED',
        narration: `Accepted for ${runtime.info.displayName}`,
        updatedAt: new Date().toISOString()
      };
      this.tasks.set(task.taskId, next);
    }
  }

  private requireDevice(binding: DeviceBinding): DeviceRuntime {
    const runtime = this.devices.get(binding.deviceId);
    if (!runtime || runtime.info.ownerPrincipalId !== binding.ownerPrincipalId) throw new Error('Device must heartbeat before claiming tasks');
    return runtime;
  }

  private deviceVisible(binding: PrincipalBinding, device: DeviceInfo): boolean {
    return device.ownerPrincipalId === binding.deviceOwnerPrincipalId &&
      (binding.allowedDeviceIds.length === 0 || binding.allowedDeviceIds.includes(device.id));
  }

  private requireOwnedTask(binding: PrincipalBinding, taskId: string): BridgeTask {
    const task = this.tasks.get(taskId);
    if (!task) throw new Error('Task not found');
    this.assertTaskOwner(binding, task);
    return task;
  }

  private assertTaskOwner(binding: PrincipalBinding, task: BridgeTask): void {
    if (task.principalId !== binding.principalId) throw new Error('Task belongs to another authenticated principal');
  }

  private updateTask(task: BridgeTask, state: TaskState, narration: string): TaskReceipt {
    return this.publicReceipt(this.updateTaskInternal(task, state, narration));
  }

  private updateTaskInternal(task: BridgeTask, state: TaskState, narration: string): BridgeTask {
    const next = { ...task, state, narration, updatedAt: new Date().toISOString() };
    this.tasks.set(task.taskId, next);
    return next;
  }

  private publicReceipt(task: BridgeTask): TaskReceipt {
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

  private withPresence(runtime: DeviceRuntime): DeviceInfo {
    const presence = Date.now() - runtime.lastSeenAt <= 90_000 ? 'ONLINE' : 'OFFLINE';
    return { ...runtime.info, presence };
  }
}

function uniqueStrings(values: readonly string[]): string[] {
  return [...new Set(values.map(value => value.trim()).filter(Boolean))];
}

function isTerminal(state: TaskState): boolean {
  return state === 'VERIFIED_DONE' || state === 'FAILED' || state === 'CANCELLED';
}
