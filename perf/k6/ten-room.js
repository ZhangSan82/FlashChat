import exec from 'k6/execution';
import { check, sleep } from 'k6';
import ws from 'k6/ws';
import { Counter, Rate, Trend } from 'k6/metrics';

import { postWrappedJson } from './lib/api.js';
import { fetchHistory, fetchNew, ackMessages } from './lib/flow.js';
import { config, envNumber, randomSuffix } from './lib/config.js';
import { buildRoomKey, setupTenRoomTopology, zeroPadRoomNumber } from './lib/ten-room-init.js';

const roomCount = envNumber('TEN_ROOM_COUNT', 10);
const participantsPerRoom = envNumber('TEN_ROOM_PARTICIPANTS_PER_ROOM', 20);
const wsListenersPerRoom = envNumber('TEN_ROOM_WS_LISTENERS_PER_ROOM', 1);
const historyEvery = envNumber('TEN_ROOM_HISTORY_EVERY', 999999);
const ackEvery = envNumber('TEN_ROOM_ACK_EVERY', 999999);
const sendThinkMs = envNumber('TEN_ROOM_SEND_THINK_MS', 200);
const wsHoldMs = envNumber('TEN_ROOM_WS_HOLD_MS', 420000);
const wsPingMs = envNumber('TEN_ROOM_WS_PING_MS', 5000);
const wsReconnectBaseMs = envNumber('TEN_ROOM_WS_RECONNECT_BASE_MS', 0);
const wsReconnectJitterMs = envNumber('TEN_ROOM_WS_RECONNECT_JITTER_MS', 0);
const mailboxShardCount = envNumber('TEN_ROOM_SHARD_COUNT', 12);
const maxRoomsPerShard = envNumber('TEN_ROOM_MAX_ROOMS_PER_SHARD', 0);
const minActiveShards = envNumber('TEN_ROOM_MIN_ACTIVE_SHARDS', 0);
const maxEmptyShards = envNumber('TEN_ROOM_MAX_EMPTY_SHARDS', 0);
const roomTitlePrefix = __ENV.TEN_ROOM_TITLE_PREFIX || 'ten-room';
const fixedTopologyRoomDuration = __ENV.TEN_ROOM_FIXED_TOPOLOGY_DURATION || 'DAY_3';
const fixedTopologyPath = __ENV.TEN_ROOM_FIXED_TOPOLOGY_PATH || '';
const fixedTopologySavePath = __ENV.TEN_ROOM_FIXED_TOPOLOGY_SAVE_PATH || '';

// listener 运行模式:
//   constant(默认,兼容旧脚本): constant-vus,VU 空闲立刻下一次 iter,等同 reconnect 循环,适合测握手承压。
//   steady: per-vu-iterations=1,每个 VU 只握手 1 次,成功就稳态持有到 wsHoldMs,失败不重试,适合测"全员稳定在线"的广播指标。
const listenerMode = (__ENV.TEN_ROOM_LISTENER_MODE || 'constant').toLowerCase();
// steady 模式下,1000 VU 同一瞬间握手仍会打穿小机 wsBusinessExecutor。
// 以 __VU 为序按比例 sleep,把握手摊到 [0, listenerStaggerSec] 区间内,避免 thundering herd。
const listenerStaggerSec = envNumber('TEN_ROOM_LISTENER_STAGGER_SEC', 0);
// steady 模式下 listeners 场景的硬超时,应覆盖 senders 全部时长 + 爬坡 + stagger。
const listenerMaxDuration = __ENV.TEN_ROOM_LISTENER_MAX_DURATION || '7m30s';
// senders 场景开始发消息的延迟,给 listener 先爬齐的时间,保证测的是"稳态全员在线"的广播。
const senderStartTime = __ENV.TEN_ROOM_SENDER_START_TIME || '0s';

function parseJsonFile(path) {
  const raw = open(path);
  if (!raw) {
    return null;
  }

  // PowerShell 5.x 用 Set-Content -Encoding utf8 时会写入 BOM，
  // k6 的 JSON.parse 不会自动剥离，所以这里兼容去掉开头的 U+FEFF。
  const normalized = raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
  return JSON.parse(normalized);
}

