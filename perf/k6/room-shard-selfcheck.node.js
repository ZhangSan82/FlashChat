import assert from 'node:assert/strict';

import {
  canAcceptRoomOnShard,
  createEmptyShardCounts,
  formatShardCounts,
  isFeasibleShardLayout,
  projectShardCounts,
  shardForRoomId,
} from './lib/room-shard-planner.js';

const empty = createEmptyShardCounts(8);
const balanced = [3, 3, 3, 3, 2, 2, 2, 2];
const impossible = [3, 3, 3, 3, 3, 3, 2, 0];

assert.equal(shardForRoomId('44jYZc', 8), 2, 'known roomId should map to shard 2');
assert.deepEqual(
  projectShardCounts(empty, 2),
  [0, 0, 1, 0, 0, 0, 0, 0],
  'projectShardCounts should increment only target shard',
);
assert.equal(isFeasibleShardLayout(balanced, 20), true, '2/3 balanced layout should be feasible');
assert.equal(isFeasibleShardLayout(impossible, 20), false, 'layout leaving one shard empty should be rejected');
assert.equal(
  canAcceptRoomOnShard([3, 3, 3, 3, 3, 2, 2, 0], 5, 20),
  false,
  'acceptance rule should block a placement that leaves an unrecoverable shard hole',
);
assert.equal(
  canAcceptRoomOnShard(empty, 2, 20),
  true,
  'acceptance rule should allow an early balanced placement',
);
assert.equal(formatShardCounts([2, 3, 2, 3]), '0:2 1:3 2:2 3:3', 'formatShardCounts should be stable');

console.log('room-shard selfcheck passed');
