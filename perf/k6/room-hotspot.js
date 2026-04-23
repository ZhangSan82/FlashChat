import { check, sleep } from 'k6';
import ws from 'k6/ws';
import { Counter, Rate, Trend } from 'k6/metrics';

import { postWrappedJson } from './lib/api.js';
import {
  ackMessages,
  autoRegister,
  createRoom,
  fetchHistory,
  fetchNew,
  joinRoom,
} from './lib/flow.js';
import { config, envNumber, randomSuffix } from './lib/config.js';

const senderCount = envNumber('HOT_ROOM_PARTICIPANTS', 30);
const wsListenerCount = envNumber('ROOM_WS_LISTENER_COUNT', 3);
const effectiveParticipantCount = Math.max(senderCount, wsListenerCount + 2);
const historyEvery = envNumber('ROOM_HISTORY_EVERY', 3);
const ackEvery = envNumber('ROOM_ACK_EVERY', 2);
const sendThinkMs = envNumber('ROOM_SEND_THINK_MS', 50);
const wsHoldMs = envNumber('ROOM_WS_HOLD_MS', 540000);
const wsPingMs = envNumber('ROOM_WS_PING_MS', 5000);

const sendStages = [
  { duration: __ENV.ROOM_STAGE_1 || '45s', target: envNumber('ROOM_TARGET_1', 10) },
  { duration: __ENV.ROOM_STAGE_2 || '75s', target: envNumber('ROOM_TARGET_2', 20) },
  { duration: __ENV.ROOM_STAGE_3 || '75s', target: envNumber('ROOM_TARGET_3', 35) },
  { duration: __ENV.ROOM_STAGE_4 || '60s', target: envNumber('ROOM_TARGET_4', 50) },
  { duration: __ENV.ROOM_STAGE_5 || '30s', target: envNumber('ROOM_TARGET_5', 0) },
];

export const sendAttempts = new Counter('send_attempts');
export const sendSuccesses = new Counter('send_successes');
export const sendFailures = new Counter('send_failures');
export const sendSuccessRate = new Rate('send_success_rate');
export const sendRateLimited = new Counter('send_rate_limited');
export const sendRoomGlobalLimited = new Counter('send_room_global_limited');
export const sendUserRoomLimited = new Counter('send_user_room_limited');
export const sendUserGlobalLimited = new Counter('send_user_global_limited');
export const sendRoomBusyFailures = new Counter('send_room_busy_failures');
export const sendSystemBusyFailures = new Counter('send_system_busy_failures');
export const sendTimeoutFailures = new Counter('send_timeout_failures');
export const sendTransportFailures = new Counter('send_transport_failures');
export const sendOtherBusinessFailures = new Counter('send_other_business_failures');
export const sendUnknownFailures = new Counter('send_unknown_failures');

export const roomWsConnectFailures = new Counter('room_ws_connect_failures');
export const roomWsLoginFailures = new Counter('room_ws_login_failures');
export const roomWsMessagesReceived = new Counter('room_ws_messages_received');
export const roomWsBroadcastLatency = new Trend('room_ws_broadcast_latency', true);

const scenarios = {
  senders: {
    executor: 'ramping-vus',
    exec: 'senders',
    stages: sendStages,
    gracefulRampDown: __ENV.ROOM_GRACEFUL_RAMP_DOWN || '10s',
  },
};

if (wsListenerCount > 0) {
  scenarios.listeners = {
    executor: 'constant-vus',
    exec: 'listeners',
    vus: wsListenerCount,
    duration: __ENV.ROOM_WS_DURATION || '6m',
    gracefulStop: '5s',
    startTime: '0s',
  };
}

export const options = {
  scenarios,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: [`rate<${__ENV.ROOM_HTTP_ERROR_RATE_MAX || '0.03'}`],
    http_req_duration: [
      `p(95)<${__ENV.ROOM_HTTP_P95_MAX_MS || '600'}`,
      `p(99)<${__ENV.ROOM_HTTP_P99_MAX_MS || '1200'}`,
    ],
    send_success_rate: [`rate>${__ENV.ROOM_SEND_SUCCESS_RATE_MIN || '0.85'}`],
    room_ws_broadcast_latency: [`p(95)<${__ENV.ROOM_WS_BROADCAST_P95_MAX_MS || '500'}`],
    room_ws_connect_failures: ['count==0'],
  },
};

