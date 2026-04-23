import { autoRegister, createRoom, joinRoom, roomMembers } from './flow.js';
import { randomSuffix } from './config.js';
import {
  createEmptyShardCounts,
  formatShardCounts,
  shardForRoomId,
} from './room-shard-planner.js';

export function zeroPadRoomNumber(value) {
  return String(value).padStart(2, '0');
}

export function buildRoomKey(index) {
  return `room_${zeroPadRoomNumber(index + 1)}`;
}

export function buildRoomTitle(prefix, index) {
  return `${prefix}-${zeroPadRoomNumber(index + 1)}-${randomSuffix()}`;
}

function getMaxShardOccupancy(shardCounts) {
  if (!Array.isArray(shardCounts) || shardCounts.length === 0) {
    return 0;
  }
  return shardCounts.reduce((maxCount, currentCount) => Math.max(maxCount, currentCount), 0);
}

function getActiveShardCount(shardCounts) {
  if (!Array.isArray(shardCounts) || shardCounts.length === 0) {
    return 0;
  }
  return shardCounts.filter((count) => count > 0).length;
}

function getEmptyShardCount(shardCounts) {
  if (!Array.isArray(shardCounts) || shardCounts.length === 0) {
    return 0;
  }
  return shardCounts.filter((count) => count <= 0).length;
}

function validateShardLayout(shardCounts, options) {
  const actualMaxRoomsPerShard = getMaxShardOccupancy(shardCounts);
  const activeShardCount = getActiveShardCount(shardCounts);
  const emptyShardCount = getEmptyShardCount(shardCounts);
  const maxRoomsPerShard = Number(options.maxRoomsPerShard || 0);
  const minActiveShards = Number(options.minActiveShards || 0);
  const maxEmptyShards = Number(options.maxEmptyShards || 0);

  if (maxRoomsPerShard > 0 && actualMaxRoomsPerShard > maxRoomsPerShard) {
    return {
      ok: false,
      reason: `actualMaxRoomsPerShard=${actualMaxRoomsPerShard} maxRoomsPerShard=${maxRoomsPerShard}`,
      actualMaxRoomsPerShard,
      activeShardCount,
      emptyShardCount,
      maxRoomsPerShard,
      minActiveShards,
      maxEmptyShards,
    };
  }

  if (minActiveShards > 0 && activeShardCount < minActiveShards) {
    return {
      ok: false,
      reason: `activeShardCount=${activeShardCount} minActiveShards=${minActiveShards}`,
      actualMaxRoomsPerShard,
      activeShardCount,
      emptyShardCount,
      maxRoomsPerShard,
      minActiveShards,
      maxEmptyShards,
    };
  }

  if (maxEmptyShards > 0 && emptyShardCount > maxEmptyShards) {
    return {
      ok: false,
      reason: `emptyShardCount=${emptyShardCount} maxEmptyShards=${maxEmptyShards}`,
      actualMaxRoomsPerShard,
      activeShardCount,
      emptyShardCount,
      maxRoomsPerShard,
      minActiveShards,
      maxEmptyShards,
    };
  }

  return {
    ok: true,
    reason: '',
    actualMaxRoomsPerShard,
    activeShardCount,
    emptyShardCount,
    maxRoomsPerShard,
    minActiveShards,
    maxEmptyShards,
  };
}

function buildShardCountsForRooms(rooms, shardCount) {
  const shardCounts = createEmptyShardCounts(shardCount);
  for (const room of rooms) {
    const shard = shardForRoomId(room.roomId, shardCount);
    shardCounts[shard] += 1;
    room.shard = shard;
  }
  return shardCounts;
}

