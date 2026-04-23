export function javaStringHash(value) {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash * 31) + value.charCodeAt(index)) | 0;
  }
  return hash;
}

export function shardForRoomId(roomId, shardCount = 12) {
  const hash = javaStringHash(roomId);
  const mixed = (hash ^ (hash >>> 16)) | 0;
  return ((mixed % shardCount) + shardCount) % shardCount;
}

export function createEmptyShardCounts(shardCount) {
  return Array.from({ length: shardCount }, () => 0);
}

export function projectShardCounts(shardCounts, shard) {
  return shardCounts.map((count, index) => (index === shard ? count + 1 : count));
}

export function isFeasibleShardLayout(shardCounts, totalRoomCount) {
  const shardCount = shardCounts.length;
  if (shardCount === 0) {
    return totalRoomCount === 0;
  }

  const balancedFloor = Math.floor(totalRoomCount / shardCount);
  const balancedCeil = Math.ceil(totalRoomCount / shardCount);
  const placedRooms = shardCounts.reduce((sum, count) => sum + count, 0);
  const remainingRooms = totalRoomCount - placedRooms;

  if (remainingRooms < 0) {
    return false;
  }

  for (const count of shardCounts) {
    if (count > balancedCeil) {
      return false;
    }
  }

  const deficits = shardCounts.reduce((sum, count) => sum + Math.max(0, balancedFloor - count), 0);
  return deficits <= remainingRooms;
}

export function canAcceptRoomOnShard(shardCounts, shard, totalRoomCount) {
  return isFeasibleShardLayout(projectShardCounts(shardCounts, shard), totalRoomCount);
}

export function formatShardCounts(shardCounts) {
  return shardCounts.map((count, shard) => `${shard}:${count}`).join(' ');
}
