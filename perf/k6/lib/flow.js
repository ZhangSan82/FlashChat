import { check, sleep } from 'k6';
import ws from 'k6/ws';
import { Counter, Trend } from 'k6/metrics';

import { config, randomMessage, randomRoomTitle } from './config.js';
import { businessFailures, getJson, getPlainJson, getPlainText, postJson, postWrappedJson } from './api.js';

export const wsConnectFailures = new Counter('ws_connect_failures');
export const wsMessagesReceived = new Counter('ws_messages_received');
export const wsBroadcastLatency = new Trend('ws_broadcast_latency', true);
export const autoRegisterRetries = new Counter('account_auto_register_retries');

function isWrappedSuccess(response, body) {
  return !!response && response.status === 200 && !!body && body.code === '200';
}

function shouldRetryAutoRegister(response, body) {
  if (!response || response.status === 0 || response.status >= 500) {
    return true;
  }
  if (!body) {
    return true;
  }
  return body.code === 'B000001';
}

function classifyAutoRegisterRetryReason(response, body) {
  if (!response || response.status === 0) {
    return 'transport';
  }
  if (response.status >= 500) {
    return 'http_5xx';
  }
  if (!body) {
    return 'invalid_body';
  }
  if (body.code === 'B000001') {
    return 'system_error';
  }
  return 'non_retryable';
}

export function autoRegister() {
  const maxAttempts = Math.max(1, config.autoRegisterMaxRetries);
  const backoffBaseSec = Math.max(0, config.autoRegisterRetryBackoffMs) / 1000;
  let lastResponse = null;
  let lastBody = null;

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const { response, body } = postWrappedJson('/api/FlashChat/v1/account/auto-register', null, null, {
      tags: { name: 'account_auto_register' },
    });

    lastResponse = response;
    lastBody = body;

    if (isWrappedSuccess(response, body)) {
      if (attempt > 1) {
        autoRegisterRetries.add(attempt - 1, { reason: 'recovered' });
      }
      return body.data;
    }

    const retryable = shouldRetryAutoRegister(response, body);
    if (!retryable || attempt >= maxAttempts) {
      businessFailures.add(1, { tag: 'account_auto_register' });
      throw new Error(
        `account_auto_register failed after ${attempt} attempts, status=${response && response.status}, body=${response && response.body}`,
      );
    }

    autoRegisterRetries.add(1, { reason: classifyAutoRegisterRetryReason(response, body) });
    sleep(backoffBaseSec * attempt);
  }

  businessFailures.add(1, { tag: 'account_auto_register' });
  throw new Error(
    `account_auto_register exhausted retries, status=${lastResponse && lastResponse.status}, body=${lastResponse && lastResponse.body}`,
  );
}

export function checkLogin(token) {
  return getJson('/api/FlashChat/v1/account/check', token, {
    tags: { name: 'account_check' },
    tag: 'account_check',
  });
}

export function setPassword(token, password = config.defaultPassword) {
  return postJson(
    '/api/FlashChat/v1/account/set-password',
    { password, confirmPassword: password },
    token,
    {
      tags: { name: 'account_set_password' },
      tag: 'account_set_password',
    },
  );
}

export function logout(token) {
  return postJson('/api/FlashChat/v1/account/logout', null, token, {
    tags: { name: 'account_logout' },
    tag: 'account_logout',
  });
}

export function login(accountId, password = config.defaultPassword) {
  return postJson(
    '/api/FlashChat/v1/account/login',
    { accountId, password },
    null,
    {
      tags: { name: 'account_login' },
      tag: 'account_login',
    },
  );
}

export function getMyAccount(token) {
  return getJson('/api/FlashChat/v1/account/me', token, {
    tags: { name: 'account_me' },
    tag: 'account_me',
  });
}

export function getCreditBalance(token) {
  return getJson('/api/FlashChat/v1/account/credits/balance', token, {
    tags: { name: 'account_credit_balance' },
    tag: 'account_credit_balance',
  });
}

export function prepareRegisteredUser() {
  const initial = autoRegister();
  checkLogin(initial.token);
  setPassword(initial.token, config.defaultPassword);
  logout(initial.token);
  const relogin = login(initial.accountId, config.defaultPassword);
  getMyAccount(relogin.token);
  return { ...relogin, password: config.defaultPassword };
}

export function createRoom(token, options = {}) {
  const payload = {
    title: options.title || randomRoomTitle(),
    maxMembers: options.maxMembers || 20,
    isPublic: options.isPublic === undefined ? 1 : options.isPublic,
    avatarUrl: options.avatarUrl || '',
    duration: options.duration || config.roomDuration,
  };

  return postJson('/api/FlashChat/v1/room/create', payload, token, {
    tags: { name: 'room_create' },
    tag: 'room_create',
  });
}

export function joinRoom(token, roomId) {
  return postJson('/api/FlashChat/v1/room/join', { roomId }, token, {
    tags: { name: 'room_join' },
    tag: 'room_join',
  });
}

export function closeRoom(token, roomId) {
  return postJson('/api/FlashChat/v1/room/close', { roomId }, token, {
    tags: { name: 'room_close' },
    tag: 'room_close',
  });
}

export function listMyRooms(token) {
  return getJson('/api/FlashChat/v1/room/my-rooms', token, {
    tags: { name: 'room_my_rooms' },
    tag: 'room_my_rooms',
  });
}

export function roomMembers(token, roomId) {
  return getJson(`/api/FlashChat/v1/room/members/${encodeURIComponent(roomId)}`, token, {
    tags: { name: 'room_members' },
    tag: 'room_members',
  });
}