function normalizeFixedTopology(options, fixedTopology) {
  if (!fixedTopology || !Array.isArray(fixedTopology.rooms)) {
    return null;
  }

  const roomCount = Number(options.roomCount || 10);
  const participantsPerRoom = Number(options.participantsPerRoom || 20);
  const shardCount = Number(options.shardCount || 12);
  const roomDuration = options.roomDuration || 'DAY_3';
  if (fixedTopology.rooms.length !== roomCount) {
    return null;
  }
  if (fixedTopology.roomDuration !== roomDuration) {
    return null;
  }

  const rooms = fixedTopology.rooms.map((room, index) => {
    if (!room || !room.roomId || !Array.isArray(room.participants) || room.participants.length < participantsPerRoom) {
      return null;
    }
    const host = room.host || room.participants[0];
    if (!host || !host.token) {
      return null;
    }

    const participants = room.participants
      .slice(0, participantsPerRoom)
      .map((participant) => ({
        token: participant.token,
        accountId: participant.accountId,
      }));

    if (participants.some((participant) => !participant || !participant.token)) {
      return null;
    }

    return {
      index,
      key: buildRoomKey(index),
      roomId: room.roomId,
      host: {
        token: host.token,
        accountId: host.accountId,
      },
      participants,
    };
  });

  if (rooms.some((room) => room === null)) {
    return null;
  }

  const shardCounts = buildShardCountsForRooms(rooms, shardCount);
  const shardLayout = validateShardLayout(shardCounts, options);
  return {
    roomCount,
    participantsPerRoom,
    totalParticipants: roomCount * participantsPerRoom,
    shardCount,
    roomDuration,
    shardCounts,
    maxRoomsPerShard: Number(options.maxRoomsPerShard || 0),
    actualMaxRoomsPerShard: shardLayout.actualMaxRoomsPerShard,
    minActiveShards: Number(options.minActiveShards || 0),
    activeShardCount: shardLayout.activeShardCount,
    maxEmptyShards: Number(options.maxEmptyShards || 0),
    emptyShardCount: shardLayout.emptyShardCount,
    rejectedRoomCreations: 0,
    topologySource: 'fixed-cache',
    rooms,
  };
}

function canReuseFixedTopology(normalizedTopology, options) {
  if (!normalizedTopology) {
    return false;
  }

  const shardLayout = validateShardLayout(normalizedTopology.shardCounts, options);
  if (!shardLayout.ok) {
    return false;
  }

  try {
    for (const room of normalizedTopology.rooms) {
      roomMembers(room.host.token, room.roomId);
    }
    return true;
  } catch (error) {
    console.warn(`[ten-room-init] fixed topology validation failed, will rebuild. reason=${error}`);
    return false;
  }
}

function rehydrateFixedTopologyParticipants(normalizedTopology) {
  let rehydratedParticipants = 0;
  for (const room of normalizedTopology.rooms) {
    for (const participant of room.participants) {
      joinRoom(participant.token, room.roomId);
      rehydratedParticipants += 1;
    }
  }
  return rehydratedParticipants;
}

