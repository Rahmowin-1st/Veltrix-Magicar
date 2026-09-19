import { randomUUID } from 'node:crypto';
import { Pool, type PoolClient, type QueryResultRow } from 'pg';

import type { DeviceBridgeBackend, DeviceHeartbeatInput, DeviceTaskControl, DeviceTaskLease, DeviceTaskUpdate } from './device-bridge.js';
import type { DeviceBinding } from './device-auth.js';
import { assertIdempotencyFingerprint, taskIdempotencyFingerprint } from './idempotency.js';
import type {
  ControlProfile,
  ControlRequestReceipt,
  DeviceInfo,
  DeviceKind,
  PrincipalBinding,
  PrincipalKind,
  SubmitTaskInput,
  TaskReceipt,
  TaskState,
  UltronBackendCapabilities
} from './types.js';

interface DeviceRow extends QueryResultRow {
  device_id: string;
  owner_principal_id: string;
  kind: DeviceKind;
  platform: string;
  display_name: string;
  control_profile: ControlProfile;
  available_capabilities: unknown;
  granted_capabilities: unknown;
  effective_capabilities: unknown;
  last_seen_at: Date | string;
}

interface TaskRow extends QueryResultRow {
  task_id: string;
  principal_id: string;
  principal_kind: PrincipalKind;
  principal_display_name: string;
  state: TaskState;
  narration: string;
  device_id: string | null;
  evidence: unknown;
  created_at: Date | string;
  updated_at: Date | string;
  objective: string;
  target: SubmitTaskInput['target'];
  required_capabilities: unknown;
  constraints: unknown;
  device_owner_principal_id: string;
  allowed_device_ids: unknown;
  idempotency_key: string | null;
  idempotency_fingerprint: string | null;
}

interface PostgresBridgeOptions {
  connectionString: string;
  maxConnections?: number;
}

const SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS ultron_bridge_devices (
  device_id TEXT PRIMARY KEY,
  owner_principal_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('PHONE','DESKTOP','CLOUD')),
  platform TEXT NOT NULL,
  display_name TEXT NOT NULL,
  control_profile TEXT NOT NULL CHECK (control_profile IN ('READ_ONLY','ASK_EACH_ACTION','MAX_APPROVED')),
  available_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
  granted_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
  effective_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ultron_bridge_tasks (
  task_id UUID PRIMARY KEY,
  principal_id TEXT NOT NULL,
  principal_kind TEXT NOT NULL CHECK (principal_kind IN ('owner','user','agent','system')),
  principal_display_name TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('RECEIVED','RUNNING','WAITING_FOR_USER','WAITING_FOR_DEVICE','PAUSED','VERIFIED_DONE','FAILED','CANCELLED')),
  narration TEXT NOT NULL,
  device_id TEXT,
  evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  objective TEXT NOT NULL,
  target TEXT NOT NULL CHECK (target IN ('auto','phone','desktop','cloud')),
  required_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
  constraints JSONB NOT NULL DEFAULT '[]'::jsonb,
  device_owner_principal_id TEXT NOT NULL,
  allowed_device_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
  idempotency_key TEXT,
  idempotency_fingerprint TEXT
);

