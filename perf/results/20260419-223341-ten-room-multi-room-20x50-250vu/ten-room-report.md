# Ten-Room Report

Run directory: D:\javaproject\FlashChat\perf\results\20260419-223341-ten-room-multi-room-20x50-250vu

| Field | Value |
| --- | --- |
| totalSendSuccesses | 359147 |
| totalSendSuccessesPerSec | 782.5617957605965 |
| totalSendSuccessRate | 1 |
| totalHttpP95Ms | 82.63686 |
| totalHttpP99Ms | 138.0791479999993 |
| totalWsListenerLogins | 20 |
| totalWsBroadcastP95Ms | 103 |
| totalWsBroadcastP99Ms | 235 |
| roomSkew | 1.1792160757053567 |
| sendTimeoutFailures |  |
| sendTransportFailures |  |
| roomsMissingWsListener |  |
| roomsWithExtraWsListeners |  |

## Per Room Send Successes Per Second

- room_01: 40.86176789976936 msg/s, successRate=1, listenerLogins=1, wsP95=100
- room_02: 37.37545484907715 msg/s, successRate=1, listenerLogins=1, wsP95=107
- room_03: 40.69616802986148 msg/s, successRate=1, listenerLogins=1, wsP95=107
- room_04: 37.30137069674994 msg/s, successRate=1, listenerLogins=1, wsP95=106
- room_05: 43.986375971452254 msg/s, successRate=1, listenerLogins=1, wsP95=122
- room_06: 40.67655751895134 msg/s, successRate=1, listenerLogins=1, wsP95=105
- room_07: 40.75935745390528 msg/s, successRate=1, listenerLogins=1, wsP95=115.75
- room_08: 40.576326018743934 msg/s, successRate=1, listenerLogins=1, wsP95=78
- room_09: 40.659125953697874 msg/s, successRate=1, listenerLogins=1, wsP95=105
- room_10: 40.57414707308725 msg/s, successRate=1, listenerLogins=1, wsP95=107
- room_11: 37.61295992565556 msg/s, successRate=1, listenerLogins=1, wsP95=106
- room_12: 37.68050724101272 msg/s, successRate=1, listenerLogins=1, wsP95=103
- room_13: 41.00122042179705 msg/s, successRate=1, listenerLogins=1, wsP95=121
- room_14: 37.59334941474542 msg/s, successRate=1, listenerLogins=1, wsP95=78
- room_15: 37.65000200181917 msg/s, successRate=1, listenerLogins=1, wsP95=102
- room_16: 37.54977050161176 msg/s, successRate=1, listenerLogins=1, wsP95=101
- room_17: 37.56066522989518 msg/s, successRate=1, listenerLogins=1, wsP95=106.14999999999964
- room_18: 37.48876002322465 msg/s, successRate=1, listenerLogins=1, wsP95=104
- room_19: 37.45825478403109 msg/s, successRate=1, listenerLogins=1, wsP95=105.5
- room_20: 37.49965475150806 msg/s, successRate=1, listenerLogins=1, wsP95=105

## Runtime Summary

- sampleCount: 43
- processCpuUsage.max: 0.16443335548470331
- mailboxBacklog.max: 55
- hikariPending.max: 

## GC Summary

- gcLogPath: D:\javaproject\FlashChat\perf\logs\flashchat-local-perf-20260419-223230.gc.log
- mmuViolationCount: 0
- evacuationFailureCount: 2
- humongousAllocationCount: 0
- maxPauseMs: 148.823
