export type PrincipalKind = 'owner' | 'user' | 'agent' | 'system';
export type TaskTarget = 'auto' | 'phone' | 'desktop' | 'cloud';
export type TaskState =
  | 'RECEIVED'
  | 'RUNNING'
  | 'WAITING_FOR_USER'
  | 'WAITING_FOR_DEVICE'
  | 'PAUSED'
  | 'VERIFIED_DONE'
  | 'FAILED'
  | 'CANCELLED';
export type DeviceKind = 'PHONE' | 'DESKTOP' | 'CLOUD';
export type DevicePresence = 'ONLINE' | 'OFFLINE' | 'DEGRADED';
export type ControlProfile = 'READ_ONLY' | 'ASK_EACH_ACTION' | 'MAX_APPROVED';

export interface PrincipalBinding {
  clientId: string;
  principalId: string;
  principalKind: PrincipalKind;
  displayName: string;
  deviceOwnerPrincipalId: string;
  allowedDeviceIds: readonly string[];
  scopes: readonly string[];
}

export interface SubmitTaskInput {
  objective: string;
  target: TaskTarget;
  deviceId?: string;
  requiredCapabilities: readonly string[];
  constraints: readonly string[];
  idempotencyKey?: string;
}

export interface TaskReceipt {
  taskId: string;
  principalId: string;
  state: TaskState;
  narration: string;
  deviceId?: string;
  evidence: readonly string[];
  createdAt: string;
  updatedAt: string;
}

export interface DeviceInfo {
  id: string;
  ownerPrincipalId: string;
  kind: DeviceKind;
  platform: string;
  displayName: string;
  presence: DevicePresence;
  controlProfile: ControlProfile;
  availableCapabilities: readonly string[];
  grantedCapabilities: readonly string[];
  effectiveCapabilities: readonly string[];
}

export interface ControlRequestReceipt {
  deviceId: string;
  state: 'UPDATED' | 'WAITING_FOR_OWNER' | 'DENIED';
  effectiveProfile: ControlProfile;
  ownerApprovalRequired: boolean;
  message: string;
}

export interface UltronBackendCapabilities {
  supportsPhone: boolean;
  supportsDesktop: boolean;
  supportsCloud: boolean;
  supportsLongRunningTasks: boolean;
  supportsEvidence: boolean;
  supportsPauseResume: boolean;
  backend: string;
}