let fixedTopology = null;
if (fixedTopologyPath) {
  fixedTopology = parseJsonFile(fixedTopologyPath);
}

const sendStages = [
  { duration: __ENV.TEN_ROOM_STAGE_1 || '30s', target: envNumber('TEN_ROOM_TARGET_1', 50) },
  { duration: __ENV.TEN_ROOM_STAGE_2 || '5m', target: envNumber('TEN_ROOM_TARGET_2', 50) },
  { duration: __ENV.TEN_ROOM_STAGE_3 || '20s', target: envNumber('TEN_ROOM_TARGET_3', 0) },
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
export const roomWsListenerLogins = new Counter('room_ws_listener_logins');
export const roomWsMessagesReceived = new Counter('room_ws_messages_received');
export const roomWsBroadcastLatency = new Trend('room_ws_broadcast_latency', true);
export const roomWsServerToListenerLatency = new Trend('room_ws_server_to_listener_latency', true);
export const roomSendToServerTimestampGap = new Trend('room_send_to_server_timestamp_gap', true);

const perRoomMetrics = Array.from({ length: roomCount }, (_, index) => {
  const roomKey = buildRoomKey(index);
  return {
    roomKey,
    sendAttempts: new Counter(`${roomKey}_send_attempts`),
    sendSuccesses: new Counter(`${roomKey}_send_successes`),
    sendFailures: new Counter(`${roomKey}_send_failures`),
    sendSuccessRate: new Rate(`${roomKey}_send_success_rate`),
    wsListenerLogins: new Counter(`${roomKey}_ws_listener_logins`),
    wsMessagesReceived: new Counter(`${roomKey}_ws_messages_received`),
    wsBroadcastLatency: new Trend(`${roomKey}_ws_broadcast_latency`, true),
    wsServerToListenerLatency: new Trend(`${roomKey}_ws_server_to_listener_latency`, true),
    sendToServerTimestampGap: new Trend(`${roomKey}_send_to_server_timestamp_gap`, true),
  };
});

const totalWsListenerCount = roomCount * wsListenersPerRoom;
const scenarios = {
  senders: {
    executor: 'ramping-vus',
    exec: 'senders',
    // startTime 默认仍为 '0s' 保持旧行为;steady 模式下一般传 '35s' 等 listener 爬齐再开火。
    startTime: senderStartTime,
    stages: sendStages,
    gracefulRampDown: __ENV.TEN_ROOM_GRACEFUL_RAMP_DOWN || '10s',
  },
};

if (totalWsListenerCount > 0) {
  if (listenerMode === 'steady') {
    // steady 模式:每 VU 只握手 1 次,成功就靠 setTimeout(close, wsHoldMs) 稳态持有,
    // 失败直接记录(iterations=1,不会再 iter 自动重连),结果不再被握手风暴污染。
    scenarios.listeners = {
      executor: 'per-vu-iterations',
      exec: 'listeners',
      vus: totalWsListenerCount,
      iterations: 1,
      maxDuration: listenerMaxDuration,
      gracefulStop: '10s',
      startTime: '0s',
    };
  } else {
    // constant 模式(旧行为):VU 空闲立刻下一次 iter,等同 reconnect 循环。
    // 保留用于握手承压对照。
    scenarios.listeners = {
      executor: 'constant-vus',
      exec: 'listeners',
      vus: totalWsListenerCount,
      duration: __ENV.TEN_ROOM_WS_DURATION || '7m',
      gracefulStop: '5s',
      startTime: '0s',
    };
  }
}

export const options = {
  scenarios,
  // 20x50 这类大拓扑初始化要先完成 20 个房间、1000 个参与者准备，
  // 60s 默认 setupTimeout 容易在本机环境下触发超时，因此这里显式放宽。
  setupTimeout: __ENV.TEN_ROOM_SETUP_TIMEOUT || '10m',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: [`rate<${__ENV.TEN_ROOM_HTTP_ERROR_RATE_MAX || '0.02'}`],
    http_req_duration: [
      `p(95)<${__ENV.TEN_ROOM_HTTP_P95_MAX_MS || '700'}`,
      `p(99)<${__ENV.TEN_ROOM_HTTP_P99_MAX_MS || '1400'}`,
    ],
    send_success_rate: [`rate>${__ENV.TEN_ROOM_SEND_SUCCESS_RATE_MIN || '0.95'}`],
    room_ws_broadcast_latency: [`p(95)<${__ENV.TEN_ROOM_WS_BROADCAST_P95_MAX_MS || '600'}`],
    room_ws_connect_failures: ['count==0'],
  },
};

