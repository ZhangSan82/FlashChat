import { sleep } from 'k6';

import {
  ackMessages,
  autoRegister,
  createRoom,
  fetchHistory,
  fetchNew,
  joinRoom,
  sendMessage,
} from './lib/flow.js';
import { envNumber, randomMessage } from './lib/config.js';

const participantCount = envNumber('HOT_ROOM_PARTICIPANTS', 30);

export const options = {
  stages: [
    { duration: __ENV.BREAK_STAGE_1 || '1m', target: envNumber('BREAK_TARGET_1', 10) },
    { duration: __ENV.BREAK_STAGE_2 || '2m', target: envNumber('BREAK_TARGET_2', 20) },
    { duration: __ENV.BREAK_STAGE_3 || '2m', target: envNumber('BREAK_TARGET_3', 35) },
    { duration: __ENV.BREAK_STAGE_4 || '2m', target: envNumber('BREAK_TARGET_4', 50) },
    { duration: __ENV.BREAK_STAGE_5 || '1m', target: 0 },
  ],
  thresholds: {
    checks: ['rate>0.95'],
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1200', 'p(99)<2500'],
    business_failures: ['count==0'],
  },
};

export function setup() {
  const host = autoRegister();
  const room = createRoom(host.token, { duration: 'MIN_10', isPublic: 1, maxMembers: participantCount + 5 });
  const participants = [];

  for (let i = 0; i < participantCount; i += 1) {
    const guest = autoRegister();
    joinRoom(guest.token, room.roomId);
    participants.push(guest);
  }

  return {
    roomId: room.roomId,
    host,
    participants,
  };
}

export default function (data) {
  const sender = data.participants[(__VU + __ITER) % data.participants.length];
  const receiver = data.participants[(__VU + __ITER + 1) % data.participants.length];

  const sent = sendMessage(sender.token, data.roomId, randomMessage('breaking'));

  if ((__ITER % 2) === 0) {
    fetchNew(receiver.token, data.roomId);
    ackMessages(receiver.token, data.roomId, sent.indexId);
  }

  if ((__ITER % 3) === 0) {
    fetchHistory(data.host.token, data.roomId);
  }

  sleep(envNumber('K6_BREAK_THINK_MS', 50) / 1000);
}
