import { check } from 'k6';

import {
  canAcceptRoomOnShard,
  createEmptyShardCounts,
  formatShardCounts,
  isFeasibleShardLayout,
  javaStringHash,
  projectShardCounts,
  shardForRoomId,
} from './lib/room-shard-planner.js';

export const options = {
  scenarios: {
    selfcheck: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '10s',
    },
  },
};

export default function () {
  const empty = createEmptyShardCounts(8);
  const impossible = [3, 3, 3, 3, 3, 3, 2, 0];
  const balanced = [3, 3, 3, 3, 2, 2, 2, 2];
  const projected = projectShardCounts(empty, 2);

  check(
    {
      knownHash: javaStringHash('44jYZc'),
      knownShard: shardForRoomId('44jYZc', 8),
      projected,
      balancedFeasible: isFeasibleShardLayout(balanced, 20),
      impossibleFeasible: isFeasibleShardLayout(impossible, 20),
      rejectImpossibleNextRoom: canAcceptRoomOnShard([3, 3, 3, 3, 3, 2, 2, 0], 5, 20),
      acceptEarlyRoom: canAcceptRoomOnShard(empty, 2, 20),
      formatted: formatShardCounts([2, 3, 2, 3]),
    },
    {
      'javaStringHash matches Java semantics for known roomId': (state) => state.knownHash === 1574174898,
      'shardForRoomId matches Java mailbox shard mapping': (state) => state.knownShard === 2,
      'projectShardCounts increments only target shard': (state) => JSON.stringify(state.projected) === JSON.stringify([0, 0, 1, 0, 0, 0, 0, 0]),
      'balanced layout is feasible': (state) => state.balancedFeasible === true,
      'impossible layout is rejected': (state) => state.impossibleFeasible === false,
      'acceptance rule blocks layout that would leave permanent shard hole': (state) => state.rejectImpossibleNextRoom === false,
      'acceptance rule allows early balanced placement': (state) => state.acceptEarlyRoom === true,
      'formatShardCounts is stable for diagnostics': (state) => state.formatted === '0:2 1:3 2:2 3:3',
    },
  );
}
