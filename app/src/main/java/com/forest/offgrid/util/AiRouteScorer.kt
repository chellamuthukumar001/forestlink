package com.forest.offgrid.util

import com.forest.offgrid.data.model.AiRouteInfo
import com.forest.offgrid.data.model.RoutingMode
import kotlin.math.min

/**
 * Native Android in-app AI Route Reliability Scorer.
 * Zero-dependency pure Kotlin evaluation of trained ForestLink Decision Forest.
 */
object AiRouteScorer {

    const val CONFIDENCE_THRESHOLD = 0.35f
    private const val LORA_SF7_SNR_LIMIT = -7.5f

    fun extractFeatures(
        rssi: Int,
        snr: Float,
        packetLossRate: Int,
        batteryPct: Int,
        batteryVoltageMv: Int,
        queueLen: Int,
        distanceM: Int,
        hopCount: Int,
        lastSeenSec: Int
    ): FloatArray {
        val linkMargin = snr - LORA_SF7_SNR_LIMIT
        val congestionRatio = min(2.0f, queueLen / 32.0f)
        val freshness = 1.0f / (1.0f + (lastSeenSec / 60.0f))

        return floatArrayOf(
            rssi.toFloat(),
            snr,
            linkMargin,
            packetLossRate.toFloat(),
            batteryPct.toFloat(),
            batteryVoltageMv.toFloat(),
            queueLen.toFloat(),
            congestionRatio,
            distanceM.toFloat(),
            hopCount.toFloat(),
            lastSeenSec.toFloat(),
            freshness
        )
    }

    fun predictReliability(features: FloatArray): Float {
        var sum = 0.0f
        sum += evalTree0(features)
        sum += evalTree1(features)
        sum += evalTree2(features)
        sum += evalTree3(features)
        sum += evalTree4(features)
        sum += evalTree5(features)
        sum += evalTree6(features)
        sum += evalTree7(features)
        sum += evalTree8(features)
        sum += evalTree9(features)
        sum += evalTree10(features)
        sum += evalTree11(features)
        return sum / 12.0f
    }

