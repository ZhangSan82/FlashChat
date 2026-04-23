import { sleep } from 'k6';

import {
  ackMessages,
  autoRegister,
  createRoom,
  fetchHistory,
  fetchNew,
  joinRoom,
  sendMessage,
  unread,
} from './lib/flow.js';
import { envNumber, randomMessage } from './lib/config.js';

export const options = {
  stages: [
    { duration: __ENV.STRESS_STAGE_1 || '1m', target: envNumber('STRESS_TARGET_1', 10) },
    { duration: __ENV.STRESS_STAGE_2 || '2m', target: envNumber('STRESS_TARGET_2', 25) },
    { duration: __ENV.STRESS_STAGE_3 || '2m', target: envNumber('STRESS_TARGET_3', 45) },
    { duration: __ENV.STRESS_STAGE_4 || '1m', target: 0 },
  ],
  thresholds: {
    checks: ['rate>0.97'],
    http_req_failed: ['rate<0.03'],
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    business_failures: ['count==0'],
  },
};

export default function () {
  const host = autoRegister();
  const guest = autoRegister();
  const room = createRoom(host.token, { duration: 'MIN_10', isPublic: 0 });

  joinRoom(guest.token, room.roomId);
  const sent = sendMessage(host.token, room.roomId, randomMessage('stress'));
  fetchHistory(host.token, room.roomId);
  fetchNew(guest.token, room.roomId);
  ackMessages(guest.token, room.roomId, sent.indexId);
  unread(guest.token);

  sleep(envNumber('K6_STRESS_THINK_MS', 100) / 1000);
}
