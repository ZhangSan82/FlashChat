# Ten-Room Report

Run directory: D:\javaproject\FlashChat\perf\results\20260419-233835-ten-room-multi-room-20x50-250vu

| Field | Value |
| --- | --- |
| totalSendSuccesses | 365881 |
| totalSendSuccessesPerSec | 820.1616258518276 |
| totalSendSuccessRate | 1 |
| totalHttpP95Ms | 71.2581 |
| totalHttpP99Ms | 144.57695 |
| totalWsListenerLogins | 20 |
| totalWsBroadcastP95Ms | 116 |
| totalWsBroadcastP99Ms | 276 |
| roomSkew | 1.18573645192805 |
| sendTimeoutFailures |  |
| sendTransportFailures |  |
| roomsMissingWsListener |  |
| roomsWithExtraWsListeners |  |

## Per Room Send Successes Per Second

- room_01: 39.2101999267526 msg/s, successRate=1, listenerLogins=1, wsP95=131
- room_02: 42.80349689008352 msg/s, successRate=1, listenerLogins=1, wsP95=122.29999999999927
- room_03: 39.075703471481326 msg/s, successRate=1, listenerLogins=1, wsP95=122.45000000000073
- room_04: 46.251089360203885 msg/s, successRate=1, listenerLogins=1, wsP95=133
- room_05: 39.00621363625783 msg/s, successRate=1, listenerLogins=1, wsP95=119
- room_06: 39.08242829424489 msg/s, successRate=1, listenerLogins=1, wsP95=124
- room_07: 39.07794507906918 msg/s, successRate=1, listenerLogins=1, wsP95=112
- room_08: 42.5972689920009 msg/s, successRate=1, listenerLogins=1, wsP95=73
- room_09: 42.63313471340657 msg/s, successRate=1, listenerLogins=1, wsP95=133
- room_10: 39.055529003190635 msg/s, successRate=1, listenerLogins=1, wsP95=110
- room_11: 39.51505855870083 msg/s, successRate=1, listenerLogins=1, wsP95=110
- room_12: 39.46574319176803 msg/s, successRate=1, listenerLogins=1, wsP95=115
- room_13: 42.83936261148919 msg/s, successRate=1, listenerLogins=1, wsP95=119.5
- room_14: 42.96489263640905 msg/s, successRate=1, listenerLogins=1, wsP95=75
- room_15: 42.95592620605763 msg/s, successRate=1, listenerLogins=1, wsP95=126
- room_16: 42.875228332894864 msg/s, successRate=1, listenerLogins=1, wsP95=131
- room_17: 39.36935406549028 msg/s, successRate=1, listenerLogins=1, wsP95=110
- room_18: 39.324521913733186 msg/s, successRate=1, listenerLogins=1, wsP95=122
- room_19: 42.7698727762657 msg/s, successRate=1, listenerLogins=1, wsP95=120
- room_20: 39.28865619232751 msg/s, successRate=1, listenerLogins=1, wsP95=133

## Runtime Summary

- sampleCount: 42
- processCpuUsage.max: 0.18341734764942416
- mailboxBacklog.max: 112
- hikariPending.max: 

## GC Summary

- gcLogPath: D:\javaproject\FlashChat\perf\logs\flashchat-local-perf-20260419-233725.gc.log
- mmuViolationCount: 0
- evacuationFailureCount: 2
- humongousAllocationCount: 0
- maxPauseMs: 138.904
