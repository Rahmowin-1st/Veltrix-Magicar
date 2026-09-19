import type { UltronBackend } from './backend.js';
import type { DeviceBinding } from './device-auth.js';
import type { ControlProfile, DeviceInfo, TaskReceipt, TaskState } from './types.js';

export interface DeviceHeartbeatInput {
  platform: string;
  availableCapabilities: readonly string[];
  grantedCapabilities: readonly string[];
}

export interface DeviceTaskLease {
  taskId: string;
  principalId: string;
  principalKind: 'owner' | 'user' | 'agent' | 'system';
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

export interface DeviceBridgeBackend extends UltronBackend {
  deviceHeartbeat(binding: DeviceBinding, input: DeviceHeartbeatInput): DeviceInfo | Promise<DeviceInfo>;
  deviceClaimNext(binding: DeviceBinding): DeviceTaskLease | undefined | Promise<DeviceTaskLease | undefined>;
  deviceInspectTask(binding: DeviceBinding, taskId: string): DeviceTaskControl | Promise<DeviceTaskControl>;
  deviceUpdateTask(binding: DeviceBinding, taskId: string, update: DeviceTaskUpdate): TaskReceipt | Promise<TaskReceipt>;
}

export function isDeviceBridgeBackend(backend: UltronBackend): backend is DeviceBridgeBackend {
  const candidate = backend as Partial<DeviceBridgeBackend>;
  return typeof candidate.deviceHeartbeat === 'function' &&
    typeof candidate.deviceClaimNext === 'function' &&
    typeof candidate.deviceInspectTask === 'function' &&
    typeof candidate.deviceUpdateTask === 'function';
}