function buildTimedMessage(prefix = 'room-hotspot') {
  const now = Date.now();
  return `${prefix}|ts=${now}|vu=${typeof __VU !== 'undefined' ? __VU : 0}|iter=${typeof __ITER !== 'undefined' ? __ITER : 0}|id=${randomSuffix()}`;
}

function parseTimestampFromContent(content) {
  if (!content || typeof content !== 'string') {
    return null;
  }
  const match = /ts=(\d{13})/.exec(content);
  return match ? Number(match[1]) : null;
}

function classifySendFailure(response, body, errorMessage) {
  const combined = `${body && body.message ? body.message : ''} ${errorMessage || ''}`.trim();

  if (/房间消息过多/.test(combined)) {
    return 'room_global_limit';
  }
  if (/在该房间发送太频繁/.test(combined)) {
    return 'user_room_limit';
  }
  if (/发送太频繁/.test(combined)) {
    return 'user_global_limit';
  }
  if (/房间当前繁忙/.test(combined)) {
    return 'room_busy';
  }
  if (/系统繁忙/.test(combined)) {
    return 'system_busy';
  }
  if (/timeout|timed out|i\/o timeout/i.test(combined)) {
    return 'timeout';
  }
  if ((response && response.status && response.status !== 200) || !response || response.status === 0) {
    return 'transport';
  }
  if (body && body.code && body.code !== '200') {
    return 'other_business';
  }
  return 'unknown';
}

function recordSendFailure(reason) {
  sendFailures.add(1);
  sendSuccessRate.add(0);

  switch (reason) {
    case 'room_global_limit':
      sendRateLimited.add(1);
      sendRoomGlobalLimited.add(1);
      break;
    case 'user_room_limit':
      sendRateLimited.add(1);
      sendUserRoomLimited.add(1);
      break;
    case 'user_global_limit':
      sendRateLimited.add(1);
      sendUserGlobalLimited.add(1);
      break;
    case 'room_busy':
      sendRoomBusyFailures.add(1);
      break;
    case 'system_busy':
      sendSystemBusyFailures.add(1);
      break;
    case 'timeout':
      sendTimeoutFailures.add(1);
      break;
    case 'transport':
      sendTransportFailures.add(1);
      break;
    case 'other_business':
      sendOtherBusinessFailures.add(1);
      break;
    default:
      sendUnknownFailures.add(1);
      break;
  }
}

function attemptSendMessage(token, roomId) {
  sendAttempts.add(1);
  const content = buildTimedMessage();
  const { response, body } = postWrappedJson(
    '/api/FlashChat/v1/chat/msg',
    { roomId, content },
    token,
    {
      tags: { name: 'chat_send_msg' },
      timeout: __ENV.ROOM_SEND_TIMEOUT || '10s',
    },
  );

  if (response && response.status === 200 && body && body.code === '200' && body.data) {
    sendSuccesses.add(1);
    sendSuccessRate.add(1);
    return {
      success: true,
      content,
      data: body.data,
    };
  }

  const reason = classifySendFailure(response, body, null);
  recordSendFailure(reason);
  return {
    success: false,
    content,
    response,
    body,
    reason,
  };
}

function wsUrlForToken(token) {
  return `${config.wsUrl}/?token=${encodeURIComponent(token)}`;
}

export function setup() {
  const host = autoRegister();
  const room = createRoom(host.token, {
    duration: 'MIN_10',
    isPublic: 1,
    maxMembers: effectiveParticipantCount + 5,
    title: `room-hotspot-${randomSuffix()}`,
  });

  const participants = [];
  for (let i = 0; i < effectiveParticipantCount; i += 1) {
    const guest = autoRegister();
    joinRoom(guest.token, room.roomId);
    participants.push(guest);
  }

  return {
    host,
    roomId: room.roomId,
    participants,
  };
}

export function senders(data) {
  const sender = data.participants[(__VU + __ITER) % data.participants.length];
  const receiver = data.participants[(__VU + __ITER + 1) % data.participants.length];
  const sendResult = attemptSendMessage(sender.token, data.roomId);

  if (sendResult.success && (__ITER % ackEvery) === 0) {
    fetchNew(receiver.token, data.roomId);
    ackMessages(receiver.token, data.roomId, sendResult.data.indexId);
  }

  if ((__ITER % historyEvery) === 0) {
    fetchHistory(data.host.token, data.roomId);
  }

  sleep(sendThinkMs / 1000);
}