export function roomPricing(token) {
  return getJson('/api/FlashChat/v1/room/pricing', token, {
    tags: { name: 'room_pricing' },
    tag: 'room_pricing',
  });
}

export function sendMessage(token, roomId, content = randomMessage()) {
  return postJson(
    '/api/FlashChat/v1/chat/msg',
    { roomId, content },
    token,
    {
      tags: { name: 'chat_send_msg' },
      tag: 'chat_send_msg',
    },
  );
}

export function fetchHistory(token, roomId, pageSize = config.historyPageSize, cursor = null) {
  const query = [`roomId=${encodeURIComponent(roomId)}`, `pageSize=${pageSize}`];
  if (cursor) {
    query.push(`cursor=${encodeURIComponent(cursor)}`);
  }
  return getJson(`/api/FlashChat/v1/chat/history?${query.join('&')}`, token, {
    tags: { name: 'chat_history' },
    tag: 'chat_history',
  });
}

export function fetchNew(token, roomId) {
  return getJson(`/api/FlashChat/v1/chat/new?roomId=${encodeURIComponent(roomId)}`, token, {
    tags: { name: 'chat_new' },
    tag: 'chat_new',
  });
}

export function ackMessages(token, roomId, lastMsgId) {
  return postJson('/api/FlashChat/v1/chat/ack', { roomId, lastMsgId }, token, {
    tags: { name: 'chat_ack' },
    tag: 'chat_ack',
  });
}

export function unread(token) {
  return getJson('/api/FlashChat/v1/chat/unread', token, {
    tags: { name: 'chat_unread' },
    tag: 'chat_unread',
  });
}

export function getActiveGame(token, roomId) {
  return getJson(`/api/FlashChat/v1/game/active/${encodeURIComponent(roomId)}`, token, {
    tags: { name: 'game_active' },
    tag: 'game_active',
  });
}

export function checkActuatorHealth() {
  const health = getPlainJson('/actuator/health', {
    tags: { name: 'actuator_health' },
    tag: 'actuator_health',
  });

  check(health, {
    'actuator health is UP': (body) => body && body.status === 'UP',
    'db health is UP or absent': (body) => !body.components || !body.components.db || body.components.db.status === 'UP',
    'redis health is UP or absent': (body) => !body.components || !body.components.redis || body.components.redis.status === 'UP',
    'disk health is UP or absent': (body) => !body.components || !body.components.diskSpace || body.components.diskSpace.status === 'UP',
  });

  return health;
}

export function checkActuatorMetric(metricPath) {
  return getPlainJson(`/actuator/metrics/${metricPath}`, {
    tags: { name: 'actuator_metric' },
    tag: `actuator_metric_${metricPath.replace(/\./g, '_')}`,
  });
}

export function checkActuatorPrometheus() {
  const body = getPlainText('/actuator/prometheus', {
    tags: { name: 'actuator_prometheus' },
    tag: 'actuator_prometheus',
  });

  check(body, {
    'prometheus text contains jvm metric': (text) => typeof text === 'string' && text.includes('jvm_memory_used_bytes'),
    'prometheus text contains http metric': (text) => typeof text === 'string' && text.includes('http_server_requests_seconds_count'),
  });

  return body;
}

function tryParseMessage(message) {
  try {
    return JSON.parse(message);
  } catch (error) {
    return null;
  }
}

export function connectWsAndAwaitBroadcast(token, roomId, expectedContent, onReady = null) {
  const wsUrl = `${config.wsUrl}/?token=${encodeURIComponent(token)}`;
  let loginSuccess = false;
  let gotPong = false;
  let gotBroadcast = false;
  let startedAfterLogin = false;
  let sendStartedAt = 0;
  const receivedTypes = [];

  const response = ws.connect(wsUrl, { tags: { name: 'ws_connect' } }, (socket) => {
    socket.on('open', () => {
      socket.setTimeout(() => {
        socket.close();
      }, config.wsTimeoutMs);
    });

    socket.on('message', (message) => {
      if (message === 'pong') {
        gotPong = true;
        return;
      }

      const payload = tryParseMessage(message);
      if (!payload) {
        return;
      }

      receivedTypes.push(payload.type);
      wsMessagesReceived.add(1, { type: String(payload.type) });

      if (payload.type === 0) {
        loginSuccess = true;
        if (!startedAfterLogin) {
          startedAfterLogin = true;
          socket.send('ping');
          socket.setTimeout(() => {
            if (onReady) {
              sendStartedAt = Date.now();
              onReady();
            }
          }, 300);
        }
      }

      if (payload.type === 1 && payload.roomId === roomId) {
        const content = payload.data && payload.data.content;
        if (!expectedContent || content === expectedContent) {
          gotBroadcast = true;
          if (sendStartedAt > 0) {
            wsBroadcastLatency.add(Date.now() - sendStartedAt);
          }
          socket.close();
        }
      }
    });
  });

  const upgraded = check(response, {
    'ws upgrade status is 101': (res) => !!res && res.status === 101,
  });

  if (!upgraded) {
    wsConnectFailures.add(1);
    throw new Error(`websocket upgrade failed: ${JSON.stringify(response)}`);
  }

  check(
    { loginSuccess, gotPong, gotBroadcast },
    {
      'ws login success received': (state) => state.loginSuccess,
      'ws pong received': (state) => state.gotPong,
      'ws broadcast received': (state) => state.gotBroadcast,
    },
  );

  if (!loginSuccess || !gotPong || !gotBroadcast) {
    throw new Error(`websocket assertions failed, roomId=${roomId}, receivedTypes=${JSON.stringify(receivedTypes)}`);
  }

  return { loginSuccess, gotPong, gotBroadcast, receivedTypes };
}
