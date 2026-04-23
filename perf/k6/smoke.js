import { check } from 'k6';

import {
  ackMessages,
  autoRegister,
  checkActuatorHealth,
  checkActuatorMetric,
  checkActuatorPrometheus,
  checkLogin,
  connectWsAndAwaitBroadcast,
  createRoom,
  fetchHistory,
  fetchNew,
  getMyAccount,
  login,
  sendMessage,
  setPassword,
  joinRoom,
  logout,
  unread,
} from './lib/flow.js';
import { config, randomMessage } from './lib/config.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1.0'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    business_failures: ['count==0'],
    ws_connect_failures: ['count==0'],
  },
};

export default function () {
  checkActuatorHealth();
  checkActuatorMetric('jvm.memory.used');
  checkActuatorMetric('hikaricp.connections.active');
  checkActuatorPrometheus();

  const hostInitial = autoRegister();
  const checked = checkLogin(hostInitial.token);
  check(checked, {
    'check login token matches auto-register token': (data) => data.token === hostInitial.token,
  });

  setPassword(hostInitial.token, config.defaultPassword);
  logout(hostInitial.token);

  const host = login(hostInitial.accountId, config.defaultPassword);
  const hostMe = getMyAccount(host.token);
  check(hostMe, {
    'host account id is available': (data) => !!data.accountId,
    'host has password after setup': (data) => data.hasPassword === true,
  });

  const room = createRoom(host.token, { duration: 'MIN_10', isPublic: 1 });
  const guest = autoRegister();
  joinRoom(guest.token, room.roomId);

  const content = randomMessage('smoke');
  const wsState = connectWsAndAwaitBroadcast(
    guest.token,
    room.roomId,
    content,
    () => sendMessage(host.token, room.roomId, content),
  );

  check(wsState, {
    'smoke ws broadcast path succeeded': (state) => state.gotBroadcast,
  });

  const history = fetchHistory(host.token, room.roomId);
  const sent = sendMessage(host.token, room.roomId, randomMessage('history-ack'));
  const fresh = fetchNew(guest.token, room.roomId);

  check(history, {
    'history returns list': (page) => Array.isArray(page.list),
  });
  check(fresh, {
    'new returns list': (page) => Array.isArray(page.list),
  });

  ackMessages(guest.token, room.roomId, sent.indexId);
  const unreadMap = unread(guest.token);
  check(unreadMap, {
    'unread map clears current room after ack': (map) => !map || !map[room.roomId] || map[room.roomId] === 0,
  });
}
