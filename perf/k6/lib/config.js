export const config = {
  baseUrl: (__ENV.BASE_URL || 'http://localhost:8081').replace(/\/$/, ''),
  wsUrl: (__ENV.WS_URL || 'ws://localhost:8090').replace(/\/$/, ''),
  authHeader: __ENV.AUTH_HEADER || 'satoken',
  defaultPassword: __ENV.DEFAULT_PASSWORD || 'PerfPass123!',
  roomDuration: __ENV.ROOM_DURATION || 'MIN_10',
  historyPageSize: Number(__ENV.HISTORY_PAGE_SIZE || 20),
  wsTimeoutMs: Number(__ENV.WS_TIMEOUT_MS || 8000),
  autoRegisterMaxRetries: Number(__ENV.AUTO_REGISTER_MAX_RETRIES || 5),
  autoRegisterRetryBackoffMs: Number(__ENV.AUTO_REGISTER_RETRY_BACKOFF_MS || 120),
};

export function envNumber(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === null || raw === '') {
    return fallback;
  }
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function randomSuffix() {
  const vu = typeof __VU !== 'undefined' ? __VU : 0;
  const iter = typeof __ITER !== 'undefined' ? __ITER : 0;
  return `${vu}-${iter}-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
}

export function randomRoomTitle(prefix = 'perf-room') {
  return `${prefix}-${randomSuffix()}`;
}

export function randomMessage(prefix = 'perf-msg') {
  return `${prefix}-${randomSuffix()}`;
}