function buildTimedMessage(roomIndex) {
  const now = Date.now();
  return `ten-room|room=${zeroPadRoomNumber(roomIndex + 1)}|ts=${now}|vu=${typeof __VU !== 'undefined' ? __VU : 0}|iter=${typeof __ITER !== 'undefined' ? __ITER : 0}|id=${randomSuffix()}`;
}

function parseTimestampFromContent(content) {
  if (!content || typeof content !== 'string') {
    return null;
  }
  const match = /ts=(\d{13})/.exec(content);
  return match ? Number(match[1]) : null;
}

function toFiniteTimestamp(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function classifySendFailure(response, body, errorMessage) {
  const combined = `${body && body.message ? body.message : ''} ${errorMessage || ''}`.trim();

  if (/鎴块棿娑堟伅杩囧/.test(combined)) {
    return 'room_global_limit';
  }
  if (/鍦ㄨ鎴块棿鍙戦€佸お棰戠箒/.test(combined)) {
    return 'user_room_limit';
  }
  if (/鍙戦€佸お棰戠箒/.test(combined)) {
    return 'user_global_limit';
  }
  if (/鎴块棿褰撳墠绻佸繖/.test(combined)) {
    return 'room_busy';
  }
  if (/绯荤粺绻佸繖/.test(combined)) {
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

function recordSendFailure(roomIndex, reason) {
  const roomMetric = perRoomMetrics[roomIndex];
  sendFailures.add(1);
  sendSuccessRate.add(0);
  roomMetric.sendFailures.add(1);
  roomMetric.sendSuccessRate.add(0);

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

function attemptSendMessage(roomIndex, token, roomId) {
  const roomMetric = perRoomMetrics[roomIndex];
  sendAttempts.add(1);
  roomMetric.sendAttempts.add(1);
  const content = buildTimedMessage(roomIndex);
  const { response, body } = postWrappedJson(
    '/api/FlashChat/v1/chat/msg',
    { roomId, content },
    token,
    {
      tags: { name: 'chat_send_msg' },
      timeout: __ENV.TEN_ROOM_SEND_TIMEOUT || '10s',
    },
  );

  if (response && response.status === 200 && body && body.code === '200' && body.data) {
    sendSuccesses.add(1);
    sendSuccessRate.add(1);
    roomMetric.sendSuccesses.add(1);
    roomMetric.sendSuccessRate.add(1);
    return {
      success: true,
      content,
      data: body.data,
    };
  }

  const reason = classifySendFailure(response, body, null);
  recordSendFailure(roomIndex, reason);
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

function maybeSleepBeforeReconnect() {
  const jitter = wsReconnectJitterMs > 0
    ? Math.floor(Math.random() * (wsReconnectJitterMs + 1))
    : 0;
  const delayMs = wsReconnectBaseMs + jitter;
  if (delayMs > 0) {
    sleep(delayMs / 1000);
  }
}

function getSenderRoomIndex() {
  return (__VU - 1) % roomCount;
}

function getSenderParticipantIndex() {
  const roomVuOrdinal = Math.floor((__VU - 1) / roomCount);
  return (roomVuOrdinal + __ITER) % participantsPerRoom;
}

function getListenerRoomIndex() {
  const listenerOrdinal = exec.scenario.iterationInTest % totalWsListenerCount;
  return listenerOrdinal % roomCount;
}

function getListenerParticipantIndex() {
  const listenerOrdinal = exec.scenario.iterationInTest % totalWsListenerCount;
  const roomListenerOrdinal = Math.floor(listenerOrdinal / roomCount);
  return (roomListenerOrdinal + 1) % participantsPerRoom;
}

export function setup() {
  return setupTenRoomTopology({
    roomCount,
    participantsPerRoom,
    roomTitlePrefix,
    roomDuration: fixedTopologyRoomDuration,
    shardCount: mailboxShardCount,
    maxRoomsPerShard,
    minActiveShards,
    maxEmptyShards,
    fixedTopology,
  });
}

export function senders(data) {
  const roomIndex = getSenderRoomIndex();
  const room = data.rooms[roomIndex];
  const senderIndex = getSenderParticipantIndex();
  const receiverIndex = (senderIndex + 1) % room.participants.length;
  const sender = room.participants[senderIndex];
  const receiver = room.participants[receiverIndex];
  const sendResult = attemptSendMessage(roomIndex, sender.token, room.roomId);

  if (sendResult.success && ackEvery > 0 && (__ITER % ackEvery) === 0) {
    fetchNew(receiver.token, room.roomId);
    ackMessages(receiver.token, room.roomId, sendResult.data.indexId);
  }

  if (historyEvery > 0 && (__ITER % historyEvery) === 0) {
    fetchHistory(room.host.token, room.roomId);
  }

  sleep(sendThinkMs / 1000);
}

export function listeners(data) {
  // 错峰启动:基于 __VU 序号按比例 sleep,把 1000 VU 的握手请求摊到 [0, listenerStaggerSec] 秒内。
  // listenerStaggerSec=0 时为 no-op,完全保留旧行为。
  if (listenerStaggerSec > 0 && totalWsListenerCount > 0) {
    const staggerSec = ((__VU - 1) % totalWsListenerCount) / totalWsListenerCount * listenerStaggerSec;
    if (staggerSec > 0) {
      sleep(staggerSec);
    }
  }

  const roomIndex = getListenerRoomIndex();
  const room = data.rooms[roomIndex];
  const participant = room.participants[getListenerParticipantIndex()];
  const roomMetric = perRoomMetrics[roomIndex];
  let loginSuccess = false;
  const response = ws.connect(wsUrlForToken(participant.token), { tags: { name: 'room_ws_listener' } }, (socket) => {
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
        if (!loginSuccess) {
          loginSuccess = true;
          roomWsListenerLogins.add(1);
          roomMetric.wsListenerLogins.add(1);
        }
        return;
      }

      if (payload.type === 1 && payload.roomId === room.roomId) {
        roomWsMessagesReceived.add(1);
        roomMetric.wsMessagesReceived.add(1);
        const now = Date.now();
        const serverTs = toFiniteTimestamp(payload.data && payload.data.timestamp);
        const content = payload.data && payload.data.content;
        const sentAt = parseTimestampFromContent(content);
        if (sentAt) {
          const latency = now - sentAt;
          roomWsBroadcastLatency.add(latency);
          roomMetric.wsBroadcastLatency.add(latency);
        }
        if (serverTs) {
          const serverLatency = now - serverTs;
          roomWsServerToListenerLatency.add(serverLatency);
          roomMetric.wsServerToListenerLatency.add(serverLatency);
          if (sentAt && serverTs >= sentAt) {
            const sendToServerGap = serverTs - sentAt;
            roomSendToServerTimestampGap.add(sendToServerGap);
            roomMetric.sendToServerTimestampGap.add(sendToServerGap);
          }
        }
      }
    });

    socket.on('close', () => {
      check({ loginSuccess }, {
        'ten room ws listener login success': (state) => state.loginSuccess,
      });
      if (!loginSuccess) {
        roomWsLoginFailures.add(1);
      }
    });
  });

  const upgraded = check(response, {
    'ten room ws upgrade status is 101': (res) => !!res && res.status === 101,
  });
  if (!upgraded) {
    roomWsConnectFailures.add(1);
  }

  if (!upgraded || !loginSuccess) {
    maybeSleepBeforeReconnect();
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
  const perRoom = [];
  const perRoomSendSuccessesPerSec = {};
  const perRoomSendSuccessRate = {};
  const perRoomWsListenerLogins = {};
  const perRoomWsBroadcastP95Ms = {};
  const perRoomWsBroadcastP99Ms = {};
  const perRoomWsServerToListenerP95Ms = {};
  const perRoomWsServerToListenerP99Ms = {};
  const perRoomSendToServerGapP95Ms = {};
  const perRoomSendToServerGapP99Ms = {};

  for (let roomIndex = 0; roomIndex < roomCount; roomIndex += 1) {
    const roomKey = buildRoomKey(roomIndex);
    const roomSummary = {
      roomKey,
      roomId: data.setup_data && data.setup_data.rooms && data.setup_data.rooms[roomIndex]
        ? data.setup_data.rooms[roomIndex].roomId
        : null,
      sendAttempts: getMetricValue(data, `${roomKey}_send_attempts`, 'count'),
      sendSuccesses: getMetricValue(data, `${roomKey}_send_successes`, 'count'),
      sendSuccessesPerSec: getMetricValue(data, `${roomKey}_send_successes`, 'rate'),
      sendFailures: getMetricValue(data, `${roomKey}_send_failures`, 'count'),
      sendSuccessRate: getMetricValue(data, `${roomKey}_send_success_rate`, 'rate'),
      wsListenerLogins: getMetricValue(data, `${roomKey}_ws_listener_logins`, 'count') || 0,
      wsMessagesReceived: getMetricValue(data, `${roomKey}_ws_messages_received`, 'count'),
      wsBroadcastP95Ms: getMetricValue(data, `${roomKey}_ws_broadcast_latency`, 'p(95)'),
      wsBroadcastP99Ms: getMetricValue(data, `${roomKey}_ws_broadcast_latency`, 'p(99)'),
      wsServerToListenerP95Ms: getMetricValue(data, `${roomKey}_ws_server_to_listener_latency`, 'p(95)'),
      wsServerToListenerP99Ms: getMetricValue(data, `${roomKey}_ws_server_to_listener_latency`, 'p(99)'),
      sendToServerGapP95Ms: getMetricValue(data, `${roomKey}_send_to_server_timestamp_gap`, 'p(95)'),
      sendToServerGapP99Ms: getMetricValue(data, `${roomKey}_send_to_server_timestamp_gap`, 'p(99)'),
    };
    perRoom.push(roomSummary);
    perRoomSendSuccessesPerSec[roomKey] = roomSummary.sendSuccessesPerSec;
    perRoomSendSuccessRate[roomKey] = roomSummary.sendSuccessRate;
    perRoomWsListenerLogins[roomKey] = roomSummary.wsListenerLogins;
    perRoomWsBroadcastP95Ms[roomKey] = roomSummary.wsBroadcastP95Ms;
    perRoomWsBroadcastP99Ms[roomKey] = roomSummary.wsBroadcastP99Ms;
    perRoomWsServerToListenerP95Ms[roomKey] = roomSummary.wsServerToListenerP95Ms;
    perRoomWsServerToListenerP99Ms[roomKey] = roomSummary.wsServerToListenerP99Ms;
    perRoomSendToServerGapP95Ms[roomKey] = roomSummary.sendToServerGapP95Ms;
    perRoomSendToServerGapP99Ms[roomKey] = roomSummary.sendToServerGapP99Ms;
  }

  const roomRates = perRoom
    .map((room) => room.sendSuccessesPerSec)
    .filter((value) => typeof value === 'number' && Number.isFinite(value) && value > 0);
  const roomSkew = roomRates.length > 0
    ? Math.max(...roomRates) / Math.min(...roomRates)
    : null;
  const roomsMissingWsListener = perRoom
    .filter((room) => room.wsListenerLogins < wsListenersPerRoom)
    .map((room) => room.roomKey);
  const roomsWithExtraWsListeners = perRoom
    .filter((room) => room.wsListenerLogins > wsListenersPerRoom)
    .map((room) => room.roomKey);

  const tenRoomReport = {
    roomCount,
    participantsPerRoom,
    totalParticipants: roomCount * participantsPerRoom,
    setupTopologySource: data.setup_data ? data.setup_data.topologySource : null,
    setupRoomDuration: data.setup_data ? data.setup_data.roomDuration : null,
    setupShardCount: data.setup_data ? data.setup_data.shardCount : null,
    setupShardCounts: data.setup_data ? data.setup_data.shardCounts : null,
    setupActualMaxRoomsPerShard: data.setup_data ? data.setup_data.actualMaxRoomsPerShard : null,
    setupActiveShardCount: data.setup_data ? data.setup_data.activeShardCount : null,
    setupEmptyShardCount: data.setup_data ? data.setup_data.emptyShardCount : null,
    wsListenersPerRoom,
    totalWsListenerCount,
    listenerMode,
    listenerStaggerSec,
    listenerMaxDuration,
    senderStartTime,
    totalSendAttempts: getMetricValue(data, 'send_attempts', 'count'),
    totalSendSuccesses: getMetricValue(data, 'send_successes', 'count'),
    totalSendSuccessesPerSec: getMetricValue(data, 'send_successes', 'rate'),
    totalSendFailures: getMetricValue(data, 'send_failures', 'count'),
    totalSendSuccessRate: getMetricValue(data, 'send_success_rate', 'rate'),
    totalHttpP95Ms: getMetricValue(data, 'http_req_duration', 'p(95)'),
    totalHttpP99Ms: getMetricValue(data, 'http_req_duration', 'p(99)'),
    totalHttpReqRate: getMetricValue(data, 'http_reqs', 'rate'),
    totalHttpReqFailedRate: getMetricValue(data, 'http_req_failed', 'rate'),
    totalWsBroadcastP95Ms: getMetricValue(data, 'room_ws_broadcast_latency', 'p(95)'),
    totalWsBroadcastP99Ms: getMetricValue(data, 'room_ws_broadcast_latency', 'p(99)'),
    totalWsServerToListenerP95Ms: getMetricValue(data, 'room_ws_server_to_listener_latency', 'p(95)'),
    totalWsServerToListenerP99Ms: getMetricValue(data, 'room_ws_server_to_listener_latency', 'p(99)'),
    totalSendToServerGapP95Ms: getMetricValue(data, 'room_send_to_server_timestamp_gap', 'p(95)'),
    totalSendToServerGapP99Ms: getMetricValue(data, 'room_send_to_server_timestamp_gap', 'p(99)'),
    totalWsListenerLogins: getMetricValue(data, 'room_ws_listener_logins', 'count') || 0,
    totalWsMessagesReceived: getMetricValue(data, 'room_ws_messages_received', 'count'),
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
    roomWsConnectFailures: getMetricValue(data, 'room_ws_connect_failures', 'count'),
    roomWsLoginFailures: getMetricValue(data, 'room_ws_login_failures', 'count'),
    perRoomSendSuccessesPerSec,
    perRoomSendSuccessRate,
    perRoomWsListenerLogins,
    perRoomWsBroadcastP95Ms,
    perRoomWsBroadcastP99Ms,
    perRoomWsServerToListenerP95Ms,
    perRoomWsServerToListenerP99Ms,
    perRoomSendToServerGapP95Ms,
    perRoomSendToServerGapP99Ms,
    roomSkew,
    roomsMissingWsListener,
    roomsWithExtraWsListeners,
    rooms: perRoom,
  };

  const outputs = {
    [`${reportDir}/ten-room-summary.json`]: JSON.stringify(tenRoomReport, null, 2),
  };

  if (fixedTopologySavePath && data.setup_data && Array.isArray(data.setup_data.rooms) && data.setup_data.rooms.length === roomCount) {
    outputs[fixedTopologySavePath] = JSON.stringify(data.setup_data, null, 2);
  }

  return outputs;
}
