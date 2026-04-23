import { sleep } from 'k6';

import {
  ackMessages,
  autoRegister,
  checkLogin,
  createRoom,
  fetchHistory,
  fetchNew,
  getActiveGame,
  getCreditBalance,
  getMyAccount,
  joinRoom,
  listMyRooms,
  login,
  roomMembers,
  roomPricing,
  sendMessage,
  setPassword,
  logout,
  unread,
} from './lib/flow.js';
import { envNumber, randomMessage } from './lib/config.js';

const duration = __ENV.K6_DURATION || '5m';

export const options = {
  scenarios: {
    chat_flow: {
      executor: 'constant-vus',
      exec: 'chatFlow',
      vus: envNumber('CHAT_VUS', 12),
      duration,
    },
    room_flow: {
      executor: 'constant-vus',
      exec: 'roomFlow',
      vus: envNumber('ROOM_VUS', 4),
      duration,
    },
    auth_flow: {
      executor: 'constant-vus',
      exec: 'authFlow',
      vus: envNumber('AUTH_VUS', 2),
      duration,
    },
    secondary_flow: {
      executor: 'constant-vus',
      exec: 'secondaryFlow',
      vus: envNumber('SECONDARY_VUS', 2),
      duration,
    },
  },
  thresholds: {
    checks: ['rate>0.98'],
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    business_failures: ['count==0'],
    ws_connect_failures: ['count==0'],
  },
};

export function chatFlow() {
  const host = autoRegister();
  const guest = autoRegister();
  const room = createRoom(host.token, { duration: 'MIN_10', isPublic: 0 });

  joinRoom(guest.token, room.roomId);
  const content = randomMessage('mixed-chat');
  const sent = sendMessage(host.token, room.roomId, content);
  fetchHistory(host.token, room.roomId);
  fetchNew(guest.token, room.roomId);
  ackMessages(guest.token, room.roomId, sent.indexId);
  unread(guest.token);

  sleep(envNumber('K6_CHAT_THINK_MS', 200) / 1000);
}

export function roomFlow() {
  const host = autoRegister();
  const guest = autoRegister();
  const room = createRoom(host.token, { duration: 'MIN_10', isPublic: 1 });

  joinRoom(guest.token, room.roomId);
  roomMembers(host.token, room.roomId);
  listMyRooms(host.token);
  roomPricing(host.token);

  sleep(envNumber('K6_ROOM_THINK_MS', 300) / 1000);
}

export function authFlow() {
  const auth = autoRegister();
  checkLogin(auth.token);
  setPassword(auth.token);
  logout(auth.token);
  const relogin = login(auth.accountId);
  getMyAccount(relogin.token);
  getCreditBalance(relogin.token);

  sleep(envNumber('K6_AUTH_THINK_MS', 250) / 1000);
}

export function secondaryFlow() {
  const user = autoRegister();
  const room = createRoom(user.token, { duration: 'MIN_10', isPublic: 1 });

  getActiveGame(user.token, room.roomId);
  getMyAccount(user.token);

  sleep(envNumber('K6_SECONDARY_THINK_MS', 300) / 1000);
}