export function listeners(data) {
  const token = data.participants[(__VU - 1) % data.participants.length].token;
  const response = ws.connect(wsUrlForToken(token), { tags: { name: 'room_ws_listener' } }, (socket) => {
    let loginSuccess = false;

    socket.setInterval(() => {
      socket.send('ping');
    }, wsPingMs);

    socket.setTimeout(() => {
      socket.close();
    }, wsHoldMs);

    socket.on('message', (message) => {
      if (message === 'pong') {
        return;
      }

      let payload;
      try {
        payload = JSON.parse(message);
      } catch (error) {
        return;
      }

      if (payload.type === 0) {
        loginSuccess = true;
        return;
      }

      if (payload.type === 1 && payload.roomId === data.roomId) {
        roomWsMessagesReceived.add(1);
        const content = payload.data && payload.data.content;
        const sentAt = parseTimestampFromContent(content);
        if (sentAt) {
          roomWsBroadcastLatency.add(Date.now() - sentAt);
        }
      }
    });

    socket.on('close', () => {
      check({ loginSuccess }, {
        'room ws listener login success': (state) => state.loginSuccess,
      });
      if (!loginSuccess) {
        roomWsLoginFailures.add(1);
      }
    });
  });

  const upgraded = check(response, {
    'room ws upgrade status is 101': (res) => !!res && res.status === 101,
  });
  if (!upgraded) {
    roomWsConnectFailures.add(1);
  }
}

function getMetricValue(data, name, key) {
  const metric = data.metrics[name];
  if (!metric) {
    return null;
  }
  const container = metric.values || metric;
  if (Object.prototype.hasOwnProperty.call(container, key)) {
    return container[key];
  }
  return null;
}

export function handleSummary(data) {
  const reportDir = __ENV.REPORT_DIR || '.';
  const hotspotReport = {
    roomId: data.setup_data && data.setup_data.roomId,
    participantCount: effectiveParticipantCount,
    wsListenerCount,
    sendAttempts: getMetricValue(data, 'send_attempts', 'count'),
    sendSuccesses: getMetricValue(data, 'send_successes', 'count'),
    sendSuccessesPerSec: getMetricValue(data, 'send_successes', 'rate'),
    sendFailures: getMetricValue(data, 'send_failures', 'count'),
    sendSuccessRate: getMetricValue(data, 'send_success_rate', 'rate'),
    sendRateLimited: getMetricValue(data, 'send_rate_limited', 'count'),
    sendRoomGlobalLimited: getMetricValue(data, 'send_room_global_limited', 'count'),
    sendUserRoomLimited: getMetricValue(data, 'send_user_room_limited', 'count'),
    sendUserGlobalLimited: getMetricValue(data, 'send_user_global_limited', 'count'),
    sendRoomBusyFailures: getMetricValue(data, 'send_room_busy_failures', 'count'),
    sendSystemBusyFailures: getMetricValue(data, 'send_system_busy_failures', 'count'),
    sendTimeoutFailures: getMetricValue(data, 'send_timeout_failures', 'count'),
    sendTransportFailures: getMetricValue(data, 'send_transport_failures', 'count'),
    sendOtherBusinessFailures: getMetricValue(data, 'send_other_business_failures', 'count'),
    sendUnknownFailures: getMetricValue(data, 'send_unknown_failures', 'count'),
    httpReqP95Ms: getMetricValue(data, 'http_req_duration', 'p(95)'),
    httpReqP99Ms: getMetricValue(data, 'http_req_duration', 'p(99)'),
    httpReqRate: getMetricValue(data, 'http_reqs', 'rate'),
    httpReqFailedRate: getMetricValue(data, 'http_req_failed', 'rate'),
    wsBroadcastP95Ms: getMetricValue(data, 'room_ws_broadcast_latency', 'p(95)'),
    wsBroadcastP99Ms: getMetricValue(data, 'room_ws_broadcast_latency', 'p(99)'),
    wsMessagesReceived: getMetricValue(data, 'room_ws_messages_received', 'count'),
    wsConnectFailures: getMetricValue(data, 'room_ws_connect_failures', 'count'),
    wsLoginFailures: getMetricValue(data, 'room_ws_login_failures', 'count'),
  };

  return {
    [`${reportDir}/room-hotspot-summary.json`]: JSON.stringify(hotspotReport, null, 2),
  };
}