ALTER TABLE ultron_bridge_tasks
  ADD COLUMN IF NOT EXISTS idempotency_fingerprint TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS ultron_bridge_tasks_idempotency
  ON ultron_bridge_tasks (principal_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ultron_bridge_tasks_device_state
  ON ultron_bridge_tasks (device_id, state, created_at);
CREATE INDEX IF NOT EXISTS ultron_bridge_tasks_waiting_owner
  ON ultron_bridge_tasks (device_owner_principal_id, state, created_at);
`;

/** Durable multi-instance bridge for cloud deployment. */
export class PostgresBridgeUltronBackend implements DeviceBridgeBackend {
  private readonly pool: Pool;
  private readonly ready: Promise<void>;

  constructor(options: PostgresBridgeOptions) {
    const connectionString = options.connectionString.trim();
    if (!connectionString) throw new Error('DATABASE_URL is required for Postgres bridge mode');
    this.pool = new Pool({
      connectionString,
      max: options.maxConnections ?? 5,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 10_000
    });
    this.ready = this.ensureSchema();
  }

  async close(): Promise<void> {
    await this.pool.end();
  }

  async submitTask(binding: PrincipalBinding, input: SubmitTaskInput): Promise<TaskReceipt> {
    await this.ready;
    const objective = input.objective.trim();
    if (!objective) throw new Error('objective is required');
    const fingerprint = input.idempotencyKey ? taskIdempotencyFingerprint(binding, input) : null;

    return this.transaction(async client => {
      if (input.idempotencyKey && fingerprint) {
        const existing = await client.query<TaskRow>(
          'SELECT * FROM ultron_bridge_tasks WHERE principal_id = $1 AND idempotency_key = $2 LIMIT 1',
          [binding.principalId, input.idempotencyKey]
        );
        if (existing.rows[0]) {
          assertIdempotencyFingerprint(existing.rows[0].idempotency_fingerprint, fingerprint);
          return this.publicReceipt(existing.rows[0]);
        }
      }

      const device = await this.selectDevice(client, binding, input);
      const online = device ? this.withPresence(device).presence === 'ONLINE' : false;
      const state: TaskState = device && online ? 'RECEIVED' : 'WAITING_FOR_DEVICE';
      const narration = device
        ? online
          ? `Accepted for ${device.display_name}`
          : `Waiting for ${device.display_name}`
        : 'Waiting for a matching delegated device';
      const taskId = randomUUID();

      if (input.idempotencyKey) await client.query('SAVEPOINT ultron_idempotent_insert');
      try {
        const inserted = await client.query<TaskRow>(
          `INSERT INTO ultron_bridge_tasks (
            task_id, principal_id, principal_kind, principal_display_name, state, narration, device_id,
            evidence, objective, target, required_capabilities, constraints, device_owner_principal_id,
            allowed_device_ids, idempotency_key, idempotency_fingerprint
          ) VALUES ($1,$2,$3,$4,$5,$6,$7,'[]'::jsonb,$8,$9,$10::jsonb,$11::jsonb,$12,$13::jsonb,$14,$15)
          RETURNING *`,
          [
            taskId,
            binding.principalId,
            binding.principalKind,
            binding.displayName,
            state,
            narration,
            device?.device_id ?? null,
            objective,
            input.target,
            JSON.stringify(uniqueStrings(input.requiredCapabilities)),
            JSON.stringify([...input.constraints]),
            binding.deviceOwnerPrincipalId,
            JSON.stringify([...binding.allowedDeviceIds]),
            input.idempotencyKey ?? null,
            fingerprint
          ]
        );
        if (input.idempotencyKey) await client.query('RELEASE SAVEPOINT ultron_idempotent_insert');
        return this.publicReceipt(requireRow(inserted.rows[0], 'Task insert failed'));
      } catch (error) {
        if (!input.idempotencyKey || !fingerprint || !isUniqueViolation(error)) throw error;
        await client.query('ROLLBACK TO SAVEPOINT ultron_idempotent_insert');
        const existing = await client.query<TaskRow>(
          'SELECT * FROM ultron_bridge_tasks WHERE principal_id = $1 AND idempotency_key = $2 LIMIT 1',
          [binding.principalId, input.idempotencyKey]
        );
        await client.query('RELEASE SAVEPOINT ultron_idempotent_insert');
        const row = requireRow(existing.rows[0], 'Idempotent task lookup failed');
        assertIdempotencyFingerprint(row.idempotency_fingerprint, fingerprint);
        return this.publicReceipt(row);
      }
    });
  }

  async getTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt | undefined> {
    await this.ready;
    const result = await this.pool.query<TaskRow>('SELECT * FROM ultron_bridge_tasks WHERE task_id = $1', [taskId]);
    const task = result.rows[0];
    if (!task) return undefined;
    this.assertTaskOwner(binding, task);
    return this.publicReceipt(task);
  }

  async pauseTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    await this.ready;
    return this.transaction(async client => {
      const task = await this.requireOwnedTask(client, binding, taskId, true);
      if (isTerminal(task.state)) throw new Error(`Cannot pause terminal task in state ${task.state}`);
      return this.publicReceipt(await this.updateTask(client, task, 'PAUSED', 'Paused by authenticated principal'));
    });
  }

  async resumeTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    await this.ready;
    return this.transaction(async client => {
      const task = await this.requireOwnedTask(client, binding, taskId, true);
      if (task.state !== 'PAUSED' && task.state !== 'WAITING_FOR_USER' && task.state !== 'WAITING_FOR_DEVICE') {
        throw new Error(`Cannot resume task in state ${task.state}`);
      }
      const device = task.device_id ? await this.findDevice(client, task.device_id, true) : undefined;
      const online = device ? this.withPresence(device).presence === 'ONLINE' : false;
      const state: TaskState = online && task.device_id ? 'RUNNING' : 'WAITING_FOR_DEVICE';
      return this.publicReceipt(
        await this.updateTask(client, task, state, state === 'RUNNING' ? 'Resume requested on device' : 'Waiting for device')
      );
    });
  }

  async cancelTask(binding: PrincipalBinding, taskId: string): Promise<TaskReceipt> {
    await this.ready;
    return this.transaction(async client => {
      const task = await this.requireOwnedTask(client, binding, taskId, true);
      if (task.state === 'VERIFIED_DONE') throw new Error('Verified task cannot be cancelled');
      return this.publicReceipt(await this.updateTask(client, task, 'CANCELLED', 'Cancelled by authenticated principal'));
    });
  }

  async listDevices(binding: PrincipalBinding): Promise<readonly DeviceInfo[]> {
    await this.ready;
    const result = await this.pool.query<DeviceRow>(
      'SELECT * FROM ultron_bridge_devices WHERE owner_principal_id = $1 ORDER BY device_id',
      [binding.deviceOwnerPrincipalId]
    );
    return result.rows.filter(row => this.deviceVisible(binding, row)).map(row => this.publicDevice(row));
  }

  async getDevice(binding: PrincipalBinding, deviceId: string): Promise<DeviceInfo | undefined> {
    await this.ready;
    const result = await this.pool.query<DeviceRow>('SELECT * FROM ultron_bridge_devices WHERE device_id = $1', [deviceId]);
    const device = result.rows[0];
    if (!device || !this.deviceVisible(binding, device)) return undefined;
    return this.publicDevice(device);
  }

  async requestControl(
    binding: PrincipalBinding,
    deviceId: string,
    profile: ControlProfile,
    _reason: string
  ): Promise<ControlRequestReceipt> {
    await this.ready;
    return this.transaction(async client => {
      const device = await this.findDevice(client, deviceId, true);
      if (!device || !this.deviceVisible(binding, device)) {
        return {
          deviceId,
          state: 'DENIED',
          effectiveProfile: 'READ_ONLY',
          ownerApprovalRequired: false,
          message: 'Device is outside authenticated scope'
        };
      }
      if (binding.principalId !== device.owner_principal_id) {
        return {
          deviceId,
          state: 'WAITING_FOR_OWNER',
          effectiveProfile: device.control_profile,
          ownerApprovalRequired: true,
          message: 'Owner approval required; remote principal cannot elevate device control'
        };
      }
      await client.query('UPDATE ultron_bridge_devices SET control_profile = $2 WHERE device_id = $1', [deviceId, profile]);
      return {
        deviceId,
        state: 'UPDATED',
        effectiveProfile: profile,
        ownerApprovalRequired: false,
        message: 'Control profile updated by device owner'
      };
    });
  }

  async capabilities(): Promise<UltronBackendCapabilities> {
    return {
      supportsPhone: true,
      supportsDesktop: true,
      supportsCloud: true,
      supportsLongRunningTasks: true,
      supportsEvidence: true,
      supportsPauseResume: true,
      backend: 'bridge-postgres'
    };
  }

  async health(): Promise<{ ok: boolean; detail: string }> {
    try {
      await this.ready;
      await this.pool.query('SELECT 1');
      return { ok: true, detail: 'durable bridge ready' };
    } catch {
      return { ok: false, detail: 'durable bridge unavailable' };
    }
  }

  async deviceHeartbeat(binding: DeviceBinding, input: DeviceHeartbeatInput): Promise<DeviceInfo> {
    await this.ready;
    const available = uniqueStrings(input.availableCapabilities);
    const granted = uniqueStrings(input.grantedCapabilities).filter(capability => available.includes(capability));
    const effective = granted.filter(capability => binding.allowedCapabilities.includes(capability));

    return this.transaction(async client => {
      const existing = await this.findDevice(client, binding.deviceId, true);
      const result = await client.query<DeviceRow>(
        `INSERT INTO ultron_bridge_devices (
          device_id, owner_principal_id, kind, platform, display_name, control_profile,
          available_capabilities, granted_capabilities, effective_capabilities, last_seen_at
        ) VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb,$8::jsonb,$9::jsonb,NOW())
        ON CONFLICT (device_id) DO UPDATE SET
          owner_principal_id = EXCLUDED.owner_principal_id,
          kind = EXCLUDED.kind,
          platform = EXCLUDED.platform,
          display_name = EXCLUDED.display_name,
          available_capabilities = EXCLUDED.available_capabilities,
          granted_capabilities = EXCLUDED.granted_capabilities,
          effective_capabilities = EXCLUDED.effective_capabilities,
          last_seen_at = NOW()
        RETURNING *`,
        [
          binding.deviceId,
          binding.ownerPrincipalId,
          binding.kind,
          input.platform.trim() || existing?.platform || 'UNKNOWN',
          binding.displayName,
          existing?.control_profile ?? binding.controlProfile,
          JSON.stringify(available),
          JSON.stringify(granted),
          JSON.stringify(effective)
        ]
      );
      const device = requireRow(result.rows[0], 'Device heartbeat failed');
      await this.assignWaitingTasks(client, device);
      return this.publicDevice(device);
    });
  }

  async deviceClaimNext(binding: DeviceBinding): Promise<DeviceTaskLease | undefined> {
    await this.ready;
    return this.transaction(async client => {
      const device = await this.requireDevice(client, binding, true);
      await client.query('UPDATE ultron_bridge_devices SET last_seen_at = NOW() WHERE device_id = $1', [binding.deviceId]);
      const refreshed = { ...device, last_seen_at: new Date() };
      await this.assignWaitingTasks(client, refreshed);

      const claimed = await client.query<TaskRow>(
        `SELECT * FROM ultron_bridge_tasks
         WHERE device_id = $1 AND state = 'RECEIVED'
         ORDER BY created_at ASC
         FOR UPDATE SKIP LOCKED LIMIT 1`,
        [binding.deviceId]
      );
      const task = claimed.rows[0];
      if (!task) return undefined;
      const running = await this.updateTask(client, task, 'RUNNING', `Claimed by ${device.display_name}`);
      return this.lease(running, device.control_profile);
    });
  }

  async deviceInspectTask(binding: DeviceBinding, taskId: string): Promise<DeviceTaskControl> {
    await this.ready;
    return this.transaction(async client => {
      await this.requireDevice(client, binding, true);
      await client.query('UPDATE ultron_bridge_devices SET last_seen_at = NOW() WHERE device_id = $1', [binding.deviceId]);
      const result = await client.query<TaskRow>('SELECT * FROM ultron_bridge_tasks WHERE task_id = $1', [taskId]);
      const task = result.rows[0];
      if (!task || task.device_id !== binding.deviceId) throw new Error('Task is not assigned to this device');
      return { taskId, state: task.state, narration: task.narration, updatedAt: iso(task.updated_at) };
    });
  }

  async deviceUpdateTask(binding: DeviceBinding, taskId: string, update: DeviceTaskUpdate): Promise<TaskReceipt> {
    await this.ready;
    return this.transaction(async client => {
      await this.requireDevice(client, binding, true);
      await client.query('UPDATE ultron_bridge_devices SET last_seen_at = NOW() WHERE device_id = $1', [binding.deviceId]);
      const result = await client.query<TaskRow>('SELECT * FROM ultron_bridge_tasks WHERE task_id = $1 FOR UPDATE', [taskId]);
      const task = result.rows[0];
      if (!task || task.device_id !== binding.deviceId) throw new Error('Task is not assigned to this device');
      if (isTerminal(task.state)) throw new Error(`Cannot update terminal task in state ${task.state}`);
      if (task.state === 'PAUSED' && update.state !== 'PAUSED' && update.state !== 'CANCELLED') {
        throw new Error('Paused task must be resumed by the authenticated principal or cancelled before device progress');
      }
      const evidence = uniqueStrings(update.evidence ?? []);
      if (update.state === 'VERIFIED_DONE' && evidence.length === 0) throw new Error('VERIFIED_DONE requires evidence');
      const nextEvidence = evidence.length > 0 ? evidence : asStrings(task.evidence);
      const updated = await client.query<TaskRow>(
        `UPDATE ultron_bridge_tasks
         SET state = $2, narration = $3, evidence = $4::jsonb, updated_at = NOW()
         WHERE task_id = $1 RETURNING *`,
        [taskId, update.state, update.narration.trim() || update.state, JSON.stringify(nextEvidence)]
      );
      return this.publicReceipt(requireRow(updated.rows[0], 'Task update failed'));
    });
  }

  private async ensureSchema(): Promise<void> {
    await this.pool.query(SCHEMA_SQL);
  }

  private async transaction<T>(fn: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const result = await fn(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }

  private async selectDevice(client: PoolClient, binding: PrincipalBinding, input: SubmitTaskInput): Promise<DeviceRow | undefined> {
    const result = await client.query<DeviceRow>(
      'SELECT * FROM ultron_bridge_devices WHERE owner_principal_id = $1 ORDER BY last_seen_at DESC',
      [binding.deviceOwnerPrincipalId]
    );
    const required = new Set(uniqueStrings(input.requiredCapabilities));
    const visible = result.rows.filter(device => this.deviceVisible(binding, device));
    const capable = visible.filter(device => [...required].every(capability => asStrings(device.effective_capabilities).includes(capability)));
    if (input.deviceId) {
      const explicit = capable.find(device => device.device_id === input.deviceId);
      if (!explicit) throw new Error('Requested device is outside scope or lacks required granted capabilities');
      return explicit;
    }
    const kind = input.target === 'phone' ? 'PHONE' : input.target === 'desktop' ? 'DESKTOP' : input.target === 'cloud' ? 'CLOUD' : undefined;
    const candidates = kind ? capable.filter(device => device.kind === kind) : capable;
    return candidates.find(device => this.withPresence(device).presence === 'ONLINE') ?? candidates[0];
  }

  private async assignWaitingTasks(client: PoolClient, device: DeviceRow): Promise<void> {
    const result = await client.query<TaskRow>(
      `SELECT * FROM ultron_bridge_tasks
       WHERE device_owner_principal_id = $1 AND state = 'WAITING_FOR_DEVICE'
       ORDER BY created_at ASC FOR UPDATE`,
      [device.owner_principal_id]
    );
    const effective = asStrings(device.effective_capabilities);
    for (const task of result.rows) {
      const allowed = asStrings(task.allowed_device_ids);
      if (allowed.length > 0 && !allowed.includes(device.device_id)) continue;
      if (task.target === 'phone' && device.kind !== 'PHONE') continue;
      if (task.target === 'desktop' && device.kind !== 'DESKTOP') continue;
      if (task.target === 'cloud' && device.kind !== 'CLOUD') continue;
      if (!asStrings(task.required_capabilities).every(capability => effective.includes(capability))) continue;
      await client.query(
        `UPDATE ultron_bridge_tasks
         SET device_id = $2, state = 'RECEIVED', narration = $3, updated_at = NOW()
         WHERE task_id = $1`,
        [task.task_id, device.device_id, `Accepted for ${device.display_name}`]
      );
    }
  }

  private async requireDevice(client: PoolClient, binding: DeviceBinding, lock: boolean): Promise<DeviceRow> {
    const device = await this.findDevice(client, binding.deviceId, lock);
    if (!device || device.owner_principal_id !== binding.ownerPrincipalId) {
      throw new Error('Device must heartbeat before claiming tasks');
    }
    return device;
  }

  private async findDevice(client: PoolClient, deviceId: string, lock: boolean): Promise<DeviceRow | undefined> {
    const suffix = lock ? ' FOR UPDATE' : '';
    const result = await client.query<DeviceRow>(`SELECT * FROM ultron_bridge_devices WHERE device_id = $1${suffix}`, [deviceId]);
    return result.rows[0];
  }

  private async requireOwnedTask(client: PoolClient, binding: PrincipalBinding, taskId: string, lock: boolean): Promise<TaskRow> {
    const suffix = lock ? ' FOR UPDATE' : '';
    const result = await client.query<TaskRow>(`SELECT * FROM ultron_bridge_tasks WHERE task_id = $1${suffix}`, [taskId]);
    const task = requireRow(result.rows[0], 'Task not found');
    this.assertTaskOwner(binding, task);
    return task;
  }

  private assertTaskOwner(binding: PrincipalBinding, task: TaskRow): void {
    if (task.principal_id !== binding.principalId) throw new Error('Task belongs to another authenticated principal');
  }

  private deviceVisible(binding: PrincipalBinding, device: DeviceRow): boolean {
    return device.owner_principal_id === binding.deviceOwnerPrincipalId &&
      (binding.allowedDeviceIds.length === 0 || binding.allowedDeviceIds.includes(device.device_id));
  }

  private async updateTask(client: PoolClient, task: TaskRow, state: TaskState, narration: string): Promise<TaskRow> {
    const result = await client.query<TaskRow>(
      'UPDATE ultron_bridge_tasks SET state = $2, narration = $3, updated_at = NOW() WHERE task_id = $1 RETURNING *',
      [task.task_id, state, narration]
    );
    return requireRow(result.rows[0], 'Task update failed');
  }

  private publicReceipt(task: TaskRow): TaskReceipt {
    return {
      taskId: task.task_id,
      principalId: task.principal_id,
      state: task.state,
      narration: task.narration,
      ...(task.device_id ? { deviceId: task.device_id } : {}),
      evidence: asStrings(task.evidence),
      createdAt: iso(task.created_at),
      updatedAt: iso(task.updated_at)
    };
  }

  private publicDevice(device: DeviceRow): DeviceInfo {
    return {
      id: device.device_id,
      ownerPrincipalId: device.owner_principal_id,
      kind: device.kind,
      platform: device.platform,
      displayName: device.display_name,
      presence: this.withPresence(device).presence,
      controlProfile: device.control_profile,
      availableCapabilities: asStrings(device.available_capabilities),
      grantedCapabilities: asStrings(device.granted_capabilities),
      effectiveCapabilities: asStrings(device.effective_capabilities)
    };
  }

  private withPresence(device: DeviceRow): { presence: 'ONLINE' | 'OFFLINE' } {
    return { presence: Date.now() - new Date(device.last_seen_at).getTime() <= 90_000 ? 'ONLINE' : 'OFFLINE' };
  }

  private lease(task: TaskRow, controlProfile: ControlProfile): DeviceTaskLease {
    return {
      taskId: task.task_id,
      principalId: task.principal_id,
      principalKind: task.principal_kind,
      principalDisplayName: task.principal_display_name,
      objective: task.objective,
      constraints: asStrings(task.constraints),
      requiredCapabilities: asStrings(task.required_capabilities),
      controlProfile,
      state: task.state,
      createdAt: iso(task.created_at),
      updatedAt: iso(task.updated_at)
    };
  }
}

function requireRow<T>(row: T | undefined, message: string): T {
  if (!row) throw new Error(message);
  return row;
}

function uniqueStrings(values: readonly string[]): string[] {
  return [...new Set(values.map(value => value.trim()).filter(Boolean))];
}

function asStrings(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((entry): entry is string => typeof entry === 'string');
}

function iso(value: Date | string): string {
  return value instanceof Date ? value.toISOString() : new Date(value).toISOString();
}

function isTerminal(state: TaskState): boolean {
  return state === 'VERIFIED_DONE' || state === 'FAILED' || state === 'CANCELLED';
}

function isUniqueViolation(error: unknown): boolean {
  return Boolean(error && typeof error === 'object' && 'code' in error && (error as { code?: unknown }).code === '23505');
}
