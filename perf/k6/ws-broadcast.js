import { sleep } from 'k6';

import {
  autoRegister,
  connectWsAndAwaitBroadcast,
  createRoom,
  joinRoom,
  prepareRegisteredUser,
  sendMessage,
} from './lib/flow.js';
import { envNumber, randomMessage } from './lib/config.js';

const listenerCount = envNumber('WS_LISTENER_COUNT', 5);

export const options = {
  scenarios: {
    listeners: {
      executor: 'per-vu-iterations',
      exec: 'listenerScenario',
      vus: listenerCount,
      iterations: 1,
      maxDuration: '30s',
    },
    sender: {
      executor: 'per-vu-iterations',
      exec: 'senderScenario',
      startTime: '2s',
      vus: 1,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    business_failures: ['count==0'],
    ws_connect_failures: ['count==0'],
  },
};

export function setup() {
  const host = prepareRegisteredUser();
  const room = createRoom(host.token, { duration: 'MIN_10', isPublic: 1 });
  const content = randomMessage('ws-broadcast');
  const listeners = [];

  for (let i = 0; i < listenerCount; i += 1) {
    const guest = autoRegister();
    joinRoom(guest.token, room.roomId);
    listeners.push(guest);
  }

  return {
    host,
    room,
    content,
    listeners,
  };
}

export function listenerScenario(data) {
  const listener = data.listeners[(__VU - 1) % data.listeners.length];
  connectWsAndAwaitBroadcast(listener.token, data.room.roomId, data.content);
}

export function senderScenario(data) {
  sleep(1);
  sendMessage(data.host.token, data.room.roomId, data.content);
}