export function setupTenRoomTopology(options = {}) {
  const roomCount = Number(options.roomCount || 10);
  const participantsPerRoom = Number(options.participantsPerRoom || 20);
  const roomTitlePrefix = options.roomTitlePrefix || 'ten-room';
  const shardCount = Number(options.shardCount || 12);
  const maxRoomsPerShard = Number(options.maxRoomsPerShard || 0);
  const minActiveShards = Number(options.minActiveShards || 0);
  const maxEmptyShards = Number(options.maxEmptyShards || 0);
  const roomDuration = options.roomDuration || 'DAY_3';
  const fixedTopology = options.fixedTopology || null;

  const normalizedFixedTopology = normalizeFixedTopology(options, fixedTopology);
  if (canReuseFixedTopology(normalizedFixedTopology, options)) {
    try {
      // fixed-cache only reuses roomId/token from the previous run.
      // After app restart, roomChannelManager is empty, so every participant
      // must replay an idempotent join to restore in-memory membership.
      const rehydratedParticipants = rehydrateFixedTopologyParticipants(normalizedFixedTopology);
      console.log(
        `[ten-room-init] reusing fixed topology, rehydratedParticipants=${rehydratedParticipants}, roomDuration=${roomDuration}, shardCount=${shardCount}, shardLayout=${formatShardCounts(normalizedFixedTopology.shardCounts)}, activeShardCount=${normalizedFixedTopology.activeShardCount}, emptyShardCount=${normalizedFixedTopology.emptyShardCount}, actualMaxRoomsPerShard=${normalizedFixedTopology.actualMaxRoomsPerShard}, maxRoomsPerShard=${maxRoomsPerShard}, minActiveShards=${minActiveShards}, maxEmptyShards=${maxEmptyShards}`,
      );
      return normalizedFixedTopology;
    } catch (error) {
      console.warn(`[ten-room-init] fixed topology rehydrate failed, will rebuild. reason=${error}`);
    }
  }

  const rooms = [];
  const shardCounts = createEmptyShardCounts(shardCount);

  // 旧方案是在这里 create -> 算 shard -> 不合格就 closeRoom() 重建。
  // 那样会制造真实废房间、窗口删除和延时任务噪音，污染正式压测。
  // 现在改成“整轮 guard”：
  // 1. 先只创建 host-only 房间并统计 shard 分布
  // 2. 分布不达标则整轮失败，由外层脚本重置环境后重跑
  // 3. 只有通过 guard，才继续补齐 guest
  for (let roomIndex = 0; roomIndex < roomCount; roomIndex += 1) {
    const host = autoRegister();
    const room = createRoom(host.token, {
      duration: roomDuration,
      isPublic: 1,
      maxMembers: participantsPerRoom + 5,
      title: buildRoomTitle(roomTitlePrefix, roomIndex),
    });
    const shard = shardForRoomId(room.roomId, shardCount);
    shardCounts[shard] += 1;

    rooms.push({
      index: roomIndex,
      key: buildRoomKey(roomIndex),
      roomId: room.roomId,
      shard,
      host,
      participants: [host],
    });
  }

  const shardLayout = validateShardLayout(shardCounts, options);
  console.log(
    `[ten-room-init] shardCount=${shardCount}, shardLayout=${formatShardCounts(shardCounts)}, activeShardCount=${shardLayout.activeShardCount}, emptyShardCount=${shardLayout.emptyShardCount}, actualMaxRoomsPerShard=${shardLayout.actualMaxRoomsPerShard}, maxRoomsPerShard=${maxRoomsPerShard}, minActiveShards=${minActiveShards}, maxEmptyShards=${maxEmptyShards}`,
  );

  if (!shardLayout.ok) {
    throw new Error(
      `TEN_ROOM_SHARD_GUARD_FAILED shardLayout=${formatShardCounts(shardCounts)} activeShardCount=${shardLayout.activeShardCount} emptyShardCount=${shardLayout.emptyShardCount} actualMaxRoomsPerShard=${shardLayout.actualMaxRoomsPerShard} maxRoomsPerShard=${maxRoomsPerShard} minActiveShards=${minActiveShards} maxEmptyShards=${maxEmptyShards} reason=${shardLayout.reason}`,
    );
  }

  for (const room of rooms) {
    for (let memberIndex = 1; memberIndex < participantsPerRoom; memberIndex += 1) {
      const guest = autoRegister();
      joinRoom(guest.token, room.roomId);
      room.participants.push(guest);
    }
  }

  return {
    roomCount,
    participantsPerRoom,
    totalParticipants: roomCount * participantsPerRoom,
    shardCount,
    roomDuration,
    shardCounts,
    maxRoomsPerShard,
    actualMaxRoomsPerShard: shardLayout.actualMaxRoomsPerShard,
    minActiveShards,
    activeShardCount: shardLayout.activeShardCount,
    maxEmptyShards,
    emptyShardCount: shardLayout.emptyShardCount,
    rejectedRoomCreations: 0,
    topologySource: 'fresh-build',
    rooms,
  };
}