    fun evaluateNextHop(
        destNodeId: String,
        candidateNextHopId: String,
        rssi: Int,
        snr: Float,
        packetLossRate: Int,
        batteryPct: Int,
        batteryVoltageMv: Int,
        queueLen: Int,
        distanceM: Int,
        hopCount: Int,
        lastSeenSec: Int,
        isEmergency: Boolean = false
    ): AiRouteInfo {
        val features = extractFeatures(
            rssi, snr, packetLossRate, batteryPct, batteryVoltageMv,
            queueLen, distanceM, hopCount, lastSeenSec
        )
        val score = predictReliability(features)

        val mode = when {
            isEmergency -> RoutingMode.EMERGENCY_PRIORITY
            score < CONFIDENCE_THRESHOLD -> RoutingMode.DETERMINISTIC_FALLBACK
            else -> RoutingMode.AI_DIRECT
        }

        val reason = when (mode) {
            RoutingMode.EMERGENCY_PRIORITY -> "Emergency SOS override (max reliability)"
            RoutingMode.DETERMINISTIC_FALLBACK -> "Confidence below 35% -> Fallback to flood"
            RoutingMode.AI_DIRECT -> "Optimal AI next-hop selected"
        }

        return AiRouteInfo(
            destNodeId = destNodeId,
            nextHopNodeId = if (mode == RoutingMode.DETERMINISTIC_FALLBACK) "FLOOD" else candidateNextHopId,
            reliabilityScore = score,
            mode = mode,
            reason = reason,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }

    // Tree 0
    private fun evalTree0(f: FloatArray): Float {
        if (f[2] <= 1.470000f) {
            if (f[4] <= 61.500000f) {
                if (f[2] <= 0.395000f) {
                    if (f[10] <= 279.000000f) {
                        return 0.000000f
                    } else {
                        return 0.083333f
                    }
                } else {
                    if (f[11] <= 0.827626f) {
                        return 0.000000f
                    } else {
                        return 0.562500f
                    }
                }
            } else {
                if (f[2] <= 0.090000f) {
                    if (f[10] <= 2.500000f) {
                        return 0.018182f
                    } else {
                        return 0.000000f
                    }
                } else {
                    if (f[8] <= 502.500000f) {
                        return 0.600000f
                    } else {
                        return 0.034483f
                    }
                }
            }
        } else {
            if (f[6] <= 8.500000f) {
                if (f[3] <= 36.500000f) {
                    if (f[7] <= 0.078125f) {
                        return 0.791925f
                    } else {
                        return 0.632327f
                    }
                } else {
                    if (f[11] <= 0.025647f) {
                        return 0.843750f
                    } else {
                        return 0.496307f
                    }
                }
            } else {
                if (f[5] <= 3659.500000f) {
                    if (f[3] <= 0.500000f) {
                        return 0.733333f
                    } else {
                        return 0.169643f
                    }
                } else {
                    if (f[0] <= -115.500000f) {
                        return 0.176471f
                    } else {
                        return 0.411504f
                    }
                }
            }
        }
    }

    // Tree 1
    private fun evalTree1(f: FloatArray): Float {
        if (f[2] <= 1.495000f) {
            if (f[2] <= 0.265000f) {
                if (f[10] <= 3142.500000f) {
                    if (f[5] <= 3657.500000f) {
                        return 0.020979f
                    } else {
                        return 0.001885f
                    }
                } else {
                    if (f[3] <= 82.500000f) {
                        return 0.000000f
                    } else {
                        return 0.375000f
                    }
                }
            } else {
                if (f[10] <= 23.500000f) {
                    if (f[9] <= 3.500000f) {
                        return 0.541667f
                    } else {
                        return 0.000000f
                    }
                } else {
                    if (f[2] <= 0.775000f) {
                        return 0.058824f
                    } else {
                        return 0.000000f
                    }
                }
            }
        } else {
            if (f[7] <= 0.296875f) {
                if (f[5] <= 3312.500000f) {
                    if (f[4] <= 5.500000f) {
                        return 0.027778f
                    } else {
                        return 0.300000f
                    }
                } else {
                    if (f[1] <= 13.265000f) {
                        return 0.543230f
                    } else {
                        return 0.712881f
                    }
                }
            } else {
                if (f[6] <= 25.500000f) {
                    if (f[0] <= -73.500000f) {
                        return 0.381323f
                    } else {
                        return 0.623377f
                    }
                } else {
                    if (f[3] <= 77.500000f) {
                        return 0.197279f
                    } else {
                        return 0.066176f
                    }
                }
            }
        }
    }

    // Tree 2
    private fun evalTree2(f: FloatArray): Float {
        if (f[2] <= 0.805000f) {
            if (f[1] <= -7.400000f) {
                if (f[6] <= 3.500000f) {
                    if (f[4] <= 41.500000f) {
                        return 0.114286f
                    } else {
                        return 0.007380f
                    }
                } else {
                    if (f[4] <= 48.500000f) {
                        return 0.008929f
                    } else {
                        return 0.000000f
                    }
                }
            } else {
                if (f[8] <= 1208.500000f) {
                    if (f[2] <= 0.335000f) {
                        return 0.833333f
                    } else {
                        return 0.166667f
                    }
                } else {
                    if (f[9] <= 3.500000f) {
                        return 0.000000f
                    } else {
                        return 0.333333f
                    }
                }
            }
        } else {
            if (f[5] <= 3389.000000f) {
                if (f[0] <= -69.000000f) {
                    if (f[11] <= 0.839202f) {
                        return 0.081818f
                    } else {
                        return 0.393939f
                    }
                } else {
                    return 0.833333f
                }
            } else {
                if (f[3] <= 37.500000f) {
                    if (f[11] <= 0.018956f) {
                        return 0.181818f
                    } else {
                        return 0.650386f
                    }
                } else {
                    if (f[5] <= 3614.500000f) {
                        return 0.237113f
                    } else {
                        return 0.406435f
                    }
                }
            }
        }
    }

    // Tree 3
    private fun evalTree3(f: FloatArray): Float {
        if (f[2] <= 1.525000f) {
            if (f[2] <= 0.000000f) {
                if (f[8] <= 3021.500000f) {
                    if (f[11] <= 0.960061f) {
                        return 0.002033f
                    } else {
                        return 0.030769f
                    }
                } else {
                    if (f[5] <= 4049.000000f) {
                        return 0.000000f
                    } else {
                        return 0.269231f
                    }
                }
            } else {
                if (f[7] <= 0.015625f) {
                    return 0.583333f
                } else {
                    if (f[1] <= -7.385000f) {
                        return 0.500000f
                    } else {
                        return 0.136364f
                    }
                }
            }
        } else {
            if (f[0] <= -87.500000f) {
                if (f[10] <= 22.500000f) {
                    if (f[3] <= 71.500000f) {
                        return 0.493235f
                    } else {
                        return 0.181818f
                    }
                } else {
                    if (f[6] <= 26.500000f) {
                        return 0.556136f
                    } else {
                        return 0.182692f
                    }
                }
            } else {
                if (f[4] <= 11.500000f) {
                    if (f[5] <= 3147.000000f) {
                        return 0.000000f
                    } else {
                        return 0.333333f
                    }
                } else {
                    if (f[9] <= 2.500000f) {
                        return 0.639286f
                    } else {
                        return 0.596026f
                    }
                }
            }
        }
    }

    // Tree 4
    private fun evalTree4(f: FloatArray): Float {
        if (f[1] <= -6.030000f) {
            if (f[1] <= -6.810000f) {
                if (f[7] <= 0.015625f) {
                    if (f[1] <= -9.165000f) {
                        return 0.000000f
                    } else {
                        return 0.285714f
                    }
                } else {
                    if (f[6] <= 4.500000f) {
                        return 0.005970f
                    } else {
                        return 0.000000f
                    }
                }
            } else {
                if (f[9] <= 1.500000f) {
                    if (f[2] <= 1.050000f) {
                        return 0.000000f
                    } else {
                        return 0.166667f
                    }
                } else {
                    if (f[0] <= -123.000000f) {
                        return 0.090909f
                    } else {
                        return 0.571429f
                    }
                }
            }
        } else {
            if (f[6] <= 8.500000f) {
                if (f[3] <= 19.500000f) {
                    if (f[7] <= 0.046875f) {
                        return 0.904412f
                    } else {
                        return 0.674938f
                    }
                } else {
                    if (f[7] <= 0.109375f) {
                        return 0.633110f
                    } else {
                        return 0.497537f
                    }
                }
            } else {
                if (f[7] <= 0.859375f) {
                    if (f[0] <= -98.500000f) {
                        return 0.301837f
                    } else {
                        return 0.511936f
                    }
                } else {
                    if (f[3] <= 77.500000f) {
                        return 0.230159f
                    } else {
                        return 0.083333f
                    }
                }
            }
        }
    }

    // Tree 5
    private fun evalTree5(f: FloatArray): Float {
        if (f[1] <= -6.005000f) {
            if (f[5] <= 3106.000000f) {
                if (f[11] <= 0.794737f) {
                    return 0.000000f
                } else {
                    return 0.500000f
                }
            } else {
                if (f[6] <= 0.500000f) {
                    if (f[0] <= -124.500000f) {
                        return 0.000000f
                    } else {
                        return 0.875000f
                    }
                } else {
                    if (f[3] <= 71.500000f) {
                        return 0.066667f
                    } else {
                        return 0.005828f
                    }
                }
            }
        } else {
            if (f[6] <= 9.500000f) {
                if (f[1] <= 14.930000f) {
                    if (f[11] <= 0.022084f) {
                        return 0.900000f
                    } else {
                        return 0.468354f
                    }
                } else {
                    if (f[4] <= 13.500000f) {
                        return 0.155172f
                    } else {
                        return 0.700000f
                    }
                }
            } else {
                if (f[4] <= 18.500000f) {
                    if (f[11] <= 0.037037f) {
                        return 0.666667f
                    } else {
                        return 0.106796f
                    }
                } else {
                    if (f[3] <= 27.500000f) {
                        return 0.530612f
                    } else {
                        return 0.288851f
                    }
                }
            }
        }
    }

    // Tree 6
    private fun evalTree6(f: FloatArray): Float {
        if (f[2] <= 2.245000f) {
            if (f[1] <= -7.395000f) {
                if (f[3] <= 60.500000f) {
                    return 0.142857f
                } else {
                    if (f[11] <= 0.944940f) {
                        return 0.004926f
                    } else {
                        return 0.039604f
                    }
                }
            } else {
                if (f[7] <= 0.234375f) {
                    if (f[10] <= 4.500000f) {
                        return 0.900000f
                    } else {
                        return 0.175000f
                    }
                } else {
                    if (f[10] <= 5.500000f) {
                        return 0.500000f
                    } else {
                        return 0.036364f
                    }
                }
            }
        } else {
            if (f[5] <= 3470.500000f) {
                if (f[5] <= 3237.000000f) {
                    if (f[7] <= 0.328125f) {
                        return 0.147541f
                    } else {
                        return 0.000000f
                    }
                } else {
                    if (f[8] <= 1283.500000f) {
                        return 0.230769f
                    } else {
                        return 0.647059f
                    }
                }
            } else {
                if (f[0] <= -102.500000f) {
                    if (f[3] <= 74.500000f) {
                        return 0.495413f
                    } else {
                        return 0.137681f
                    }
                } else {
                    if (f[6] <= 9.500000f) {
                        return 0.692872f
                    } else {
                        return 0.417603f
                    }
                }
            }
        }
    }

    // Tree 7
    private fun evalTree7(f: FloatArray): Float {
        if (f[3] <= 64.500000f) {
            if (f[8] <= 248.500000f) {
                if (f[4] <= 9.000000f) {
                    if (f[7] <= 0.312500f) {
                        return 0.166667f
                    } else {
                        return 0.750000f
                    }
                } else {
                    if (f[7] <= 0.328125f) {
                        return 0.711165f
                    } else {
                        return 0.457746f
                    }
                }
            } else {
                if (f[6] <= 8.500000f) {
                    if (f[2] <= 20.815000f) {
                        return 0.510965f
                    } else {
                        return 0.666124f
                    }
                } else {
                    if (f[6] <= 23.500000f) {
                        return 0.396437f
                    } else {
                        return 0.133333f
                    }
                }
            }
        } else {
            if (f[2] <= 0.655000f) {
                if (f[3] <= 78.500000f) {
                    if (f[8] <= 2989.000000f) {
                        return 0.004619f
                    } else {
                        return 0.027778f
                    }
                } else {
                    return 0.000000f
                }
            } else {
                if (f[7] <= 0.984375f) {
                    if (f[7] <= 0.921875f) {
                        return 0.331148f
                    } else {
                        return 0.733333f
                    }
                } else {
                    if (f[0] <= -103.500000f) {
                        return 0.048077f
                    } else {
                        return 0.208955f
                    }
                }
            }
        }
    }

    // Tree 8
    private fun evalTree8(f: FloatArray): Float {
        if (f[1] <= -6.005000f) {
            if (f[2] <= 0.100000f) {
                if (f[7] <= 0.296875f) {
                    if (f[10] <= 344.500000f) {
                        return 0.006468f
                    } else {
                        return 0.076087f
                    }
                } else {
                    return 0.000000f
                }
            } else {
                if (f[10] <= 23.500000f) {
                    if (f[4] <= 21.000000f) {
                        return 0.750000f
                    } else {
                        return 0.189189f
                    }
                } else {
                    if (f[5] <= 3779.500000f) {
                        return 0.000000f
                    } else {
                        return 0.142857f
                    }
                }
            }
        } else {
            if (f[5] <= 3327.000000f) {
                if (f[6] <= 2.500000f) {
                    if (f[2] <= 13.200000f) {
                        return 0.181818f
                    } else {
                        return 0.600000f
                    }
                } else {
                    if (f[10] <= 7.500000f) {
                        return 0.333333f
                    } else {
                        return 0.067416f
                    }
                }
            } else {
                if (f[8] <= 553.500000f) {
                    if (f[7] <= 0.296875f) {
                        return 0.675641f
                    } else {
                        return 0.377451f
                    }
                } else {
                    if (f[2] <= 9.470000f) {
                        return 0.354467f
                    } else {
                        return 0.498433f
                    }
                }
            }
        }
    }

    // Tree 9
    private fun evalTree9(f: FloatArray): Float {
        if (f[4] <= 11.500000f) {
            if (f[6] <= 0.500000f) {
                if (f[11] <= 0.714691f) {
                    return 0.800000f
                } else {
                    return 0.100000f
                }
            } else {
                if (f[0] <= -115.500000f) {
                    if (f[0] <= -124.500000f) {
                        return 0.000000f
                    } else {
                        return 0.068966f
                    }
                } else {
                    if (f[1] <= 10.655000f) {
                        return 0.333333f
                    } else {
                        return 0.096386f
                    }
                }
            }
        } else {
            if (f[1] <= -4.660000f) {
                if (f[0] <= -120.500000f) {
                    if (f[4] <= 31.500000f) {
                        return 0.000000f
                    } else {
                        return 0.027327f
                    }
                } else {
                    return 0.875000f
                }
            } else {
                if (f[2] <= 19.395000f) {
                    if (f[3] <= 75.500000f) {
                        return 0.486983f
                    } else {
                        return 0.125000f
                    }
                } else {
                    if (f[7] <= 0.296875f) {
                        return 0.701214f
                    } else {
                        return 0.400722f
                    }
                }
            }
        }
    }

    // Tree 10
    private fun evalTree10(f: FloatArray): Float {
        if (f[0] <= -121.500000f) {
            if (f[3] <= 67.500000f) {
                if (f[2] <= -0.700000f) {
                    return 0.000000f
                } else {
                    if (f[8] <= 2157.000000f) {
                        return 0.464286f
                    } else {
                        return 0.000000f
                    }
                }
            } else {
                if (f[1] <= -6.815000f) {
                    if (f[10] <= 281.500000f) {
                        return 0.002500f
                    } else {
                        return 0.037594f
                    }
                } else {
                    if (f[8] <= 2708.000000f) {
                        return 0.098361f
                    } else {
                        return 0.346154f
                    }
                }
            }
        } else {
            if (f[4] <= 11.500000f) {
                if (f[11] <= 0.902307f) {
                    if (f[6] <= 16.000000f) {
                        return 0.194915f
                    } else {
                        return 0.000000f
                    }
                } else {
                    if (f[7] <= 0.203125f) {
                        return 0.875000f
                    } else {
                        return 0.375000f
                    }
                }
            } else {
                if (f[6] <= 19.000000f) {
                    if (f[2] <= 21.265000f) {
                        return 0.474443f
                    } else {
                        return 0.666667f
                    }
                } else {
                    if (f[11] <= 0.745370f) {
                        return 0.275168f
                    } else {
                        return 0.162500f
                    }
                }
            }
        }
    }

    // Tree 11
    private fun evalTree11(f: FloatArray): Float {
        if (f[8] <= 936.500000f) {
            if (f[1] <= -5.580000f) {
                if (f[2] <= 0.050000f) {
                    if (f[3] <= 71.500000f) {
                        return 0.080645f
                    } else {
                        return 0.002618f
                    }
                } else {
                    if (f[6] <= 7.500000f) {
                        return 0.307692f
                    } else {
                        return 0.000000f
                    }
                }
            } else {
                if (f[5] <= 3467.000000f) {
                    if (f[5] <= 3239.000000f) {
                        return 0.082192f
                    } else {
                        return 0.285714f
                    }
                } else {
                    if (f[7] <= 0.421875f) {
                        return 0.608634f
                    } else {
                        return 0.183036f
                    }
                }
            }
        } else {
            if (f[3] <= 66.500000f) {
                if (f[0] <= -109.500000f) {
                    if (f[2] <= 0.225000f) {
                        return 0.000000f
                    } else {
                        return 0.385000f
                    }
                } else {
                    if (f[3] <= 37.500000f) {
                        return 0.647541f
                    } else {
                        return 0.427536f
                    }
                }
            } else {
                if (f[3] <= 67.500000f) {
                    if (f[6] <= 2.500000f) {
                        return 0.000000f
                    } else {
                        return 0.320000f
                    }
                } else {
                    if (f[1] <= -6.745000f) {
                        return 0.002212f
                    } else {
                        return 0.194030f
                    }
                }
            }
        }
    }

}
