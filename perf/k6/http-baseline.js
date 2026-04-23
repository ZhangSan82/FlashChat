import { sleep } from 'k6';

import {
  ackMessages,
  autoRegister,
  createRoom,
  fetchHistory,
  fetchNew,
  getMyAccount,
  joinRoom,
  prepareRegisteredUser,
  sendMessage,
  unread,
} from './lib/flow.js';
import { envNumber, randomMessage } from './lib/config.js';

export const options = {
  vus: envNumber('K6_VUS', 12),
  duration: __ENV.K6_DURATION || '3m',
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<350', 'p(99)<800'],
    business_failures: ['count==0'],
  },
};

export default function () {
  const host = prepareRegisteredUser();
  const guest = autoRegister();
  const room = createRoom(host.token, { duration: 'MIN_10', isPublic: 0 });

  joinRoom(guest.token, room.roomId);
  const sent = sendMessage(host.token, room.roomId, randomMessage('baseline'));
  fetchHistory(host.token, room.roomId);
  fetchNew(guest.token, room.roomId);
  ackMessages(guest.token, room.roomId, sent.indexId);
  unread(guest.token);
  getMyAccount(host.token);

  sleep(envNumber('K6_THINK_TIME_MS', 300) / 1000);
}
